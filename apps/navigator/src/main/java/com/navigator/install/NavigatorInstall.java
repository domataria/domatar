/*
 * Copyright (c) 2024 Domatar
 */

package com.navigator.install;

import com.domatar.install.AppInstall;
import com.domatar.install.CatalogInstall;
import com.domatar.install.NavigatorReplica;
import com.domatar.util.DomatarException;
import com.domatar.util.DomatarMsgClient;

/**
 * Install routine for the Navigator app (Spec-Navigator PART 10.1;
 * Spec-Desktop-Synchronized.txt PART 3.1).
 *
 * <p>Creates the per-provider Navigator replica
 * {@code navigator-&lt;actId&gt;-&lt;prvId&gt;} (root + app-navigator).
 * Must run FIRST among install routines because other apps link into
 * the qualified navigator root.
 *
 * Every step is idempotent: a second call is a no-op.
 */
public class NavigatorInstall implements AppInstall
{
  @Override
  public void installProvider(final String prvId, final String domain)
      throws DomatarException
  {
    CatalogInstall.registerInCatalog(prvId, "navigator");
  }

  @Override
  public void installUser(final String actId,
                          final String usrId,
                          final String usrName,
                          final String prvId,
                          final String domain,
                          final DomatarMsgClient msgClient) throws DomatarException
  {
    NavigatorReplica.ensure(actId, usrName, domain, prvId, msgClient);
  }
}
