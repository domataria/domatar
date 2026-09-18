/*
 * Copyright (c) 2024 Domatar
 */

package com.canton.install;

import com.canton.ledger.IouTemplates;
import com.canton.ledger.TemplateDesc;
import com.canton.ledger.TemplateDesc.ChoiceDesc;
import com.canton.ledger.TemplateDesc.FieldDesc;
import com.domatar.db.HstDb;
import com.domatar.db.LnkDb;
import com.domatar.db.ObjDb;
import com.domatar.install.AppInstall;
import com.domatar.install.CatalogInstall;
import com.domatar.install.ClsInstall;
import com.domatar.install.SrvInstall;
import com.domatar.util.DomId;
import com.domatar.util.DomatarException;
import com.domatar.util.DomatarMsgClient;
import com.domatar.util.Json;
import com.domatar.util.JsonArrayList;
import com.domatar.util.JsonHashMap;
import com.domatar.util.JsonList;
import com.domatar.util.Lnk;
import com.domatar.util.ObjAttrs;

/**
 * Per-provider catalog row and per-user host / Navigator skeleton.
 */
public class CantonInstall implements AppInstall
{
  @Override
  public void installProvider(final String prvId, final String domain)
      throws DomatarException
  {
    CatalogInstall.registerInCatalog(prvId, "canton");
  }

  @Override
  public void installUser(final String actId,
                          final String usrId,
                          final String usrName,
                          final String prvId,
                          final String domain,
                          final DomatarMsgClient msgClient) throws DomatarException
  {
    final String cantonHstId = DomId.subHstId("canton", actId);
    final String navHstId = DomId.subHstId("navigator", actId, prvId);

    final DomId rootId = new DomId(navHstId, "navigator", actId, "root");
    final DomId appCantonId = new DomId(cantonHstId, "canton", actId, "app-canton");
    final DomId partyId = new DomId(cantonHstId, "canton", actId, "party");
    final DomId activeId = new DomId(cantonHstId, "canton", actId, "active");

    if (HstDb.getHst(cantonHstId) == null)
      HstDb.addHst(cantonHstId, domain, prvId);

    ObjDb.addObjIfMissing(appCantonId, "canton", "app",
        "Canton", "Handles on Canton contracts");
    ObjDb.reclassObj(appCantonId, "canton", "app");

    final ObjAttrs partyAttrs = new ObjAttrs();
    partyAttrs.addAttr("PartyId", "");
    ObjDb.addObjIfMissing(partyId, "canton", "party",
        "Party", "Your Canton Party", partyAttrs);

    ObjDb.addObjIfMissing(activeId, "canton", "contracts",
        "Active", "Contracts you can see");

    addLnkIfMissing(rootId, appCantonId,
        "canton", "app",
        "Canton", "Handles on Canton contracts",
        "navigator", "app", null, 12);

    addLnkIfMissing(appCantonId, partyId,
        "canton", "party",
        "Party", "Your Canton Party",
        "navigator", "container", null, 1);

    addLnkIfMissing(appCantonId, activeId,
        "canton", "contracts",
        "Active", "Contracts you can see",
        "navigator", "container", null, 2);

    ClsInstall.ensureClssContainer(appCantonId,
        "Class descriptors for Canton", "canton", 9);
    SrvInstall.ensureSrvsContainer(appCantonId,
        "Service descriptors for Canton", "canton", 10);

    upsertPair(appCantonId, "app", "Canton app entry point",
        appSrvJson(),
        clsJson("app", "canton.app",
            msgPolicy("canton.app", "BindParty", "Write", "Owner"),
            msgPolicy("canton.app", "GetParty", "Read", "Owner"),
            msgPolicy("canton.app", "ListContracts", "Read", "Owner"),
            msgPolicy("canton.app", "Sync", "Write", "Owner"),
            msgPolicy("canton.app", "CreateIou", "Write", "Owner"),
            msgPolicy("canton.app", "GetIou", "Read", "Owner"),
            msgPolicy("canton.app", "Transfer", "Destructive", "Controller"),
            msgPolicy("canton.app", "Settle", "Destructive", "Controller")));

    upsertPair(appCantonId, "party", "Bound Canton Party",
        partySrvJson(),
        clsJson("party", "canton.party",
            msgPolicy("canton.party", "GetParty", "Read", "Owner"),
            msgPolicy("canton.party", "BindParty", "Write", "Owner")));

    upsertPair(appCantonId, "contracts", "Active contract set",
        contractsSrvJson(),
        clsJson("contracts", "canton.contracts",
            msgPolicy("canton.contracts", "Sync", "Write", "Owner"),
            msgPolicy("canton.contracts", "Create", "Write", "Owner"),
            msgPolicy("canton.contracts", "GetContracts", "Read", "Owner")));

    upsertPair(appCantonId, IouTemplates.CLS_ID, "Iou contract handle",
        iouSrvJson(),
        clsJson(IouTemplates.CLS_ID, "canton.iou",
            msgPolicy("canton.iou", "GetIou", "Read", "Owner"),
            msgPolicy("canton.iou", "Transfer", "Destructive", "Controller"),
            msgPolicy("canton.iou", "Settle", "Destructive", "Controller"),
            msgPolicy("canton.iou", "Archive", "Destructive", "Controller")));

    upsertPair(appCantonId, "contract", "Generic contract handle",
        contractSrvJson(),
        clsJson("contract", "canton.contract",
            msgPolicy("canton.contract", "GetContract", "Read", "Owner"),
            msgPolicy("canton.contract", "Exercise", "Write", "Owner")));
  }

  private static void upsertPair(final DomId baseId, final String clsId,
                                 final String desc, final String srvJson,
                                 final String clsJson) throws DomatarException
  {
    SrvInstall.upsertSrvObj(baseId, "canton", clsId, desc, srvJson);
    ClsInstall.upsertClsImplementing(baseId, "canton", clsId, desc, clsJson);
  }

  private static String appSrvJson() throws DomatarException
  {
    final JsonHashMap root = srvRoot("app");
    final JsonList msgs = new JsonArrayList();

    msgs.add(msg("BindParty", "Write", parms(parm("PartyId", "String"))));
    msgs.add(msg("GetParty", "Read", null));
    msgs.add(msg("ListContracts", "Read", null));
    msgs.add(msg("Sync", "Write", null));
    msgs.add(msg("CreateIou", "Write",
        parms(parm("Issuer", "String"), parm("Owner", "String"),
            parm("Amount", "String"), parm("Currency", "String"))));
    msgs.add(msg("GetIou", "Read", parms(parm("ContractId", "String"))));
    msgs.add(msg("Transfer", "Destructive",
        parms(parm("ContractId", "String"), parm("NewOwner", "String"))));
    msgs.add(msg("Settle", "Destructive", parms(parm("ContractId", "String"))));
    root.put("Msgs", msgs);
    return Json.toJson(root);
  }

  private static String partySrvJson() throws DomatarException
  {
    final JsonHashMap root = srvRoot("party");
    final JsonList attrs = new JsonArrayList();
    final JsonList msgs = new JsonArrayList();

    attrs.add(attr("PartyId", "String"));
    msgs.add(msg("GetParty", "Read", null));
    msgs.add(msg("BindParty", "Write", parms(parm("PartyId", "String"))));
    root.put("Attrs", attrs);
    root.put("Msgs", msgs);
    return Json.toJson(root);
  }

  private static String contractsSrvJson() throws DomatarException
  {
    final JsonHashMap root = srvRoot("contracts");
    final JsonList msgs = new JsonArrayList();

    msgs.add(msg("Sync", "Write", null));
    msgs.add(msg("Create", "Write",
        parms(parm("TemplateId", "String"), parm("Issuer", "String"),
            parm("Owner", "String"), parm("Amount", "String"),
            parm("Currency", "String"))));
    msgs.add(msg("GetContracts", "Read", null));
    root.put("Msgs", msgs);
    return Json.toJson(root);
  }

  private static String iouSrvJson() throws DomatarException
  {
    final TemplateDesc t = IouTemplates.iou();
    final JsonHashMap root = srvRoot(IouTemplates.CLS_ID);
    final JsonList attrs = new JsonArrayList();
    final JsonList msgs = new JsonArrayList();

    attrs.add(attr("ContractId", "String"));
    attrs.add(attr("TemplateId", "String"));
    attrs.add(attr("PackageId?", "String"));
    for (final FieldDesc f : t.payloadFields)
      attrs.add(attr(f.name, clsType(f.type)));
    attrs.add(attrList("Signatories", "String"));
    attrs.add(attrList("Observers", "String"));
    msgs.add(msg("GetIou", "Read", null));
    for (final ChoiceDesc c : t.choices)
    {
      final JsonList parms = new JsonArrayList();

      for (final FieldDesc p : c.argumentFields)
        parms.add(parm(p.name, clsType(p.type)));
      msgs.add(msg(c.name, c.consuming ? "Destructive" : "Write",
          parms.isEmpty() ? null : parms));
    }
    root.put("Attrs", attrs);
    root.put("Msgs", msgs);
    return Json.toJson(root);
  }

  private static String contractSrvJson() throws DomatarException
  {
    final JsonHashMap root = srvRoot("contract");
    final JsonList attrs = new JsonArrayList();
    final JsonList msgs = new JsonArrayList();

    attrs.add(attr("ContractId", "String"));
    attrs.add(attr("TemplateId", "String"));
    attrs.add(attrList("Signatories", "String"));
    attrs.add(attrList("Observers", "String"));
    attrs.add(attr("Payload", "String"));
    msgs.add(msg("GetContract", "Read", null));
    msgs.add(msg("Exercise", "Write",
        parms(parm("Choice", "String"), parm("Argument", "String"))));
    root.put("Attrs", attrs);
    root.put("Msgs", msgs);
    return Json.toJson(root);
  }

  private static JsonHashMap srvRoot(final String srvId)
  {
    final JsonHashMap root = new JsonHashMap();

    root.put("SrvAppId", "canton");
    root.put("SrvId", srvId);
    return root;
  }

  private static String clsJson(final String clsId, final String implementsSrv,
                                final JsonHashMap... policies)
      throws DomatarException
  {
    final JsonHashMap root = new JsonHashMap();
    final JsonList impl = new JsonArrayList();
    final JsonList policy = new JsonArrayList();

    impl.add(implementsSrv);
    root.put("ClsAppId", "canton");
    root.put("ClsId", clsId);
    root.put("Implements", impl);
    for (final JsonHashMap p : policies)
      policy.add(p);
    root.put("MsgPolicy", policy);
    return Json.toJson(root);
  }

  private static JsonHashMap msgPolicy(final String srv, final String name,
                                       final String sideEffect, final String auth)
  {
    final JsonHashMap p = new JsonHashMap();

    p.put("Srv", srv);
    p.put("Name", name);
    p.put("SideEffect", sideEffect);
    p.put("Auth", auth);
    return p;
  }

  private static JsonHashMap attr(final String name, final String type)
  {
    final JsonHashMap a = new JsonHashMap();

    a.put("Name", name);
    a.put("Type", type);
    return a;
  }

  private static JsonHashMap attrList(final String name, final String elem)
  {
    final JsonHashMap a = new JsonHashMap();
    final JsonList t = new JsonArrayList();

    t.add(elem);
    a.put("Name", name);
    a.put("Type", t);
    return a;
  }

  private static JsonHashMap msg(final String name, final String sideEffect,
                                 final JsonList parms)
  {
    final JsonHashMap m = new JsonHashMap();

    m.put("Name", name);
    m.put("SideEffect", sideEffect);
    if (parms != null)
      m.put("Parms", parms);
    return m;
  }

  private static JsonList parms(final JsonHashMap... items)
  {
    final JsonList list = new JsonArrayList();

    for (final JsonHashMap item : items)
      list.add(item);
    return list;
  }

  private static JsonHashMap parm(final String name, final String type)
  {
    final JsonHashMap p = new JsonHashMap();

    p.put("Name", name);
    p.put("Type", type);
    return p;
  }

  private static String clsType(final String damlType)
  {
    return "String";
  }

  static void addLnkIfMissing(final DomId domId,
                              final DomId lnkDomId,
                              final String lnkClsAppId,
                              final String lnkClsId,
                              final String lnkObjName,
                              final String lnkObjDesc,
                              final String tagAppId,
                              final String tag,
                              final String val,
                              final long seqNum) throws DomatarException
  {
    if (LnkDb.getLnk(domId, lnkDomId, tagAppId, tag) == null)
      LnkDb.addLnk(new Lnk(domId, lnkDomId,
                            lnkClsAppId, lnkClsId,
                            lnkObjName, lnkObjDesc,
                            tagAppId, tag,
                            val, seqNum));
  }
}
