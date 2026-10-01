/*
 * Copyright (c) 2024 Domatar
 */

package com.hecto.install;

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
import com.domatar.util.Hst;
import com.domatar.util.Json;
import com.domatar.util.Obj;
import com.domatar.util.JsonArrayList;
import com.domatar.util.JsonHashMap;
import com.domatar.util.JsonList;
import com.domatar.util.Lnk;
import com.hecto.objimpl.HectoBookingImpl;
import com.hecto.objimpl.HectoIds;

/**
 * Catalog row, home-host desk, and per-user Hecto skeleton.
 * The facade lists a 25-credit Subscribe price and does not draw it.
 */
public class HectoInstall implements AppInstall
{
  @Override
  public void installProvider(final String prvId, final String domain)
      throws DomatarException
  {
    CatalogInstall.registerInCatalog(prvId, HectoIds.HOME);
    ensureDesk(prvId);
    refreshInstalledUsers();
  }

  @Override
  public void installUser(final String actId,
                          final String usrId,
                          final String usrName,
                          final String prvId,
                          final String domain,
                          final DomatarMsgClient msgClient) throws DomatarException
  {
    final String hectoHstId = DomId.subHstId(HectoIds.HOME, actId);
    final String navHstId = DomId.subHstId("navigator", actId, prvId);
    final DomId rootId = new DomId(navHstId, "navigator", actId, "root");
    final DomId appId = HectoIds.subHost(actId, "app-hecto");

    if (HstDb.getHst(hectoHstId) == null)
      HstDb.addHst(hectoHstId, domain, prvId);

    ObjDb.addObjIfMissing(appId, HectoIds.HOME, "app",
        "Hecto", "Subscriptions in Hecto funds");
    ObjDb.reclassObj(appId, HectoIds.HOME, "app");

    addLnkIfMissing(rootId, appId,
        HectoIds.HOME, "app",
        "Hecto", "Subscriptions in Hecto funds",
        "navigator", "app", null, 13);

    writeUserDescriptors(appId);
    HectoBookingImpl.ensure(actId);
  }

  private static void refreshInstalledUsers() throws DomatarException
  {
    for (final Obj cls : ObjDb.listClsDescriptors())
    {
      if (cls.domId == null || !HectoIds.HOME.equals(cls.domId.appId)
          || !"appCls".equals(cls.domId.objId))
        continue;

      writeUserDescriptors(HectoIds.subHost(cls.domId.actId, "app-hecto"));
      HectoBookingImpl.ensure(cls.domId.actId);
    }
  }

  private static void writeUserDescriptors(final DomId appId) throws DomatarException
  {
    ClsInstall.ensureClssContainer(appId,
        "Class descriptors for Hecto", HectoIds.HOME, 9);
    SrvInstall.ensureSrvsContainer(appId,
        "Service descriptors for Hecto", HectoIds.HOME, 10);
    SrvInstall.upsertSrvObj(appId, HectoIds.HOME, "app",
        "Hecto app entry point", appSrvJson());
    ClsInstall.upsertClsImplementing(appId, HectoIds.HOME, "app",
        "Hecto app entry point",
        clsJson("app", "hecto.app",
            msgPolicy("hecto.app", "Quote", "Read", "Owner"),
            msgPolicy("hecto.app", "Credits", "Read", "Owner"),
            msgPolicy("hecto.app", "GetBooking", "Read", "Owner"),
            msgPolicy("hecto.app", "Subscribe", "Write", "Owner",
                null, Long.valueOf(HectoIds.COST)),
            msgPolicy("hecto.app", "Undo", "Write", "Owner"),
            msgPolicy("hecto.app", "ApplyTwice", "Write", "Owner"),
            msgPolicy("hecto.app", "Echo", "Read", "Owner"),
            msgPolicy("hecto.app", "Refill", "Write", "Owner")));
    SrvInstall.upsertSrvObj(appId, HectoIds.HOME, HectoIds.BOOKING,
        "Record of Hecto subscriptions", bookingSrvJson());
    ClsInstall.upsertClsImplementing(appId, HectoIds.HOME, HectoIds.BOOKING,
        "Record of Hecto subscriptions",
        clsJson(HectoIds.BOOKING, "hecto.booking",
            msgPolicy("hecto.booking", "GetBooking", "Read", "Owner"),
            msgPolicy("hecto.booking", "Post", "Write", "Owner", "Unpost", null),
            msgPolicy("hecto.booking", "Unpost", "Write", "Owner")));
  }

  private static void ensureDesk(final String prvId) throws DomatarException
  {
    final Hst home = HstDb.getHst(HectoIds.HOME);

    if (home == null || prvId == null || !prvId.equals(home.prvId))
      return;

    final DomId desk = HectoIds.desk();

    if (desk == null)
    {
      System.out.println("WARN: HectoInstall desk skipped — "
          + "home user hecto@hecto not found");
      return;
    }

    ObjDb.addObjIfMissing(desk, HectoIds.HOME, HectoIds.DESK,
        "Desk", "Priced subscriptions of Hecto holdings");
    ClsInstall.ensureClssContainer(desk,
        "Class descriptors for the Hecto desk", HectoIds.HOME, 1);
    SrvInstall.ensureSrvsContainer(desk,
        "Service descriptors for the Hecto desk", HectoIds.HOME, 2);
    SrvInstall.upsertSrvObj(desk, HectoIds.HOME, HectoIds.DESK,
        "Priced subscriptions of Hecto holdings", deskSrvJson());
    ClsInstall.upsertClsImplementing(desk, HectoIds.HOME, HectoIds.DESK,
        "Priced subscriptions of Hecto holdings",
        clsJson(HectoIds.DESK, "hecto.desk",
            msgPolicy("hecto.desk", "Quote", "Read", "Verified"),
            msgPolicy("hecto.desk", "Credits", "Read", "Verified"),
            msgPolicy("hecto.desk", "Subscribe", "Write", "Verified",
                "ReverseSubscribe", Long.valueOf(HectoIds.COST)),
            msgPolicy("hecto.desk", "ReverseSubscribe", "Write", "Verified"),
            msgPolicy("hecto.desk", "ApplyTwice", "Write", "Verified"),
            msgPolicy("hecto.desk", "Echo", "Read", "Verified"),
            msgPolicy("hecto.desk", "Refill", "Write", "Verified")));
  }

  private static String deskSrvJson() throws DomatarException
  {
    final JsonHashMap root = new JsonHashMap();
    final JsonList msgs = new JsonArrayList();
    final JsonHashMap subscribe = msg("Subscribe", "Write",
        parms(parm("ContractId", "String"), parm("Delta", "String")));
    final JsonHashMap cost = new JsonHashMap();

    root.put("SrvAppId", HectoIds.HOME);
    root.put("SrvId", HectoIds.DESK);
    cost.put("Amount", Long.valueOf(HectoIds.COST));
    cost.put("Unit", "credit");
    subscribe.put("Cost", cost);
    subscribe.put("Compensates", "ReverseSubscribe");
    subscribe.put("Description",
        "Add Delta to a Hecto holding and post the caller's booking. Draws "
            + HectoIds.COST + " credits unless the desk owner calls it.");
    msgs.add(msg("Quote", "Read", parms(parm("ContractId", "String"))));
    msgs.add(msg("Credits", "Read", null));
    msgs.add(subscribe);
    msgs.add(msg("ReverseSubscribe", "Write", null));
    msgs.add(msg("ApplyTwice", "Write",
        parms(parm("ContractId", "String"), parm("Delta", "String"))));
    msgs.add(msg("Echo", "Read", null));
    msgs.add(msg("Refill", "Write", null));
    root.put("Msgs", msgs);
    return Json.toJson(root);
  }

  private static String appSrvJson() throws DomatarException
  {
    final JsonHashMap root = new JsonHashMap();
    final JsonList msgs = new JsonArrayList();
    final JsonHashMap subscribe = described("Subscribe", "Write",
        parms(parm("ContractId", "String"), parm("Delta", "String")),
        "Adds Delta to a Hecto holding. Draws " + HectoIds.COST + " credits.");
    final JsonHashMap cost = new JsonHashMap();

    root.put("SrvAppId", HectoIds.HOME);
    root.put("SrvId", "app");
    cost.put("Amount", Long.valueOf(HectoIds.COST));
    cost.put("Unit", "credit");
    subscribe.put("Cost", cost);
    msgs.add(msg("Quote", "Read", parms(parm("ContractId", "String"))));
    msgs.add(msg("Credits", "Read", null));
    msgs.add(msg("GetBooking", "Read", null));
    msgs.add(subscribe);
    msgs.add(msg("Undo", "Write", null));
    msgs.add(msg("ApplyTwice", "Write",
        parms(parm("ContractId", "String"), parm("Delta", "String"))));
    msgs.add(msg("Echo", "Read", null));
    msgs.add(msg("Refill", "Write", null));
    root.put("Msgs", msgs);
    return Json.toJson(root);
  }

  private static String bookingSrvJson() throws DomatarException
  {
    final JsonHashMap root = new JsonHashMap();
    final JsonList attrs = new JsonArrayList();
    final JsonList msgs = new JsonArrayList();

    root.put("SrvAppId", HectoIds.HOME);
    root.put("SrvId", HectoIds.BOOKING);
    attrs.add(attr("NetDelta", "String"));
    attrs.add(attr("LastDelta", "String"));
    msgs.add(msg("GetBooking", "Read", null));
    msgs.add(msg("Post", "Write", parms(parm("Delta", "String"))));
    msgs.add(msg("Unpost", "Write",
        parms(parm("Delta", "String"), parm("OrigContextId", "String"))));
    root.put("Attrs", attrs);
    root.put("Msgs", msgs);
    return Json.toJson(root);
  }

  private static String clsJson(final String clsId, final String implementsSrv,
                                final JsonHashMap... policies)
      throws DomatarException
  {
    final JsonHashMap root = new JsonHashMap();
    final JsonList impl = new JsonArrayList();
    final JsonList policy = new JsonArrayList();

    impl.add(implementsSrv);
    root.put("ClsAppId", HectoIds.HOME);
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
    return msgPolicy(srv, name, sideEffect, auth, null, null);
  }

  private static JsonHashMap msgPolicy(final String srv, final String name,
                                       final String sideEffect, final String auth,
                                       final String compensates, final Long cost)
  {
    final JsonHashMap p = new JsonHashMap();

    p.put("Srv", srv);
    p.put("Name", name);
    p.put("SideEffect", sideEffect);
    p.put("Auth", auth);
    if (compensates != null && !compensates.isEmpty())
      p.put("Compensates", compensates);
    if (cost != null && cost.longValue() > 0L)
    {
      final JsonHashMap priced = new JsonHashMap();

      priced.put("Amount", cost);
      priced.put("Unit", "credit");
      p.put("Cost", priced);
    }
    return p;
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

  private static JsonHashMap described(final String name, final String sideEffect,
                                       final JsonList parms, final String description)
  {
    final JsonHashMap m = msg(name, sideEffect, parms);

    if (description != null)
      m.put("Description", description);
    return m;
  }

  private static JsonHashMap attr(final String name, final String type)
  {
    final JsonHashMap a = new JsonHashMap();

    a.put("Name", name);
    a.put("Type", type);
    return a;
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

  private static void addLnkIfMissing(final DomId domId,
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
