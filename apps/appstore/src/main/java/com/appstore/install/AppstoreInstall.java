/*
 * Copyright (c) 2024 Domatar
 */

package com.appstore.install;

import com.domatar.db.HstDb;
import com.domatar.db.LnkDb;
import com.domatar.db.ObjDb;
import com.domatar.install.AppInstall;
import com.domatar.install.CatalogInstall;
import com.domatar.install.ClsInstall;
import com.domatar.install.HomeHostInstall;
import com.domatar.install.SrvInstall;
import com.domatar.install.UserHostIds;
import com.domatar.util.Hst;
import com.domatar.util.Lnk;
import com.domatar.util.DomId;
import com.domatar.util.DomatarException;
import com.domatar.util.DomatarMsgClient;

/**
 * Install routine for the App Store application.
 * Spec-Installation.txt PART 7.
 *
 * Per-user structure:
 *   - appstore-&lt;actId&gt;-&lt;prvId&gt; hst row (shell replica per login home)
 *   - home obj  (appstore, home)
 *   - app-appstore obj
 *   - root → app-appstore lnk
 *   - app-appstore → home lnk
 *
 * Every step is idempotent: a second call is a no-op.
 */
public class AppstoreInstall implements AppInstall
{
  @Override
  public void installProvider(final String prvId, final String domain) throws DomatarException
  {
    CatalogInstall.registerInCatalog(prvId, "appstore");

    final Hst appstoreHst = HstDb.getHst("appstore");

    if (appstoreHst != null && prvId != null && prvId.equals(appstoreHst.prvId))
      ensureRegistry(domain, prvId);
  }

  /**
 * Idempotently create the global registry container on the App Store
 * home host (Spec-AppStore PART 4.3 / Update-AppStore KD9).
   */
  private static void ensureRegistry(final String domain, final String prvId)
      throws DomatarException
  {
    String registryActId = HomeHostInstall.actId("appstore");

    if (registryActId == null)
    {
      System.out.println("WARN: AppstoreInstall ensureRegistry skipped — "
          + "home user appstore@appstore not found");
      return;
    }

    final DomId regId = new DomId("appstore", "appstore", registryActId, "registry");

    ObjDb.addObjIfMissing(regId, "appstore", "registry",
        "App Store Registry", "Global listings and offers");

    SrvInstall.ensureSrvsContainer(regId,
        "Service descriptors for App Store registry", "appstore", 1);
    ClsInstall.ensureClssContainer(regId,
        "Class descriptors for App Store registry", "appstore", 2);

    SrvInstall.upsertSrvObj(regId, "appstore", "registry",
        "Global App Store registry (listings and offers)",
        "{" +
        "\"Description\":\"Marketplace registry. SearchApps / GetListing for discovery; RegisterOffer / WithdrawOffer for providers.\"," +
        "\"Msgs\":[" +
        "{\"Name\":\"SearchApps\",\"SideEffect\":\"Read\"}," +
        "{\"Name\":\"GetListing\",\"SideEffect\":\"Read\"}," +
        "{\"Name\":\"RegisterOffer\",\"SideEffect\":\"Write\"}," +
        "{\"Name\":\"WithdrawOffer\",\"SideEffect\":\"Write\"}" +
        "]" +
        "}");

    ClsInstall.upsertClsImplementing(regId, "appstore", "registry",
        "Global App Store registry (listings and offers)",
        "{\"ClsAppId\":\"appstore\",\"ClsId\":\"registry\"," +
        "\"Implements\":[\"appstore.registry\"]," +
        "\"Auth\":\"isVerified\"," +
        "\"MsgPolicy\":[" +
        "{\"Srv\":\"appstore.registry\",\"Name\":\"SearchApps\",\"SideEffect\":\"Read\"}," +
        "{\"Srv\":\"appstore.registry\",\"Name\":\"GetListing\",\"SideEffect\":\"Read\"}," +
        "{\"Srv\":\"appstore.registry\",\"Name\":\"RegisterOffer\",\"SideEffect\":\"Write\"}," +
        "{\"Srv\":\"appstore.registry\",\"Name\":\"WithdrawOffer\",\"SideEffect\":\"Write\"}" +
        "]}");
  }

  @Override
  public void installUser(final String actId,
                          final String usrId,
                          final String usrName,
                          final String prvId,
                          final String domain,
                          final DomatarMsgClient msgClient) throws DomatarException
  {
    install(actId, usrId, usrName, domain, prvId);
  }

  private static void install(final String actId,
                              final String usrId,
                              final String usrName,
                              final String domain,
                              final String prvId) throws DomatarException
  {
    final String appstoreHstId = UserHostIds.resolve("appstore", actId, usrId, prvId);
    final String navHstId      = DomId.subHstId("navigator", actId, prvId);

    // 1. Per-user appstore sub-host
    if (HstDb.getHst(appstoreHstId) == null)
      HstDb.addHst(appstoreHstId, domain, prvId);

    final DomId rootId        = new DomId(navHstId,      "navigator", actId, "root");
    final DomId appAppstoreId = new DomId(appstoreHstId, "appstore",  actId, "app-appstore");
    final DomId homeId        = new DomId(appstoreHstId, "appstore",  actId, "home");

    // 2. home obj — the App Store landing page object
    ObjDb.addObjIfMissing(homeId, "appstore", "home", "App Store", "Browse and install apps");

    // 3. app-appstore obj on the appstore sub-host (seqNum 8: after Quippin=1, Login=2, Desktop=3, Navigator=4, AIAgent=5, Bookstore=6, Spreadsheet=7)
    ObjDb.addObjIfMissing(appAppstoreId, "appstore", "app", "App Store", "Browse and install apps");
    ObjDb.reclassObj(appAppstoreId, "appstore", "app");

    // 4. root → app-appstore lnk. A leftover portable dest
    // (appstore-<actId>) can collide on truncated lnk PK with the replica
    // dest; skip in that case — Wui addresses the replica home directly.
    if (LnkDb.getLnk(rootId, appAppstoreId, "navigator", "app") == null)
    {
      try
      {
        LnkDb.addLnk(new Lnk(rootId, appAppstoreId,
                              "appstore", "app",
                              "App Store", "Browse and install apps",
                              "navigator", "app",
                              null, 8));
      }
      catch (final DomatarException e)
      {
        final String m = e.getMessage();

        if (m == null || m.indexOf("Duplicate") < 0)
          throw e;
      }
    }

    // 5. app-appstore → home lnk
    if (LnkDb.getLnk(appAppstoreId, homeId, "navigator", "container") == null)
      LnkDb.addLnk(new Lnk(appAppstoreId, homeId,
                            "appstore", "home",
                            "App Store", "Browse and install apps",
                            "navigator", "container",
                            null, 1));

    // 6. Services container + class container + service/slim-class descriptor objects.
    SrvInstall.ensureSrvsContainer(appAppstoreId,
        "Service descriptors for App Store", "appstore", 2);
    ClsInstall.ensureClssContainer(appAppstoreId,
        "Class descriptors for App Store", "appstore", 3);

    // appstore.app — structured LLM-native entry point with natural-language description
    SrvInstall.upsertSrvObj(appAppstoreId, "appstore", "app",
        "App Store app entry point (LLM-native operations)",
        "{" +
        "\"Description\":\"App Store entry point. Use GetApps to list installed and available applications in a single call.\"," +
        "\"Msgs\":[" +
        "{\"Name\":\"GetApps\",\"Description\":\"List all installed apps and all apps available for installation.\",\"SideEffect\":\"Read\"}" +
        "]," +
        "\"Attrs\":\"DisplayName, IconPath, LaunchPath\"" +
        "}");

    ClsInstall.upsertClsImplementing(appAppstoreId, "appstore", "app",
        "App Store app entry point (LLM-native operations)",
        "{\"ClsAppId\":\"appstore\",\"ClsId\":\"app\"," +
        "\"Implements\":[\"appstore.app\"]," +
        "\"Auth\":\"isVerified\"" +
        "}");

    // appstore.home — structured container
    SrvInstall.addSrvObj(homeId, "appstore", "home",
        "App Store home — lists installed and available applications",
        "{\"SrvAppId\":\"appstore\",\"SrvId\":\"home\",\"Attrs\":[]," +
        "\"Msgs\":[" +
        "{\"Name\":\"GetApps\",\"Type\":{\"InstalledApps\":[{\"AppId\":\"String\",\"AppName\":\"String\",\"AppDesc\":\"String\",\"IsCore\":\"String\"}],\"AvailableApps\":[{\"AppId\":\"String\",\"AppName\":\"String\",\"AppDesc\":\"String\"}]},\"Parms\":[]}," +
        "{\"Name\":\"InstallApp\",\"Parms\":[{\"Name\":\"AppId\",\"Type\":\"String\"}],\"Type\":{\"Status\":\"String\"}}," +
        "{\"Name\":\"UninstallApp\",\"Parms\":[{\"Name\":\"AppId\",\"Type\":\"String\"}],\"Type\":{\"Status\":\"String\"}}]}");

    ClsInstall.upsertClsImplementing(homeId, "appstore", "home",
        "App Store home — lists installed and available applications",
        "{\"ClsAppId\":\"appstore\",\"ClsId\":\"home\"," +
        "\"Implements\":[\"appstore.home\"]," +
        "\"MsgPolicy\":[" +
        "{\"Srv\":\"appstore.home\",\"Name\":\"GetApps\",\"SideEffect\":\"Read\"}," +
        "{\"Srv\":\"appstore.home\",\"Name\":\"UninstallApp\",\"SideEffect\":\"Destructive\"}" +
        "]}");
  }
}
