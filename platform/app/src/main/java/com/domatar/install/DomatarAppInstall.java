/*
 * Copyright (c) 2024 Domatar
 */

package com.domatar.install;

import com.domatar.util.DomatarException;
import com.domatar.util.DomatarMsgClient;

/**
 * AppInstall implementation for the platform (domatar) application.
 *
 * <p>installProvider registers the platform in the app catalog.
 * The heavy provider bootstrap (account creation, sub-host graph) is
 * performed by DomatarProviderInstall.install(), called from
 * DomatarSetupServlet.doProviderBootstrap() before the per-app installs.
 *
 * <p>installUser installs core class descriptors on the navigator host
 * and ensures the per-login-provider user substrate
 * {@code domatar-&lt;actId&gt;-&lt;prvId&gt;}.
 */
public class DomatarAppInstall implements AppInstall
{
  @Override
  public void installProvider(final String prvId, final String domain)
      throws DomatarException
  {
    CatalogInstall.registerInCatalog(prvId, "domatar");
  }

  @Override
  public void installUser(final String actId, final String usrId, final String usrName,
                          final String prvId, final String domain,
                          final DomatarMsgClient msgClient) throws DomatarException
  {
    DomatarInstall.install(actId, prvId);
    UserSubstrateInstall.ensureUserSubstrate(
        actId, usrId, usrName, prvId, domain, msgClient);
  }
}
