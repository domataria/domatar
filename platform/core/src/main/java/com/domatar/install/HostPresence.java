/*
 * Copyright (c) 2024 Domatar
 */

package com.domatar.install;

import java.util.List;

import com.domatar.core.DomatarConfig;
import com.domatar.db.HstDb;
import com.domatar.db.ObjDb;
import com.domatar.util.Hst;
import com.domatar.util.Obj;
import com.domatar.util.DomId;
import com.domatar.util.DomatarException;

/**
 * Whether an account already has membership presence on a provider
 * (login-home or object-only), and whether portable app hosts remain
 * there. Spec-Foreign-Provider-Installation.txt PART 5 / Update KD3,
 * TASK 6.2. Lives in domatar-core so login AccountsWui can call it.
 */
public final class HostPresence
{
  private HostPresence() {}

  /**
   * True if {@code targetPrvId} is this node, or a non-tombstoned membership
   * peer for {@code actId} lists that PrvId. Scans domatar substrate first,
   * then the login replica.
   */
  public static boolean hasPeer(final String actId, final String targetPrvId)
  {
    if (actId == null || targetPrvId == null)
      return false;

    if (targetPrvId.equals(DomatarConfig.getPrvId()))
      return true;

    try
    {
      final String homePrv = DomatarConfig.getPrvId();

      if (homePrv == null)
        return false;

      final DomId membership = UserSubstrateIds.membership(actId, homePrv);

      if (scanPeers(membership.hstId, "domatar", actId, targetPrvId))
        return true;

      final String loginHst = DomId.subHstId("login", actId, homePrv);

      return scanPeers(loginHst, "login", actId, targetPrvId);
    }
    catch (final Exception e)
    {
      System.out.println("WARN: HostPresence.hasPeer failed for actId=" + actId
          + " targetPrvId=" + targetPrvId + ": " + e);
      return false;
    }
  }

  /**
   * True if this node's {@code hst} table has a portable user-app host for
   * {@code actId} physically on {@code prvId} (not substrate / shell
   * replicas / central catalog hosts).
   */
  public static boolean hasPortableAppHosts(final String actId, final String prvId)
  {
    if (actId == null || prvId == null)
      return false;

    try
    {
      final List<Hst> hsts = HstDb.getAllHsts();

      if (hsts == null)
        return false;

      for (final Hst h : hsts)
      {
        if (h == null || h.hstId == null)
          continue;

        if (!prvId.equals(h.prvId))
          continue;

        if (isPortableUserAppHost(h.hstId, actId, prvId))
          return true;
      }

      return false;
    }
    catch (final Exception e)
    {
      System.out.println("WARN: HostPresence.hasPortableAppHosts failed for actId="
          + actId + " prvId=" + prvId + ": " + e);
      return false;
    }
  }

  /**
   * Conservative portable-host matcher (no DB). Public for unit tests (L6.1).
   */
  public static boolean isPortableUserAppHost(final String hstId,
                                              final String actId,
                                              final String prvId)
  {
    if (hstId == null || hstId.isEmpty() || actId == null || actId.isEmpty())
      return false;

    if (prvId != null && hstId.equals(UserSubstrateIds.hstId(actId, prvId)))
      return false;

    if (prvId != null)
    {
      for (final String replicaApp : UserSubstrateIds.ROLE_IDS)
      {
        if (hstId.equals(DomId.subHstId(replicaApp, actId, prvId)))
          return false;
      }
    }

    final String suffix = DomId.HOST_SEP + actId;

    if (hstId.endsWith(suffix) && hstId.length() > suffix.length())
      return true;

    final String mid = DomId.HOST_SEP + actId + DomId.HOST_SEP;

    return hstId.indexOf(mid) > 0;
  }

  private static boolean scanPeers(final String hstId, final String appId,
                                   final String actId, final String targetPrvId)
      throws DomatarException
  {
    if (hstId == null)
      return false;

    final List<Obj> rows = ObjDb.getObjPrefix(hstId, appId, actId, "peer",
        null, 1000);

    if (rows == null)
      return false;

    for (final Obj row : rows)
    {
      if (row == null || row.attrs == null)
        continue;

      if ("True".equals(row.attrs.getAttr("Tombstone")))
        continue;

      if (targetPrvId.equals(row.attrs.getAttr("PrvId")))
        return true;
    }

    return false;
  }
}
