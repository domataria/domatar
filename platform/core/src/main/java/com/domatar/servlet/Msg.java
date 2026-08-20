package com.domatar.servlet;

import java.io.IOException;
import java.io.PrintWriter;

import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import com.domatar.core.Context;
import com.domatar.core.HttpClient;
import com.domatar.core.ImplMap;
import com.domatar.core.RequestContext;
import com.domatar.core.DomatarConfig;
import com.domatar.crypto.Binding;
import com.domatar.crypto.Delegation;
import com.domatar.crypto.KeyOps;
import com.domatar.crypto.NonceCache;
import com.domatar.crypto.OriginBlock;
import com.domatar.crypto.PathChain;
import com.domatar.db.ActDb;
import com.domatar.db.DbConnection;
import com.domatar.db.HstDb;
import com.domatar.db.ObjDb;
import com.domatar.install.DirectoryLookup;
import com.domatar.util.Base64Encoder;
import com.domatar.util.Hst;
import com.domatar.util.JsonMap;
import com.domatar.util.JsonMsg;
import com.domatar.util.Obj;
import com.domatar.util.ObjImpl;
import com.domatar.util.DomatarException;
import com.domatar.util.DomatarInterface;
import com.domatar.util.DomId;

import java.security.PublicKey;

/**
 * Servlet implementation class Msg
 */
@WebServlet("/Msg")
public class Msg extends HttpServlet
{
  private static final long serialVersionUID = -1048792698141910023L;

  private static String dbConnection = null;
  // This server's own hstId (from DOMATAR_HSTID); used to decide whether an incoming
  // DomId can be served here (i.e. its hst.PrvId equals this value).
  private static String prvHstId = null;

  @Override
  public void init(ServletConfig config)
    throws ServletException
  {
    super.init(config);

    final ServletContext servletContext = getServletContext();

    prvHstId = DomatarConfig.getHstId();

    final String dbOverride = DomatarConfig.getDbUrlOverride();

    dbConnection = (dbOverride != null) ? dbOverride : servletContext.getInitParameter("DbConnection");

    DbConnection.setConnectStr(dbConnection);
  }

  @Override
  public void doGet(HttpServletRequest req, HttpServletResponse res) throws ServletException, IOException
  {
    doAction(req, res);
  }

  @Override
  public void doPost(HttpServletRequest req, HttpServletResponse res) throws ServletException, IOException
  {
    doAction(req, res);
  }

  /**
   * Inbound message dispatch (cross-prv trust boundary).
   *
   * Identity vs. authorization is split:
   *
   *  - VERIFICATION (identity): we run LoginRemote.verifyLogin once here
   *    against THIS prv's act table, regardless of what the wire envelope
   *    claims. On success the dispatch context carries verified=true plus
   *    the locally-resolved actId/usrName; on failure it carries the wire
   *    claim with verified=false. Either way we keep going - we do NOT
   *    reject the request at this layer, because some handlers (the
   *    directory, the news feed, login itself) are public-by-design and
   *    do not need a verified caller.
   *
   *  - AUTHORIZATION (per-object policy): delegated to the destination
   *    handler via ObjImpl.hasRights(). Handlers that require a verified
   *    caller call Auth.isVerified(inMsg), which is now a flag read - no
   *    DB hit per handler dispatch, no recursion.
   *
   * Cross-prv trust does not transfer: we never trust the wire's Verified
   * flag, we always overwrite it with the result of our own local check.
   *
   * Class identification is unchanged: the envelope wins, the obj row is
   * loaded only as a fallback when the envelope carries no class.
   */
  public void doAction(final HttpServletRequest req, final HttpServletResponse res)
      throws ServletException, IOException
  {
    // Phase 6: propagate TLS client certificate identity to handlers via
    // a thread-local.  Clear first to prevent leakage from prior requests.
    RequestContext.clear();
    extractClientCert(req);

    final String srcDomain = req.getServerName();

    String contextPath = req.getContextPath();
    contextPath = contextPath.replaceAll("//", "/"); // If it starts with two slashes change it to one slash

    final String realPath = req.getSession().getServletContext().getRealPath("");
    String contextRealPath = realPath.replace("\\", "/");

    if (contextRealPath.charAt(contextRealPath.length() - 1) == '/') // If it ends with a slash
      contextRealPath = contextRealPath.substring(0, contextRealPath.length() - 1);

    res.setContentType("text/html; charset=UTF-8");
    res.setCharacterEncoding("UTF-8");

    final PrintWriter out = res.getWriter();
    String retMsg;

    try
    {
      final String[] value = req.getParameterValues("Msg");

      if (value != null)
      {
        try
        {
          String msg = value[0];

          final JsonMsg jsonMsg = new JsonMsg(msg);

          final DomId dstDomId = jsonMsg.getDstId();

          final Context dispatchContext = verifyAndStamp(jsonMsg,
                                                         srcDomain,
                                                         contextPath,
                                                         contextRealPath);

          // Re-serialize so handlers that parse `msg` (rather than the
          // already-parsed jsonMsg) also see the verified context.
          msg = jsonMsg.toString();

          String clsAppId = jsonMsg.getClsAppId();
          String clsId    = jsonMsg.getClsId();
          Obj    obj      = null;

          if (clsAppId == null || clsId == null)
          {
            obj = ObjDb.getObj(dstDomId);

            if (obj != null)
            {
              clsAppId = obj.clsAppId;
              clsId    = obj.clsId;
            }
          }

          if (obj == null && (clsAppId == null || clsId == null))
          {
            final Hst hst = HstDb.getHst(dstDomId.hstId);

            if (hst == null || hst.prvId == null || !hst.prvId.equals(prvHstId))
              retMsg = errorMsg(jsonMsg.getOperation(), "Hst not found");
            else
              retMsg = errorMsg(jsonMsg.getOperation(), "Obj not found");
          }
          else
          {
            final DomatarInterface msgHandler = ImplMap.get(clsAppId, clsId);

            if (msgHandler != null)
            {
              // Phase 5 (Q2): if the handler requires full path provenance,
              // run PathChain.verify now. On failure, demote verified to false
              // so hasRights() rejects the request via the normal authorization path.
              Context pathDispatchContext = dispatchContext;

              if (msgHandler instanceof ObjImpl
                  && ((ObjImpl) msgHandler).requiresPath())
              {
                if (!verifyPath(jsonMsg, dispatchContext))
                {
                  // Path check failed — demote to unverified.
                  pathDispatchContext = new Context(
                      dispatchContext.actId,
                      dispatchContext.usrId,
                      dispatchContext.usrName,
                      dispatchContext.usrIp,
                      dispatchContext.token,
                      false,
                      dispatchContext.httpHeaders,
                      dispatchContext.domIdPath);

                  jsonMsg.setContext(pathDispatchContext);
                  // Re-serialize so the handler sees the updated Context.
                  msg = jsonMsg.toString();
                }
              }

              retMsg = msgHandler.handleMsg(msg, obj, contextPath, contextRealPath,
                  new HttpClient(dstDomId, srcDomain, pathDispatchContext,
                                 contextPath, contextRealPath));
            }
            else
              retMsg = errorMsg(jsonMsg.getOperation(), "Handler not found");
          }
        }
        catch (DomatarException e)
        {
          retMsg = errorMsg("Unknown", e.toString());
        }
      }
      else
      {
        retMsg = errorMsg("Unknown", "No Message");
      }

      out.print(retMsg);
      out.close();
    }
    finally
    {
      // Phase 6: always clean up the per-request TLS identity to avoid
      // leaking data to the next request handled by this thread.
      RequestContext.clear();
    }
  }

  /**
   * Phase 6: extracts the TLS client certificate's public key from the
   * request attribute {@code javax.servlet.request.X509Certificate} and
   * stores it in {@link RequestContext} for use by handlers (e.g.
   * {@code HstsImpl.updateHst} mTLS enforcement).
   * No-op when the attribute is absent (HTTP or one-way TLS).
   */
  private static void extractClientCert(final HttpServletRequest req)
  {
    try
    {
      final Object attr = req.getAttribute("javax.servlet.request.X509Certificate");

      if (attr == null)
        return;

      // The attribute is an array of X509Certificate (chain order: leaf first).
      final java.security.cert.X509Certificate[] chain =
          (java.security.cert.X509Certificate[]) attr;

      if (chain.length > 0 && chain[0] != null)
        RequestContext.setClientCertPubKeyEncoded(
            chain[0].getPublicKey().getEncoded());
    }
    catch (final Exception e)
    {
      // Non-fatal: mTLS enforcement in handlers will simply see null.
      System.out.println("WARN: extractClientCert failed: " + e);
    }
  }

  /**
   * Phase 4 cross-provider trust boundary (Spec-Security.txt PART 7.3).
   *
   * Replaces the legacy LoginRemote token-verification path with a
   * credential-chain check:
   *   (a) Parse Head.Sec.  If absent → verified=false (keep dispatching;
   *       public handlers still run).
   *   (b) Verify Delegation: self-cert (actId == fingerprint(rootPubKey)),
   *       signature valid under rootPubKey, NotAfter in the future.
   *       Also check Delegation.PrvId == Origin.SignerPrvId (provider match).
   *   (c) Fetch the SignerPrv's operational public key from the local hst
   *       cache (populated by Phase 3).  Verify OriginSig.
   *   (d) Verify BodyHash == sha256(canonical body).
   *   (e) Replay/expiry: Timestamp within ±skew AND Nonce not seen before.
   *
   * On full success: verified=true, actId = Origin.ActId.
   * On any failure: verified=false.  NEVER trust the wire's Verified flag.
   *
   * Exemptions (verified=false, dispatch continues):
   *   - Directory traffic (hst/hsts): GetHst/UpdateHst are public and
   *     must be reachable before any signatures can be verified.
   *   - Messages without a Sec block: remain unverified; public handlers run.
   */
  private Context verifyAndStamp(final JsonMsg jsonMsg,
                                 final String srcDomain,
                                 final String contextPath,
                                 final String contextRealPath) throws DomatarException
  {
    final Context wireCtx  = jsonMsg.getContext();
    final String  wireIp   = wireCtx == null ? null : wireCtx.usrIp;
    final String  wireUsrId = wireCtx == null ? null : wireCtx.usrId;
    final String  wireToken = wireCtx == null ? null : wireCtx.token;

    final String  clsAppId           = jsonMsg.getClsAppId();
    final String  clsId              = jsonMsg.getClsId();
    final boolean isDirectoryDispatch = "hst".equals(clsAppId) && "hsts".equals(clsId);

    // Parse the Sec block.
    final JsonMsg.SecEnvelope sec = jsonMsg.getSec();

    boolean credentialVerified = false;
    String  verifiedActId      = null;

    if (!isDirectoryDispatch && sec.hasOrigin())
    {
      credentialVerified = verifyCredentialChain(sec, jsonMsg, wireIp);

      if (credentialVerified)
        verifiedActId = sec.origin.actId;
    }

    // Build the dispatch context.
    Context dispatchCtx;

    if (credentialVerified && verifiedActId != null)
    {
      // Resolve usrId/usrName from the wire context if present (they are
      // informational; we key on actId for authorization).
      dispatchCtx = new Context(verifiedActId,
                                wireUsrId,
                                wireCtx == null ? null : wireCtx.usrName,
                                wireIp,
                                wireToken,
                                true,
                                wireCtx == null ? null : wireCtx.httpHeaders,
                                wireCtx == null ? new DomId[0] : wireCtx.domIdPath);
    }
    else
    {
      dispatchCtx = new Context(wireCtx == null ? null : wireCtx.actId,
                                wireUsrId,
                                wireCtx == null ? null : wireCtx.usrName,
                                wireIp,
                                wireToken,
                                false,
                                wireCtx == null ? null : wireCtx.httpHeaders,
                                wireCtx == null ? new DomId[0] : wireCtx.domIdPath);
    }

    jsonMsg.setContext(dispatchCtx);

    return dispatchCtx;
  }

  /**
   * Credential-chain check (Spec-OwnIds.txt PART 11; Update-OwnIds.txt Phase 5).
   * Requires a Binding on the Sec envelope (legacy path removed). When the
   * origin actId is hosted locally, also enforces binding Version freshness
   * against the stored BindingVersion.
   * Returns true iff all checks pass.
   */
  private boolean verifyCredentialChain(final JsonMsg.SecEnvelope sec,
                                        final JsonMsg jsonMsg,
                                        final String wireIp)
  {
    try
    {
      final OriginBlock origin     = sec.origin;
      final Delegation  delegation = sec.delegation;

      if (origin == null)
        return false;

      // Tightened (K7): delegation without Binding is unverified.
      if (!sec.hasBinding())
        return false;

      final Binding binding = sec.binding;

      // Resolve the account's stored fingerprint version (default 1).
      final int fpVersion = ActDb.getFpVersion(origin.actId);

      // (b0) Binding self-authenticates under genesis for this FpVersion.
      if (binding == null || !binding.verify(fpVersion))
        return false;

      // (b1) Origin names the account (actId).
      if (!origin.actId.equals(binding.actId))
        return false;

      // Freshness (PART 6.4 / 11.3, single-provider): if this node hosts the
      // account, reject a superseded (lower-Version) binding.
      final ActDb.BindingRow local = ActDb.getBindingRow(origin.actId);
      if (local != null && binding.version < local.version)
        return false;

      // (b2) Delegation chains to the binding's current ownId.
      if (delegation == null || !delegation.verify(binding, fpVersion))
        return false;

      // (b3) Provider match.
      if (!delegation.prvId.equals(origin.signerPrvId))
        return false;

      // (b4) Fetch the signer provider's public key from the local hst cache.
      // If missing or OriginSig fails (stale key after peer rotation), refresh
      // once from the directory and retry.
      Hst signerHst = HstDb.getHst(origin.signerPrvId);

      if (signerHst == null || signerHst.pubKey == null)
        signerHst = DirectoryLookup.fetchAndCache(origin.signerPrvId);

      if (signerHst == null || signerHst.pubKey == null)
        return false; // no key published yet

      final JsonMap body = jsonMsg.getBodyMap();
      byte[]        pubKeyBytes = Base64Encoder.decode(signerHst.pubKey);
      PublicKey     signerPubKey = KeyOps.publicKeyFromBytes(pubKeyBytes);

      if (!origin.verify(signerPubKey, body))
      {
        final Hst refreshed = DirectoryLookup.fetchAndCache(origin.signerPrvId);

        if (refreshed == null || refreshed.pubKey == null)
          return false;

        pubKeyBytes  = Base64Encoder.decode(refreshed.pubKey);
        signerPubKey = KeyOps.publicKeyFromBytes(pubKeyBytes);

        if (!origin.verify(signerPubKey, body))
          return false;
      }

      // (b5) Replay / expiry.
      final long skewMs = DomatarConfig.getMsgSkewMs();
      final long now    = System.currentTimeMillis();

      if (Math.abs(now - origin.timestamp) > skewMs)
        return false; // message too old or too far in the future

      if (NonceCache.getInstance().reject(origin.signerPrvId, origin.nonce))
        return false; // replay detected

      return true;
    }
    catch (final Exception e)
    {
      System.out.println("WARN: verifyCredentialChain exception: " + e);
      return false;
    }
  }

  /**
   * Phase 5 path-provenance check (Spec-Security.txt PART 8.3).
   * Delegates to {@link PathChain#verify}.
   *
   * @return true iff the signed hop chain is intact
   */
  private boolean verifyPath(final JsonMsg jsonMsg, final Context dispatchCtx)
  {
    try
    {
      final JsonMsg.SecEnvelope sec = jsonMsg.getSec();

      if (!sec.hasPath())
        return false;

      final OriginBlock q1Origin = sec.origin;
      final JsonMap     body     = jsonMsg.getBodyMap();

      return PathChain.verify(sec.path, q1Origin, body, prvId ->
      {
        try
        {
          final Hst hst = HstDb.getHst(prvId);

          if (hst == null || hst.pubKey == null)
            return null;

          final byte[] pubBytes = Base64Encoder.decode(hst.pubKey);
          return KeyOps.publicKeyFromBytes(pubBytes);
        }
        catch (final Exception e)
        {
          return null;
        }
      });
    }
    catch (final Exception e)
    {
      System.out.println("WARN: verifyPath exception: " + e);
      return false;
    }
  }

  private static String errorMsg(final String operation, final String errorMsg) throws ServletException
  {
    try
    {
      final JsonMsg msg = new JsonMsg();

      msg.addError(operation, errorMsg);

      return msg.toString();
    }
    catch (Exception e)
    {
      throw new ServletException(e);
    }
  }
}
