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
import com.domatar.crypto.CanonicalJson;
import com.domatar.crypto.Path;
import com.domatar.crypto.Provenance;
import com.domatar.crypto.SecWire;
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
  private final Provenance prov;
  private final boolean rootCapable;

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
    this(domId, domain, context, contextPath, contextRealPath, Provenance.empty(), true);
  }

  /**
   * Platform factory Msg.doAction uses to hand a handler a client
   * carrying the verified chain. The returned client can only extend
   * that chain; it cannot root.
   */
  public static HttpClient inbound(final DomId dstDomId,
                                   final String srcDomain,
                                   final Context stampedCtx,
                                   final String contextPath,
                                   final String contextRealPath,
                                   final Provenance verified)
  {
    return new HttpClient(dstDomId, srcDomain, stampedCtx, contextPath, contextRealPath,
                          verified != null ? verified : Provenance.empty(),
                          false);
  }

  private HttpClient(final DomId domId,
                     final String domain,
                     final Context context,
                     final String contextPath,
                     final String contextRealPath,
                     final Provenance prov,
                     final boolean rootCapable)
  {
    srcDomId = domId;
    srcDomain = domain;
    srcContext = context;
    srcContextPath = contextPath;
    srcContextRealPath = contextRealPath;
    this.prov = prov != null ? prov : Provenance.empty();
    this.rootCapable = rootCapable;
  }

  @Override
  public DomId getSrcId()
  {
    return srcDomId;
  }

  /**
   * Platform only (DomatarServlet, bootstrap, directory bring-up). Mints
   * hop 0. Throws when this client already holds a chain. Roots with
   * {@code srcContext.actId} when verified, else a null actId, and
   * attaches Binding + Delegation when a fingerprint actId is asserted.
   */
  public JsonMsg root(final DomId dst, final JsonMsg msg) throws DomatarException
  {
    if (!rootCapable || !prov.isEmpty())
      throw new DomatarException("root on a non-empty chain");

    final String actId = srcContext != null && srcContext.isVerified()
                         ? srcContext.actId : null;
    return dispatchRoot(dst, msg, actId);
  }

  /**
   * Platform only. Sole legitimate caller: {@code ActManagerImpl.addAct}.
   * Mints a fresh hop 0 and ContextId under {@code actId} and attaches
   * that account's Binding and Delegation.
   */
  public JsonMsg rootAs(final String actId, final DomId dst, final JsonMsg msg)
      throws DomatarException
  {
    return dispatchRoot(dst, msg, actId);
  }

  /**
   * Platform only. Sole legitimate caller: {@code ActManagerImpl.addAct}.
   * Returns a new root-capable client stamped as the just-created account
   * so the install chain can send as a new lineage, not a hand-built
   * Context with an empty path.
   */
  public HttpClient rootAs(final String actId,
                           final String usrId,
                           final String usrName,
                           final String usrIp,
                           final String token)
  {
    final Context stamped = new Context(actId, usrId, usrName, usrIp, token,
                                        Trust.ACCOUNT, null, null);
    return new HttpClient(srcDomId, srcDomain, stamped, srcContextPath,
                          srcContextRealPath, Provenance.empty(), true);
  }

  @Override
  public String send(DomId dstDomId, String document) throws DomatarException
  {
    return send(dstDomId, new JsonMsg(document)).toString();
  }

  /**
   * Appends a hop from the held chain. An ordinary handler-composed send
   * costs one Hop signature and no database read (Spec PART 8.11).
   * Throws when the chain is empty on an inbound client — an app that
   * needs to start something calls a platform root, not send. A
   * root-capable empty client (public constructor / {@link #rootAs})
   * mints hop 0, which is how bootstrap and LoginRemote still send.
   */
  @Override
  public JsonMsg send(DomId dstDomId, JsonMsg jsonMsg) throws DomatarException
  {
    if (prov.isEmpty())
    {
      if (!rootCapable)
        throw new DomatarException("send on an empty chain");

      final String actId = srcContext != null && srcContext.isVerified()
                           ? srcContext.actId : null;
      return dispatchRoot(dstDomId, jsonMsg, actId);
    }

    final DomId sendDomId = resolveSendDomId(dstDomId);

    try
    {
      final byte[] canonicalBody = CanonicalJson.canonicalize(jsonMsg.getBodyMap());
      final Provenance outbound = prov.append(srcDomId, sendDomId, canonicalBody,
                                              DomatarConfig.getPrvId());
      return dispatchWith(sendDomId, jsonMsg, outbound);
    }
    catch (final DomatarException e)
    {
      System.out.println("Error - HttpClient.send: " + e);
      throw e;
    }
  }

  @Override
  public DomatarMsgClient withToken(final String token) throws DomatarException
  {
    final Context c = new Context(srcContext.actId, srcContext.usrId, srcContext.usrName,
                                  srcContext.usrIp, token, srcContext.trust,
                                  srcContext.contextId, srcContext.httpHeaders);
    return new HttpClient(srcDomId, srcDomain, c, srcContextPath, srcContextRealPath,
                          prov, rootCapable);
  }

  @Override
  public DomId[] domIdPath() throws DomatarException
  {
    return prov.domIdPath();
  }

  private JsonMsg dispatchRoot(final DomId dst, final JsonMsg msg, final String actId)
      throws DomatarException
  {
    final DomId sendDomId = resolveSendDomId(dst);

    try
    {
      final byte[] canonicalBody = CanonicalJson.canonicalize(msg.getBodyMap());
      final Provenance outbound = mintRoot(sendDomId, canonicalBody, actId);
      return dispatchWith(sendDomId, msg, outbound);
    }
    catch (final DomatarException e)
    {
      System.out.println("Error - HttpClient.root: " + e);
      throw e;
    }
  }

  /**
   * Stamps informational Head.Context from the outbound provenance and
   * dispatches. Provenance travels as {@code Sec=}, never inside JsonMsg.
   * Called by root, rootAs and send.
   */
  private JsonMsg dispatchWith(final DomId sendDomId, final JsonMsg msg,
                               final Provenance outbound) throws DomatarException
  {
    final Context msgContext = new Context(srcContext.actId, srcContext.usrId,
                                           srcContext.usrName, srcContext.usrIp,
                                           srcContext.token, srcContext.trust,
                                           outbound.contextId(),
                                           srcContext.httpHeaders);

    msg.addRequestHead(srcDomId, sendDomId, msgContext);

    final JsonMsg retMsg = dispatch(sendDomId, msg, outbound);

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
  private JsonMsg dispatch(final DomId sendDomId, final JsonMsg jsonMsg,
                           final Provenance outbound) throws DomatarException
  {
    final String selfHstId = DomatarConfig.getHstId();

    if (sendDomId.hstId != null && sendDomId.hstId.equals(selfHstId))
      return new JsonMsg(sendLocal(sendDomId, jsonMsg, outbound));

    // Directory service (domatar, hst, domatar@hst, hsts): there is no
    // routable hst row for "domatar". Reach it via DOMATAR_DIRECTORY the
    // same way getRemoteHst does (Spec-Domatar.txt PART 4.2; K10).
    if (isDirectoryDomId(sendDomId))
      return sendDirectory(jsonMsg);

    final Hst hst = getHst(sendDomId.hstId);

    JsonMsg retMsg = dispatchOnce(sendDomId, jsonMsg, hst, outbound);

    if (shouldRefreshAndRetry(retMsg))
    {
      final Hst refreshed = refreshHst(sendDomId.hstId);

      if (refreshed != null)
        retMsg = dispatchOnce(sendDomId, jsonMsg, refreshed, outbound);
    }

    return retMsg;
  }

  private JsonMsg dispatchOnce(final DomId sendDomId, final JsonMsg jsonMsg, final Hst hst,
                               final Provenance outbound)
      throws DomatarException
  {
    final String selfHstId = DomatarConfig.getHstId();

    final boolean isLocal = hst != null
                         && hst.prvId != null
                         && hst.prvId.equals(selfHstId);

    if (isLocal)
      return new JsonMsg(sendLocal(sendDomId, jsonMsg, outbound));

    if (hst == null || hst.domain == null)
      return errorJson(jsonMsg.getOperation(), "Hst not found");

    final String retStr = sendRemote(hst.domain, jsonMsg, outbound);

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
   *
   * WHY the callee's client is built from {@code extended}, not
   * {@code srcContext}: the shipped sendLocal constructed a fresh public
   * HttpClient and dropped every in-process hop (Spec PART 11.2). The
   * handler must receive the extended provenance so its send() appends
   * rather than restarting the chain.
   */
  private String sendLocal(final DomId dstDomId, final JsonMsg jsonMsg,
                           final Provenance extended) throws DomatarException
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

    final Context childCtx = new Context(srcContext.actId, srcContext.usrId,
                                         srcContext.usrName, srcContext.usrIp,
                                         srcContext.token, srcContext.trust,
                                         extended.contextId(),
                                         srcContext.httpHeaders);

    final DomatarMsgClient msgClient = inbound(dstDomId, srcDomain, childCtx,
                                               srcContextPath, srcContextRealPath,
                                               extended);

    jsonMsg.setContext(childCtx);

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

  private String sendRemote(final String dstDomain, final JsonMsg jsonMsg,
                            final Provenance outbound) throws DomatarException
  {
    return sendHttp(dstDomain, jsonMsg, outbound);
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

      final JsonMsg retMsg = sendDirectory(getHstMsg);

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
   * Named Spec PART 10.3 carve-out. The operations needed to verify
   * (GetHst) must be reachable without prior verification. Widening this
   * method's callers is a security change.
   */
  private JsonMsg sendDirectory(final JsonMsg msg) throws DomatarException
  {
    return new JsonMsg(sendHttp(DomatarConfig.getDirectory(), msg, null));
  }

  private static boolean isDirectoryDomId(final DomId id)
  {
    return id != null
        && "domatar".equals(id.hstId)
        && "hst".equals(id.appId)
        && "hsts".equals(id.objId);
  }

  private DomId resolveSendDomId(final DomId dstDomId) throws DomatarException
  {
    if (dstDomId.hstId.length() == 0)
      return new DomId(srcDomId.hstId, dstDomId.appId, dstDomId.actId, dstDomId.objId);

    return dstDomId;
  }

  private Provenance mintRoot(final DomId dst, final byte[] canonicalBody,
                              final String actId) throws DomatarException
  {
    final String prvId = DomatarConfig.getPrvId();
    String hopActId = null;
    Binding binding = null;
    Delegation deleg = null;

    if (actId != null && !actId.isEmpty() && actId.indexOf('@') < 0)
    {
      hopActId = actId;
      binding = ActDb.getBinding(actId);
      deleg = Delegation.ensureValid(actId, prvId);
    }

    try
    {
      final Path path = Path.root(srcDomId, dst, canonicalBody, hopActId, prvId);
      return Provenance.of(path, deleg, binding);
    }
    catch (final RuntimeException e)
    {
      throw new DomatarException(e);
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

  private String sendHttp(final String dstDomain, final JsonMsg jsonMsg,
                          final Provenance prov)
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
      String postStr = "Msg=" + URLEncoder.encode(msgStr, "UTF-8");

      if (prov != null)
        postStr += "&" + SecWire.PARAM + "="
            + URLEncoder.encode(SecWire.encode(prov), "UTF-8");

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
