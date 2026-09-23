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

import com.domatar.core.HttpClient;
import com.domatar.core.RequestContext;
import com.domatar.core.DomatarConfig;
import com.domatar.crypto.Provenance;
import com.domatar.crypto.SecWire;
import com.domatar.db.DbConnection;
import com.domatar.install.OfferedHostsInstall;
import com.domatar.util.JsonMsg;
import com.domatar.util.DomatarException;

/**
 * Servlet implementation class Msg
 */
@WebServlet("/Msg")
public class Msg extends HttpServlet
{
  private static final long serialVersionUID = -1048792698141910023L;

  private static String dbConnection = null;

  @Override
  public void init(ServletConfig config)
    throws ServletException
  {
    super.init(config);

    final ServletContext servletContext = getServletContext();

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
   *  - DELIVERY: {@link HttpClient#receive} verifies the chain, stamps
   *    trust, applies requiresPath, and delivers in-process. It does
   *    not append a hop and does not write op_msg.
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

          if (inboundProv != null)
            retMsg = HttpClient.receive(jsonMsg, inboundProv, srcDomain,
                contextPath, contextRealPath);
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
