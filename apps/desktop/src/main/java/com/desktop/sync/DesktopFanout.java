/*
 * Copyright (c) 2024 Domatar
 */

package com.desktop.sync;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;

import com.domatar.core.DomatarConfig;
import com.domatar.db.ObjDb;
import com.domatar.util.JsonList;
import com.domatar.util.JsonMap;
import com.domatar.util.JsonMsg;
import com.domatar.util.Obj;
import com.domatar.util.ObjAttrs;
import com.domatar.util.DomId;
import com.domatar.util.DomatarMsgClient;

/**
 * Best-effort Desktop tile fan-out + peer discovery from the local
 * Login membership (Spec-Desktop-Synchronized.txt PART 5 / 7.2).
 *
 * <p>Discovers the replica set from local membership peer rows (distinct
 * PrvId, minus local). Failures are non-fatal: display-time ReconcileApps
 * repairs anything a push missed.
 */
public final class DesktopFanout
{
  private static final Logger LOG = Logger.getLogger(DesktopFanout.class.getName());

  private static final String[] INTRINSIC_APP_IDS =
      { "login", "navigator", "desktop" };

  private DesktopFanout() {}

  /**
   * Distinct peer PrvId values from the local membership, excluding
   * {@code localPrvId}. Reads peer rows via ObjDb (no Sec required).
   * Non-fatal: returns an empty list on any failure.
   */
  public static List<String> discoverPeerPrvIds(final String actId,
                                                 final String localPrvId,
                                                 final DomatarMsgClient msgClient)
  {
    final Set<String> result = new LinkedHashSet<String>();

    if (actId == null)
      return new ArrayList<String>(result);

    try
    {
      final String local = localPrvId != null ? localPrvId : DomatarConfig.getPrvId();
      final String hstId = DomId.subHstId("login", actId, local);
      final List<Obj> rows = ObjDb.getObjPrefix(hstId, "login", actId, "peer", null, 1000);

      for (final Obj row : rows)
      {
        if (row == null || row.attrs == null)
          continue;

        if ("True".equals(row.attrs.getAttr("Tombstone")))
          continue;

        final String prvId = row.attrs.getAttr("PrvId");

        if (prvId == null || prvId.isEmpty())
          continue;

        if (local != null && local.equals(prvId))
          continue;

        result.add(prvId);
      }
    }
    catch (final Exception e)
    {
      LOG.warning("discoverPeerPrvIds failed for actId=" + actId + ": " + e);
    }

    return new ArrayList<String>(result);
  }

  /**
   * Best-effort MergeApps of {@code tiles} to every peer Desktop replica.
   * Skips intrinsic system-app tiles. Non-fatal per peer.
   */
  public static void pushTiles(final String actId,
                               final String localPrvId,
                               final JsonList tiles,
                               final DomatarMsgClient msgClient)
  {
    if (actId == null || tiles == null || msgClient == null)
      return;

    final JsonList filtered = filterIntrinsic(tiles);

    if (filtered.size() == 0)
      return;

    final String local = localPrvId != null ? localPrvId : DomatarConfig.getPrvId();

    for (final String remotePrvId : discoverPeerPrvIds(actId, local, msgClient))
    {
      try
      {
        final DomId dst = new DomId(
            DomId.subHstId("desktop", actId, remotePrvId),
            "desktop", actId, "apps");

        final ObjAttrs attrs = new ObjAttrs();

        attrs.addAttr("Apps", filtered);

        final JsonMsg msg = new JsonMsg();

        msg.addRequestBody("MergeApps", attrs);
        msg.addClsId("desktop", "apps");

        final JsonMsg resp = msgClient.send(dst, msg);
        final String err = resp != null ? resp.getError() : "null-resp";

        if (!"Success".equals(err))
          LOG.warning("pushTiles MergeApps to prvId=" + remotePrvId
              + " actId=" + actId + " error=" + err
              + " msg=" + (resp != null ? resp.getErrorMsg() : ""));
      }
      catch (final Exception e)
      {
        LOG.warning("pushTiles to prvId=" + remotePrvId
            + " failed for actId=" + actId + ": " + e);
      }
    }
  }

  private static JsonList filterIntrinsic(final JsonList tiles)
  {
    final JsonList out = new com.domatar.util.JsonArrayList();

    for (int i = 0; i < tiles.size(); i++)
    {
      final Object item = tiles.get(i);

      if (!(item instanceof JsonMap))
        continue;

      final String appId = ((JsonMap) item).getString("AppId");

      if (isIntrinsic(appId))
        continue;

      out.add(item);
    }

    return out;
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
}
