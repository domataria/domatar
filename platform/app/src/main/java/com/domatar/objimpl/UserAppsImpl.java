/*
 * Copyright (c) 2024 Domatar
 */

package com.domatar.objimpl;

import java.util.ArrayList;
import java.util.List;

import com.domatar.act.MarketplaceInstall;
import com.domatar.core.Auth;
import com.domatar.core.Context;
import com.domatar.core.DomatarConfig;
import com.domatar.install.AppUrls;
import com.domatar.install.IconPaths;
import com.domatar.install.LaunchPaths;
import com.domatar.db.LnkDb;
import com.domatar.db.ObjDb;
import com.domatar.util.Base64Encoder;
import com.domatar.util.IdGen;
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
 * Handler for {@code (domatar, userApps)} on the user substrate host
 * (Update-Mandatory-App-Rewrite.txt Phase 1 / KD6).
 */
public class UserAppsImpl extends ObjImpl
{
  @Override
  public String handleMsg(final String msg, final Obj obj, final String contextPath,
                          final String contextRealPath, final DomatarMsgClient msgClient)
      throws DomatarException
  {
    final JsonMsg inMsg  = new JsonMsg(msg);
    final String  opr    = inMsg.getOperation();
    final JsonMsg outMsg = new JsonMsg();

    if (!hasRights(inMsg, obj, msgClient))
      return notAuthorized(inMsg);

    if ("GetUserApps".equals(opr))
      getUserApps(opr, inMsg, outMsg);
    else if ("GetUserAppsVersion".equals(opr))
      getUserAppsVersion(opr, inMsg, outMsg);
    else if ("PullUserApps".equals(opr))
      pullUserApps(opr, inMsg, outMsg);
    else if ("MergeUserApps".equals(opr))
      mergeUserApps(opr, inMsg, outMsg);
    else if ("ReconcileUserApps".equals(opr))
      reconcileUserApps(opr, inMsg, outMsg);
    else if ("InstallUserApp".equals(opr))
      installUserApp(opr, inMsg, outMsg);
    else if ("SetAppUrl".equals(opr))
      setAppUrl(opr, inMsg, outMsg);
    else if ("UninstallUserApp".equals(opr))
      uninstallUserApp(opr, inMsg, outMsg);
    else
      return super.handleMsg(msg, obj, contextPath, contextRealPath, msgClient);

    return outMsg.toString();
  }

  @Override
  public boolean hasRights(final JsonMsg inMsg, final Obj obj,
                           final DomatarMsgClient msgClient) throws DomatarException
  {
    if (!Auth.isVerified(inMsg))
      return false;

    final Context ctx = inMsg.getContext();
    final DomId   dst = inMsg.getDstId();

    return ctx != null && ctx.actId != null && dst != null
        && ctx.actId.equals(dst.actId);
  }

  private void getUserApps(final String opr, final JsonMsg inMsg, final JsonMsg outMsg)
      throws DomatarException
  {
    final DomId dst = inMsg.getDstId();
    final List<Obj> rows = liveRows(dst);
    final JsonList apps = new JsonArrayList();
    String maxVersion = "";

    for (final Obj row : sortedByPosition(rows))
    {
      final ObjAttrs a = row.attrs;
      final JsonMap entry = new JsonHashMap();
      final String version = a != null ? a.getAttr("Version") : null;

      fillAppEntry(entry, row, false);
      apps.add(entry);
      maxVersion = maxVersion(maxVersion, version);
    }

    final ObjAttrs outAttrs = new ObjAttrs();

    outAttrs.addAttr("Apps", apps);
    outAttrs.addAttr("Version", maxVersion.isEmpty()
        ? readContainerVersion(dst) : maxVersion);
    outMsg.addResponseBody(opr, outAttrs);
  }

  private void getUserAppsVersion(final String opr, final JsonMsg inMsg,
                                  final JsonMsg outMsg) throws DomatarException
  {
    final ObjAttrs outAttrs = new ObjAttrs();

    outAttrs.addAttr("Version", readContainerVersion(inMsg.getDstId()));
    outMsg.addResponseBody(opr, outAttrs);
  }

  private void pullUserApps(final String opr, final JsonMsg inMsg, final JsonMsg outMsg)
      throws DomatarException
  {
    final DomId dst = inMsg.getDstId();
    final List<Obj> all = ObjDb.getObjPrefix(dst.hstId, "domatar", dst.actId, "app",
        null, 1000);
    final JsonList apps = new JsonArrayList();

    for (final Obj row : all)
    {
      if (!"domatar".equals(row.clsAppId) || !"userApp".equals(row.clsId))
        continue;

      final ObjAttrs a = row.attrs;
      final JsonMap entry = new JsonHashMap();

      fillAppEntry(entry, row, true);
      apps.add(entry);
    }

    final ObjAttrs outAttrs = new ObjAttrs();

    outAttrs.addAttr("Apps", apps);
    outMsg.addResponseBody(opr, outAttrs);
  }

  private void mergeUserApps(final String opr, final JsonMsg inMsg, final JsonMsg outMsg)
      throws DomatarException
  {
    final DomId dst = inMsg.getDstId();
    final JsonList incoming = inMsg.getAttrs() != null
        ? inMsg.getAttrs().getAttrList("Apps") : null;
    int merged = 0;

    if (incoming != null)
    {
      for (int i = 0; i < incoming.size(); i++)
      {
        final Object item = incoming.get(i);

        if (!(item instanceof JsonMap))
          continue;

        final JsonMap row = (JsonMap) item;
        final String appId = row.getString("AppId");

        if (appId == null || appId.isEmpty())
          continue;

        if (mergeRow(dst, appId, row))
          merged++;
      }
    }

    final ObjAttrs outAttrs = new ObjAttrs();

    outAttrs.addAttr("Merged", "True");
    outAttrs.addAttr("Count", Integer.toString(merged));
    outMsg.addResponseBody(opr, outAttrs);
  }

  private void reconcileUserApps(final String opr, final JsonMsg inMsg,
                                 final JsonMsg outMsg) throws DomatarException
  {
    // Phase 1 stub — peer pull lands in a later phase.
    final ObjAttrs outAttrs = new ObjAttrs();

    outAttrs.addAttr("Reconciled", "True");
    outAttrs.addAttr("Version", readContainerVersion(inMsg.getDstId()));
    outMsg.addResponseBody(opr, outAttrs);
  }

  private void installUserApp(final String opr, final JsonMsg inMsg, final JsonMsg outMsg)
      throws DomatarException
  {
    final DomId dst = inMsg.getDstId();
    final String appId = inMsg.getAttr("AppId");
    final String displayName = inMsg.getAttr("DisplayName");
    final String iconPath = inMsg.getAttr("IconPath");
    final String launchPath = inMsg.getAttr("LaunchPath");
    final String hostPrvId = inMsg.getAttr("HostPrvId");
    final String appHstId = inMsg.getAttr("AppHstId");
    String position = inMsg.getAttr("Position");
    String version = inMsg.getAttr("Version");
    String tombstone = inMsg.getAttr("Tombstone");

    if (appId == null || displayName == null || iconPath == null || launchPath == null)
    {
      outMsg.addError(opr, "Missing AppId / DisplayName / IconPath / LaunchPath");
      return;
    }

    if (position == null || position.isEmpty())
      position = "100";

    if (version == null || version.isEmpty())
      version = IdGen.getCurTimeBase64();

    if (tombstone == null || tombstone.isEmpty())
      tombstone = "False";

    final DomId rowDomId = new DomId(dst.hstId, "domatar", dst.actId,
        IdGen.createId("app", appId));
    final Obj existing = ObjDb.getObj(rowDomId);

    if (existing != null)
      ObjDb.deleteObj(rowDomId);

    final ObjAttrs rowAttrs = new ObjAttrs();

    rowAttrs.addAttr("DisplayName", displayName);
    rowAttrs.addAttr("IconPath", iconPath);
    rowAttrs.addAttr("LaunchPath", launchPath);
    rowAttrs.addAttr("Position", position);
    rowAttrs.addAttr("Version", version);
    rowAttrs.addAttr("Tombstone", tombstone);

    copyAttr(inMsg, rowAttrs, "AppUrl");
    copyAttr(inMsg, rowAttrs, "HostBrowserOrigin");
    copyAttr(inMsg, rowAttrs, "HostPublicDomain");
    copyAttr(inMsg, rowAttrs, "HostDomain");

    if (hostPrvId != null && !hostPrvId.isEmpty())
      rowAttrs.addAttr("HostPrvId", hostPrvId);

    if (appHstId != null && !appHstId.isEmpty())
      rowAttrs.addAttr("AppHstId", appHstId);

    ObjDb.addObj(new Obj(rowDomId, "domatar", "userApp", displayName,
        "Installed application", rowAttrs));

    if (LnkDb.getLnk(dst, rowDomId, "domatar", "userApp") == null)
      LnkDb.addLnk(new Lnk(dst, rowDomId,
          "domatar", "userApp",
          displayName, "Installed application",
          "domatar", "userApp",
          appId, 0));

    bumpContainerVersion(dst, version);

    final ObjAttrs outAttrs = new ObjAttrs();

    outAttrs.addAttr("Installed", "True");
    outMsg.addResponseBody(opr, outAttrs);
  }

  private void setAppUrl(final String opr, final JsonMsg inMsg, final JsonMsg outMsg)
      throws DomatarException
  {
    final DomId dst = inMsg.getDstId();
    final String appId = inMsg.getAttr("AppId");
    final String appUrl = inMsg.getAttr("AppUrl");

    if (appId == null || appId.isEmpty())
    {
      outMsg.addError(opr, "Missing AppId");
      return;
    }

    final DomId rowDomId = new DomId(dst.hstId, "domatar", dst.actId,
        IdGen.createId("app", appId));
    final Obj existing = ObjDb.getObj(rowDomId);

    if (existing == null)
    {
      outMsg.addError(opr, "App not installed: " + appId);
      return;
    }

    final String now = IdGen.getCurTimeBase64();
    final ObjAttrs rowAttrs = existing.attrs != null ? existing.attrs : new ObjAttrs();
    final String hostPrvId = rowAttrs.getAttr("HostPrvId");
    final boolean remote = hostPrvId != null && !hostPrvId.isEmpty()
        && !hostPrvId.equals(DomatarConfig.getPrvId());
    final String scheme = DomatarConfig.getWireScheme();

    if (appUrl == null || appUrl.isEmpty())
    {
      rowAttrs.addAttr("AppUrl", "");
      rowAttrs.addAttr("IconPath", IconPaths.forInstall(remote, scheme, null,
          rowAttrs.getAttr("HostBrowserOrigin"),
          rowAttrs.getAttr("HostPublicDomain"),
          rowAttrs.getAttr("HostDomain"), appId));
      rowAttrs.addAttr("LaunchPath", LaunchPaths.forInstall(remote, scheme, null,
          rowAttrs.getAttr("HostBrowserOrigin"),
          rowAttrs.getAttr("HostPublicDomain"),
          rowAttrs.getAttr("HostDomain"), appId));
    }
    else
    {
      final String base = AppUrls.trimSlash(AppUrls.withScheme(scheme, appUrl));

      rowAttrs.addAttr("AppUrl", base != null ? base : appUrl);
      rowAttrs.addAttr("IconPath", AppUrls.iconPath(base, appId));
      rowAttrs.addAttr("LaunchPath",
          AppUrls.launchPath(base, appId, LaunchPaths.launchPage(appId)));
    }

    rowAttrs.addAttr("Version", now);
    ObjDb.modifyObj(existing.modify(null, null, null, null, null, rowAttrs));
    bumpContainerVersion(dst, now);

    final ObjAttrs outAttrs = new ObjAttrs();

    outAttrs.addAttr("AppUrl", rowAttrs.getAttr("AppUrl"));
    outAttrs.addAttr("IconPath", rowAttrs.getAttr("IconPath"));
    outAttrs.addAttr("LaunchPath", rowAttrs.getAttr("LaunchPath"));
    outMsg.addResponseBody(opr, outAttrs);
  }

  private void uninstallUserApp(final String opr, final JsonMsg inMsg,
                                final JsonMsg outMsg) throws DomatarException
  {
    final DomId dst = inMsg.getDstId();
    final String appId = inMsg.getAttr("AppId");
    final ObjAttrs outAttrs = new ObjAttrs();

    outAttrs.addAttr("Installed", "False");

    if (appId == null)
    {
      outMsg.addError(opr, "Missing AppId");
      return;
    }

    final DomId rowDomId = new DomId(dst.hstId, "domatar", dst.actId,
        IdGen.createId("app", appId));
    final Obj existing = ObjDb.getObj(rowDomId);

    if (existing == null)
    {
      outMsg.addResponseBody(opr, outAttrs);
      return;
    }

    final String now = IdGen.getCurTimeBase64();
    final ObjAttrs rowAttrs = existing.attrs != null ? existing.attrs : new ObjAttrs();

    rowAttrs.addAttr("Tombstone", "True");
    rowAttrs.addAttr("Version", now);
    ObjDb.modifyObj(existing.modify(null, null, null, null, null, rowAttrs));
    bumpContainerVersion(dst, now);
    outMsg.addResponseBody(opr, outAttrs);
  }

  private static boolean mergeRow(final DomId dst, final String appId, final JsonMap row)
      throws DomatarException
  {
    final String candidateVersion = row.getString("Version");
    final DomId rowDomId = new DomId(dst.hstId, "domatar", dst.actId,
        IdGen.createId("app", appId));
    final Obj existing = ObjDb.getObj(rowDomId);
    final String localVersion = existing != null && existing.attrs != null
        ? existing.attrs.getAttr("Version") : null;

    if (compareVersion(candidateVersion, localVersion) <= 0)
      return false;

    final String displayName = row.getString("DisplayName");
    final String iconPath = row.getString("IconPath");
    final String launchPath = row.getString("LaunchPath");
    String position = row.getString("Position");
    String tombstone = row.getString("Tombstone");

    if (position == null || position.isEmpty())
      position = "100";

    if (tombstone == null || tombstone.isEmpty())
      tombstone = "False";

    if (existing != null)
      ObjDb.deleteObj(rowDomId);

    final ObjAttrs attrs = new ObjAttrs();

    attrs.addAttr("DisplayName", displayName != null ? displayName : appId);
    attrs.addAttr("IconPath", iconPath != null ? iconPath : "");
    attrs.addAttr("LaunchPath", launchPath != null ? launchPath : "");
    attrs.addAttr("Position", position);
    attrs.addAttr("Version", candidateVersion);
    attrs.addAttr("Tombstone", tombstone);

    copyRowAttr(row, attrs, "AppUrl");
    copyRowAttr(row, attrs, "HostBrowserOrigin");
    copyRowAttr(row, attrs, "HostPublicDomain");
    copyRowAttr(row, attrs, "HostDomain");

    final String hostPrvId = row.getString("HostPrvId");
    final String appHstId = row.getString("AppHstId");

    if (hostPrvId != null && !hostPrvId.isEmpty())
      attrs.addAttr("HostPrvId", hostPrvId);

    if (appHstId != null && !appHstId.isEmpty())
      attrs.addAttr("AppHstId", appHstId);

    ObjDb.addObj(new Obj(rowDomId, "domatar", "userApp",
        displayName != null ? displayName : appId,
        "Installed application", attrs));

    if (LnkDb.getLnk(dst, rowDomId, "domatar", "userApp") == null)
      LnkDb.addLnk(new Lnk(dst, rowDomId,
          "domatar", "userApp",
          displayName != null ? displayName : appId, "Installed application",
          "domatar", "userApp",
          appId, 0));

    bumpContainerVersion(dst, candidateVersion);
    return true;
  }

  private static List<Obj> liveRows(final DomId dst) throws DomatarException
  {
    final List<Obj> all = ObjDb.getObjPrefix(dst.hstId, "domatar", dst.actId, "app",
        null, 1000);
    final List<Obj> rows = new ArrayList<>();

    for (final Obj o : all)
    {
      if (!"domatar".equals(o.clsAppId) || !"userApp".equals(o.clsId))
        continue;

      if ("True".equals(o.attrs != null ? o.attrs.getAttr("Tombstone") : null))
        continue;

      rows.add(o);
    }

    return rows;
  }

  private static List<Obj> sortedByPosition(final List<Obj> rows) throws DomatarException
  {
    final Obj[] sorted = rows.toArray(new Obj[0]);

    for (int i = 1; i < sorted.length; i++)
    {
      final Obj cur = sorted[i];
      int j = i - 1;

      while (j >= 0 && comparePosition(sorted[j], cur) > 0)
      {
        sorted[j + 1] = sorted[j];
        j--;
      }

      sorted[j + 1] = cur;
    }

    final List<Obj> out = new ArrayList<>(sorted.length);

    for (final Obj o : sorted)
      out.add(o);

    // Stable secondary: AppId when Position ties.
    for (int i = 1; i < out.size(); i++)
    {
      final Obj cur = out.get(i);
      int j = i - 1;

      while (j >= 0
          && comparePosition(out.get(j), cur) == 0
          && extractAppId(out.get(j).domId.objId)
              .compareTo(extractAppId(cur.domId.objId)) > 0)
      {
        out.set(j + 1, out.get(j));
        j--;
      }

      out.set(j + 1, cur);
    }

    return out;
  }

  private static int comparePosition(final Obj a, final Obj b) throws DomatarException
  {
    return Integer.compare(parsePosition(a.attrs != null ? a.attrs.getAttr("Position") : null),
        parsePosition(b.attrs != null ? b.attrs.getAttr("Position") : null));
  }

  private static int parsePosition(final String p)
  {
    if (p == null || p.isEmpty())
      return 100;

    try
    {
      return Integer.parseInt(p);
    }
    catch (final NumberFormatException e)
    {
      return 100;
    }
  }

  private static void putOptional(final JsonMap entry, final ObjAttrs a, final String key)
      throws DomatarException
  {
    if (a == null)
      return;

    final String val = a.getAttr(key);

    if (val != null && !val.isEmpty())
      entry.put(key, val);
  }

  private static void fillAppEntry(final JsonMap entry, final Obj row,
                                   final boolean includeTombstone)
      throws DomatarException
  {
    final ObjAttrs a = row.attrs;
    final String appId = extractAppId(row.domId.objId);

    entry.put("AppId", appId);
    entry.put("DisplayName", a != null ? a.getAttr("DisplayName") : null);

    String iconPath = reachableOrRelative(
        a != null ? a.getAttr("IconPath") : null);
    String launchPath = reachableOrRelative(
        a != null ? a.getAttr("LaunchPath") : null);
    final String appUrlStoredRaw = a != null ? a.getAttr("AppUrl") : null;
    final String appUrlStored =
        (appUrlStoredRaw != null && AppUrls.isBrowserReachable(appUrlStoredRaw))
            ? appUrlStoredRaw : null;
    final String hostPrvId = a != null ? a.getAttr("HostPrvId") : null;
    final boolean remote = hostPrvId != null && !hostPrvId.isEmpty()
        && !hostPrvId.equals(DomatarConfig.getPrvId());
    final String hostBrowserOrigin =
        a != null ? a.getAttr("HostBrowserOrigin") : null;
    final String hostPublicDomain =
        a != null ? a.getAttr("HostPublicDomain") : null;
    final String hostDomain = a != null ? a.getAttr("HostDomain") : null;
    String base = AppUrls.resolve(appUrlStored,
        DomatarConfig.getWireScheme(),
        hostBrowserOrigin, hostPublicDomain, hostDomain);

    if (base == null && remote && !AppUrls.isBlank(hostDomain))
    {
      final String fetched =
          MarketplaceInstall.fetchOfferingBrowserOrigin(hostDomain);

      if (fetched != null)
        base = AppUrls.resolve(null, DomatarConfig.getWireScheme(),
            fetched, hostPublicDomain, hostDomain);
    }

    final String launchPage = LaunchPaths.launchPage(appId);

    if (base != null && (!AppUrls.isBlank(appUrlStored) || remote))
    {
      iconPath = AppUrls.iconPath(base, appId);
      launchPath = AppUrls.launchPath(base, appId, launchPage);
    }
    else
    {
      if (iconPath == null)
        iconPath = AppUrls.iconPath(null, appId);

      final String rewritten = AppUrls.replaceLaunchAsset(launchPath, launchPage);

      if (rewritten != null)
        launchPath = rewritten;
      else
        launchPath = AppUrls.launchPath(null, appId, launchPage);
    }

    entry.put("IconPath", iconPath);
    entry.put("LaunchPath", launchPath);

    if (!AppUrls.isBlank(appUrlStored))
      entry.put("AppUrl", appUrlStored);
    else if (base != null && remote)
      entry.put("AppUrl", base);

    entry.put("Position", a != null ? a.getAttr("Position") : null);
    entry.put("Version", a != null ? a.getAttr("Version") : null);

    if (includeTombstone)
      entry.put("Tombstone", a != null ? a.getAttr("Tombstone") : "False");

    putOptional(entry, a, "HostPrvId");
    putOptional(entry, a, "AppHstId");
    putOptional(entry, a, "HostBrowserOrigin");
    putOptional(entry, a, "HostPublicDomain");
    putOptional(entry, a, "HostDomain");
  }

  private static void copyAttr(final JsonMsg inMsg, final ObjAttrs attrs,
                               final String key) throws DomatarException
  {
    final String v = inMsg.getAttr(key);

    if (v != null && !v.isEmpty())
      attrs.addAttr(key, v);
  }

  private static void copyRowAttr(final JsonMap row, final ObjAttrs attrs,
                                  final String key) throws DomatarException
  {
    final String v = row.getString(key);

    if (v != null && !v.isEmpty())
      attrs.addAttr(key, v);
  }

  /** Keep relative paths; drop Docker-only absolute URLs. */
  private static String reachableOrRelative(final String path)
  {
    if (path == null || path.isEmpty())
      return null;

    if (path.startsWith("/"))
      return path;

    return AppUrls.isBrowserReachable(path) ? path : null;
  }

  private static String extractAppId(final String objId)
  {
    return IdGen.getIdSuffix(objId);
  }

  private static String readContainerVersion(final DomId containerDomId)
      throws DomatarException
  {
    final Obj container = ObjDb.getObj(containerDomId);

    if (container == null || container.attrs == null)
      return "";

    final String version = container.attrs.getAttr("Version");

    return version != null ? version : "";
  }

  private static void bumpContainerVersion(final DomId containerDomId,
                                           final String candidateVersion)
      throws DomatarException
  {
    if (candidateVersion == null || candidateVersion.isEmpty())
      return;

    final Obj container = ObjDb.getObj(containerDomId);

    if (container == null)
      return;

    final String current = container.attrs != null
        ? container.attrs.getAttr("Version") : null;

    if (compareVersion(candidateVersion, current) <= 0)
      return;

    final ObjAttrs attrs = container.attrs != null ? container.attrs : new ObjAttrs();

    attrs.addAttr("Version", candidateVersion);
    ObjDb.modifyObj(container.modify(null, null, null, null, null, attrs));
  }

  private static String maxVersion(final String a, final String b)
  {
    return compareVersion(a, b) >= 0 ? (a != null ? a : "") : (b != null ? b : "");
  }

  /** Positive if a is newer than b. Empty/null is oldest. */
  private static int compareVersion(final String a, final String b)
  {
    if ((a == null || a.isEmpty()) && (b == null || b.isEmpty()))
      return 0;

    if (a == null || a.isEmpty())
      return -1;

    if (b == null || b.isEmpty())
      return 1;

    try
    {
      final long la = Base64Encoder.decodeToLong(a);
      final long lb = Base64Encoder.decodeToLong(b);

      return Long.compare(la, lb);
    }
    catch (final Exception e)
    {
      return a.compareTo(b);
    }
  }
}
