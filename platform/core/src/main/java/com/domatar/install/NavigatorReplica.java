/*
 * Copyright (c) 2024 Domatar
 */

package com.domatar.install;

import com.domatar.db.HstDb;
import com.domatar.db.LnkDb;
import com.domatar.db.ObjDb;
import com.domatar.util.Lnk;
import com.domatar.util.Obj;
import com.domatar.util.DomId;
import com.domatar.util.DomatarException;
import com.domatar.util.DomatarMsgClient;

/**
 * Creates a per-provider Navigator replica
 * {@code navigator-&lt;actId&gt;-&lt;prvId&gt;} (Spec-Desktop-Synchronized.txt
 * PART 3.1). Each provider serves its OWN navigator roots locally; the
 * tree is not content-merged across providers.
 *
 * <p>Idempotent: a second call is a no-op when the root obj already exists.
 */
public final class NavigatorReplica
{
  private NavigatorReplica() {}

  /**
   * Ensure hst + directory registration + root + app-navigator
   * (+ srv/cls descriptors) exist for {@code (actId, prvId)}.
   */
  public static void ensure(final String actId,
                            final String usrName,
                            final String domain,
                            final String prvId,
                            final DomatarMsgClient msgClient) throws DomatarException
  {
    if (actId == null || domain == null || prvId == null)
      throw new DomatarException("NavigatorReplica.ensure requires actId, domain, prvId");

    final String navRepId = DomId.subHstId("navigator", actId, prvId);

    if (HstDb.getHst(navRepId) == null)
      HstDb.addHst(navRepId, domain, prvId);

    DirectoryRegister.registerHst(navRepId, domain, prvId, msgClient);

    final DomId rootId   = new DomId(navRepId, "navigator", actId, "root");
    final DomId appNavId = new DomId(navRepId, "navigator", actId, "app-navigator");

    // ObjName is the fixed label "Root" (not usrName / actId).
    ObjDb.addObjIfMissing(rootId, "navigator", "root", "Root", "Root");
    ensureRootDisplayName(rootId);

    ObjDb.addObjIfMissing(appNavId, "domatar", "app",
        "Navigator", "Browse your Domatar objects");

    if (LnkDb.getLnk(rootId, appNavId, "navigator", "app") == null)
      LnkDb.addLnk(new Lnk(rootId, appNavId,
                            "domatar", "app",
                            "Navigator", "Browse your Domatar objects",
                            "navigator", "app",
                            null, 4));

    SrvInstall.ensureSrvsContainer(appNavId,
        "Service descriptors for Navigator", "navigator", 1);
    ClsInstall.ensureClssContainer(appNavId,
        "Class descriptors for Navigator", "navigator", 2);

    installDescriptors(rootId);
  }

  /**
   * Ensure the navigator root's ObjName/ObjDesc are the fixed "Root"
   * label (repairs older seeds that used usrName or actId as ObjName).
   */
  public static void ensureRootDisplayName(final DomId rootId)
      throws DomatarException
  {
    if (rootId == null)
      return;

    final Obj root = ObjDb.getObj(rootId);

    if (root == null)
      return;

    final boolean nameOk = "Root".equals(root.objName);
    final boolean descOk = "Root".equals(root.objDesc);

    if (nameOk && descOk)
      return;

    ObjDb.modifyObj(root.modify(null, null, null,
        nameOk ? null : "Root",
        descOk ? null : "Root",
        null));
  }

  private static void installDescriptors(final DomId rootId) throws DomatarException
  {
    SrvInstall.addSrvObj(rootId, "navigator", "root",
        "Root of the Navigator tree",
        "{\"SrvAppId\":\"navigator\",\"SrvId\":\"root\",\"Attrs\":[]," +
        "\"Msgs\":[{\"Name\":\"GetObj\",\"Type\":{\"ObjName\":\"String\",\"ObjDesc\":\"String\"}}," +
        "{\"Name\":\"Open\",\"Type\":[{\"Name\":\"String\",\"Desc\":\"String\",\"DomId\":\"String\",\"ClsAppId\":\"String\",\"ClsId\":\"String\"}]}]}");

    ClsInstall.upsertClsImplementing(rootId, "navigator", "root",
        "Root of the Navigator tree",
        "{\"ClsAppId\":\"navigator\",\"ClsId\":\"root\"," +
        "\"Implements\":[\"navigator.root\"]," +
        "\"MsgPolicy\":[" +
        "{\"Srv\":\"navigator.root\",\"Name\":\"GetObj\",\"SideEffect\":\"Read\"}," +
        "{\"Srv\":\"navigator.root\",\"Name\":\"Open\",\"SideEffect\":\"Read\"}" +
        "]}");
  }
}
