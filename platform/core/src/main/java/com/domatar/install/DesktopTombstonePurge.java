/*
 * Copyright (c) 2024 Domatar
 */

package com.domatar.install;

import java.util.List;
import java.util.logging.Logger;

import com.domatar.core.DomatarConfig;
import com.domatar.db.HstDb;
import com.domatar.db.LnkDb;
import com.domatar.db.ObjDb;
import com.domatar.util.Base64Encoder;
import com.domatar.util.Hst;
import com.domatar.util.Obj;
import com.domatar.util.DomId;
import com.domatar.util.DomatarException;

/**
 * Optional physical purge of aged Desktop tile tombstones
 * (Spec-Desktop-Synchronized.txt PART 6.4 / PART 14).
 *
 * <p>Operates on local {@code desktop-&lt;actId&gt;-&lt;localPrvId&gt;} replicas
 * only. Does not coordinate with peers — operators should ensure replicas
 * have reconciled before purging, or a lagging peer may resurrect the tile.
 */
public final class DesktopTombstonePurge
{
  private static final Logger LOG =
      Logger.getLogger(DesktopTombstonePurge.class.getName());

  /** Conservative default: 30 days (Update-Desktop-Synchronized Phase 5). */
  public static final long TOMBSTONE_PURGE_WINDOW_MS =
      30L * 24L * 60L * 60L * 1000L;

  private DesktopTombstonePurge() {}

  public static String purge() throws DomatarException
  {
    return purge(TOMBSTONE_PURGE_WINDOW_MS);
  }

  public static String purge(final long windowMs) throws DomatarException
  {
    final String localPrvId = DomatarConfig.getPrvId();
    final long cutoff = System.currentTimeMillis() - windowMs;
    final String suffix = "-" + localPrvId;

    int scanned = 0;
    int purged  = 0;
    int skipped = 0;

    for (final Hst h : HstDb.getAllHsts())
    {
      if (h == null || h.hstId == null)
        continue;
      if (!h.hstId.startsWith("desktop-") || !h.hstId.endsWith(suffix))
        continue;
      if (localPrvId != null && h.prvId != null && !localPrvId.equals(h.prvId))
        continue;

      final String actId = DomId.replicaActId(h.hstId);

      if (actId == null)
        continue;

      final List<Obj> tiles =
          ObjDb.getObjPrefix(h.hstId, "desktop", actId, "app", null, 1000);

      for (final Obj tile : tiles)
      {
        if (tile == null || tile.attrs == null || tile.domId == null)
          continue;

        scanned++;

        if (!"True".equals(tile.attrs.getAttr("Tombstone")))
        {
          skipped++;
          continue;
        }

        final String tv = tile.attrs.getAttr("TileVersion");
        final long tvMs = decodeTileVersionMs(tv);

        if (tvMs < 0 || tvMs >= cutoff)
        {
          skipped++;
          continue;
        }

        final DomId apps = new DomId(h.hstId, "desktop", actId, "apps");

        LnkDb.deleteLnks(apps, tile.domId, "desktop", "app", null, null);
        ObjDb.deleteObj(tile.domId);
        purged++;
      }
    }

    final String summary = "DesktopTombstonePurge complete (prvId=" + localPrvId
        + ", windowMs=" + windowMs + ").\n"
        + "  scanned=" + scanned + "  purged=" + purged
        + "  skipped=" + skipped + "\n";

    LOG.info(summary);
    return summary;
  }

  private static long decodeTileVersionMs(final String tv)
  {
    if (tv == null || tv.isEmpty())
      return -1L;

    try
    {
      return Base64Encoder.decodeToLong(tv);
    }
    catch (final Exception e)
    {
      return -1L;
    }
  }
}
