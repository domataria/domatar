/*
 * Copyright (c) 2024 Domatar
 */

package com.appstore.objimpl;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.domatar.app.App;
import com.domatar.app.AppRegistry;
import com.domatar.core.AppConfig;
import com.domatar.core.Context;
import com.domatar.core.DomatarConfig;
import com.domatar.db.HstDb;
import com.domatar.db.LnkDb;
import com.domatar.db.ObjDb;
import com.domatar.install.UserSubstrateIds;
import com.domatar.util.Hst;
import com.domatar.util.JsonArrayList;
import com.domatar.util.JsonHashMap;
import com.domatar.util.JsonList;
import com.domatar.util.JsonMap;
import com.domatar.util.JsonMsg;
import com.domatar.util.Lnk;
import com.domatar.util.Obj;
import com.domatar.util.ObjAttrs;
import com.domatar.util.ObjImpl;
import com.domatar.util.DomId;
import com.domatar.util.DomatarException;
import com.domatar.util.DomatarMsgClient;

/**
 * Handler for (appstore, home) — the App Store landing page object.
 * Spec-Installation.txt PART 7 / Spec-AppStore.txt PART 7.5.
 *
 * Supported messages:
 *   Open          — standard Navigator open (from ObjImpl)
 *   GetApps       — InstalledApps + AvailableApps (local catalog)
 *   GetInstalled  — InstalledApps only (Spec PART 7.5 shape)
 *   UninstallApp  — removes the navigator link for an app (hides it; data preserved)
 *
 * InstallApp is handled by ActManagerImpl (domatar WAR) so that the
 * per-app **InstallUser** handlers ({@link com.domatar.install.UserInstallDispatch}).
 * the appstore WAR does not need compile-time knowledge of every app.
 */
public class AppstoreImpl extends ObjImpl
{
  /** Apps that cannot be uninstalled by the user. */
  private static final Set<String> CORE_APPS = new HashSet<>(Arrays.asList(
      "navigator", "login", "desktop", "domatar", "appstore"));

  @Override
  public String handleMsg(final String msg,
                          final Obj obj,
                          final String contextPath,
                          final String contextRealPath,
                          final DomatarMsgClient msgClient) throws DomatarException
  {
    final JsonMsg inMsg  = new JsonMsg(msg);
    final String  opr    = inMsg.getOperation();
    final JsonMsg outMsg = new JsonMsg();

    if ("GetApps".equals(opr))
      getApps(opr, inMsg, outMsg, obj, true);
    else if ("GetInstalled".equals(opr))
      getApps(opr, inMsg, outMsg, obj, false);
    else if ("UninstallApp".equals(opr))
      uninstallApp(opr, inMsg, outMsg, obj, msgClient);
    else
      return super.handleMsg(msg, obj, contextPath, contextRealPath, msgClient);

    return outMsg.toString();
  }

  // -------------------------------------------------------------------------

  /**
   * Returns InstalledApps (and optionally AvailableApps from the local catalog).
   * Installed entries are enriched with PrvId/Domain/HstId from HstDb when
   * the portable host {@code <appId>-<actId>} is registered.
   */
  private void getApps(final String opr, final JsonMsg inMsg, final JsonMsg outMsg,
                       final Obj obj, final boolean includeAvailable)
      throws DomatarException
  {
    final String actId    = obj != null ? obj.domId.actId : inMsg.getDstId().actId;
    final String prvId    = DomatarConfig.getPrvId();
    final String prvActId = DomatarConfig.getPrvActId() != null
                                ? DomatarConfig.getPrvActId()
                                : prvId + "@" + prvId;

    // 1. All apps available on this provider (from app-catalog)
    final DomId     catalogId = new DomId(DomId.subHstId("domatar", prvActId), "domatar", prvActId, "app-catalog");
    final List<Lnk> catLnks   = LnkDb.getLnks(catalogId, "domatar", "catalogEntry",
                                               null, null, 1000, false);

    // 2. Apps installed on this account: live userApps rows (Desktop
    //    authority) plus Navigator root → app-* links (precursor).
    final Set<String> installedIds = new HashSet<>();
    addLiveUserAppIds(installedIds, actId, prvId);

    final DomId     rootId  = new DomId(
        DomId.localSubHstId("navigator", actId, DomatarConfig.getPrvId()),
        "navigator", actId, "root");
    final List<Lnk> navLnks = LnkDb.getLnks(rootId, "navigator", "app", null, null, 200, false);

    final String hstSuffix = DomId.HOST_SEP + actId;
    for (final Lnk lnk : navLnks)
    {
      final String hstId = lnk.lnkDomId != null ? lnk.lnkDomId.hstId : null;
      if (hstId != null && hstId.endsWith(hstSuffix))
        installedIds.add(hstId.substring(0, hstId.length() - hstSuffix.length()));
    }

    // 3. Partition catalog into installed vs. available
    final JsonList    installed  = new JsonArrayList();
    final JsonList    available  = new JsonArrayList();
    final Set<String> catalogIds = new HashSet<>();

    for (final Lnk lnk : catLnks)
    {
      final String appId = lnk.val;   // val = appId, set by CatalogInstall.registerInCatalog
      if (appId == null || appId.isEmpty())
        continue;

      final Obj entry = ObjDb.getObj(lnk.lnkDomId);
      if (entry == null)
        continue;

      catalogIds.add(appId);

      final ObjAttrs attrs   = entry.attrs != null ? entry.attrs : new ObjAttrs();
      String         appName = attrs.getAttr("AppName");
      String         appDesc = attrs.getAttr("AppDesc");
      if (appName == null)
        appName = appId;
      if (appDesc == null)
        appDesc = "";

      final JsonMap row = new JsonHashMap();
      row.put("AppId",   appId);
      row.put("AppName", appName);
      row.put("AppDesc", appDesc);
      row.put("IsCore",  CORE_APPS.contains(appId) ? "True" : "False");

      if (installedIds.contains(appId))
      {
        enrichInstalledRow(row, appId, actId);
        installed.add(row);
      }
      else if (includeAvailable)
        available.add(row);
    }

    // 4. Include installed apps whose catalog entry is missing (e.g. app was
    //    installed before its /Setup was run to register it in the catalog).
    for (final String appId : installedIds)
    {
      if (catalogIds.contains(appId))
        continue;

      final App         appReg  = AppRegistry.get(appId);
      final ClassLoader cl      = (appReg != null) ? appReg.classLoader
                                                   : Thread.currentThread().getContextClassLoader();
      final AppConfig   cfg     = AppConfig.load(appId, cl);
      final String      appName = (cfg != null) ? cfg.getAppName() : appId;
      final String      appDesc = (cfg != null) ? cfg.getAppDesc() : "";

      final JsonMap row = new JsonHashMap();
      row.put("AppId",   appId);
      row.put("AppName", appName);
      row.put("AppDesc", appDesc);
      row.put("IsCore",  CORE_APPS.contains(appId) ? "True" : "False");
      enrichInstalledRow(row, appId, actId);

      installed.add(row);
    }

    final ObjAttrs out = new ObjAttrs();
    out.addAttr("InstalledApps", installed);
    if (includeAvailable)
      out.addAttr("AvailableApps", available);
    outMsg.addResponseBody(opr, out);
  }

  /** Live (non-tombstoned) userApps rows — same set Desktop GetUserApps paints. */
  private static void addLiveUserAppIds(final Set<String> installedIds,
                                        final String actId, final String prvId)
      throws DomatarException
  {
    final DomId userApps = UserSubstrateIds.userApps(actId, prvId);
    final List<Lnk> uaLnks = LnkDb.getLnks(userApps, "domatar", "userApp",
        null, null, 200, false);

    for (final Lnk lnk : uaLnks)
    {
      final Obj row = ObjDb.getObj(lnk.lnkDomId);

      if (row == null)
        continue;
      if ("True".equals(row.attrs != null ? row.attrs.getAttr("Tombstone") : null))
        continue;

      String appId = lnk.val;

      if (appId == null || appId.isEmpty())
      {
        final String objId = row.domId != null ? row.domId.objId : null;

        if (objId != null && objId.startsWith("app-"))
          appId = objId.substring("app-".length());
      }

      if (appId != null && !appId.isEmpty())
        installedIds.add(appId);
    }
  }

  /** Adds PrvId / Domain / HstId when the portable app host is in HstDb. */
  private static void enrichInstalledRow(final JsonMap row, final String appId,
                                         final String actId)
      throws DomatarException
  {
    final String hstId = DomId.subHstId(appId, actId);
    final Hst    hst   = HstDb.getHst(hstId);

    row.put("HstId", hstId);

    if (hst != null)
    {
      if (hst.prvId != null)
        row.put("PrvId", hst.prvId);
      if (hst.domain != null)
        row.put("Domain", hst.domain);
    }
  }

  // -------------------------------------------------------------------------

  /**
   * Removes the navigator root → app-&lt;AppId&gt; link, tombstones the
   * userApps row (Desktop GetUserApps), and tombstones the legacy Desktop
   * tile. Does NOT delete app data objs or WithdrawOffer
   * (Spec-AppStore PART 7.6 / Spec-Installation PART 5.5 /
   * Spec-Mandatory-App-Rewrite PART 4.4).
   */
  private void uninstallApp(final String opr, final JsonMsg inMsg, final JsonMsg outMsg,
                            final Obj obj, final DomatarMsgClient msgClient)
      throws DomatarException
  {
    final String appId = inMsg.getAttr("AppId");
    final String actId = obj != null ? obj.domId.actId : inMsg.getDstId().actId;

    if (appId == null || appId.isEmpty())
    {
      outMsg.addError(opr, "Missing AppId");
      return;
    }

    if (CORE_APPS.contains(appId))
    {
      outMsg.addError(opr, "Cannot uninstall core app: " + appId);
      return;
    }

    // Remove the root → app-<appId> link
    final DomId rootId    = new DomId(
        DomId.localSubHstId("navigator", actId, DomatarConfig.getPrvId()),
        "navigator", actId, "root");
    final DomId appNodeId = new DomId(DomId.subHstId(appId, actId),      appId,       actId, "app-" + appId);

    LnkDb.deleteLnks(rootId, appNodeId, "navigator", "app", null, null);

    tombstoneUserApp(inMsg, actId, appId, msgClient);
    tombstoneDesktopTile(inMsg, actId, appId, msgClient);

    final ObjAttrs out = new ObjAttrs();
    out.addAttr("Status", "Uninstalled");
    outMsg.addResponseBody(opr, out);
  }

  /** Best-effort UninstallUserApp on the local domatar userApps registry. */
  private static void tombstoneUserApp(final JsonMsg inMsg, final String actId,
                                       final String appId,
                                       final DomatarMsgClient msgClient)
  {
    if (msgClient == null || actId == null || appId == null)
      return;

    try
    {
      final String homePrv = DomatarConfig.getPrvId();
      final DomId userApps = UserSubstrateIds.userApps(actId, homePrv);

      dispatchUninstall(inMsg, msgClient, userApps, "UninstallUserApp",
          "domatar", "userApps", appId);
    }
    catch (final Exception e)
    {
      System.out.println("WARN: AppstoreImpl UninstallUserApp transport failed for "
          + appId + ": " + e);
    }
  }

  /** Best-effort Desktop UninstallApp on the home replica. */
  private static void tombstoneDesktopTile(final JsonMsg inMsg, final String actId,
                                           final String appId,
                                           final DomatarMsgClient msgClient)
  {
    if (msgClient == null || actId == null || appId == null)
      return;

    try
    {
      final String homePrv = DomatarConfig.getPrvId();
      final DomId desktopApps = new DomId(
          DomId.localSubHstId("desktop", actId, homePrv),
          "desktop", actId, "apps");

      dispatchUninstall(inMsg, msgClient, desktopApps, "UninstallApp",
          "desktop", "apps", appId);
    }
    catch (final Exception e)
    {
      System.out.println("WARN: AppstoreImpl Desktop UninstallApp transport failed for "
          + appId + ": " + e);
    }
  }

  private static void dispatchUninstall(final JsonMsg inMsg,
                                        final DomatarMsgClient msgClient,
                                        final DomId dst,
                                        final String opr,
                                        final String clsAppId,
                                        final String clsId,
                                        final String appId)
      throws DomatarException
  {
    final JsonMsg req = new JsonMsg();
    final Context ctx = inMsg.getContext();
    DomId src = null;

    try { src = inMsg.getSrcId(); }
    catch (final Exception ignored) {}

    if (src == null)
      src = msgClient.getSrcId();

    if (src != null && ctx != null)
      req.addRequestHead(src, dst, ctx);

    final ObjAttrs attrs = new ObjAttrs();
    attrs.addAttr("AppId", appId);
    req.addRequestBody(opr, attrs);
    req.addClsId(clsAppId, clsId);

    final JsonMsg resp = msgClient.send(dst, req);

    if (resp != null && resp.isFailure())
    {
      System.out.println("WARN: AppstoreImpl " + opr + " failed for "
          + appId + ": " + resp.getErrorMsg());
    }
  }
}
