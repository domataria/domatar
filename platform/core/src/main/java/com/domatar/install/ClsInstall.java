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
 * Shared helpers for creating class-descriptor objects (Spec-Class.txt).
 *
 * <h3>Invariant: classes are always local</h3>
 * A class descriptor is always owned by — and installed by — the same
 * application whose {@code ClsAppId} it carries.  No install routine should
 * ever create a descriptor whose {@code ClsAppId} differs from its own
 * {@code appId}.  To consume an interface defined by another application,
 * implement its <em>service</em> via {@link #upsertClsImplementing} and
 * reference that service's {@code SrvAppId.SrvId} in the {@code Implements}
 * list (Spec-Service.txt PART 7).
 *
 * <h3>Typical call sequence (service-aware install routine)</h3>
 * <pre>
 *   // 1. Ensure the Services container exists.
 *   SrvInstall.ensureSrvsContainer(appXxxId, "Services", myAppId, seqNum);
 *
 *   // 2. Register the service interface (interface only, no Auth/SideEffect).
 *   SrvInstall.addSrvObj(baseId, "myapp", "myservice", "description", srvJson);
 *
 *   // 3. Register the slim class descriptor (Implements + MsgPolicy only).
 *   ClsInstall.upsertClsImplementing(baseId, "myapp", "myclass", "description", clsJson);
 * </pre>
 *
 * All calls are idempotent.
 */
public class ClsInstall
{
  private static final String CLS_APP_ID  = "domatar";
  private static final String CLS_CLS_ID  = "cls";
  private static final String CLSS_CLS_ID = "clss";
  private static final String CLSS_OBJ_ID = "clss";
  static final String CLSS_OBJ_NAME = "Classes";

  // ---------------------------------------------------------------------------
  // Classes container
  // ---------------------------------------------------------------------------

  /**
   * Creates the "clss" (Classes) container object on the same sub-host as
   * appObjId and adds the link  appObjId → clss  so it appears as a child
   * in the Navigator tree.
   *
   * @param appObjId  The app-xxx Navigator node for this app (supplies the
   *                  hstId, appId, actId that locate the sub-host).
   * @param appDesc   Short description stored in the clss object's ObjDesc.
   * @param tagAppId  The app that owns this relationship (pass your appId).
   *                  Used as TagAppId on the link; Tag is always "clss".
   * @param seqNum    SeqNum for the app-xxx → clss link (put it after
   *                  any existing data-container children).
   */
  public static void ensureClssContainer(DomId  appObjId,
                                          String appDesc,
                                          String tagAppId,
                                          long   seqNum) throws DomatarException
  {
    DomId clssId = clssIdFor(appObjId);

    ObjDb.addObjIfMissing(clssId, CLS_APP_ID, CLSS_CLS_ID, CLSS_OBJ_NAME, appDesc);

    if (LnkDb.getLnk(appObjId, clssId, tagAppId, "clss") == null)
      LnkDb.addLnk(new Lnk(appObjId, clssId,
                            CLS_APP_ID, CLSS_CLS_ID,
                            CLSS_OBJ_NAME, appDesc,
                            tagAppId, "clss",
                            null, seqNum));
  }

  // ---------------------------------------------------------------------------
  // Classes container (object only — no app link)
  // ---------------------------------------------------------------------------

  /**
   * Ensures the "clss" container object exists on the sub-host identified by
   * baseId, WITHOUT creating the app-xxx → clss Navigator link.
   *
   * Call this at the start of any install routine that uses
   * {@link #upsertClsImplementing} on a sub-host that already has its
   * app-xxx → clss link wired elsewhere (e.g. by a provider-level install).
   *
   * @param baseId   Any DomId whose hstId/appId/actId identify the sub-host.
   * @param appDesc  Short description stored in the clss ObjDesc field.
   */
  public static void ensureClssObj(DomId baseId, String appDesc) throws DomatarException
  {
    ObjDb.addObjIfMissing(clssIdFor(baseId), CLS_APP_ID, CLSS_CLS_ID, CLSS_OBJ_NAME, appDesc);
  }

  // ---------------------------------------------------------------------------
  // Class descriptor objects
  // ---------------------------------------------------------------------------

  /**
   * Creates a class-descriptor object for the class (clsAppId, clsId) on the
   * sub-host identified by baseId.  baseId supplies the hstId, appId, and
   * actId; the objId is derived as clsId + "Cls".
   *
   * If a "clss" container already exists on the same sub-host this method also
   * adds the link  clss → class-descriptor.  Because this method is
   * idempotent (addObjIfMissing) it does NOT update an existing descriptor.
   * Prefer {@link #upsertClsImplementing} for new service-based descriptors,
   * which always refreshes the descriptor and invalidates the ClsMap.
   *
   * @param baseId    Any DomId whose hstId/appId/actId identify the sub-host.
   * @param clsAppId  The ClsAppId of the class being described (must equal the
   *                  owning app's appId — see class invariant above).
   * @param clsId     The ClsId of the class being described.
   * @param objDesc   Short human-readable description (≤ 100 chars).
   * @param attrsJson The slim class-definition JSON (Spec-Class.txt PART 3):
   *                  Implements, Conventions?, MsgPolicy?, AttrStorage?.
   *                  No inline Attrs or Msgs (those live in the service).
   */
  public static void addClsObj(DomId   baseId,
                                String  clsAppId,
                                String  clsId,
                                String  objDesc,
                                String  attrsJson) throws DomatarException
  {
    // ObjId = "<clsId>Cls"  — the Cls suffix distinguishes class descriptors
    //         from same-named data objects (e.g. "followsCls" vs "follows").
    //         No app-prefix needed: ObjId is scoped within the app sub-host.
    String   objId    = clsId + "Cls";
    String   objName  = clsId + "Cls";
    DomId    clsDomId = new DomId(baseId.hstId, baseId.appId, baseId.actId, objId);
    ObjAttrs attrs    = new ObjAttrs(attrsJson);

    ObjDb.addObjIfMissing(clsDomId, CLS_APP_ID, CLS_CLS_ID, objName, objDesc, attrs);
    linkClsToClss(baseId, clsDomId, objName, objDesc, objId);
  }

  /**
   * Like {@link #addClsObj} but refreshes the descriptor attrs when the
   * object already exists (e.g. after adding agent-friendly Msg fields).
   */
  public static void upsertClsObj(DomId   baseId,
                                   String  clsAppId,
                                   String  clsId,
                                   String  objDesc,
                                   String  attrsJson) throws DomatarException
  {
    String   objId    = clsId + "Cls";
    String   objName  = clsId + "Cls";
    DomId    clsDomId = new DomId(baseId.hstId, baseId.appId, baseId.actId, objId);
    ObjAttrs attrs    = new ObjAttrs(attrsJson);

    Obj existing = ObjDb.getObj(clsDomId);
    if (existing == null)
      ObjDb.addObjIfMissing(clsDomId, CLS_APP_ID, CLS_CLS_ID, objName, objDesc, attrs);
    else
      ObjDb.modifyObj(new Obj(clsDomId, CLS_APP_ID, CLS_CLS_ID, objName, objDesc, attrs));

    linkClsToClss(baseId, clsDomId, objName, objDesc, objId);
    ClsMap.invalidate(clsAppId, clsId);
  }

  /**
   * Create or refresh a member-less class descriptor that implements one or more
   * services (Spec-Service.txt PART 7). attrsJson is the slim class document:
   * Implements, Conventions?, MsgPolicy?, AttrStorage?. No inline Attrs/Msgs.
   *
   * Identical to upsertClsObj but semantically marks the caller's intent and
   * guarantees ClsMap is invalidated even if upsertClsObj's internal invalidation
   * is refactored in the future.
   */
  public static void upsertClsImplementing(DomId   baseId,
                                            String  clsAppId,
                                            String  clsId,
                                            String  objDesc,
                                            String  attrsJson) throws DomatarException
  {
    upsertClsObj(baseId, clsAppId, clsId, objDesc, attrsJson);
    ClsMap.invalidate(clsAppId, clsId);
  }

  private static void linkClsToClss(DomId  baseId,
                                     DomId  clsDomId,
                                     String objName,
                                     String objDesc,
                                     String objId) throws DomatarException
  {
    DomId clssId = clssIdFor(baseId);

    if (ObjDb.getObj(clssId) != null
        && LnkDb.getLnk(clssId, clsDomId, CLS_APP_ID, CLS_CLS_ID) == null)
    {
      LnkDb.addLnk(new Lnk(clssId, clsDomId,
                            CLS_APP_ID, CLS_CLS_ID,
                            objName, objDesc,
                            CLS_APP_ID, CLS_CLS_ID,
                            objId, 0));
    }
  }

  // ---------------------------------------------------------------------------

  private static DomId clssIdFor(DomId ref) throws DomatarException
  {
    return new DomId(ref.hstId, ref.appId, ref.actId, CLSS_OBJ_ID);
  }
}
