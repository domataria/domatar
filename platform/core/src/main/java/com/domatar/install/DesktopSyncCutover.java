/*
 * Copyright (c) 2024 Domatar
 */

package com.domatar.install;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.domatar.core.DomatarConfig;
import com.domatar.db.ActDb;
import com.domatar.db.HstDb;
import com.domatar.db.LnkDb;
import com.domatar.db.ObjDb;
import com.domatar.util.Act;
import com.domatar.util.Base64Encoder;
import com.domatar.util.Hst;
import com.domatar.util.IdGen;
import com.domatar.util.Lnk;
import com.domatar.util.Obj;
import com.domatar.util.ObjAttrs;
import com.domatar.util.DomId;
import com.domatar.util.DomatarException;
import com.domatar.util.DomatarMsgClient;

/**
 * Phase 4 cutover: move bare {@code desktop-&lt;actId&gt;} /
 * {@code navigator-&lt;actId&gt;} hosts onto provider-qualified replicas and
 * DELETE the bare hosts (Update-Desktop-Synchronized.txt Phase 4).
 * Idempotent per node.
 */
public final class DesktopSyncCutover
{
  private static final Logger LOG = Logger.getLogger(DesktopSyncCutover.class.getName());

  private static final String[] INTRINSIC_APP_IDS =
      { "login", "navigator", "desktop" };

  private DesktopSyncCutover() {}

  public static String cutover() throws DomatarException
  {
    return cutover(null);
  }

  public static String cutover(final DomatarMsgClient msgClient) throws DomatarException
  {
    final String prvId  = DomatarConfig.getPrvId();
    final String domain = DomatarConfig.getDomain() != null
        ? DomatarConfig.getDomain() : "localhost";

    int migrated = 0;
    int skipped  = 0;
    int failed   = 0;
    final StringBuilder failures = new StringBuilder();

    for (final String actId : distinctActIds())
    {
      try
      {
        final String oldDesk = DomId.subHstId("desktop", actId);
        final String oldNav  = DomId.subHstId("navigator", actId);

        if (HstDb.getHst(oldDesk) == null && HstDb.getHst(oldNav) == null)
        {
          skipped++;
          continue;
        }

        final String usrName = usrNameFor(actId);

        cutoverOne(actId, usrName, prvId, domain, msgClient);
        migrated++;
        LOG.info("DesktopSyncCutover migrated actId=" + actId);
      }
      catch (final Exception e)
      {
        failed++;
        failures.append("  actId=").append(actId).append(": ").append(e).append('\n');
        LOG.log(Level.WARNING, "DesktopSyncCutover failed for actId=" + actId, e);
      }
    }

    try
    {
      final int orphans = purgeOrphanBareHosts(prvId);

      if (orphans > 0)
        LOG.info("DesktopSyncCutover purged orphan bare hosts=" + orphans);
    }
    catch (final Exception e)
    {
      failed++;
      failures.append("  orphan-purge: ").append(e).append('\n');
    }

    final StringBuilder summary = new StringBuilder();

    summary.append("DesktopSyncCutover complete (prvId=").append(prvId).append(").\n");
    summary.append("  migrated=").append(migrated)
           .append("  skipped=").append(skipped)
           .append("  failed=").append(failed).append('\n');

    if (failures.length() > 0)
    {
      summary.append("Failures:\n");
      summary.append(failures);
    }

    LOG.info(summary.toString());
    return summary.toString();
  }

  private static void cutoverOne(final String actId,
                                 final String usrName,
                                 final String prvId,
                                 final String domain,
                                 final DomatarMsgClient msgClient) throws DomatarException
  {
    final String oldNavHst  = DomId.subHstId("navigator", actId);
    final String oldDeskHst = DomId.subHstId("desktop", actId);
    final String navRepId   = DomId.subHstId("navigator", actId, prvId);
    final String deskRepId  = DomId.subHstId("desktop", actId, prvId);

    // --- b. NAVIGATOR first ---
    NavigatorReplica.ensure(actId, usrName, domain, prvId, msgClient);
    moveNavigatorRoot(actId, oldNavHst, oldDeskHst, navRepId);

    // --- c. DESKTOP tiles ---
    DesktopReplica.ensure(actId, domain, prvId, msgClient);
    moveDesktopTiles(actId, oldDeskHst, deskRepId);

    // --- d. Repoint qualified root -> app-desktop@replica ---
    repointRootAppDesktop(actId, navRepId, deskRepId, oldDeskHst);

    // --- e. DELETE bare hosts ---
    deleteBareHost(oldDeskHst);
    deleteBareHost(oldNavHst);
  }

  /**
   * Recreate lnks FROM the old bare navigator root onto the qualified root.
   * Targets on the old bare navigator / desktop hosts are skipped (recreated
   * by NavigatorReplica / DesktopReplica / repointRootAppDesktop).
   */
  private static void moveNavigatorRoot(final String actId,
                                        final String oldNavHst,
                                        final String oldDeskHst,
                                        final String navRepId) throws DomatarException
  {
    if (HstDb.getHst(oldNavHst) == null)
      return;

    final DomId oldRoot = new DomId(oldNavHst, "navigator", actId, "root");
    final DomId newRoot = new DomId(navRepId,  "navigator", actId, "root");

    if (ObjDb.getObj(oldRoot) == null)
      return;

    final List<Lnk> lnks = LnkDb.getLnks(oldRoot, "navigator", "app", null, null, 1000, false);

    for (final Lnk lnk : lnks)
    {
      if (lnk == null || lnk.lnkDomId == null)
        continue;

      final String tgtHst = lnk.lnkDomId.hstId;

      // Intrinsic / bare-local targets: skip — ensure helpers recreate them.
      if (oldNavHst.equals(tgtHst) || oldDeskHst.equals(tgtHst))
        continue;

      final DomId newFrom = newRoot;
      final DomId newTo   = lnk.lnkDomId;

      if (LnkDb.getLnk(newFrom, newTo, "navigator", "app") != null)
        continue;

      LnkDb.addLnk(new Lnk(newFrom, newTo,
                            lnk.lnkClsAppId, lnk.lnkClsId,
                            lnk.lnkObjName, lnk.lnkObjDesc,
                            "navigator", "app",
                            lnk.val, lnk.seqNum));
    }
  }

  /**
   * Move non-intrinsic {@code app-&lt;appId&gt;} tile rows from the bare Desktop
   * host onto the qualified replica, preserving attrs and apps→tile lnks.
   */
  private static void moveDesktopTiles(final String actId,
                                       final String oldDeskHst,
                                       final String deskRepId) throws DomatarException
  {
    if (HstDb.getHst(oldDeskHst) == null)
      return;

    final DomId oldApps = new DomId(oldDeskHst, "desktop", actId, "apps");
    final DomId newApps = new DomId(deskRepId,  "desktop", actId, "apps");

    final List<Obj> tiles = ObjDb.getObjPrefix(oldDeskHst, "desktop", actId, "app", null, 1000);

    String maxVersion = null;
    final String now = IdGen.getCurTimeBase64();

    for (final Obj tile : tiles)
    {
      if (tile == null || tile.domId == null)
        continue;

      final String objId = tile.domId.objId;

      if (objId == null || !objId.startsWith("app-"))
        continue;

      final String appId = objId.substring("app-".length());

      if (isIntrinsic(appId))
        continue;

      final DomId newTile = new DomId(deskRepId, "desktop", actId, objId);
      final Obj existing = ObjDb.getObj(newTile);

      final ObjAttrs attrs = tile.attrs != null ? tile.attrs : new ObjAttrs();

      if (attrs.getAttr("TileVersion") == null || attrs.getAttr("TileVersion").isEmpty())
        attrs.addAttr("TileVersion", now);

      if (attrs.getAttr("Tombstone") == null)
        attrs.addAttr("Tombstone", "False");

      final String tv = attrs.getAttr("TileVersion");

      if (existing == null)
      {
        ObjDb.addObj(new Obj(newTile,
            tile.clsAppId != null ? tile.clsAppId : "desktop",
            tile.clsId != null ? tile.clsId : "app",
            tile.objName != null ? tile.objName : appId,
            tile.objDesc != null ? tile.objDesc : "Installed application",
            attrs));
      }
      else if (compareTileVersion(tv, existing.attrs != null
          ? existing.attrs.getAttr("TileVersion") : null) > 0)
      {
        ObjDb.modifyObj(existing.modify(null,
            tile.clsAppId, tile.clsId, tile.objName, tile.objDesc, attrs));
      }

      if (LnkDb.getLnk(newApps, newTile, "desktop", "app") == null)
      {
        LnkDb.addLnk(new Lnk(newApps, newTile,
                              "desktop", "app",
                              tile.objName != null ? tile.objName : appId,
                              tile.objDesc != null ? tile.objDesc : "Installed application",
                              "desktop", "app",
                              appId, 0));
      }

      maxVersion = maxVersion(maxVersion, tv);
    }

    // Also consider the bare container Version.
    final Obj oldContainer = ObjDb.getObj(oldApps);

    if (oldContainer != null && oldContainer.attrs != null)
      maxVersion = maxVersion(maxVersion, oldContainer.attrs.getAttr("Version"));

    if (maxVersion != null)
      setContainerVersion(newApps, maxVersion);
  }

  private static void repointRootAppDesktop(final String actId,
                                            final String navRepId,
                                            final String deskRepId,
                                            final String oldDeskHst) throws DomatarException
  {
    final DomId rootId    = new DomId(navRepId,  "navigator", actId, "root");
    final DomId appDeskId = new DomId(deskRepId, "desktop",   actId, "app-desktop");

    if (ObjDb.getObj(rootId) == null)
      return;

    // PK is (from, tag, seqNum, val) WITHOUT the target — remove any
    // existing root->app-desktop (seq 3) that does not already point at
    // the replica before inserting.
    final List<Lnk> rootApps = LnkDb.getLnks(rootId, "navigator", "app", null, null, 100, false);

    for (final Lnk existing : rootApps)
    {
      if (existing == null || existing.lnkDomId == null)
        continue;
      if (existing.seqNum != 3)
        continue;
      if (!"app-desktop".equals(existing.lnkDomId.objId))
        continue;
      if (appDeskId.equals(existing.lnkDomId))
        continue;

      LnkDb.deleteLnks(rootId, existing.lnkDomId, "navigator", "app", null, null);
    }

    // Also drop any root->app-desktop still aimed at the bare desktop host
    // regardless of seqNum (defensive).
    if (oldDeskHst != null)
    {
      final DomId bareAppDesk = new DomId(oldDeskHst, "desktop", actId, "app-desktop");

      if (LnkDb.getLnk(rootId, bareAppDesk, "navigator", "app") != null)
        LnkDb.deleteLnks(rootId, bareAppDesk, "navigator", "app", null, null);
    }

    NavAppEntry.ensureRootLnk(rootId, appDeskId, "desktop",
        "Desktop", "Your home screen", 3);
  }

  private static void deleteBareHost(final String hstId) throws DomatarException
  {
    if (hstId == null || HstDb.getHst(hstId) == null)
      return;

    LnkDb.deleteByHstId(hstId);
    ObjDb.deleteByHstId(hstId);

    if (HstDb.getHst(hstId) != null)
      HstDb.deleteHst(hstId);
  }

  /**
   * Best-effort: delete leftover bare desktop/navigator hosts with no
   * matching act row.
   */
  private static int purgeOrphanBareHosts(final String prvId) throws DomatarException
  {
    int purged = 0;
    final Set<String> localActs = distinctActIds();

    for (final Hst h : HstDb.getAllHsts())
    {
      if (h == null || h.hstId == null)
        continue;

      final String actId = bareHostActId(h.hstId);

      if (actId == null)
        continue;
      if (localActs.contains(actId))
        continue;

      deleteBareHost(h.hstId);
      purged++;
    }

    return purged;
  }

  /**
   * If {@code hstId} is a bare {@code desktop-&lt;actId&gt;} or
   * {@code navigator-&lt;actId&gt;} (no {@code -prvN} suffix), return actId;
   * else null.
   */
  static String bareHostActId(final String hstId)
  {
    if (hstId == null)
      return null;

    final String prefix;
    if (hstId.startsWith("desktop-"))
      prefix = "desktop-";
    else if (hstId.startsWith("navigator-"))
      prefix = "navigator-";
    else
      return null;

    // Provider-qualified: desktop-<actId>-prvN / navigator-<actId>-prvN
    if (hstId.matches(prefix + ".+-prv\\d+$"))
      return null;

    final String actId = hstId.substring(prefix.length());

    return actId.isEmpty() ? null : actId;
  }

  private static void setContainerVersion(final DomId containerDomId,
                                          final String version) throws DomatarException
  {
    final Obj container = ObjDb.getObj(containerDomId);

    if (container == null || version == null)
      return;

    final String current = container.attrs != null
        ? container.attrs.getAttr("Version") : null;

    if (compareTileVersion(version, current) <= 0)
      return;

    final ObjAttrs attrs = container.attrs != null ? container.attrs : new ObjAttrs();

    attrs.addAttr("Version", version);
    ObjDb.modifyObj(container.modify(null, null, null, null, null, attrs));
  }

  private static String usrNameFor(final String actId) throws DomatarException
  {
    for (final Act act : ActDb.getAllActs())
    {
      if (actId.equals(act.actId) && act.usrName != null && !act.usrName.isEmpty())
        return act.usrName;
    }

    return actId;
  }

  private static Set<String> distinctActIds() throws DomatarException
  {
    final Set<String> ids = new LinkedHashSet<String>();

    for (final Act act : ActDb.getAllActs())
    {
      if (act.actId != null && !act.actId.isEmpty())
        ids.add(act.actId);
    }

    return ids;
  }

  private static boolean isIntrinsic(final String appId)
  {
    if (appId == null)
      return false;

    for (final String id : INTRINSIC_APP_IDS)
      if (id.equals(appId))
        return true;

    return false;
  }

  private static String maxVersion(final String a, final String b)
  {
    if (a == null || a.isEmpty())
      return b;
    if (b == null || b.isEmpty())
      return a;

    return compareTileVersion(a, b) >= 0 ? a : b;
  }

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
    }

    return a.compareTo(b);
  }

  static boolean isPlausibleTileVersion(final String v)
  {
    if (v == null || v.isEmpty())
      return false;

    try
    {
      final long t = Base64Encoder.decodeToLong(v);

      if (t < 0)
        return false;

      final long skewMs = 7L * 24 * 60 * 60 * 1000;

      return t <= System.currentTimeMillis() + skewMs;
    }
    catch (final Exception e)
    {
      return false;
    }
  }
}
