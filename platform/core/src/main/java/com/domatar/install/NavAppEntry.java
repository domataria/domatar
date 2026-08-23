/*
 * Copyright (c) 2024 Domatar
 */

package com.domatar.install;

import com.domatar.db.LnkDb;
import com.domatar.db.ObjDb;
import com.domatar.util.Lnk;
import com.domatar.util.Obj;
import com.domatar.util.DomId;
import com.domatar.util.DomatarException;

/**
 * Navigator root child for an installed app: object and lnk class
 * {@code (<appId>, app)} so the tree can use class icons with no
 * special case.
 */
public final class NavAppEntry
{
  private NavAppEntry() {}

  public static void ensureObj(final DomId appObjId,
                               final String appId,
                               final String name,
                               final String desc) throws DomatarException
  {
    ObjDb.addObjIfMissing(appObjId, appId, "app", name, desc);
    ObjDb.reclassObj(appObjId, appId, "app");
  }

  /**
   * Create or repair root → app-obj. Existing lnks keep seqNum; only
   * the class pair is rewritten when it is not {@code (appId, app)}.
   */
  public static void ensureRootLnk(final DomId rootId,
                                   final DomId appObjId,
                                   final String appId,
                                   final String name,
                                   final String desc,
                                   final long seqNum) throws DomatarException
  {
    ensureObj(appObjId, appId, name, desc);

    final Lnk existing = LnkDb.getLnk(rootId, appObjId, "navigator", "app");

    if (existing == null)
    {
      LnkDb.addLnk(new Lnk(rootId, appObjId,
          appId, "app",
          name, desc,
          "navigator", "app",
          null, seqNum));
      return;
    }

    if (!appId.equals(existing.lnkClsAppId) || !"app".equals(existing.lnkClsId))
      LnkDb.reclassLnk(rootId, appObjId, "navigator", "app", appId, "app");
  }

  /**
   * Reclass obj (if present) and lnk. Creates the lnk when missing.
   * @return true if a new lnk was inserted
   */
  public static boolean repairRootLnk(final DomId rootId,
                                      final DomId appObjId,
                                      final String appId,
                                      final String name,
                                      final String desc,
                                      final long seqIfCreate) throws DomatarException
  {
    final Obj obj = ObjDb.getObj(appObjId);

    if (obj != null)
      ObjDb.reclassObj(appObjId, appId, "app");

    final Lnk existing = LnkDb.getLnk(rootId, appObjId, "navigator", "app");

    if (existing == null)
    {
      LnkDb.addLnk(new Lnk(rootId, appObjId,
          appId, "app",
          name, desc,
          "navigator", "app",
          null, seqIfCreate));
      return true;
    }

    if (!appId.equals(existing.lnkClsAppId) || !"app".equals(existing.lnkClsId))
      LnkDb.reclassLnk(rootId, appObjId, "navigator", "app", appId, "app");

    return false;
  }
}
