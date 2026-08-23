/*
 * Copyright (c) 2024 Domatar
 */

package com.domatar.install;

import com.domatar.db.HstDb;
import com.domatar.db.LnkDb;
import com.domatar.db.ObjDb;
import com.domatar.util.Lnk;
import com.domatar.util.DomId;
import com.domatar.util.DomatarException;
import com.domatar.util.DomatarMsgClient;

/**
 * Creates a per-provider Desktop replica
 * {@code desktop-&lt;actId&gt;-&lt;prvId&gt;} (Spec-Desktop-Synchronized.txt
 * PART 3). Twin of {@link MembershipReplica}.
 *
 * <p>Idempotent: a second call is a no-op when the apps container already
 * exists.
 */
public final class DesktopReplica
{
  private DesktopReplica() {}

  /**
   * Ensure hst + directory registration + apps container + app-desktop
   * (+ srv/cls descriptors) exist for {@code (actId, prvId)}.
   */
  public static void ensure(final String actId,
                            final String domain,
                            final String prvId,
                            final DomatarMsgClient msgClient) throws DomatarException
  {
    if (actId == null || domain == null || prvId == null)
      throw new DomatarException("DesktopReplica.ensure requires actId, domain, prvId");

    final String desktopRepId = DomId.subHstId("desktop", actId, prvId);

    if (HstDb.getHst(desktopRepId) == null)
      HstDb.addHst(desktopRepId, domain, prvId);

    DirectoryRegister.registerHst(desktopRepId, domain, prvId, msgClient);

    final DomId appsId    = new DomId(desktopRepId, "desktop", actId, "apps");
    final DomId appDeskId = new DomId(desktopRepId, "desktop", actId, "app-desktop");

    ObjDb.addObjIfMissing(appsId, "desktop", "apps",
        "Apps", "Installed applications");

    // Intrinsic Desktop tile — seeded locally per provider (PART 4.4a).
    ObjDb.addObjIfMissing(appDeskId, "desktop", "app",
        "Desktop", "Your home screen");
    ObjDb.reclassObj(appDeskId, "desktop", "app");

    if (LnkDb.getLnk(appDeskId, appsId, "navigator", "container") == null)
      LnkDb.addLnk(new Lnk(appDeskId, appsId,
                            "desktop", "apps",
                            "Apps", "Installed applications",
                            "navigator", "container",
                            null, 1));

    SrvInstall.ensureSrvsContainer(appDeskId,
        "Service descriptors for Desktop", "desktop", 2);
    ClsInstall.ensureClssContainer(appDeskId,
        "Class descriptors for Desktop", "desktop", 3);

    installDescriptors(appDeskId, appsId);
  }

  private static void installDescriptors(final DomId appDeskId,
                                          final DomId appsId) throws DomatarException
  {
    SrvInstall.upsertSrvObj(appDeskId, "desktop", "app",
        "Desktop app entry point (LLM-native operations)",
        "{" +
        "\"Description\":\"Desktop home screen. Use GetApps to list the apps installed as tiles on the user's home screen.\"," +
        "\"Msgs\":[" +
        "{\"Name\":\"GetApps\",\"Description\":\"List all apps installed as tiles on the user's desktop home screen.\",\"SideEffect\":\"Read\"}" +
        "]," +
        "\"Attrs\":\"DisplayName, IconPath, LaunchPath\"" +
        "}");

    ClsInstall.upsertClsImplementing(appDeskId, "desktop", "app",
        "Desktop app entry point (LLM-native operations)",
        "{\"ClsAppId\":\"desktop\",\"ClsId\":\"app\"," +
        "\"Implements\":[\"desktop.app\"]," +
        "\"Auth\":\"isVerified\"" +
        "}");

    SrvInstall.addSrvObj(appsId, "desktop", "apps",
        "Container listing all apps installed on the user home screen",
        "{\"SrvAppId\":\"desktop\",\"SrvId\":\"apps\",\"Attrs\":[]," +
        "\"Msgs\":[" +
        "{\"Name\":\"GetApps\",\"Type\":{\"Apps\":[{\"AppId\":\"String\",\"DisplayName\":\"String\",\"IconPath\":\"String\",\"LaunchPath\":\"String\",\"Position\":\"String\",\"TileVersion\":\"String\"}]},\"Parms\":[]}," +
        "{\"Name\":\"GetAppsVersion\",\"Type\":{\"Version\":\"String\"},\"Parms\":[]}," +
        "{\"Name\":\"PullApps\",\"Type\":{\"Apps\":[{\"AppId\":\"String\",\"DisplayName\":\"String\",\"IconPath\":\"String\",\"LaunchPath\":\"String\",\"Position\":\"String\",\"TileVersion\":\"String\",\"Tombstone\":\"String\"}]},\"Parms\":[]}," +
        "{\"Name\":\"MergeApps\",\"Parms\":[{\"Name\":\"Apps\",\"Type\":\"List\"}],\"Type\":{\"Merged\":\"String\"}}," +
        "{\"Name\":\"ReconcileApps\",\"Parms\":[],\"Type\":{\"Reconciled\":\"String\",\"MergedFrom\":\"String\"}}," +
        "{\"Name\":\"InstallApp\",\"Parms\":[{\"Name\":\"AppId\",\"Type\":\"String\"},{\"Name\":\"DisplayName\",\"Type\":\"String\"},{\"Name\":\"IconPath\",\"Type\":\"String\"},{\"Name\":\"LaunchPath\",\"Type\":\"String\"},{\"Name\":\"Position\",\"Type\":\"String?\"}],\"Type\":{\"Installed\":\"String\"}}," +
        "{\"Name\":\"UninstallApp\",\"Parms\":[{\"Name\":\"AppId\",\"Type\":\"String\"}],\"Type\":{\"Installed\":\"String\"}}]}");

    ClsInstall.upsertClsImplementing(appsId, "desktop", "apps",
        "Container listing all apps installed on the user home screen",
        "{\"ClsAppId\":\"desktop\",\"ClsId\":\"apps\"," +
        "\"Implements\":[\"desktop.apps\"]," +
        "\"MsgPolicy\":[" +
        "{\"Srv\":\"desktop.apps\",\"Name\":\"GetApps\",\"SideEffect\":\"Read\"}," +
        "{\"Srv\":\"desktop.apps\",\"Name\":\"GetAppsVersion\",\"SideEffect\":\"Read\"}," +
        "{\"Srv\":\"desktop.apps\",\"Name\":\"PullApps\",\"SideEffect\":\"Read\"}," +
        "{\"Srv\":\"desktop.apps\",\"Name\":\"ReconcileApps\",\"SideEffect\":\"Read\"}," +
        "{\"Srv\":\"desktop.apps\",\"Name\":\"UninstallApp\",\"SideEffect\":\"Destructive\"}" +
        "]}");
  }
}
