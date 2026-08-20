/*
 * Copyright (c) 2024 Domatar
 */

package com.domatar.servlet;

import java.io.IOException;
import java.io.PrintWriter;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import com.domatar.app.App;
import com.domatar.app.AppRegistry;
import com.domatar.install.ClssMigration;
import com.domatar.install.SecurityMigration;
import com.domatar.install.ServiceMigration;

/**
 * Platform setup servlet.  Extends {@link PlatformSetupServlet} and supplies
 * the provider-bootstrap hook by invoking {@code DomatarProviderInstall.install()}
 * via the "domatar" app's classloader (loaded from WEB-INF/apps/ by AppLoader).
 * Using reflection avoids a cross-classloader direct reference.
 */
public class DomatarSetupServlet extends PlatformSetupServlet
{
  private static final long serialVersionUID = 1L;

  @Override
  protected void doProviderBootstrap() throws Exception
  {
    final App domatarApp = AppRegistry.get("domatar");
    if (domatarApp == null)
      throw new IllegalStateException(
          "DomatarSetupServlet: 'domatar' app not yet loaded in AppRegistry — "
          + "ensure AppLoader has initialised before calling /Setup");
    final Class<?> cls = Class.forName(
        "com.domatar.install.DomatarProviderInstall", true, domatarApp.classLoader);
    cls.getMethod("install").invoke(null);
  }

  /**
   * Intercept  GET /Setup?migrate=services  to run the service-migration
   * utility without going through the "already done" guard in SetupServlet.
   * All other requests are handled by the parent class normally.
   */
  @Override
  protected void doGet(final HttpServletRequest req, final HttpServletResponse res)
      throws ServletException, IOException
  {
    final String migrate = req.getParameter("migrate");

    if ("services".equals(migrate))
      handleServicesMigrate(req, res);
    else if ("security".equals(migrate))
      handleSecurityMigrate(req, res);
    else if ("classes".equals(migrate))
      handleClassesMigrate(req, res);
    else
      super.doGet(req, res);
  }

  @Override
  protected void doPost(final HttpServletRequest req, final HttpServletResponse res)
      throws ServletException, IOException
  {
    final String migrate = req.getParameter("migrate");

    if ("services".equals(migrate))
      handleServicesMigrate(req, res);
    else if ("security".equals(migrate))
      handleSecurityMigrate(req, res);
    else if ("classes".equals(migrate))
      handleClassesMigrate(req, res);
    else
      super.doPost(req, res);
  }

  private void handleServicesMigrate(final HttpServletRequest req, final HttpServletResponse res)
      throws IOException
  {
    res.setContentType("text/plain; charset=UTF-8");

    final String remoteAddr = req.getRemoteAddr();

    if (!"127.0.0.1".equals(remoteAddr) && !"::1".equals(remoteAddr))
    {
      res.setStatus(HttpServletResponse.SC_FORBIDDEN);
      res.getWriter().println("Migration endpoint is only accessible from localhost.");
      return;
    }

    final PrintWriter out = res.getWriter();

    try
    {
      final String summary = ServiceMigration.migrateAll();
      res.setStatus(HttpServletResponse.SC_OK);
      out.println("Service migration completed.");
      out.println(summary);
    }
    catch (Exception e)
    {
      res.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
      out.println("Service migration failed: " + e.getMessage());
      e.printStackTrace();
    }
  }

  /**
   * Handles  GET /Setup?migrate=classes  — creates the missing "clss" (Classes)
   * container objects and app → clss Navigator links for all existing users on
   * this provider.  Localhost only.  Idempotent.
   */
  private void handleClassesMigrate(final HttpServletRequest req, final HttpServletResponse res)
      throws IOException
  {
    res.setContentType("text/plain; charset=UTF-8");

    final String remoteAddr = req.getRemoteAddr();

    if (!"127.0.0.1".equals(remoteAddr) && !"::1".equals(remoteAddr))
    {
      res.setStatus(HttpServletResponse.SC_FORBIDDEN);
      res.getWriter().println("Migration endpoint is only accessible from localhost.");
      return;
    }

    final PrintWriter out = res.getWriter();

    try
    {
      final String summary = ClssMigration.migrateAll();
      res.setStatus(HttpServletResponse.SC_OK);
      out.println("Classes migration completed.");
      out.println(summary);
    }
    catch (Exception e)
    {
      res.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
      out.println("Classes migration failed: " + e.getMessage());
      e.printStackTrace();
    }
  }

  /**
   * Handles  GET /Setup?migrate=security  — runs the Phase 2 security migration
   * (actId fingerprint + host-id separator flip) without going through the
   * "already done" guard in SetupServlet.  Localhost only.
   *
   * Spec-Security.txt PART 3.3; Update-Security.txt Task 2.12.
   */
  private void handleSecurityMigrate(final HttpServletRequest req, final HttpServletResponse res)
      throws IOException
  {
    res.setContentType("text/plain; charset=UTF-8");

    final String remoteAddr = req.getRemoteAddr();

    if (!"127.0.0.1".equals(remoteAddr) && !"::1".equals(remoteAddr))
    {
      res.setStatus(HttpServletResponse.SC_FORBIDDEN);
      res.getWriter().println("Migration endpoint is only accessible from localhost.");
      return;
    }

    final PrintWriter out = res.getWriter();

    try
    {
      final String summary = SecurityMigration.migrateAll();
      res.setStatus(HttpServletResponse.SC_OK);
      out.println(summary);
    }
    catch (Exception e)
    {
      res.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
      out.println("Security migration failed: " + e.getMessage());
      e.printStackTrace();
    }
  }
}
