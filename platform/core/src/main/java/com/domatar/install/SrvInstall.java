/*
 * Copyright (c) 2024 Domatar
 */

package com.domatar.install;

import com.domatar.core.ClsMap;
import com.domatar.db.LnkDb;
import com.domatar.db.ObjDb;
import com.domatar.util.Lnk;
import com.domatar.util.Obj;
import com.domatar.util.ObjAttrs;
import com.domatar.util.DomId;
import com.domatar.util.DomatarException;

/**
 * Shared helpers for creating service-descriptor objects (Spec-Service.txt).
 *
 * Mirrors ClsInstall, with two critical differences:
 *   1. A service descriptor's obj AppId is the SrvAppId (not baseId.appId),
 *      so services are owned by the defining application's namespace.
 *   2. ObjId = srvId + "Srv" (parallel to ClsInstall's srvId + "Cls").
 *
 * Services are immutable in production: addSrvObj uses addObjIfMissing.
 * During development, upsertSrvObj can force an in-place descriptor update.
 *
 * Typical call sequence in an app install routine:
 *
 *   SrvInstall.ensureSrvsContainer(appXxxId, "Service descriptors", "myapp", seqNum);
 *   SrvInstall.addSrvObj(baseId, "myapp", "myservice", "description", attrsJson);
 *   ...
 *
 * All calls are idempotent.
 */
public class SrvInstall
{
  private static final String CLS_APP_ID  = "domatar";
  private static final String SRV_CLS_ID  = "srv";
  private static final String SRVS_CLS_ID = "srvs";
  private static final String SRVS_OBJ_ID = "srvs";
  static final String SRVS_OBJ_NAME = "Services";

  // ---------------------------------------------------------------------------
  // Services container
  // ---------------------------------------------------------------------------

  /**
   * Creates the "srvs" (Services) container object on the same sub-host as
   * appObjId and adds the link  appObjId → srvs  so it appears as a child
   * in the Navigator tree.
   *
   * @param appObjId  The app-xxx Navigator node for this app.
   * @param appDesc   Short description stored in the srvs object's ObjDesc.
   * @param tagAppId  The app that owns this relationship.
   * @param seqNum    SeqNum for the app-xxx → srvs link.
   */
  public static void ensureSrvsContainer(DomId  appObjId,
                                          String appDesc,
                                          String tagAppId,
                                          long   seqNum) throws DomatarException
  {
    DomId srvsId = srvsIdFor(appObjId);

    ObjDb.addObjIfMissing(srvsId, CLS_APP_ID, SRVS_CLS_ID, SRVS_OBJ_NAME, appDesc);

    if (LnkDb.getLnk(appObjId, srvsId, tagAppId, "srvs") == null)
      LnkDb.addLnk(new Lnk(appObjId, srvsId,
                            CLS_APP_ID, SRVS_CLS_ID,
                            SRVS_OBJ_NAME, appDesc,
                            tagAppId, "srvs",
                            null, seqNum));
  }

  /**
   * Ensures the "srvs" container object exists on the sub-host identified by
   * baseId, WITHOUT creating the app-xxx → srvs Navigator link.
   */
  public static void ensureSrvsObj(DomId baseId, String desc) throws DomatarException
  {
    ObjDb.addObjIfMissing(srvsIdFor(baseId), CLS_APP_ID, SRVS_CLS_ID, SRVS_OBJ_NAME, desc);
  }

  // ---------------------------------------------------------------------------
  // Service descriptor objects
  // ---------------------------------------------------------------------------

  /**
   * Creates a service-descriptor object for (srvAppId, srvId) on baseId's sub-host.
   *
   * obj DomId = (baseId.hstId, srvAppId, baseId.actId, srvId + "Srv").
   * Note: obj AppId = srvAppId, NOT baseId.appId. This scopes the service
   * under its defining application's namespace.
   *
   * Idempotent (addObjIfMissing): a second call for the same service is a no-op.
   * Auto-links srvs → descriptor when the srvs container exists on baseId.
   * Invalidates ClsMap because a new service can alter resolved class descriptors.
   *
   * @param baseId    Any DomId whose hstId/actId identify the target sub-host.
   * @param srvAppId  The application namespace that defines this service.
   * @param srvId     The service identifier within srvAppId's namespace.
   * @param objDesc   Short human-readable description.
   * @param attrsJson The service-definition JSON document (Spec-Service.txt PART 5).
   */
  public static void addSrvObj(DomId   baseId,
                                String  srvAppId,
                                String  srvId,
                                String  objDesc,
                                String  attrsJson) throws DomatarException
  {
    String   objId    = srvId + "Srv";
    DomId    srvDomId = new DomId(baseId.hstId, srvAppId, baseId.actId, objId);
    ObjAttrs attrs    = new ObjAttrs(attrsJson);

    ObjDb.addObjIfMissing(srvDomId, CLS_APP_ID, SRV_CLS_ID, objId, objDesc, attrs);
    linkSrvToSrvs(baseId, srvDomId, objId, objDesc);
    ClsMap.invalidateAll();
  }

  /**
   * Idempotent upsert: creates or updates the service descriptor object.
   * Use this (instead of {@link #addSrvObj}) when the descriptor JSON has
   * changed in-place during development and the existing DB row must be
   * refreshed. In production, prefer defining a new SrvId instead.
   *
   * Invalidates ClsMap after the update.
   */
  public static void upsertSrvObj(DomId   baseId,
                                   String  srvAppId,
                                   String  srvId,
                                   String  objDesc,
                                   String  attrsJson) throws DomatarException
  {
    String   objId    = srvId + "Srv";
    DomId    srvDomId = new DomId(baseId.hstId, srvAppId, baseId.actId, objId);
    ObjAttrs attrs    = new ObjAttrs(attrsJson);

    Obj existing = ObjDb.getObj(srvDomId);
    if (existing == null)
      ObjDb.addObjIfMissing(srvDomId, CLS_APP_ID, SRV_CLS_ID, objId, objDesc, attrs);
    else
      ObjDb.modifyObj(new Obj(srvDomId, CLS_APP_ID, SRV_CLS_ID, objId, objDesc, attrs));
    linkSrvToSrvs(baseId, srvDomId, objId, objDesc);
    ClsMap.invalidateAll();
  }

  // ---------------------------------------------------------------------------
  // Private helpers
  // ---------------------------------------------------------------------------

  private static void linkSrvToSrvs(DomId  baseId,
                                     DomId  srvDomId,
                                     String objName,
                                     String objDesc) throws DomatarException
  {
    DomId srvsId = srvsIdFor(baseId);

    if (ObjDb.getObj(srvsId) != null
        && LnkDb.getLnk(srvsId, srvDomId, CLS_APP_ID, SRV_CLS_ID) == null)
    {
      LnkDb.addLnk(new Lnk(srvsId, srvDomId,
                            CLS_APP_ID, SRV_CLS_ID,
                            objName, objDesc,
                            CLS_APP_ID, SRV_CLS_ID,
                            objName, 0));
    }
  }

  private static DomId srvsIdFor(DomId ref) throws DomatarException
  {
    return new DomId(ref.hstId, ref.appId, ref.actId, SRVS_OBJ_ID);
  }
}
