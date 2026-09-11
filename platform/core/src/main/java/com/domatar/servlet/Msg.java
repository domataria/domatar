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
import com.domatar.core.Trust;
import com.domatar.core.DomatarConfig;
import com.domatar.crypto.Provenance;
import com.domatar.crypto.SecWire;
import com.domatar.crypto.Verdict;
import com.domatar.db.ActDb;
import com.domatar.db.DbConnection;
import com.domatar.db.HstDb;
import com.domatar.db.ObjDb;
import com.domatar.install.DirectoryKeyResolver;
import com.domatar.install.OfferedHostsInstall;
import com.domatar.log.OpLog;
import com.domatar.util.Hst;
import com.domatar.util.JsonMsg;
import com.domatar.util.Obj;
import com.domatar.util.ObjImpl;
import com.domatar.util.DomatarException;
import com.domatar.util.DomatarInterface;
import com.domatar.util.DomId;

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

    try
    {
      OfferedHostsInstall.ensure();
    }
    catch (DomatarException e)
    {
      System.out.println("WARN: OfferedHostsInstall.ensure failed: " + e);
    }
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
   *  - VERIFICATION (identity): {@link Provenance#verify} on the inbound
   *    path, unconditionally. Trust, actId and contextId come from the
   *    Verdict; informational fields come from the wire Context. We never
   *    trust the wire's ActId, Verified, contextId, or DomIdPath.
   *
   *  - AUTHORIZATION: {@link ObjImpl#hasRights}. Handlers that require a
   *    verified caller call Auth.isVerified (Trust.ACCOUNT). {@code
   *    requiresPath()} is policy only: a non-ACCOUNT verdict is fatal for
   *    that class (KD14).
   *
   * Unsigned HTTP messages (no {@code Sec=} parameter, empty path, or
   * unsigned hop 0) are rejected. "Unsigned is allowed" would be a
   * downgrade attack (strip the signature, claim pre-bootstrap). The
   * directory carve-out is the exception (Spec PART 10.3 / 8.10).
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
    String retMsg = null;

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
          final boolean directory = isDirectoryDispatch(jsonMsg);

          Provenance inboundProv;
          try
          {
            inboundProv = inboundProvenance(req);
          }
          catch (final DomatarException e)
          {
            retMsg = errorMsg(jsonMsg.getOperation(), e.getMessage());
            inboundProv = null;
          }

          if (inboundProv != null && !directory && inboundProv.isUnsignedHttp())
          {
            retMsg = errorMsg(jsonMsg.getOperation(), "Unsigned message");
          }
          else if (inboundProv != null)
          {
            final Verdict verdict = verifyInbound(inboundProv, jsonMsg);

            final Context dispatchContext = stampFromVerdict(jsonMsg, verdict);
            jsonMsg.setContext(dispatchContext);
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
                if (msgHandler instanceof ObjImpl
                    && ((ObjImpl) msgHandler).requiresPath()
                    && verdict.trust != Trust.ACCOUNT)
                {
                  System.out.println("WARN: requiresPath: " + verdict.reason);
                  retMsg = errorMsg(jsonMsg.getOperation(), "Not authorized");
                }
                else
                {
                  final HttpClient client = HttpClient.inbound(dstDomId, srcDomain,
                      dispatchContext, contextPath, contextRealPath, inboundProv);

                  if (msgHandler instanceof ObjImpl)
                  {
                    final ObjImpl impl = (ObjImpl) msgHandler;
                    client.snapshotPriors(dispatchContext.contextId,
                        jsonMsg.getDstId().toString(), jsonMsg.getOperation());
                    if (!impl.hasRights(jsonMsg, obj, client))
                      retMsg = impl.notAuthorized(jsonMsg);
                    else
                    {
                      OpLog.admitIfNeeded(client, jsonMsg, obj, inboundProv);
                      retMsg = msgHandler.handleMsg(msg, obj, contextPath,
                          contextRealPath, client);
                    }
                  }
                  else
                  {
                    retMsg = msgHandler.handleMsg(msg, obj, contextPath, contextRealPath,
                        client);
                  }
                }
              }
              else
                retMsg = errorMsg(jsonMsg.getOperation(), "Handler not found");
            }
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

  private Verdict verifyInbound(final Provenance prov, final JsonMsg jsonMsg)
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

  private static boolean isDirectoryDispatch(final JsonMsg jsonMsg)
      throws DomatarException
  {
    return "hst".equals(jsonMsg.getClsAppId())
        && "hsts".equals(jsonMsg.getClsId());
  }

  private static Context stampFromVerdict(final JsonMsg jsonMsg,
                                          final Verdict verdict)
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

  private static Provenance inboundProvenance(final HttpServletRequest req)
      throws DomatarException
  {
    final String[] secValues = req.getParameterValues(SecWire.PARAM);
    final String secJson = (secValues != null && secValues.length > 0)
        ? secValues[0] : null;

    if (secJson == null || secJson.isEmpty())
      return Provenance.empty();

    return SecWire.decode(secJson);
  }
}
