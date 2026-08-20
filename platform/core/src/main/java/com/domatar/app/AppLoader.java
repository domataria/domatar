/*
 * Copyright (c) 2024 Domatar
 */

package com.domatar.app;

import com.domatar.core.ClsMap;
import com.domatar.core.ImplMap;
import com.domatar.install.AppInstall;
import com.domatar.install.AppUserInstallHandler;
import com.domatar.servlet.AppAssetServlet;
import com.domatar.util.DomatarException;
import com.domatar.util.DomatarInterface;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;
import jakarta.servlet.ServletRegistration;
import jakarta.servlet.http.HttpServlet;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The sole {@link ServletContextListener} declared in the platform WAR's
 * {@code web.xml}.
 *
 * <p>On startup, discovers every {@code *.jar} in {@code WEB-INF/apps/},
 * loads each one with its own {@link URLClassLoader}, reads its
 * {@code app.manifest}, registers its handlers in {@link ImplMap}, registers
 * its Wui servlets programmatically, and registers a per-app
 * {@link AppAssetServlet}.
 *
 * <p>On shutdown, closes each URLClassLoader (best-effort).
 * Hot-reload of individual app JARs is out of scope for this upgrade.
 */
public class AppLoader implements ServletContextListener
{
  private static final Logger LOG = Logger.getLogger(AppLoader.class.getName());

  private final List<URLClassLoader> loaders = new ArrayList<>();

  @Override
  public void contextInitialized(ServletContextEvent sce)
  {
    ServletContext ctx = sce.getServletContext();

    String appsRealPath = ctx.getRealPath("/WEB-INF/apps");

    if (appsRealPath == null)
    {
      LOG.warning("AppLoader: getRealPath returned null for /WEB-INF/apps — skipping app loading");
      return;
    }

    File appsDir = new File(appsRealPath);

    if (!appsDir.exists() || !appsDir.isDirectory())
    {
      LOG.warning("AppLoader: WEB-INF/apps directory not found at " + appsRealPath);
      return;
    }

    File[] jars = appsDir.listFiles((dir, name) -> name.endsWith(".jar"));

    if (jars == null || jars.length == 0)
    {
      LOG.info("AppLoader: no app JARs found in " + appsRealPath);
      return;
    }

    Arrays.sort(jars, (a, b) -> a.getName().compareTo(b.getName()));

    LOG.info("AppLoader: discovered " + jars.length + " app JARs in WEB-INF/apps/");

    for (File jar : jars)
      loadApp(ctx, jar);

    // Start with a cold ClsMap so stale resolved descriptors from a
    // previous deployment do not survive a redeploy.
    ClsMap.invalidateAll();

    // Self-test: log all registered handlers.
    List<String> entries = ImplMap.describe();

    LOG.info("AppLoader: ImplMap has " + entries.size() + " (clsAppId, clsId) entries");

    for (String entry : entries)
      LOG.fine("AppLoader:   " + entry);

    int appCount = AppRegistry.all().size();

    if (appCount != jars.length)
      LOG.warning("AppLoader: AppRegistry has " + appCount
          + " apps but " + jars.length + " JARs were found — check for load errors");
  }

  @Override
  public void contextDestroyed(ServletContextEvent sce)
  {
    for (URLClassLoader cl : loaders)
    {
      try
      {
        cl.close();
      }
      catch (Exception e)
      {
        LOG.log(Level.WARNING, "AppLoader: error closing URLClassLoader", e);
      }
    }

    loaders.clear();
  }

  // -------------------------------------------------------------------------

  private void loadApp(ServletContext ctx, File jar)
  {
    String jarName = jar.getName();

    try
    {
      URL jarUrl = jar.toURI().toURL();

      URLClassLoader cl = new URLClassLoader(
          new URL[]{ jarUrl },
          AppLoader.class.getClassLoader());

      loaders.add(cl);

      AppManifest m = AppManifest.read(cl);

      // Instantiate the install class.
      Class<?> installCls = Class.forName(m.getInstallClassName(), true, cl);
      AppInstall installInstance =
          (AppInstall) installCls.getDeclaredConstructor().newInstance();

      // Register handlers from the manifest.
      for (AppManifest.HandlerEntry he : m.getHandlers())
      {
        Class<?> hc = Class.forName(he.className, true, cl);
        DomatarInterface h =
            (DomatarInterface) hc.getDeclaredConstructor().newInstance();
        ImplMap.register(he.clsAppId, he.clsId, h);
      }

      // Register the generic install handler for this app.
      ImplMap.register(m.getAppId(), "install",
                       new AppUserInstallHandler(m.getAppId()));

      // Register Wui servlets.
      for (AppManifest.WuiEntry we : m.getWuis())
      {
        Class<?> wc = Class.forName(we.className, true, cl);
        String   urlPattern = "/" + m.getAppId() + "/Wui/" + we.wuiName + "/*";
        String   servletName = m.getAppId() + "." + we.wuiName;

        HttpServlet wuiServlet =
            (HttpServlet) wc.getDeclaredConstructor().newInstance();

        ServletRegistration.Dynamic reg =
            ctx.addServlet(servletName, wuiServlet);

        if (reg != null)
          reg.addMapping(urlPattern);
      }

      // Register the generic asset servlet — catches /<appId>/* after Wui.
      AppAssetServlet asset =
          new AppAssetServlet(m.getAppId(), m.getAssetDirectory(), cl);

      String assetServletName = m.getAppId() + ".assets";
      String assetPattern     = "/" + m.getAppId() + "/*";

      ServletRegistration.Dynamic ar =
          ctx.addServlet(assetServletName, asset);

      if (ar != null)
        ar.addMapping(assetPattern);

      // Record the loaded app.
      AppRegistry.register(
          new App(m.getAppId(), cl, m, installInstance, jarUrl));

      LOG.info("AppLoader: loaded " + m.getAppId()
          + " v" + m.getVersion()
          + "  (" + m.getHandlers().size() + " handlers, "
          + m.getWuis().size() + " Wuis)");
    }
    catch (DomatarException e)
    {
      LOG.log(Level.SEVERE,
          "AppLoader: failed to load " + jarName + " — " + e.getMessage(), e);
    }
    catch (Exception e)
    {
      LOG.log(Level.SEVERE,
          "AppLoader: unexpected error loading " + jarName, e);
    }
  }
}
