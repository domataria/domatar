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
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Scanner;
import java.util.logging.Logger;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLSocketFactory;

import com.domatar.crypto.Binding;
import com.domatar.crypto.Delegation;
import com.domatar.crypto.DirectoryTrust;
import com.domatar.crypto.CanonicalJson;
import com.domatar.crypto.Path;
import com.domatar.crypto.Provenance;
import com.domatar.crypto.SecWire;
import com.domatar.crypto.Verdict;
import com.domatar.install.DirectoryKeyResolver;
import com.domatar.db.ActDb;
import com.domatar.db.HstDb;
import com.domatar.db.ObjDb;
import com.domatar.db.OpLogDb;
import com.domatar.log.OpLog;
import com.domatar.log.OpMsg;
import com.domatar.util.Act;
import com.domatar.util.Base64Encoder;
import com.domatar.util.Hst;
import com.domatar.util.Json;
import com.domatar.util.JsonList;
import com.domatar.util.JsonMap;
import com.domatar.util.JsonMsg;
import com.domatar.util.Obj;
import com.domatar.util.ObjAttrs;
import com.domatar.util.ObjImpl;
import com.domatar.util.DomatarException;
import com.domatar.util.DomatarInterface;
import com.domatar.util.DomatarMsgClient;
import com.domatar.util.DomId;

public class HttpClient implements DomatarMsgClient
{
  private static final Logger LOG = Logger.getLogger(HttpClient.class.getName());

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

  private int priorVisitSnapshot = 0;
  private int priorVisitAnySnapshot = 0;
  private boolean snapshotted = false;
  private String inboundMsgName;
  /** Not on DomatarMsgClient; Payment draws on INSERT. */
  public boolean thisAdmitIsFirst;

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

  HttpClient(DomId domId, String domain, Context context, String contextPath, String contextRealPath)
  {
    this(domId, domain, context, contextPath, contextRealPath, Provenance.empty(), true);
  }

  /**
   * Platform factory Msg.doAction uses to hand a handler a client
   * carrying the verified chain. The returned client can only extend
   * that chain; it cannot root.
   */
  static HttpClient inbound(final DomId dstDomId,
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

  @Override
  public String contextId()
  {
    return srcContext == null ? null : srcContext.contextId;
  }

  /** Verdict-stamped inbound Context. Not on DomatarMsgClient. */
  Context inboundContext()
  {
    return srcContext;
  }

  Provenance inboundProvenance()
  {
    return prov;
  }

  String handlerContextPath()
  {
    return srcContextPath;
  }

  String handlerContextRealPath()
  {
    return srcContextRealPath;
  }

  /**
   * Table state before this admit. Idempotent: the first call wins so
   * alreadyEntered still sees the pre-upsert counts after admitIfNeeded.
   */
  public void snapshotPriors(final String contextId, final String dstDomId,
                             final String msgName) throws DomatarException
  {
    if (snapshotted)
      return;

    inboundMsgName = msgName;

    if (OpLog.isSkipVisit() || contextId == null || contextId.isEmpty())
    {
      priorVisitSnapshot = 0;
      priorVisitAnySnapshot = 0;
      snapshotted = true;
      return;
    }

    final String hstId = new DomId(dstDomId).hstId;
    priorVisitSnapshot = OpLogDb.priorVisitCount(hstId, contextId, dstDomId,
        msgName);
    priorVisitAnySnapshot = OpLogDb.priorVisitCountAny(hstId, contextId,
        dstDomId);
    snapshotted = true;
  }

  public int priorVisitSnapshot()
  {
    return priorVisitSnapshot;
  }

  public int priorVisitAnySnapshot()
  {
    return priorVisitAnySnapshot;
  }

  /**
   * Platform only (DomatarServlet, bootstrap, directory bring-up). Mints
   * hop 0. Throws when this client already holds a chain. Roots with
   * {@code srcContext.actId} when verified, else a null actId, and
   * attaches Binding + Delegation when a fingerprint actId is asserted.
   */
  JsonMsg root(final DomId dst, final JsonMsg msg) throws DomatarException
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
  JsonMsg rootAs(final String actId, final DomId dst, final JsonMsg msg)
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
  HttpClient rootAs(final String actId,
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
      if (!OpLog.isSkipVisit() && !isDirectoryDomId(sendDomId)
          && srcContext != null && srcContext.contextId != null)
      {
        OpLogDb.insertMsg(
            srcDomId.hstId,
            srcContext.contextId,
            srcDomId.toString(),
            sendDomId.toString(),
            jsonMsg.getOperation(),
            OpLog.nowMs());
      }
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
    final HttpClient next = new HttpClient(srcDomId, srcDomain, c, srcContextPath,
        srcContextRealPath, prov, rootCapable);
    next.priorVisitSnapshot = priorVisitSnapshot;
    next.priorVisitAnySnapshot = priorVisitAnySnapshot;
    next.snapshotted = snapshotted;
    next.inboundMsgName = inboundMsgName;
    next.thisAdmitIsFirst = thisAdmitIsFirst;
    return next;
  }

  @Override
  public DomId[] domIdPath() throws DomatarException
  {
    return prov.domIdPath();
  }

  @Override
  public int priorVisitCount() throws DomatarException
  {
    return priorVisitCount(currentContextId());
  }

  @Override
  public int priorVisitCount(final String contextId) throws DomatarException
  {
    if (contextId != null && contextId.equals(currentContextId()) && snapshotted)
      return priorVisitSnapshot;

    if (contextId == null || inboundMsgName == null)
      return 0;

    return OpLogDb.priorVisitCount(thisHstId(), contextId, thisDomId(),
        inboundMsgName);
  }

  @Override
  public boolean alreadyEntered() throws DomatarException
  {
    if (!snapshotted)
      return false;

    return priorVisitCount() > 0;
  }

  @Override
  public boolean alreadyEntered(final String contextId) throws DomatarException
  {
    if (contextId != null && contextId.equals(currentContextId()) && !snapshotted)
      return false;

    return priorVisitCount(contextId) > 0;
  }

  @Override
  public int priorVisitCountAny() throws DomatarException
  {
    return priorVisitCountAny(currentContextId());
  }

  @Override
  public int priorVisitCountAny(final String contextId) throws DomatarException
  {
    if (contextId != null && contextId.equals(currentContextId()) && snapshotted)
      return priorVisitAnySnapshot;

    if (contextId == null)
      return 0;

    return OpLogDb.priorVisitCountAny(thisHstId(), contextId, thisDomId());
  }

  @Override
  public List<OpMsg> outMsgs() throws DomatarException
  {
    return outMsgs(currentContextId());
  }

  @Override
  public List<OpMsg> outMsgs(final String contextId) throws DomatarException
  {
    if (contextId == null)
      return Collections.emptyList();

    return OpLogDb.listOutMsgs(thisHstId(), contextId, thisDomId());
  }

  @Override
  public void attach(final String slot, final Object json,
      final long attachExpiresAt) throws DomatarException
  {
    attach(currentContextId(), inboundMsgName, slot, json, attachExpiresAt);
  }

  @Override
  public void attach(final String contextId, final String slot, final Object json,
      final long attachExpiresAt) throws DomatarException
  {
    attach(contextId, inboundMsgName, slot, json, attachExpiresAt);
  }

  @Override
  public void attach(final String contextId, final String msgName, final String slot,
      final Object json, final long attachExpiresAt) throws DomatarException
  {
    if (OpLog.isReservedSlot(slot))
      return;

    if (contextId == null || msgName == null)
      return;

    final String body = jsonTextOrReject(json);

    if (json != null && body == null)
      return;

    OpLogDb.attach(thisHstId(), contextId, thisDomId(), msgName, slot, body,
        attachExpiresAt);
  }

  @Override
  public Object attachment(final String slot) throws DomatarException
  {
    return attachment(currentContextId(), inboundMsgName, slot);
  }

  @Override
  public Object attachment(final String contextId, final String slot)
      throws DomatarException
  {
    return attachment(contextId, inboundMsgName, slot);
  }

  @Override
  public Object attachment(final String contextId, final String msgName,
      final String slot) throws DomatarException
  {
    if (contextId == null || msgName == null || slot == null)
      return null;

    return parseAttachment(OpLogDb.attachmentBody(thisHstId(), contextId,
        thisDomId(), msgName, slot));
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
   * Same-JVM hop. Builds the child context from the extended chain
   * (context id is hop 0's, identity and trust are the caller's) and
   * delivers through {@link #deliverLocal}. The handler's client
   * carries {@code extended} so its {@code send()} appends rather
   * than restarting the chain.
   */
  private String sendLocal(final DomId dstDomId, final JsonMsg jsonMsg,
                           final Provenance extended) throws DomatarException
  {
    final Context childCtx = new Context(srcContext.actId, srcContext.usrId,
                                         srcContext.usrName, srcContext.usrIp,
                                         srcContext.token, srcContext.trust,
                                         extended.contextId(),
                                         srcContext.httpHeaders);

    return deliverLocal(dstDomId, jsonMsg, childCtx, srcDomain,
                        srcContextPath, srcContextRealPath, extended);
  }

  /**
   * In-process delivery to an object already on this JVM.
   * {@code sendLocal} calls this after it has appended a hop.
   * {@code Msg.doAction} calls this for the object the HTTP request
   * addressed, passing the provenance that arrived on the request:
   * the hop and the {@code op_msg} edge already exist on the sender,
   * so this method writes neither.
   *
   * Class identification: the envelope wins; the obj row is loaded
   * only when the envelope carries no class. Container services work
   * without an obj row by relying on the envelope class.
   *
   * {@code stamped} is the context the caller already decided
   * (the child context after a hop, or the Verdict stamp on HTTP
   * inbound). This method does not rebuild it from {@code chain}.
   */
  static String deliverLocal(final DomId dstDomId, final JsonMsg jsonMsg,
                                    final Context stamped, final String srcDomain,
                                    final String contextPath, final String contextRealPath,
                                    final Provenance chain) throws DomatarException
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

    final Provenance held = chain != null ? chain : Provenance.empty();
    final HttpClient msgClient = inbound(dstDomId, srcDomain, stamped,
                                         contextPath, contextRealPath, held);
    final HandlerClient handler = new HandlerClient(msgClient);

    jsonMsg.setContext(stamped);

    if (msgHandler instanceof ObjImpl)
    {
      final ObjImpl impl = (ObjImpl) msgHandler;
      final String contextId = stamped != null ? stamped.contextId : null;
      msgClient.snapshotPriors(contextId, jsonMsg.getDstId().toString(),
          jsonMsg.getOperation());
      if (!OpLog.admit(impl, handler, jsonMsg, obj, held))
        return impl.notAuthorized(jsonMsg);
    }

    final String ret = msgHandler.handleMsg(jsonMsg.toString(), obj,
        contextPath, contextRealPath, handler);
    if (msgHandler instanceof ObjImpl)
      OpLog.handled(handler, jsonMsg, obj, ret);
    return ret;
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

  private String currentContextId()
  {
    return srcContext != null ? srcContext.contextId : null;
  }

  private String thisDomId()
  {
    return srcDomId.toString();
  }

  String destinationDomId()
  {
    return thisDomId();
  }

  private String thisHstId()
  {
    return srcDomId.hstId;
  }

  /**
   * JSON text for attach, or null to skip the write (rejected type).
   * {@code json == null} is a delete, not a reject.
   */
  private static String jsonTextOrReject(final Object json) throws DomatarException
  {
    if (json == null)
      return null;

    if (json instanceof byte[]
        || !(json instanceof JsonMap || json instanceof JsonList
            || json instanceof String || json instanceof Number
            || json instanceof Boolean))
    {
      LOG.warning("HttpClient.attach: rejected non-JSON type "
          + json.getClass().getName());
      return null;
    }

    if (json instanceof JsonMap)
      return new String(CanonicalJson.canonicalize((JsonMap) json),
          StandardCharsets.UTF_8);

    return Json.toJson(json);
  }

  /**
   * Cross-provider entry. Verifies {@code inboundProv}, stamps trust from
   * that verdict, then {@link #deliverLocal}. A caller-supplied context
   * on {@code jsonMsg} is replaced.
   */
  public static String receive(final JsonMsg jsonMsg, final Provenance inboundProv,
                               final String srcDomain, final String contextPath,
                               final String contextRealPath) throws DomatarException
  {
    if (jsonMsg == null || inboundProv == null)
      return errorMsg("Unknown", "No Message");

    final DomId dstDomId = jsonMsg.getDstId();
    final boolean directory = isDirectoryDispatch(jsonMsg);

    if (!directory && inboundProv.isUnsignedHttp())
      return errorMsg(jsonMsg.getOperation(), "Unsigned message");

    final Verdict verdict = verifyInbound(inboundProv, jsonMsg);
    final Context dispatchContext = stampFromVerdict(jsonMsg, verdict);
    jsonMsg.setContext(dispatchContext);

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
      final String prvHstId = DomatarConfig.getHstId();
      final Hst hst = dstDomId == null ? null : HstDb.getHst(dstDomId.hstId);

      if (hst == null || hst.prvId == null || !hst.prvId.equals(prvHstId))
        return errorMsg(jsonMsg.getOperation(), "Hst not found");

      return errorMsg(jsonMsg.getOperation(), "Obj not found");
    }

    final DomatarInterface msgHandler = ImplMap.get(clsAppId, clsId);

    if (msgHandler == null)
      return errorMsg(jsonMsg.getOperation(), "Handler not found");

    if (msgHandler instanceof ObjImpl
        && ((ObjImpl) msgHandler).requiresPath()
        && verdict.trust != Trust.ACCOUNT)
    {
      System.out.println("WARN: requiresPath: " + verdict.reason);
      return errorMsg(jsonMsg.getOperation(), "Not authorized");
    }

    return deliverLocal(dstDomId, jsonMsg, dispatchContext, srcDomain,
                        contextPath, contextRealPath, inboundProv);
  }

  /**
   * Browser entry. Resolves the session inside core, then returns a
   * client whose {@code send} can mint hop 0 as that account. Null when
   * the token does not match. Anonymous mode stamps {@code act@act}.
   */
  public static BrowserSession openBrowserSession(final boolean anonymous,
                                                  final String usrId,
                                                  final String token,
                                                  final String usrIp,
                                                  final JsonMap httpHeaders,
                                                  final String srcDomain,
                                                  final String contextPath,
                                                  final String contextRealPath,
                                                  final String prvHstId) throws DomatarException
  {
    final Act act;

    if (anonymous)
      act = new Act("act@act", "act@act", "Act", null);
    else
    {
      final Context loginContext = new Context(null, usrId, null, usrIp, token,
                                               Trust.NONE, null, httpHeaders);
      final DomId loginDomId = new DomId(prvHstId, "act", "login@act", "loginObj");
      final HttpClient loginClient = new HttpClient(loginDomId, srcDomain, loginContext,
                                                    contextPath, contextRealPath);
      act = LoginRemote.verifyLogin(usrId, null, token, usrIp, loginClient);

      if (act == null)
        return null;
    }

    final DomId srcDomId = new DomId(prvHstId, "ui", act.actId, "uiObj");
    final Context context = new Context(act.actId, usrId, act.usrName, usrIp, token,
                                        Trust.ACCOUNT, null, httpHeaders);
    final HttpClient rootClient = new HttpClient(srcDomId, srcDomain, context,
                                                 contextPath, contextRealPath);
    return new BrowserSession(rootClient, new HandlerClient(rootClient), act, context);
  }

  /**
   * Mints a new lineage for an account {@link com.domatar.db.ActDb#addAct}
   * just inserted. Refuses a missing permit or any other actId.
   */
  public static DomatarMsgClient lineageForNewAccount(final com.domatar.db.LineagePermit permit,
                                                      final String actId,
                                                      final String usrId,
                                                      final String usrName,
                                                      final String usrIp,
                                                      final String token,
                                                      final DomatarMsgClient parent)
      throws DomatarException
  {
    if (!(parent instanceof HandlerClient) || permit == null || !permit.consume(actId))
      throw new DomatarException("lineage permit refused");

    final HttpClient parentInner = ((HandlerClient) parent).inner();
    final Context stamped = new Context(actId, usrId, usrName, usrIp, token,
                                        Trust.ACCOUNT, null, null);
    final HttpClient rooted = new HttpClient(parentInner.srcDomId, parentInner.srcDomain,
                                             stamped, parentInner.srcContextPath,
                                             parentInner.srcContextRealPath,
                                             Provenance.empty(), true);
    return new HandlerClient(rooted);
  }

  /** Local operator tasks (Setup). Not a caller-chosen trust for app code. */
  static DomatarMsgClient operatorClient(final DomId src, final String domain,
                                         final String contextPath, final String contextRealPath,
                                         final String actId)
  {
    final Context stamped = new Context(actId, null, null, "127.0.0.1", null,
                                        Trust.ACCOUNT, null, null);
    return new HandlerClient(new HttpClient(src, domain, stamped, contextPath,
                                            contextRealPath, Provenance.empty(), true));
  }

  private static boolean isDirectoryDispatch(final JsonMsg jsonMsg) throws DomatarException
  {
    return "hst".equals(jsonMsg.getClsAppId()) && "hsts".equals(jsonMsg.getClsId());
  }

  private static Verdict verifyInbound(final Provenance prov, final JsonMsg jsonMsg)
      throws DomatarException
  {
    if (isDirectoryDispatch(jsonMsg))
      return Verdict.none("directory carve-out");

    int fpVersion = 1;
    Long localBindingVersion = null;
    final String hopActId = prov.hop0ActId();

    if (hopActId != null)
    {
      fpVersion = ActDb.getFpVersion(hopActId);
      final ActDb.BindingRow local = ActDb.getBindingRow(hopActId);
      if (local != null)
        localBindingVersion = Long.valueOf(local.version);
    }

    return prov.verify(jsonMsg.getBodyMap(), DirectoryKeyResolver.INSTANCE,
                       fpVersion, localBindingVersion);
  }

  private static Context stampFromVerdict(final JsonMsg jsonMsg, final Verdict verdict)
      throws DomatarException
  {
    final Context wireCtx = jsonMsg.getContext();

    return new Context(verdict.actId,
                       wireCtx == null ? null : wireCtx.usrId,
                       wireCtx == null ? null : wireCtx.usrName,
                       wireCtx == null ? null : wireCtx.usrIp,
                       wireCtx == null ? null : wireCtx.token,
                       verdict.trust,
                       verdict.contextId,
                       wireCtx == null ? null : wireCtx.httpHeaders);
  }

  private static Object parseAttachment(final String body) throws DomatarException
  {
    if (body == null || body.isEmpty())
      return null;

    try
    {
      return Json.parse(body);
    }
    catch (final DomatarException e)
    {
      return body;
    }
  }
}
