/*
 * Copyright (c) 2024 Domatar
 */

package com.domatar.servlet;

import com.domatar.core.SetupServlet;
import com.domatar.app.App;
import com.domatar.app.AppRegistry;
import com.domatar.core.DomatarConfig;
import com.domatar.db.ActDb;
import com.domatar.db.ObjDb;
import com.domatar.util.DomId;

/**
 * One-shot provider bootstrap endpoint at {@code /Setup} for the platform WAR.
 *
 * <p>Replaces the per-module setup chain from the old multi-WAR layout.
 * Registered in {@code WEB-INF/web.xml} (see Section 6.6).
 *
 * <p>doSetup sequence:
 * <ol>
 *   <li>Call {@link #doProviderBootstrap()} — creates the provider account,
 *       the domatar-app sub-host graph, and the App Catalog container.
 *       Default implementation is a no-op; the {@code domatar} module supplies
 *       this via a concrete subclass until {@code DomatarProviderInstall} is
 *       moved to {@code domatar-core} in Task 12.</li>
 *   <li>For each {@link App} in {@link AppRegistry#all()}, call
 *       {@code app.installInstance.installProvider(prvId, domain)}.  In the
 *       transitional build (Pass 1), the registry is empty so this is a no-op.</li>
 * </ol>
 *
 * <p>contextPath is always {@code DomatarConfig.PLATFORM_CONTEXT_PATH} in
 * Option D; preserved as a parameter on lower-level calls for future Option C
 * support.
 */
public class PlatformSetupServlet extends SetupServlet
{
  private static final long serialVersionUID = 1L;

  /**
   * Setup is considered done when the provider account exists AND the
   * app-catalog container object has been created.
   */
  @Override
  protected boolean isAlreadyDone() throws Exception
  {
    String prvActId = DomatarConfig.getPrvActId();

    if (prvActId == null)
      return false;

    if (ActDb.getAct(prvActId) == null)
      return false;

    DomId catalogId = new DomId(
        DomId.subHstId("domatar", prvActId), "domatar", prvActId, "app-catalog");

    return ObjDb.getObj(catalogId) != null;
  }

  @Override
  protected void doSetup() throws Exception
  {
    String prvId  = DomatarConfig.getPrvId();
    String domain = DomatarConfig.getDomain();

    // 1. Platform-level bootstrap (provider account + domatar sub-host graph).
    doProviderBootstrap();

    // 2. Per-app provider install (registers each app in the catalog).
    for (App app : AppRegistry.all())
      app.installInstance.installProvider(prvId, domain);
  }

  /**
   * Performs platform-level provider bootstrap: creates the provider account,
   * the domatar-app sub-host object graph, and the App Catalog container.
   *
   * <p>The default implementation is a no-op.  The {@code domatar} WAR module
   * overrides this (or wires {@code DomatarProviderInstall.install()} directly)
   * in Task 12 once {@code DomatarProviderInstall} is available to
   * {@code domatar-core}.
   */
  protected void doProviderBootstrap() throws Exception {}
}
