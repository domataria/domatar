/*
 * Copyright (c) 2024 Domatar
 */

package com.domatar.servlet;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.UnsupportedEncodingException;
import java.net.URL;
import java.net.URLDecoder;

import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import com.domatar.core.Context;
import com.domatar.core.HttpClient;
import com.domatar.core.LoginRemote;
import com.domatar.core.DomatarConfig;
import com.domatar.db.DbConnection;
import com.domatar.util.Act;
import com.domatar.util.JsonHashMap;
import com.domatar.util.JsonList;
import com.domatar.util.JsonMap;
import com.domatar.util.JsonMsg;
import com.domatar.util.DomatarException;
import com.domatar.util.DomatarMsgClient;
import com.domatar.util.DomId;

public abstract class DomatarServlet extends HttpServlet
{
  private static final long serialVersionUID = 1013814213213857810L;

  private static String dbConnection = null;
  // This server's own hstId - read from DOMATAR_HSTID at startup.
  // Other hosts that this server provides will name it in their PrvId column.
  protected static String prvHstId = null;

  //protected abstract Obj getDstObj(HttpServletRequest req, DomId srcDomId, Act srcAct) throws DomatarException;

  protected abstract JsonMsg getMsg(HttpServletRequest req,
                                    DomId srcDomId,
                                    Context context,
                                    Act srcAct,
                                    DomatarMsgClient msgClient) throws DomatarException;

  /**
   * Override to return true in servlets that handle anonymous / account-creation
   * requests (e.g. ActWui). When true, doAction skips login verification and
   * fabricates a placeholder Act so the handler can proceed unauthenticated.
   * Default: false (all servlets require a verified user session).
   */
  protected boolean isAnonymousAccess()
  {
    return false;
  }

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
  public void doGet(HttpServletRequest req, HttpServletResponse res)
    throws ServletException, IOException
  {
    doAction(req, res);
  }

  @Override
  public void doPost(HttpServletRequest req, HttpServletResponse res)
    throws ServletException, IOException
  {
    doAction(req, res);
  }

  private void doAction(final HttpServletRequest req, final HttpServletResponse res) throws ServletException
  {
    // Declared outside the try so the catch block can write a JSON error
    // response instead of letting Tomcat produce an HTML 500 page.
    PrintWriter out = null;

    try
    {
      res.setContentType("text/html; charset=UTF-8");
      res.setCharacterEncoding("UTF-8");
      res.addHeader("P3P","CP='IDC DSP COR ADM DEVi TAIi PSA PSD IVAi IVDi CONi HIS OUR IND CNT'");
      out = res.getWriter();

      final String usrIp = req.getRemoteAddr();

      final Cookie[] cookies = req.getCookies();

      final String token = Cookies.getCookie(cookies, "token");
      final String usrId = Cookies.getCookie(cookies, "usrId");
      final String geoLoc = Cookies.getCookie(cookies, "loc");

      final JsonMap httpHeaders = new JsonHashMap();

      final String userAgent = req.getHeader("user-agent");
      final String referer = req.getHeader("referer");
      final String language = req.getHeader("accept-language");

      if (userAgent != null)
        httpHeaders.put("UserAgent", userAgent);

      if (referer != null)
        httpHeaders.put("Referer", referer);

      if (language != null)
        httpHeaders.put("AcceptLanguage", language);

      if (geoLoc != null)
        httpHeaders.put("GeoLoc", geoLoc);

      // srcDomain = req.getServerName();
      final String srcDomain = new URL(req.getRequestURL().toString()).getHost();

      final ServletContext servletContext = req.getServletContext();

      final String path = servletContext.getContextPath();
      final String contextPath = path.replaceAll("//", "/"); // If it starts with two slashes change it to one slash

      final String realPath = servletContext.getRealPath("");

      String contextRealPath;

      if (realPath == null)
        contextRealPath = null;
      else
      {
        contextRealPath = realPath.replace("\\", "/");

        if (contextRealPath.charAt(contextRealPath.length() - 1) == '/') // If it ends with a slash
          contextRealPath = contextRealPath.substring(0, contextRealPath.length() - 1);
      }

      Act act;

      if (isAnonymousAccess())
        act = new Act("act@act", "act@act", "Act", null);
      else
      {
        // Pre-verify context: unverified by definition - we are CALLING
        // verifyLogin, not having been verified yet.
        final Context loginContext = new Context(null,
                                                 usrId,
                                                 null,
                                                 usrIp,
                                                 token,
                                                 false,
                                                 httpHeaders,
                                                 new DomId[0]);

        final DomId loginDomId = new DomId(prvHstId, "act", "login@act", "loginObj");

        final DomatarMsgClient loginMsgClient = new HttpClient(loginDomId,
                                                               srcDomain,
                                                               loginContext,
                                                               contextPath,
                                                               contextRealPath);

        act = LoginRemote.verifyLogin(usrId, null, token, usrIp, loginMsgClient);

        if (act == null)
        {
          final JsonMsg errorMsg = new JsonMsg();

          errorMsg.addError("", "Not Logged in");

          out.print(errorMsg.getWui());

          return;
        }
      }

      final DomId srcDomId = new DomId(prvHstId, "ui", act.actId, "uiObj");

      // Trust boundary: we just verified usrId/token against the local act
      // table. Mark the dispatch context verified=true so downstream
      // handlers can authorize via a flag read - no per-handler DB hit.
      final Context context = new Context(act.actId,
                                          usrId,
                                          act.usrName,
                                          usrIp,
                                          token,
                                          true,
                                          httpHeaders,
                                          new DomId[] { srcDomId });

      final DomatarMsgClient msgClient = new HttpClient(srcDomId,
                                                        srcDomain,
                                                        context,
                                                        contextPath,
                                                        contextRealPath);

      final JsonMsg msg = getMsg(req, srcDomId, context, act, msgClient);

      if (msg.isResponse())
      {
        out.print(msg.getWui());

        return;
      }

      final JsonMsg retMsg = msgClient.send(msg.getDstId(), msg);

      final JsonList jsonCookies = retMsg.getCookies();

      if (jsonCookies != null)
      {
        for (Object obj : jsonCookies)
        {
          final JsonMap jsonCookie = (JsonMap) obj;

          final String name = jsonCookie.getString("Name");
          final String value = jsonCookie.getString("Value");
          final Number age = jsonCookie.getNumber("Age");

          if (name != null && value != null && age != null)
            Cookies.setCookie(res, name, value, age.intValue());
        }
      }

      retMsg.removeCookies();

      out.print(retMsg.getWui());
    }
    catch (Exception e)
    {
      // Log the full stack trace so it still appears in catalina.out /
      // localhost.YYYY-MM-DD.log for server-side diagnosis.
      System.err.println("DomatarServlet unhandled exception: " + e);
      e.printStackTrace();

      // Return a JSON error so the browser sees {"Error":"Failure",...}
      // rather than a Tomcat HTML 500 page.  The jQuery success callback
      // will fire and display the message in the UI's error banner.
      try
      {
        if (out == null)
          out = res.getWriter();

        final JsonMsg errorMsg = new JsonMsg();
        final String  detail   = e.getMessage() != null ? e.getMessage()
                                                        : e.getClass().getSimpleName();
        errorMsg.addError("", detail);
        out.print(errorMsg.getWui());
      }
      catch (Exception e2)
      {
        // If we cannot even write the JSON error, fall back to a 500.
        throw new ServletException(e);
      }
    }
  }

  protected String getParam(final HttpServletRequest req, final String param)
  {
    final String[] vals = req.getParameterValues(param);

    String val;

    if (vals != null)
      val = vals[0];
    else
      val = null;

    return decode(val);
  }

  private String decode(final String val)
  {
    if (val == null)
      return null;

    try
    {
      return URLDecoder.decode(val.trim(), "UTF-8");
    }
    catch (UnsupportedEncodingException e)
    {
      return null;
    }
  }
}
