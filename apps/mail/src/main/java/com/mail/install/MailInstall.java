/*
 * Copyright (c) 2024 Domatar
 */

package com.mail.install;

import com.domatar.db.HstDb;
import com.domatar.db.LnkDb;
import com.domatar.db.ObjDb;
import com.domatar.install.AppInstall;
import com.domatar.install.CatalogInstall;
import com.domatar.install.DirectoryRegister;
import com.domatar.install.IconPaths;
import com.domatar.install.LaunchPaths;
import com.domatar.install.UserAppLocal;
import com.domatar.util.Lnk;
import com.domatar.util.DomId;
import com.domatar.util.DomatarException;
import com.domatar.util.DomatarMsgClient;

/**
 * Minimal Mail app install (Update-Mandatory-App-Rewrite.txt Phase 7).
 */
public class MailInstall implements AppInstall
{
  private static final String APP_ID = "mail";
  private static final String APP_NAME = "Mail";
  private static final String APP_DESC = "Send and receive mail";
  private static final long NAV_SEQ = 18L;

  @Override
  public void installProvider(final String prvId, final String domain)
      throws DomatarException
  {
    CatalogInstall.registerInCatalog(prvId, APP_ID, APP_NAME, APP_DESC, "0.1");
  }

  @Override
  public void installUser(final String actId,
                          final String usrId,
                          final String usrName,
                          final String prvId,
                          final String domain,
                          final DomatarMsgClient msgClient) throws DomatarException
  {
    final String appHstId = DomId.subHstId(APP_ID, actId);
    final String navHstId = DomId.subHstId("navigator", actId, prvId);

    if (HstDb.getHst(appHstId) == null)
      HstDb.addHst(appHstId, domain, prvId);

    DirectoryRegister.registerHst(appHstId, domain, prvId, msgClient);

    final DomId rootId = new DomId(navHstId, "navigator", actId, "root");
    final DomId appObjId = new DomId(appHstId, APP_ID, actId, "app-" + APP_ID);

    ObjDb.addObjIfMissing(appObjId, APP_ID, "app", APP_NAME, APP_DESC);

    if (LnkDb.getLnk(rootId, appObjId, "navigator", "app") == null)
      LnkDb.addLnk(new Lnk(rootId, appObjId,
          APP_ID, "app",
          APP_NAME, APP_DESC,
          "navigator", "app",
          null, NAV_SEQ));

    UserAppLocal.upsert(actId, usrId, usrName, prvId, domain,
        APP_ID, APP_NAME,
        IconPaths.launcher(APP_ID), LaunchPaths.relative(APP_ID),
        prvId, appHstId, String.valueOf(NAV_SEQ), msgClient);
  }
}
