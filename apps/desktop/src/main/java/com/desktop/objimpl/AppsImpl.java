/*
 * Copyright (c) 2024 Domatar
 */

package com.desktop.objimpl;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

import com.desktop.sync.DesktopFanout;
import com.domatar.core.Auth;
import com.domatar.core.Context;
import com.domatar.core.DomatarConfig;
import com.domatar.install.AppUrls;
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
 * Per-user "apps" container handler. Lives at
 *   (HstId=desktop-&lt;actId&gt;[ -&lt;prvId&gt;], AppId=desktop, ActId=&lt;actId&gt;,
 *    ObjId=apps)
 * and tracks which apps are installed on the user's home screen.
 *
 * Spec-Desktop.txt PART 6; Spec-Desktop-Synchronized.txt PART 4 / 6 / 7 / 8.
 *
 * Operations:
 *   GetApps         - read live tiles (Tombstone!=True), emit TileVersion.
 *   GetAppsVersion  - cheap container Version probe.
 *   PullApps        - all tiles incl. tombstones (for LWW merge); skips
 *                     intrinsic {login,navigator,desktop}.
 *   MergeApps       - LWW-merge a set of tiles into this replica.
 *   ReconcileApps   - display-time pull from peer replicas (PART 7.1).
 *   InstallApp      - add/update an "app-&lt;appId&gt;" row with fresh TileVersion;
 *                     best-effort fan-out to peers.
 *   UninstallApp    - soft-delete (Tombstone=True + fresh TileVersion);
 *                     best-effort fan-out to peers.
 *   GetTileOrder / SetTileOrder - Desktop-owned launcher layout (UI order
 *                     of AppIds); independent of userApps / shells.
 *
 * Authorization (Spec-Desktop-Synchronized.txt PART 9): verified session
 * AND owner-match (ctx.actId equals the apps container's actId).
 */
public class AppsImpl extends ObjImpl
{
  private static final Logger LOG = Logger.getLogger(AppsImpl.class.getName());

  /** CSV of AppIds for launcher display order (Desktop UI only). */
  private static final String ATTR_TILE_ORDER = "TileOrder";
  private static final String ATTR_TILE_ORDER_VERSION = "TileOrderVersion";

  @Override
  public String handleMsg(final String msg, final Obj obj, final String contextPath,
                          final String contextRealPath, final DomatarMsgClient msgClient)
      throws DomatarException
  {
    final JsonMsg inMsg  = new JsonMsg(msg);
    final String  opr    = inMsg.getOperation();

    if ("Compensate".equals(opr))
      return super.handleMsg(msg, obj, contextPath, contextRealPath, msgClient);

    final JsonMsg outMsg = new JsonMsg();

    if (!hasRights(inMsg, obj, msgClient))
      return notAuthorized(inMsg);

    if ("GetApps".equals(opr))
      getApps(opr, inMsg, outMsg);
    else if ("GetAppsVersion".equals(opr))
      getAppsVersion(opr, inMsg, outMsg);
    else if ("PullApps".equals(opr))
      pullApps(opr, inMsg, outMsg);
    else if ("MergeApps".equals(opr))
      mergeApps(opr, inMsg, outMsg);
    else if ("ReconcileApps".equals(opr))
      reconcileApps(opr, inMsg, outMsg, msgClient);
    else if ("InstallApp".equals(opr))
      installApp(opr, inMsg, outMsg, msgClient);
    else if ("UninstallApp".equals(opr))
      uninstallApp(opr, inMsg, outMsg, msgClient);
    else if ("GetTileOrder".equals(opr))
      getTileOrder(opr, inMsg, outMsg);
    else if ("SetTileOrder".equals(opr))
      setTileOrder(opr, inMsg, outMsg);
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

  /**
   * Read the catalog and return it as a Position-sorted list. Lazy
   * bootstrap on empty: seed Quippin + Login icons, re-read. Hides
   * tombstoned tiles and emits TileVersion (PART 4.3 / PART 8).
   */
  private void getApps(final String opr, final JsonMsg inMsg, final JsonMsg outMsg)
      throws DomatarException
  {
    final DomId dst = inMsg.getDstId();

    // seedDefaultCatalog is idempotent (addCatalogRow guards on
    // ObjDb.getObj), so calling it on every GetApps is safe.  Calling
    // it unconditionally (not just when the list is empty) ensures that
    // newly added default tiles (e.g. Navigator) appear for users who
    // already had an older catalog seeded before the tile existed.
    seedDefaultCatalog(dst);

    final List<Obj> allRows = ObjDb.getObjPrefix(dst.hstId, "desktop", dst.actId, "app", null, 1000);

    // Only include proper Desktop catalog entries (class "desktop"/"app").
    // Other objects on this host — e.g. the Navigator's app-desktop node
    // (class "navigator"/"app") — share the ObjId prefix but must not
    // appear as tiles. Tombstoned tiles never render (PART 4.3).
    final List<Obj> rows = new ArrayList<>();
    for (final Obj o : allRows)
    {
      if (!"desktop".equals(o.clsAppId) || !"app".equals(o.clsId))
        continue;
      if ("True".equals(o.attrs != null ? o.attrs.getAttr("Tombstone") : null))
        continue;
      rows.add(o);
    }

    final JsonList apps = new JsonArrayList();

    // Sort by Position. ObjDb.getObjPrefix returns the rows in
    // ObjId-desc order; Position is independent. n is small (initial
    // catalog has two rows), so a one-pass insertion sort is fine.
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

    for (final Obj row : sorted)
    {
      final ObjAttrs a = row.attrs;

      final JsonMap entry = new JsonHashMap();

      final String appId = extractAppId(row.domId.objId);

      entry.put("AppId",       appId);
      entry.put("DisplayName", a.getAttr("DisplayName"));
      entry.put("IconPath",    a.getAttr("IconPath"));
      entry.put("LaunchPath",  launchPathForBrowser(appId, a.getAttr("LaunchPath")));
      entry.put("Position",    a.getAttr("Position"));
      entry.put("TileVersion", a.getAttr("TileVersion"));
      putOptional(entry, a, "HostPrvId");
      putOptional(entry, a, "AppHstId");

      apps.add(entry);
    }

    final ObjAttrs outAttrs = new ObjAttrs();

    outAttrs.addAttr("Apps", apps);

    outMsg.addResponseBody(opr, outAttrs);
  }

  /**
   * Cheap changed-since probe: return the apps container's Version attr.
   */
  private void getAppsVersion(final String opr, final JsonMsg inMsg,
                               final JsonMsg outMsg)
      throws DomatarException
  {
    final DomId dst = inMsg.getDstId();

    final ObjAttrs outAttrs = new ObjAttrs();

    outAttrs.addAttr("Version", readContainerVersion(dst));

    outMsg.addResponseBody(opr, outAttrs);
  }

  /**
   * Desktop-owned launcher layout: ordered AppIds (UI only).
   * Does not consult userApps or shells.
   */
  private void getTileOrder(final String opr, final JsonMsg inMsg,
                            final JsonMsg outMsg) throws DomatarException
  {
    final DomId dst = inMsg.getDstId();
    final Obj container = ObjDb.getObj(dst);
    final ObjAttrs outAttrs = new ObjAttrs();

    String order = "";
    String version = "";

    if (container != null && container.attrs != null)
    {
      final String raw = container.attrs.getAttr(ATTR_TILE_ORDER);

      if (raw != null)
        order = raw;

      final String ver = container.attrs.getAttr(ATTR_TILE_ORDER_VERSION);

      if (ver != null)
        version = ver;
    }

    outAttrs.addAttr("AppIds", order);
    outAttrs.addAttr("Version", version);
    outMsg.addResponseBody(opr, outAttrs);
  }

  /**
   * Persist launcher layout. Body: AppIds = comma-separated AppId list.
   */
  private void setTileOrder(final String opr, final JsonMsg inMsg,
                            final JsonMsg outMsg) throws DomatarException
  {
    final DomId dst = inMsg.getDstId();
    final String raw = inMsg.getAttr("AppIds");
    final String normalized = normalizeTileOrderCsv(raw);
    final String now = IdGen.getCurTimeBase64();

    Obj container = ObjDb.getObj(dst);

    if (container == null)
    {
      // Ensure apps container exists (same lazy path as GetApps).
      seedDefaultCatalog(dst);
      container = ObjDb.getObj(dst);
    }

    if (container == null)
    {
      outMsg.addError(opr, "Apps container missing");
      return;
    }

    final ObjAttrs attrs = container.attrs != null
        ? container.attrs : new ObjAttrs();

    attrs.addAttr(ATTR_TILE_ORDER, normalized);
    attrs.addAttr(ATTR_TILE_ORDER_VERSION, now);

    ObjDb.modifyObj(container.modify(null, null, null, null, null, attrs));

    final ObjAttrs outAttrs = new ObjAttrs();

    outAttrs.addAttr("AppIds", normalized);
    outAttrs.addAttr("Version", now);
    outMsg.addResponseBody(opr, outAttrs);
  }

  private static String normalizeTileOrderCsv(final String raw)
  {
    if (raw == null || raw.trim().isEmpty())
      return "";

    final StringBuilder sb = new StringBuilder();
    final java.util.LinkedHashSet<String> seen = new java.util.LinkedHashSet<>();

    for (final String part : raw.split(","))
    {
      if (part == null)
        continue;

      final String id = part.trim();

      if (id.isEmpty() || !seen.add(id))
        continue;

      if (sb.length() > 0)
        sb.append(',');

      sb.append(id);
    }

    return sb.toString();
  }

  /**
   * Full tile state for LWW merge, including tombstones. Skips intrinsic
   * system-app tiles (PART 4.4 / D7).
   */
  private void pullApps(final String opr, final JsonMsg inMsg, final JsonMsg outMsg)
      throws DomatarException
  {
    final DomId dst = inMsg.getDstId();

    final List<Obj> allRows = ObjDb.getObjPrefix(dst.hstId, "desktop", dst.actId, "app", null, 1000);

    final JsonList apps = new JsonArrayList();

    for (final Obj row : allRows)
    {
      if (!"desktop".equals(row.clsAppId) || !"app".equals(row.clsId))
        continue;

      final String appId = extractAppId(row.domId.objId);

      if (isIntrinsic(appId))
        continue;

      final ObjAttrs a = row.attrs;
      final JsonMap entry = new JsonHashMap();

      entry.put("AppId",       appId);
      entry.put("DisplayName", a.getAttr("DisplayName"));
      entry.put("IconPath",    a.getAttr("IconPath"));
      entry.put("LaunchPath",  a.getAttr("LaunchPath"));
      entry.put("Position",    a.getAttr("Position"));
      entry.put("TileVersion", a.getAttr("TileVersion"));
      entry.put("Tombstone",   a.getAttr("Tombstone") != null
          ? a.getAttr("Tombstone") : "False");
      putOptional(entry, a, "HostPrvId");
      putOptional(entry, a, "AppHstId");

      apps.add(entry);
    }

    final ObjAttrs outAttrs = new ObjAttrs();

    outAttrs.addAttr("Apps", apps);

    outMsg.addResponseBody(opr, outAttrs);
  }

  /**
   * LWW-merge a set of tiles into this replica. Idempotent. Skips
   * intrinsic system-app rows in the input (PART 4.4 / D7).
   */
  private void mergeApps(final String opr, final JsonMsg inMsg, final JsonMsg outMsg)
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

        final JsonMap entry = (JsonMap) item;
        final String appId = entry.getString("AppId");

        if (appId == null || appId.isEmpty())
          continue;
        if (isIntrinsic(appId))
          continue;

        if (mergeTileRow(dst, appId,
            entry.getString("DisplayName"),
            entry.getString("IconPath"),
            entry.getString("LaunchPath"),
            entry.getString("Position"),
            entry.getString("TileVersion"),
            entry.getString("Tombstone") != null
                ? entry.getString("Tombstone") : "False"))
          merged++;
      }
    }

    final ObjAttrs outAttrs = new ObjAttrs();

    outAttrs.addAttr("Merged", Integer.toString(merged));

    outMsg.addResponseBody(opr, outAttrs);
  }

  /**
   * Display-time pull from peer Desktop replicas (PART 7.1). Non-fatal
   * per peer. Pull-only is sufficient for correctness (push-back optional).
   */
  private void reconcileApps(final String opr, final JsonMsg inMsg,
                              final JsonMsg outMsg,
                              final DomatarMsgClient msgClient)
      throws DomatarException
  {
    final DomId dst = inMsg.getDstId();
    final String actId = dst.actId;
    final String localPrvId = replicaPrvIdOrConfig(dst.hstId);
    final String localVersion = readContainerVersion(dst);

    int mergedTiles = 0;
    int mergedFrom  = 0;
    int skipped     = 0;
    int failed      = 0;

    for (final String remotePrvId : DesktopFanout.discoverPeerPrvIds(
            actId, localPrvId, msgClient))
    {
      try
      {
        final DomId remoteApps = new DomId(
            DomId.subHstId("desktop", actId, remotePrvId),
            "desktop", actId, "apps");

        final JsonMsg verReq = new JsonMsg();

        verReq.addRequestBody("GetAppsVersion", null);
        verReq.addClsId("desktop", "apps");

        final JsonMsg verResp = msgClient.send(remoteApps, verReq);

        if (verResp == null || !"Success".equals(verResp.getError()))
        {
          failed++;
          final String err = verResp != null ? verResp.getErrorMsg() : "null response";
          System.out.println("WARN: ReconcileApps GetAppsVersion failed for prvId="
              + remotePrvId + " actId=" + actId + ": " + err
              + " error=" + (verResp != null ? verResp.getError() : "null"));
          continue;
        }

        final String remoteVersion = verResp.getAttr("Version");

        if (compareTileVersion(remoteVersion, localVersion) <= 0)
        {
          skipped++;
          continue;
        }

        final JsonMsg pullReq = new JsonMsg();

        pullReq.addRequestBody("PullApps", null);
        pullReq.addClsId("desktop", "apps");

        final JsonMsg pullResp = msgClient.send(remoteApps, pullReq);

        if (pullResp == null || !"Success".equals(pullResp.getError()))
        {
          failed++;
          continue;
        }

        final JsonList apps = pullResp.getAttrs() != null
            ? pullResp.getAttrs().getAttrList("Apps") : null;

        int fromThisPeer = 0;

        if (apps != null)
        {
          for (int i = 0; i < apps.size(); i++)
          {
            final Object item = apps.get(i);

            if (!(item instanceof JsonMap))
              continue;

            final JsonMap entry = (JsonMap) item;
            final String appId = entry.getString("AppId");

            if (appId == null || isIntrinsic(appId))
              continue;

            if (mergeTileRow(dst, appId,
                entry.getString("DisplayName"),
                entry.getString("IconPath"),
                entry.getString("LaunchPath"),
                entry.getString("Position"),
                entry.getString("TileVersion"),
                entry.getString("Tombstone") != null
                    ? entry.getString("Tombstone") : "False"))
            {
              mergedTiles++;
              fromThisPeer++;
            }
          }
        }

        if (fromThisPeer > 0)
          mergedFrom++;
      }
      catch (final Exception e)
      {
        failed++;
        LOG.warning("ReconcileApps skip remote prvId=" + remotePrvId
            + " actId=" + actId + ": " + e);
      }
    }

    final ObjAttrs outAttrs = new ObjAttrs();

    outAttrs.addAttr("Reconciled", "True");
    outAttrs.addAttr("MergedFrom", Integer.toString(mergedFrom));
    outAttrs.addAttr("MergedTiles", Integer.toString(mergedTiles));
    outAttrs.addAttr("Skipped", Integer.toString(skipped));
    outAttrs.addAttr("Failed", Integer.toString(failed));
    outAttrs.addAttr("Version", readContainerVersion(dst));

    outMsg.addResponseBody(opr, outAttrs);
  }

  private void installApp(final String opr, final JsonMsg inMsg, final JsonMsg outMsg,
                           final DomatarMsgClient msgClient)
      throws DomatarException
  {
    final DomId dst = inMsg.getDstId();

    final String appId       = inMsg.getAttr("AppId");
    final String displayName = inMsg.getAttr("DisplayName");
    final String iconPath    = inMsg.getAttr("IconPath");
    final String launchPath  = inMsg.getAttr("LaunchPath");
    final String hostPrvId   = inMsg.getAttr("HostPrvId");
    final String appHstId    = inMsg.getAttr("AppHstId");
    String       position    = inMsg.getAttr("Position");

    if (appId == null || displayName == null || iconPath == null || launchPath == null)
    {
      outMsg.addError(opr, "Missing AppId / DisplayName / IconPath / LaunchPath");
      return;
    }

    if (position == null)
      position = "100";

    final String now = IdGen.getCurTimeBase64();

    final String objId = IdGen.createId("app", appId);

    final DomId rowDomId = new DomId(dst.hstId, "desktop", dst.actId, objId);

    // Idempotent: an InstallApp on an already-installed app overwrites
    // the row (same shape as Spec-Desktop.txt PART 11 reorder TODO).
    final Obj existing = ObjDb.getObj(rowDomId);

    if (existing != null)
      ObjDb.deleteObj(rowDomId);

    final ObjAttrs rowAttrs = new ObjAttrs();

    rowAttrs.addAttr("DisplayName", displayName);
    rowAttrs.addAttr("IconPath",    iconPath);
    rowAttrs.addAttr("LaunchPath",  launchPath);
    rowAttrs.addAttr("Position",    position);
    rowAttrs.addAttr("TileVersion", now);
    rowAttrs.addAttr("Tombstone",   "False");

    if (hostPrvId != null && !hostPrvId.isEmpty())
      rowAttrs.addAttr("HostPrvId", hostPrvId);
    if (appHstId != null && !appHstId.isEmpty())
      rowAttrs.addAttr("AppHstId", appHstId);

    final Obj newObj = new Obj(rowDomId, "desktop", "app", displayName, "Installed application", rowAttrs);

    ObjDb.addObj(newObj);

    // Navigator lnk: apps container -> this app row.
    // dst IS the apps container DomId (the dispatch target).
    if (LnkDb.getLnk(dst, rowDomId, "desktop", "app") == null)
      LnkDb.addLnk(new Lnk(dst, rowDomId,
                            "desktop", "app",
                            displayName, "Installed application",
                            "desktop", "app",
                            appId, 0));

    bumpContainerVersion(dst, now);

    fanoutTile(dst, appId, displayName, iconPath, launchPath, position, now, "False",
        hostPrvId, appHstId, msgClient);

    final ObjAttrs outAttrs = new ObjAttrs();

    outAttrs.addAttr("Installed", "True");

    outMsg.addResponseBody(opr, outAttrs);
  }

  private void uninstallApp(final String opr, final JsonMsg inMsg, final JsonMsg outMsg,
                             final DomatarMsgClient msgClient)
      throws DomatarException
  {
    final DomId dst = inMsg.getDstId();

    final String appId = inMsg.getAttr("AppId");

    if (appId == null)
    {
      outMsg.addError(opr, "Missing AppId");
      return;
    }

    final String objId = IdGen.createId("app", appId);

    final DomId rowDomId = new DomId(dst.hstId, "desktop", dst.actId, objId);

    final Obj existing = ObjDb.getObj(rowDomId);

    final ObjAttrs outAttrs = new ObjAttrs();

    outAttrs.addAttr("Installed", "False");

    // Soft-delete: a physical delete would be resurrected by an older
    // still-present copy on a peer at the next merge (PART 6.4).
    if (existing == null)
    {
      outMsg.addResponseBody(opr, outAttrs);
      return;
    }

    final String now = IdGen.getCurTimeBase64();

    final ObjAttrs rowAttrs = existing.attrs != null ? existing.attrs : new ObjAttrs();

    rowAttrs.addAttr("Tombstone",   "True");
    rowAttrs.addAttr("TileVersion", now);

    ObjDb.modifyObj(existing.modify(null, null, null, null, null, rowAttrs));

    bumpContainerVersion(dst, now);

    fanoutTile(dst, appId,
        rowAttrs.getAttr("DisplayName"),
        rowAttrs.getAttr("IconPath"),
        rowAttrs.getAttr("LaunchPath"),
        rowAttrs.getAttr("Position"),
        now, "True",
        rowAttrs.getAttr("HostPrvId"),
        rowAttrs.getAttr("AppHstId"),
        msgClient);

    outMsg.addResponseBody(opr, outAttrs);
  }

  private static void fanoutTile(final DomId dst,
                                  final String appId,
                                  final String displayName,
                                  final String iconPath,
                                  final String launchPath,
                                  final String position,
                                  final String tileVersion,
                                  final String tombstone,
                                  final String hostPrvId,
                                  final String appHstId,
                                  final DomatarMsgClient msgClient)
  {
    if (msgClient == null || isIntrinsic(appId))
      return;

    try
    {
      final JsonMap entry = new JsonHashMap();

      entry.put("AppId",       appId);
      entry.put("DisplayName", displayName);
      entry.put("IconPath",    iconPath);
      entry.put("LaunchPath",  launchPath);
      entry.put("Position",    position);
      entry.put("TileVersion", tileVersion);
      entry.put("Tombstone",   tombstone);

      if (hostPrvId != null && !hostPrvId.isEmpty())
        entry.put("HostPrvId", hostPrvId);
      if (appHstId != null && !appHstId.isEmpty())
        entry.put("AppHstId", appHstId);

      final JsonList tiles = new JsonArrayList();

      tiles.add(entry);

      DesktopFanout.pushTiles(dst.actId, replicaPrvIdOrConfig(dst.hstId),
          tiles, msgClient);
    }
    catch (final Exception e)
    {
      LOG.warning("fanoutTile failed for appId=" + appId
          + " actId=" + dst.actId + ": " + e);
    }
  }

  private static void putOptional(final JsonMap entry, final ObjAttrs a,
                                  final String key)
      throws DomatarException
  {
    final String val = a.getAttr(key);

    if (val != null && !val.isEmpty())
      entry.put(key, val);
  }

  private static String replicaPrvIdOrConfig(final String hstId)
  {
    final String parsed = DomId.replicaPrvId(hstId);

    return parsed != null ? parsed : DomatarConfig.getPrvId();
  }

  /**
   * Legacy empty-desktop fallback catalog (Spec-Desktop.txt PART 8).
   * No shell tiles (GetShells) and no "#" stub apps — search/chat/mail
   * are real JARs (Phase 7).
   */
  private void seedDefaultCatalog(final DomId dst) throws DomatarException
  {
    String maxSeen = null;

    maxSeen = maxVersion(maxSeen, seedOne(dst, "quippin", "Quippin",
        "Microblog and follow feed",
        "/domatar/quippin/icons/app.svg", "/domatar/quippin/news.html", "3"));
    maxSeen = maxVersion(maxSeen, seedOne(dst, "aiagent", "AI Agent",
        "Chat with an AI in your Domatar",
        "/domatar/aiagent/icons/app.svg", "/domatar/aiagent/aiagent.html", "5"));
    maxSeen = maxVersion(maxSeen, seedOne(dst, "bookstore", "Bookstore",
        "Buy and sell books",
        "/domatar/bookstore/icons/app.svg", "/domatar/bookstore/bookstore.html", "6"));
    maxSeen = maxVersion(maxSeen, seedOne(dst, "spreadsheet", "Spreadsheet",
        "Work with tabular data",
        "/domatar/spreadsheet/icons/app.svg", "/domatar/spreadsheet/spreadsheet.html", "7"));
    maxSeen = maxVersion(maxSeen, seedOne(dst, "appstore", "App Store",
        "Browse and install apps",
        "/domatar/appstore/icons/app.svg", "/domatar/appstore/appstore.html", "9"));
    maxSeen = maxVersion(maxSeen, seedOne(dst, "money", "Money",
        "Banking and payments",
        "/domatar/money/icons/app.svg", "/domatar/money/money.html", "15"));

    // Only bump when something was inserted/backfilled — avoid churning
    // the container Version on steady-state GetApps.
    if (maxSeen != null)
      bumpContainerVersion(dst, maxSeen);
  }

  /**
   * Seed or backfill one catalog tile.
   * @return the TileVersion written on insert/backfill, or null if the
   *         row already had a TileVersion (no Version churn).
   */
  private static String seedOne(final DomId dst,
                                 final String appId,
                                 final String displayName,
                                 final String objDesc,
                                 final String iconPath,
                                 final String launchPath,
                                 final String position) throws DomatarException
  {
    return addCatalogRow(dst, appId, displayName, objDesc, iconPath, launchPath,
        position, IdGen.getCurTimeBase64());
  }

  /**
   * @return the TileVersion written on insert/backfill, or null when the
   *         row already carried a TileVersion (caller must not bump).
   */
  private static String addCatalogRow(final DomId dst,
                                       final String appId,
                                       final String displayName,
                                       final String objDesc,
                                       final String iconPath,
                                       final String launchPath,
                                       final String position,
                                       final String candidateVersion)
      throws DomatarException
  {
    final String objId = IdGen.createId("app", appId);

    final DomId rowDomId = new DomId(dst.hstId, "desktop", dst.actId, objId);

    final Obj existing = ObjDb.getObj(rowDomId);

    if (existing != null)
    {
      final String storedVersion = existing.attrs != null
          ? existing.attrs.getAttr("TileVersion") : null;

      // Row already has a TileVersion (seeded or marketplace-installed).
      // Do not clobber LaunchPath / HostPrvId / Position on every GetApps —
      // seedDefaultCatalog would otherwise reset absolute cross-provider
      // LaunchPaths to relative defaults.
      if (storedVersion != null && !storedVersion.isEmpty())
      {
        if (existing.attrs != null && existing.attrs.getAttr("Tombstone") == null)
        {
          final ObjAttrs attrs = existing.attrs;

          attrs.addAttr("Tombstone", "False");
          ObjDb.modifyObj(existing.modify(null, null, null, existing.objName,
              existing.objDesc, attrs));
        }

        return null;
      }

      // Legacy row without TileVersion: backfill once only.
      final ObjAttrs attrs = existing.attrs != null ? existing.attrs : new ObjAttrs();

      attrs.addAttr("DisplayName", displayName);
      attrs.addAttr("IconPath",    iconPath);
      attrs.addAttr("LaunchPath",  launchPath);
      attrs.addAttr("Position",    position);
      attrs.addAttr("TileVersion", candidateVersion);
      attrs.addAttr("Tombstone",   "False");

      ObjDb.modifyObj(existing.modify(null, null, null, displayName, objDesc, attrs));
      return candidateVersion;
    }

    final ObjAttrs attrs = new ObjAttrs();

    attrs.addAttr("DisplayName", displayName);
    attrs.addAttr("IconPath",    iconPath);
    attrs.addAttr("LaunchPath",  launchPath);
    attrs.addAttr("Position",    position);
    attrs.addAttr("TileVersion", candidateVersion);
    attrs.addAttr("Tombstone",   "False");

    final Obj row = new Obj(rowDomId, "desktop", "app", displayName, objDesc, attrs);

    ObjDb.addObj(row);

    // Navigator lnk: apps container -> this catalog row.
    if (LnkDb.getLnk(dst, rowDomId, "desktop", "app") == null)
      LnkDb.addLnk(new Lnk(dst, rowDomId,
                            "desktop", "app",
                            displayName, objDesc,
                            "desktop", "app",
                            extractAppId(rowDomId.objId), 0));

    return candidateVersion;
  }

  /**
   * LWW upsert of a tile row. Returns true when the local row was written.
   */
  private static boolean mergeTileRow(final DomId dst,
                                       final String appId,
                                       final String displayName,
                                       final String iconPath,
                                       final String launchPath,
                                       final String position,
                                       final String tileVersion,
                                       final String tombstone)
      throws DomatarException
  {
    if (tileVersion == null || tileVersion.isEmpty())
      return false;

    final String objId = IdGen.createId("app", appId);
    final DomId rowDomId = new DomId(dst.hstId, "desktop", dst.actId, objId);

    final Obj existing = ObjDb.getObj(rowDomId);

    if (existing != null)
    {
      final String storedVersion = existing.attrs != null
          ? existing.attrs.getAttr("TileVersion") : null;

      if (compareTileVersion(tileVersion, storedVersion) <= 0)
        return false;
    }

    final String resolvedName = displayName != null ? displayName : appId;
    final String resolvedPos  = position != null ? position : "100";
    final String resolvedTomb = "True".equals(tombstone) ? "True" : "False";

    final ObjAttrs rowAttrs = existing != null && existing.attrs != null
        ? existing.attrs : new ObjAttrs();

    if (displayName != null)
      rowAttrs.addAttr("DisplayName", displayName);
    if (iconPath != null)
      rowAttrs.addAttr("IconPath", iconPath);
    if (launchPath != null)
      rowAttrs.addAttr("LaunchPath", launchPath);
    rowAttrs.addAttr("Position",    resolvedPos);
    rowAttrs.addAttr("TileVersion", tileVersion);
    rowAttrs.addAttr("Tombstone",   resolvedTomb);

    if (existing != null)
    {
      ObjDb.modifyObj(existing.modify(null, null, null, resolvedName,
          "Installed application", rowAttrs));
    }
    else
    {
      ObjDb.addObj(new Obj(rowDomId, "desktop", "app", resolvedName,
          "Installed application", rowAttrs));
    }

    // Live tiles need the apps->tile lnk; tombstones keep it (harmless).
    if (!"True".equals(resolvedTomb)
        && LnkDb.getLnk(dst, rowDomId, "desktop", "app") == null)
      LnkDb.addLnk(new Lnk(dst, rowDomId,
                            "desktop", "app",
                            resolvedName, "Installed application",
                            "desktop", "app",
                            appId, 0));

    bumpContainerVersion(dst, tileVersion);

    return true;
  }

  private static String readContainerVersion(final DomId containerDomId)
      throws DomatarException
  {
    final Obj container = ObjDb.getObj(containerDomId);

    if (container == null || container.attrs == null)
      return maxPlausibleTileVersion(containerDomId);

    final String version = container.attrs.getAttr("Version");

    if (isPlausibleTileVersion(version))
      return version;

    // Self-heal: a poisoned Version (e.g. all-~ sentinel) blocks reconcile
    // because every real TileVersion compares as older. Fall back to the
    // max plausible tile stamp and rewrite the container when found.
    final String repaired = maxPlausibleTileVersion(containerDomId);

    if (repaired != null && !repaired.isEmpty())
    {
      final ObjAttrs attrs = container.attrs != null ? container.attrs : new ObjAttrs();

      attrs.addAttr("Version", repaired);
      ObjDb.modifyObj(container.modify(null, null, null, null, null, attrs));
      return repaired;
    }

    return "";
  }

  /**
   * Monotonic container Version bump: set to max(current, candidate).
   * candidate is the just-written TileVersion (the new max).
   */
  private static void bumpContainerVersion(final DomId containerDomId,
                                            final String candidateVersion)
      throws DomatarException
  {
    if (!isPlausibleTileVersion(candidateVersion))
      return;

    final Obj container = ObjDb.getObj(containerDomId);

    if (container == null)
      return;

    final String current = container.attrs != null
        ? container.attrs.getAttr("Version") : null;

    if (compareTileVersion(candidateVersion, current) <= 0)
      return;

    final ObjAttrs attrs = container.attrs != null ? container.attrs : new ObjAttrs();

    attrs.addAttr("Version", candidateVersion);

    ObjDb.modifyObj(container.modify(null, null, null, null, null, attrs));
  }

  /**
   * Max plausible TileVersion among desktop/app rows on this host.
   * Used to repair a poisoned container Version.
   */
  private static String maxPlausibleTileVersion(final DomId containerDomId)
      throws DomatarException
  {
    if (containerDomId == null)
      return "";

    final List<Obj> rows = ObjDb.getObjPrefix(
        containerDomId.hstId, "desktop", containerDomId.actId, "app", null, 1000);

    String max = "";

    for (final Obj row : rows)
    {
      if (!"desktop".equals(row.clsAppId) || !"app".equals(row.clsId))
        continue;

      final String tv = row.attrs != null ? row.attrs.getAttr("TileVersion") : null;

      if (!isPlausibleTileVersion(tv))
        continue;

      max = maxVersion(max, tv);
    }

    return max != null ? max : "";
  }

  /**
   * Intrinsic sync exemption removed in Phase 5 (KD10). Kept as a
   * no-op helper so call sites stay readable until a later cleanup.
   */
  private static boolean isIntrinsic(final String appId)
  {
    return false;
  }

  /**
   * "app-quippin" -&gt; "quippin". Used for surfacing the appId in the
   * response body so the browser can do round-trip operations
   * (uninstall, reorder) without re-deriving it.
   */
  private static String extractAppId(final String objId)
  {
    return IdGen.getIdSuffix(objId);
  }

  private static String launchPathForBrowser(final String appId,
                                                final String stored)
  {
    final String rewritten = AppUrls.replaceLaunchAsset(stored,
        LaunchPaths.launchPage(appId));

    return rewritten != null ? rewritten : stored;
  }

  private static int comparePosition(final Obj a, final Obj b) throws DomatarException
  {
    final String pa = a.attrs.getAttr("Position");
    final String pb = b.attrs.getAttr("Position");

    final int ia = parsePosition(pa);
    final int ib = parsePosition(pb);

    return Integer.compare(ia, ib);
  }

  private static int parsePosition(final String p)
  {
    if (p == null)
      return Integer.MAX_VALUE;

    try
    {
      return Integer.parseInt(p);
    }
    catch (NumberFormatException e)
    {
      return Integer.MAX_VALUE;
    }
  }

  private static String maxVersion(final String a, final String b)
  {
    if (a == null || a.isEmpty())
      return b;
    if (b == null || b.isEmpty())
      return a;
    return compareTileVersion(a, b) >= 0 ? a : b;
  }

  /**
   * Reject null/empty and far-future stamps (e.g. the all-~ sentinel
   * "~~~~~~~~" = 2^48-1) that would otherwise permanently pin container
   * Version above every real IdGen time and stall reconcile.
   */
  static boolean isPlausibleTileVersion(final String v)
  {
    if (v == null || v.isEmpty())
      return false;

    try
    {
      final long t = Base64Encoder.decodeToLong(v);

      if (t < 0)
        return false;

      // Allow a small clock-skew window; reject clearly synthetic maxima.
      final long skewMs = 7L * 24 * 60 * 60 * 1000;

      return t <= System.currentTimeMillis() + skewMs;
    }
    catch (final Exception e)
    {
      return false;
    }
  }

  /**
   * Compare TileVersion stamps. Prefer numeric decode of the base64 time;
   * fall back to lexicographic compare if decode fails. Nulls and
   * implausible stamps sort lowest.
   */
  static int compareTileVersion(final String a, final String b)
  {
    final boolean pa = isPlausibleTileVersion(a);
    final boolean pb = isPlausibleTileVersion(b);

    if (!pa && !pb)
      return 0;
    if (!pa)
      return -1;
    if (!pb)
      return 1;

    try
    {
      final long la = Base64Encoder.decodeToLong(a);
      final long lb = Base64Encoder.decodeToLong(b);

      if (la >= 0 && lb >= 0)
        return Long.compare(la, lb);
    }
    catch (final Exception ignored)
    {
      // fall through
    }

    return a.compareTo(b);
  }
}
