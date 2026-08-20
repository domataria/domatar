/*
 * Copyright (c) 2024 Domatar
 */

package com.domatar.install;

import com.domatar.db.LnkDb;
import com.domatar.db.ObjDb;
import com.domatar.util.IdGen;
import com.domatar.util.Lnk;
import com.domatar.util.Obj;
import com.domatar.util.ObjAttrs;
import com.domatar.util.DomId;
import com.domatar.util.DomatarException;
import com.domatar.util.DomatarMsgClient;

/**
 * Direct ObjDb upsert of a {@code userApps} row for InstallUser paths that
 * cannot call {@code UserAppRegistry} (app JARs must not depend on
 * domatar-app). Same row shape as UserAppsImpl.InstallUserApp.
 */
public final class UserAppLocal
{
  private UserAppLocal() {}

  public static void upsert(final String actId,
                            final String usrId,
                            final String usrName,
                            final String homePrvId,
                            final String domain,
                            final String appId,
                            final String displayName,
                            final String iconPath,
                            final String launchPath,
                            final String hostPrvId,
                            final String appHstId,
                            final String position,
                            final DomatarMsgClient msgClient)
      throws DomatarException
  {
    if (actId == null || homePrvId == null || appId == null)
      return;

    if (domain != null && !domain.isEmpty())
    {
      final DomId userApps = UserSubstrateIds.userApps(actId, homePrvId);

      if (ObjDb.getObj(userApps) == null)
        UserSubstrateInstall.ensureUserSubstrate(
            actId,
            usrId != null ? usrId : actId,
            usrName != null ? usrName : actId,
            homePrvId, domain, msgClient);
    }

    final DomId userApps = UserSubstrateIds.userApps(actId, homePrvId);
    final String pos = (position != null && !position.isEmpty())
        ? position : "100";
    final String version = IdGen.getCurTimeBase64();
    final DomId rowDomId = UserSubstrateIds.userAppRow(actId, homePrvId, appId);
    final Obj existing = ObjDb.getObj(rowDomId);

    if (existing != null)
      ObjDb.deleteObj(rowDomId);

    final ObjAttrs rowAttrs = new ObjAttrs();

    rowAttrs.addAttr("DisplayName",
        displayName != null ? displayName : appId);
    rowAttrs.addAttr("IconPath", iconPath != null ? iconPath : "");
    rowAttrs.addAttr("LaunchPath", launchPath != null ? launchPath : "");
    rowAttrs.addAttr("Position", pos);
    rowAttrs.addAttr("Version", version);
    rowAttrs.addAttr("Tombstone", "False");

    if (hostPrvId != null && !hostPrvId.isEmpty())
      rowAttrs.addAttr("HostPrvId", hostPrvId);

    if (appHstId != null && !appHstId.isEmpty())
      rowAttrs.addAttr("AppHstId", appHstId);

    final String name = displayName != null ? displayName : appId;

    ObjDb.addObj(new Obj(rowDomId, "domatar", "userApp", name,
        "Installed application", rowAttrs));

    if (LnkDb.getLnk(userApps, rowDomId, "domatar", "userApp") == null)
      LnkDb.addLnk(new Lnk(userApps, rowDomId,
          "domatar", "userApp",
          name, "Installed application",
          "domatar", "userApp",
          appId, 0));
  }
}
