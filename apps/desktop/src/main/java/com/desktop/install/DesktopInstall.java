/*
 * Copyright (c) 2024 Domatar
 */

package com.desktop.install;

import com.domatar.install.AppInstall;
import com.domatar.install.CatalogInstall;
import com.domatar.install.DesktopReplica;
import com.domatar.install.NavAppEntry;
import com.domatar.install.NavigatorReplica;
import com.domatar.util.DomId;
import com.domatar.util.DomatarException;
import com.domatar.util.DomatarMsgClient;

/**
 * Install routine for the Desktop app (Spec-Navigator PART 10.1;
 * Spec-Desktop-Synchronized.txt PART 3).
 *
 * <p>Creates the per-provider Desktop replica
 * {@code desktop-&lt;actId&gt;-&lt;prvId&gt;} and links it from the qualified
 * navigator root. Catalog tiles are still lazily seeded by AppsImpl on
 * first GetApps.
 *
 * Every step is idempotent: a second call is a no-op.
 */
public class DesktopInstall implements AppInstall
{
  @Override
  public void installProvider(final String prvId, final String domain) throws DomatarException
  {
    CatalogInstall.registerInCatalog(prvId, "desktop");
  }

  @Override
  public void installUser(final String actId,
                          final String usrId,
                          final String usrName,
                          final String prvId,
                          final String domain,
                          final DomatarMsgClient msgClient) throws DomatarException
  {
    install(actId, usrName, domain, prvId, msgClient);
  }

  private static void install(final String actId,
                              final String usrName,
                              final String domain,
                              final String prvId,
                              final DomatarMsgClient msgClient) throws DomatarException
  {
    // Defensive: ensure the qualified navigator root exists before linking.
    NavigatorReplica.ensure(actId, usrName, domain, prvId, msgClient);
    DesktopReplica.ensure(actId, domain, prvId, msgClient);

    final String desktopRepId = DomId.subHstId("desktop", actId, prvId);
    final String navRepId     = DomId.subHstId("navigator", actId, prvId);

    final DomId rootId    = new DomId(navRepId,     "navigator", actId, "root");
    final DomId appDeskId = new DomId(desktopRepId, "desktop",   actId, "app-desktop");

    NavAppEntry.ensureRootLnk(rootId, appDeskId, "desktop",
        "Desktop", "Your home screen", 3);
  }
}
