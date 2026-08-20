/*
 * Copyright (c) 2024 Domatar
 */

package com.domatar.install;

import java.util.List;
import java.util.logging.Logger;

import com.domatar.core.ClsMap;
import com.domatar.db.LnkDb;
import com.domatar.db.ObjDb;
import com.domatar.util.Json;
import com.domatar.util.JsonArrayList;
import com.domatar.util.JsonHashMap;
import com.domatar.util.JsonList;
import com.domatar.util.JsonMap;
import com.domatar.util.Lnk;
import com.domatar.util.Obj;
import com.domatar.util.ObjAttrs;
import com.domatar.util.DomId;
import com.domatar.util.DomatarException;

/**
 * Splits legacy inline class descriptors into service descriptors + slim class
 * descriptors (Spec-Service.txt / Update-to-Services.txt T1.8).
 *
 * Run once per provider via:  GET /Setup?migrate=services
 *
 * The migration is idempotent: descriptors that already have an "Implements"
 * field (i.e. are already split) are skipped silently. A second run is a no-op.
 *
 * Two descriptor formats are handled:
 *   Structured — "Attrs" and "Msgs" are JSON arrays. The service document gets
 *                the interface arrays; SideEffect/Auth entries are removed from
 *                Msgs and moved to the class's MsgPolicy list.
 *   Plain-text — "Msgs" is a plain string (signature notation) and "SideEffect"
 *                is a companion string. The service document gets the Msgs/Attrs
 *                strings as-is; the class document keeps SideEffect/Auth as
 *                top-level strings, which ClsResolver copies through to the
 *                resolved document (simpler path, Spec T1.8).
 */
public class ServiceMigration
{
  private static final Logger LOG = Logger.getLogger(ServiceMigration.class.getName());

  /**
   * Migrate every legacy inline class descriptor on this provider's database.
   * Returns a one-line summary suitable for printing to an HTTP response.
   */
  public static String migrateAll() throws DomatarException
  {
    List<Obj> allCls = ObjDb.listClsDescriptors();

    int migrated = 0;
    int skipped  = 0;
    int errors   = 0;

    for (Obj clsRow : allCls)
    {
      try
      {
        if (migrateOne(clsRow))
          migrated++;
        else
          skipped++;
      }
      catch (Exception e)
      {
        errors++;
        LOG.warning("Migration failed for " + clsRow.domId + ": " + e.getMessage());
      }
    }

    return "Total: " + allCls.size()
        + "  Migrated: " + migrated
        + "  Skipped: "  + skipped
        + "  Errors: "   + errors;
  }

  // ---------------------------------------------------------------------------

  /**
   * Migrate one class descriptor row.
   * @return true if the row was converted, false if it was already migrated.
   */
  private static boolean migrateOne(Obj clsRow) throws DomatarException
  {
    JsonMap clsDoc;
    try
    {
      clsDoc = Json.parseMap(clsRow.attrs.toString());
    }
    catch (Exception e)
    {
      LOG.warning("Skipping " + clsRow.domId + ": cannot parse Attrs — " + e.getMessage());
      return false;
    }

    String srvAppId = clsDoc.getString("ClsAppId");
    String srvId    = clsDoc.getString("ClsId");

    if (srvAppId == null || srvAppId.isEmpty()
        || srvId  == null || srvId.isEmpty())
    {
      LOG.warning("Skipping " + clsRow.domId + ": missing ClsAppId/ClsId in Attrs");
      return false;
    }

    // Use the class object's own objId as a non-empty placeholder; addSrvObj
    // only uses hstId / appId / actId from baseId (objId is not forwarded).
    DomId baseId = new DomId(clsRow.domId.hstId, clsRow.domId.appId,
                              clsRow.domId.actId, clsRow.domId.objId);

    // ---- Build the SERVICE document (skip if already created by install code) ----
    String srvObjId = srvId + "Srv";
    DomId  srvDomId = new DomId(clsRow.domId.hstId, srvAppId,
                                 clsRow.domId.actId, srvObjId);

    // Always wire Navigator links regardless of whether the class was already
    // migrated — this path is idempotent and catches accounts where the class
    // was slimmed but the srvs container / Navigator links were never created.
    ensureNavigatorSrvs(baseId, clsRow.objDesc);

    // Already migrated (has an Implements list): Navigator links are now wired,
    // but we still ensure the service object is linked to the srvs container.
    if (clsDoc.get("Implements") instanceof JsonList)
    {
      linkSrvToSrvsIfMissing(baseId, srvDomId, srvObjId, clsRow.objDesc);
      return false;
    }

    if (ObjDb.getObj(srvDomId) == null)
    {
      String srvJson = buildServiceJson(srvAppId, srvId, clsDoc);
      SrvInstall.addSrvObj(baseId, srvAppId, srvId, clsRow.objDesc, srvJson);
    }
    else
    {
      // Service object already exists; ensure it is linked to the srvs container.
      linkSrvToSrvsIfMissing(baseId, srvDomId, srvObjId, clsRow.objDesc);
    }

    // ---- Build the SLIM CLASS document ----
    String slimJson = buildSlimClassJson(srvAppId, srvId, clsDoc);

    String objName = clsRow.objName;

    ObjDb.modifyObj(new Obj(clsRow.domId,
                            "domatar", "cls",
                            objName, clsRow.objDesc,
                            new ObjAttrs(slimJson)));

    ClsMap.invalidate(srvAppId, srvId);

    LOG.info("Migrated " + srvAppId + "." + srvId
             + " on " + clsRow.domId.hstId + " / " + clsRow.domId.actId);
    return true;
  }

  // ---------------------------------------------------------------------------
  // Service document builder
  // ---------------------------------------------------------------------------

  private static String buildServiceJson(String srvAppId,
                                          String srvId,
                                          JsonMap clsDoc) throws DomatarException
  {
    JsonHashMap srvDoc = new JsonHashMap();
    srvDoc.put("SrvAppId", srvAppId);
    srvDoc.put("SrvId",    srvId);

    Object desc = clsDoc.get("Description");
    if (desc != null)
      srvDoc.put("Description", desc);

    Object msgsRaw  = clsDoc.get("Msgs");
    Object attrsRaw = clsDoc.get("Attrs");

    if (msgsRaw instanceof JsonList)
    {
      // Structured format: strip SideEffect / Auth from each Msg entry.
      JsonList strippedMsgs = new JsonArrayList();
      for (Object m : (JsonList) msgsRaw)
      {
        if (!(m instanceof JsonMap))
        {
          strippedMsgs.add(m);
          continue;
        }
        JsonHashMap stripped = new JsonHashMap();
        stripped.putAll((JsonMap) m);
        stripped.remove("SideEffect");
        stripped.remove("Auth");
        strippedMsgs.add(stripped);
      }
      srvDoc.put("Msgs", strippedMsgs);

      if (attrsRaw instanceof JsonList)
        srvDoc.put("Attrs", attrsRaw);
      else
        srvDoc.put("Attrs", new JsonArrayList());
    }
    else if (msgsRaw instanceof String)
    {
      // Plain-text format: copy Msgs and Attrs strings as-is; SideEffect/Auth
      // are not in the Msgs string, they are in a separate top-level field.
      srvDoc.put("Msgs", msgsRaw);
      if (attrsRaw instanceof String)
        srvDoc.put("Attrs", attrsRaw);
    }

    return Json.toJson(srvDoc);
  }

  // ---------------------------------------------------------------------------
  // Slim class document builder
  // ---------------------------------------------------------------------------

  private static String buildSlimClassJson(String  srvAppId,
                                            String  srvId,
                                            JsonMap clsDoc) throws DomatarException
  {
    JsonHashMap slimDoc = new JsonHashMap();
    slimDoc.put("ClsAppId", srvAppId);
    slimDoc.put("ClsId",    srvId);

    Object desc = clsDoc.get("Description");
    if (desc != null)
      slimDoc.put("Description", desc);

    Object conv = clsDoc.get("Conventions");
    if (conv != null)
      slimDoc.put("Conventions", conv);

    JsonArrayList implements_ = new JsonArrayList();
    implements_.add(srvAppId + "." + srvId);
    slimDoc.put("Implements", implements_);

    Object msgsRaw = clsDoc.get("Msgs");

    if (msgsRaw instanceof JsonList)
    {
      // Structured format: build MsgPolicy from per-Msg SideEffect/Auth.
      JsonList msgPolicy = buildMsgPolicy(srvAppId + "." + srvId, (JsonList) msgsRaw);
      if (!msgPolicy.isEmpty())
        slimDoc.put("MsgPolicy", msgPolicy);
    }
    else if (msgsRaw instanceof String)
    {
      // Plain-text format: keep SideEffect/Auth as top-level strings on the class
      // (ClsResolver copies them through to the resolved document).
      copyStringIfPresent(clsDoc, slimDoc, "SideEffect");
      copyStringIfPresent(clsDoc, slimDoc, "Auth");
    }

    return Json.toJson(slimDoc);
  }

  private static JsonList buildMsgPolicy(String srvRef, JsonList msgs)
  {
    JsonList policy = new JsonArrayList();

    for (Object m : msgs)
    {
      if (!(m instanceof JsonMap))
        continue;
      JsonMap msg  = (JsonMap) m;
      String  name = msg.getString("Name");
      if (name == null || name.isEmpty())
        continue;

      String sideEffect = msg.getString("SideEffect");
      String auth       = msg.getString("Auth");

      if (sideEffect == null && auth == null)
        continue;

      JsonHashMap entry = new JsonHashMap();
      entry.put("Srv", srvRef);
      entry.put("Name", name);
      if (sideEffect != null)
        entry.put("SideEffect", sideEffect);
      if (auth != null)
        entry.put("Auth", auth);
      policy.add(entry);
    }

    return policy;
  }

  private static void copyStringIfPresent(JsonMap src, JsonHashMap dst, String key)
  {
    Object val = src.get(key);
    if (val instanceof String)
      dst.put(key, val);
  }

  // ---------------------------------------------------------------------------
  // Navigator wiring helpers
  // ---------------------------------------------------------------------------

  /**
   * Ensures the "srvs" (Services) container exists on baseId's sub-host, then
   * wires the app-node → srvs Navigator link so Services appears as a child in
   * the Navigator tree.
   *
   * The app node ObjId convention is "app-" + baseId.appId (e.g. "app-bookstore").
   * If no such app node exists on the sub-host (e.g. navigator or domatar
   * platform sub-hosts), only the container is created — the link is skipped.
   */
  private static void ensureNavigatorSrvs(DomId  baseId,
                                           String desc) throws DomatarException
  {
    SrvInstall.ensureSrvsObj(baseId, desc);

    DomId srvsId  = new DomId(baseId.hstId, baseId.appId, baseId.actId, "srvs");
    DomId appNode = new DomId(baseId.hstId, baseId.appId, baseId.actId,
                               "app-" + baseId.appId);

    if (ObjDb.getObj(appNode) == null)
      return;

    if (LnkDb.getLnk(appNode, srvsId, baseId.appId, "srvs") == null)
      LnkDb.addLnk(new Lnk(appNode, srvsId,
                            "domatar", "srvs",
                            SrvInstall.SRVS_OBJ_NAME, desc,
                            baseId.appId, "srvs",
                            null, 99));
  }

  /**
   * Ensures a srvs → service link exists when the service object was pre-created
   * by the install code and addSrvObj was therefore skipped.
   */
  private static void linkSrvToSrvsIfMissing(DomId  baseId,
                                              DomId  srvDomId,
                                              String srvObjId,
                                              String desc) throws DomatarException
  {
    DomId srvsId = new DomId(baseId.hstId, baseId.appId, baseId.actId, "srvs");

    if (ObjDb.getObj(srvsId) == null)
      return;

    if (LnkDb.getLnk(srvsId, srvDomId, "domatar", "srv") == null)
      LnkDb.addLnk(new Lnk(srvsId, srvDomId,
                            "domatar", "srv",
                            srvObjId, desc,
                            "domatar", "srv",
                            srvObjId, 0));
  }
}
