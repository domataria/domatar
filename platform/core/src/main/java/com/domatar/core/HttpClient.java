/*
 * Copyright (c) 2024 Domatar
 */

package com.domatar.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.Scanner;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLSocketFactory;

import com.domatar.crypto.Binding;
import com.domatar.crypto.Delegation;
import com.domatar.crypto.DirectoryTrust;
import com.domatar.crypto.Hop;
import com.domatar.crypto.OriginBlock;
import com.domatar.crypto.PathChain;
import com.domatar.db.ActDb;
import com.domatar.db.HstDb;
import com.domatar.db.ObjDb;
import com.domatar.util.Base64Encoder;
import com.domatar.util.Hst;
import com.domatar.util.JsonMsg;
import com.domatar.util.Obj;
import com.domatar.util.ObjAttrs;
import com.domatar.util.DomatarException;
import com.domatar.util.DomatarInterface;
import com.domatar.util.DomatarMsgClient;
import com.domatar.util.DomId;

public class HttpClient implements DomatarMsgClient
{
  // Inbound endpoint.  Full URL: <scheme>:// + dstDomain + PLATFORM_CONTEXT_PATH + servletPath
  // The scheme is read from DOMATAR_WIRE_SCHEME (default "https"; use "http" for dev-only).
  private static final String servletPath = "/Msg";

  // Local hst cache TTL. Entries older than this trigger a refresh against the directory.
  // Conservative for a local sim - production will tune this.
  private static final long HST_TTL_MILLIS = 60_000L;

  // Error sentinel used by sendHttp when the wire call fails outright (not just a
  // server-side error reply). Treated as a refresh-and-retry trigger by send().
  private static final String TRANSPORT_FAILURE = "Failure";

  private final DomId srcDomId;
  private final String srcDomain;
  private final Context srcContext;
  private final String srcContextPath;
  private final String srcContextRealPath;

  // Stateless directory DomId used as the "from" of GetHst calls. The hstId/actId
  // are placeholders; the directory does not check them.
  private static DomId domatarHstsId;

  {
    try
    {
      domatarHstsId = new DomId("domatar", "hst", "domatar@hst", "hsts");
    }
    catch (DomatarException e)
    {
      e.printStackTrace();
    }
  }

  public HttpClient(DomId domId, String domain, Context context, String contextPath, String contextRealPath)
  {
    srcDomId = domId;
    srcDomain = domain;
    srcContext = context;
    srcContextPath = contextPath;
    srcContextRealPath = contextRealPath;
  }

  /**
   * Build a peer HttpClient that shares this client's transport
   * coordinates (srcDomId, srcDomain, contextPath, contextRealPath)
   * but carries a different identity envelope. Used by handlers that
   * issue side-effect dispatches under a freshly-minted identity -
   * e.g. ActManagerImpl.addAct stamping the just-issued token onto a
   * RecordLogin / AddPeer to the account directory
   * (Spec-Login-Multiple.txt — membership replica post-cutover).
   */
  public HttpClient derive(final Context context)
  {
    return new HttpClient(srcDomId, srcDomain, context, srcContextPath, srcContextRealPath);
  }

  @Override
  public DomId getSrcId()
  {
    return srcDomId;
  }

  @Override
  public String send(DomId dstDomId, String document) throws DomatarException
  {
    return send(dstDomId, new JsonMsg(document)).toString();
  }

  @Override
  public JsonMsg send(DomId dstDomId, JsonMsg jsonMsg) throws DomatarException
  {
    DomId sendDomId;

    if (dstDomId.hstId.length() == 0)
    {
      sendDomId = new DomId(srcDomId.hstId,
                            dstDomId.appId,
                            dstDomId.actId,
                            dstDomId.objId);
    }
    else
    {
      sendDomId = dstDomId;
    }

    JsonMsg retMsg;

    try
    {
      // Append the destination to the path so LogsImpl can see the full hop chain.
      final DomId[] oldPath = srcContext.domIdPath != null ? srcContext.domIdPath : new DomId[0];
      final DomId[] newPath = new DomId[oldPath.length + 1];
      System.arraycopy(oldPath, 0, newPath, 0, oldPath.length);
      newPath[oldPath.length] = sendDomId;
      final Context msgContext = new Context(srcContext.actId, srcContext.usrId, srcContext.usrName,
                                             srcContext.usrIp, srcContext.token, srcContext.verified,
                                             srcContext.httpHeaders, newPath);

      jsonMsg.addRequestHead(srcDomId, sendDomId, msgContext);

      // Phase 4: attach an OriginBlock + Delegation when:
      //  - this is the first outbound hop (no Sec block yet),
      //  - a real account actId is present in the context (not a synthetic
      //    placeholder like "act@act" or "login@act"), and
      //  - the message is a cross-provider request (not a self-dispatch that
      //    will be handled inline without crossing a trust boundary).
      attachSecIfAbsent(srcDomId, sendDomId, jsonMsg, msgContext);

      // Phase 5: append a signed Hop to Sec.Path for every send
      // (network or in-process) that carries a Sec block, so that
      // provenance-requiring receivers can verify the full causal chain.
      appendHopToPath(srcDomId, sendDomId, jsonMsg);

      retMsg = dispatch(sendDomId, jsonMsg);
    }
    catch (DomatarException e)
    {
      System.out.println("Error - HttpClient.send: " + e);
      throw new DomatarException(e);
    }

    retMsg.addResponseHead(sendDomId, srcDomId, srcContext);

    return retMsg;
  }

  /**
   * Resolve the destination and dispatch.  Two branches:
   *
   * <p>1. Self-dispatch: when the destination's hstId equals this server's
   * own DOMATAR_HSTID, dispatch in-process via sendLocal.  The shortcut
   * avoids a redundant directory lookup and prevents a stack-overflow loop
   * when the platform server doubles as the global directory (the self-call
   * to GetHst would re-enter dispatch indefinitely).
   *
   * <p>2. Remote dispatch: resolve the destination's hst row (cache-first,
   * refresh-on-miss), then send HTTP to its domain.  On any transport error
   * or "Hst moved" reply, refresh the hst row once and retry.
   */
  private JsonMsg dispatch(final DomId sendDomId, final JsonMsg jsonMsg) throws DomatarException
  {
    final String selfHstId = DomatarConfig.getHstId();

    if (sendDomId.hstId != null && sendDomId.hstId.equals(selfHstId))
      return new JsonMsg(sendLocal(sendDomId, jsonMsg));

    // Directory service (domatar, hst, domatar@hst, hsts): there is no
    // routable hst row for "domatar". Reach it via DOMATAR_DIRECTORY the
    // same way getRemoteHst does (Spec-Domatar.txt PART 4.2; K10).
    if ("domatar".equals(sendDomId.hstId)
        && "hst".equals(sendDomId.appId)
        && "hsts".equals(sendDomId.objId))
      return new JsonMsg(sendHttp(DomatarConfig.getDirectory(), jsonMsg));

    final Hst hst = getHst(sendDomId.hstId);

    JsonMsg retMsg = dispatchOnce(sendDomId, jsonMsg, hst);

    if (shouldRefreshAndRetry(retMsg))
    {
      final Hst refreshed = refreshHst(sendDomId.hstId);

      if (refreshed != null)
        retMsg = dispatchOnce(sendDomId, jsonMsg, refreshed);
    }

    return retMsg;
  }

  private JsonMsg dispatchOnce(final DomId sendDomId, final JsonMsg jsonMsg, final Hst hst)
      throws DomatarException
  {
    final String selfHstId = DomatarConfig.getHstId();

    final boolean isLocal = hst != null
                         && hst.prvId != null
                         && hst.prvId.equals(selfHstId);

    if (isLocal)
      return new JsonMsg(sendLocal(sendDomId, jsonMsg));

    if (hst == null || hst.domain == null)
      return errorJson(jsonMsg.getOperation(), "Hst not found");

    final String retStr = sendRemote(hst.domain, jsonMsg);

    return new JsonMsg(retStr);
  }

  private boolean shouldRefreshAndRetry(final JsonMsg retMsg) throws DomatarException
  {
    final String error = retMsg.getError();

    if (!"Failure".equals(error))
      return false;

    final String errorMsg = retMsg.getErrorMsg();

    if (errorMsg == null)
      return true; // generic transport failure

    return "Hst not found".equals(errorMsg)
        || "Hst moved".equals(errorMsg);
  }

  /**
   * Local dispatch. Class identification matches Msg.doAction: the envelope
   * wins; the obj row is loaded only as a fallback when the envelope carries
   * no class. Container services (quips, news, follows, ...) work without an
   * obj row by relying on the envelope class.
   */
  private String sendLocal(final DomId dstDomId, final JsonMsg jsonMsg) throws DomatarException
  {
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
      return errorMsg(jsonMsg.getOperation(), "Obj not found");

    final DomatarInterface msgHandler = ImplMap.get(clsAppId, clsId);

    if (msgHandler == null)
      return errorMsg(jsonMsg.getOperation(), "Handler not found");

    final DomatarMsgClient msgClient = new HttpClient(dstDomId,
                                                      srcDomain,
                                                      srcContext,
                                                      srcContextPath,
                                                      srcContextRealPath);

    return msgHandler.handleMsg(jsonMsg.toString(), obj, srcContextPath, srcContextRealPath, msgClient);
  }

  private static String errorMsg(final String operation, final String errorMsg) throws DomatarException
  {
    final JsonMsg msg = new JsonMsg();

    msg.addError(operation, errorMsg);

    return msg.toString();
  }

  private static JsonMsg errorJson(final String operation, final String errorMsg) throws DomatarException
  {
    final JsonMsg msg = new JsonMsg();

    msg.addError(operation, errorMsg);

    return msg;
  }

  private String sendRemote(final String dstDomain, final JsonMsg jsonMsg) throws DomatarException
  {
    return sendHttp(dstDomain, jsonMsg);
  }

  /**
   * Lazy, TTL-aware getHst. Returns the local cache row if present and fresh,
   * otherwise refreshes from the directory.
   */
  private Hst getHst(final String hstId) throws DomatarException
  {
    final Hst hst = HstDb.getHst(hstId);

    if (hst != null && isFresh(hst))
      return hst;

    final Hst refreshed = refreshHst(hstId);

    if (refreshed != null)
      return refreshed;

    return hst; // fall back to stale cache if directory is unreachable
  }

  private static boolean isFresh(final Hst hst)
  {
    if (hst.fetchedAt <= 0)
      return false;

    return (System.currentTimeMillis() - hst.fetchedAt) < HST_TTL_MILLIS;
  }

  private Hst refreshHst(final String hstId) throws DomatarException
  {
    final Hst hst = getRemoteHst(hstId);

    if (hst != null)
      HstDb.updateHst(hstId, hst.domain, hst.prvId, hst.version, hst.fetchedAt,
                      hst.pubKey, hst.recordSig);

    return hst;
  }

  private Hst getRemoteHst(final String hstId) throws DomatarException
  {
    try
    {
      final JsonMsg getHstMsg = new JsonMsg();

      getHstMsg.addRequestHead(srcDomId, domatarHstsId, srcContext);

      // Stateless service: clsAppId/clsId must match ImplMap's registration for
      // HstsImpl ("hst", "hsts") so the directory dispatches it without an obj row.
      getHstMsg.addClsId("hst", "hsts");

      final ObjAttrs attrs = new ObjAttrs();

      attrs.addAttr("HstId", hstId);

      getHstMsg.addRequestBody("GetHst", attrs);

      final String directory = DomatarConfig.getDirectory();

      final String retMsgStr = sendHttp(directory, getHstMsg);

      final JsonMsg retMsg = new JsonMsg(retMsgStr);

      if (!"Success".equals(retMsg.getError()))
        return null;

      final ObjAttrs retAttrs = retMsg.getAttrs();

      if (retAttrs == null)
        return null;

      final String dom = retAttrs.getAttr("Domain");
      final String prv = retAttrs.getAttr("PrvId");

      if (dom == null && prv == null)
        return null;

      final String ver = retAttrs.getAttr("Version");
      long version = 0L;

      if (ver != null)
      {
        try
        {
          version = Long.parseLong(ver);
        }
        catch (NumberFormatException ignored)
        {
        }
      }

      final String pubKey    = retAttrs.getAttr("PubKey");
      final String recordSig = retAttrs.getAttr("RecordSig");

      // Phase 3: verify the directory-root signature when both are present and
      // the root public key is configured.  A bad signature means the record
      // may have been tampered; treat as "Hst not found" to prevent caching an
      // unverified provider key (Spec-Security.txt PART 10.2).
      if (pubKey != null && recordSig != null && isDirectoryRootPubKeyConfigured())
      {
        final byte[] canonical = DirectoryTrust.canonicalHstRecord(hstId, dom, prv, version, pubKey);
        final byte[] sigBytes  = Base64Encoder.decode(recordSig);

        if (!DirectoryTrust.verifyHstRecord(canonical, sigBytes))
        {
          System.out.println("WARN: GetHst RecordSig verification FAILED for hstId=" + hstId
              + " — discarding record (possible tamper)");
          return null;
        }
      }

      return new Hst(hstId, dom, prv, version, System.currentTimeMillis(), pubKey, recordSig);
    }
    catch (Exception e)
    {
      throw new DomatarException(e);
    }
  }

  /**
   * Appends a signed {@link Hop} to the {@code Head.Sec.Path} list for every
   * send that carries a Sec block (Phase 5, Spec-Security.txt PART 8.2).
   * No-op when:
   *   - the message has no Sec block (unsigned message), or
   *   - ProviderKeyStore has no key yet (pre-bootstrap).
   *
   * Both network sends (sendHttp) and in-process sends (sendLocal) go through
   * this path so the causal chain stays complete for the eventual receiver.
   */
  private void appendHopToPath(final DomId src, final DomId dst, final JsonMsg jsonMsg)
  {
    try
    {
      if (!jsonMsg.hasSec())
        return;

      final JsonMsg.SecEnvelope sec = jsonMsg.getSec();
      final java.util.List<Hop> existingPath = sec.path;

      final String prvId = DomatarConfig.getPrvId();

      final Hop hop = PathChain.append(existingPath, src, dst,
                                       jsonMsg.getBodyMap(), prvId);

      jsonMsg.appendHopToSec(hop);
    }
    catch (final Exception e)
    {
      System.out.println("WARN: appendHopToPath failed: " + e);
    }
  }

  /**
   * Attaches an OriginBlock + Delegation + Binding to {@code jsonMsg} when:
   *   - the message has no Sec block yet (first hop only),
   *   - {@code msgContext.actId} is a fingerprint actId (not a synthetic
   *     placeholder like "act@act"), and
   *   - {@code msgContext.verified} is true (cookie-verified browser session).
   *
   * <p>OwnIds Phase 5: always attaches the Binding. An account with no
   * binding is treated as a bug (log WARNING and skip signing).
   *
   * Failures are non-fatal: a missing delegation means the message goes out
   * without a Sec block. The receiving Msg.verifyAndStamp will set
   * verified=false, and public handlers still run.
   */
  private void attachSecIfAbsent(final DomId src,
                                 final DomId dst,
                                 final JsonMsg jsonMsg,
                                 final Context ctx)
  {
    try
    {
      if (jsonMsg.hasSec())
        return; // already signed (e.g. a forwarded message)

      final String actId = ctx.actId;

      if (actId == null || actId.isEmpty() || actId.indexOf('@') >= 0)
        return; // synthetic or legacy actId — no signing

      if (!ctx.verified)
        return; // not yet cookie-verified; skip (no credentials to assert)

      final String prvId = DomatarConfig.getPrvId();

      final Binding binding = ActDb.getBinding(actId);
      if (binding == null)
      {
        System.out.println("WARN: attachSecIfAbsent: no binding for actId=" + actId
                           + " — skipping Sec (Phase 5 requires binding)");
        return;
      }

      final Delegation deleg = Delegation.ensureValid(actId, prvId);

      if (deleg == null)
        return; // no valid delegation available

      final OriginBlock origin = OriginBlock.sign(src, dst, jsonMsg.getBodyMap(), actId, prvId);
      jsonMsg.addSec(origin, deleg, binding);
    }
    catch (final Exception e)
    {
      // Non-fatal: log and continue without the Sec block.
      System.out.println("WARN: attachSecIfAbsent failed: " + e);
    }
  }

  /** Returns true iff the directory root public key is configured on this node. */
  private static boolean isDirectoryRootPubKeyConfigured()
  {
    try
    {
      DirectoryTrust.rootPublicKey();
      return true;
    }
    catch (IllegalStateException ignored)
    {
      return false;
    }
  }

  private String sendHttp(final String dstDomain, final JsonMsg jsonMsg)
  {
    String retStr = "{ \"Error\" : \"" + TRANSPORT_FAILURE + "\" }";
    OutputStreamWriter osw = null;
    InputStream in = null;

    try
    {
      final String scheme = DomatarConfig.getWireScheme();
      final String urlStr = scheme + "://" + dstDomain + DomatarConfig.PLATFORM_CONTEXT_PATH + servletPath;
      final URL url = new URL(urlStr);
      final HttpURLConnection conn = (HttpURLConnection) url.openConnection();

      // Phase 6: when HTTPS, apply the configured TLS trust store / key store.
      if ("https".equals(scheme) && conn instanceof HttpsURLConnection)
      {
        final SSLSocketFactory sf = TlsConfig.getSocketFactory();
        if (sf != null)
          ((HttpsURLConnection) conn).setSSLSocketFactory(sf);
        // If sf == null the JVM default is used (works for CA-signed certs).
      }

      conn.setDoInput(true);
      conn.setDoOutput(true);
      conn.setRequestMethod("POST");
      conn.setRequestProperty("Content-type", "application/x-www-form-urlencoded; charset=utf-8");
      conn.connect();

      osw = new OutputStreamWriter(conn.getOutputStream(), "UTF-8");
      final PrintWriter pout = new PrintWriter(osw, true);

      final String msgStr = jsonMsg.toString();
      final String postStr = "Msg=" + URLEncoder.encode(msgStr, "UTF-8");

      pout.print(postStr);
      pout.flush();

      in = conn.getInputStream();

      retStr = getInputString(in);
    }
    catch (Exception e)
    {
      try
      {
        final JsonMsg msg = new JsonMsg();

        msg.addError(jsonMsg.getOperation(), e.toString());

        retStr = msg.toString();
      }
      catch (Exception e2)
      {
        System.out.println("Error2 sending http: " + e2);
      }

      System.out.println("Error sending http: " + e);
      System.out.println("dstDomain: " + dstDomain);
      System.out.println("msg: " + jsonMsg.toString());

      e.printStackTrace();
    }
    finally
    {
      try
      {
        if (osw != null)
          osw.close();

        if (in != null)
          in.close();
      }
      catch (IOException e)
      {
        System.out.println("Error closing OutputStreamWriter: " + e);
        e.printStackTrace();
      }
    }

    return retStr;
  }

  private String getInputString(final InputStream in) throws IOException
  {
    final Scanner scanner = new Scanner(in, "UTF-8");

    final String retStr = scanner.useDelimiter("\\A").next();

    scanner.close();

    return retStr;
  }
}
