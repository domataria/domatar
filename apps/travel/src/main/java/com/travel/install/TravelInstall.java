/*
 * Copyright (c) 2024 Domatar
 */

package com.travel.install;

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
import com.domatar.util.JsonArrayList;
import com.domatar.util.JsonHashMap;
import com.domatar.util.JsonList;
import com.domatar.util.Lnk;
import com.domatar.util.Obj;
import com.travel.objimpl.TravelCache;
import com.travel.objimpl.TravelIds;

/**
 * Catalog row on every provider. The home host is created by
 * OfferedHostsInstall on the provider that offers travel. User
 * objects are created by installUser on that same provider.
 */
public class TravelInstall implements AppInstall
{
  @Override
  public void installProvider(final String prvId, final String domain)
      throws DomatarException
  {
    CatalogInstall.registerInCatalog(prvId, TravelIds.HOME);

    final Hst home = HstDb.getHst(TravelIds.HOME);

    if (home == null || prvId == null || !prvId.equals(home.prvId))
      return;

    refreshUserDescriptors();
  }

  /**
   * Setup calls installProvider and does not call installUser again.
   * Refresh each installed (travel, app) so BookTrip's Compensates
   * and CancelTrip are on the descriptor useApp reads.
   */
  private static void refreshUserDescriptors() throws DomatarException
  {
    for (final Obj cls : ObjDb.listClsDescriptors())
    {
      if (cls.domId == null
          || !TravelIds.HOME.equals(cls.domId.appId)
          || !"appCls".equals(cls.domId.objId))
        continue;

      final DomId appId = new DomId(cls.domId.hstId, cls.domId.appId,
          cls.domId.actId, TravelIds.APP);

      if (ObjDb.getObj(appId) == null)
        continue;
      writeUserDescriptors(appId);
    }
  }

  @Override
  public void installUser(final String actId,
                          final String usrId,
                          final String usrName,
                          final String prvId,
                          final String domain,
                          final DomatarMsgClient msgClient) throws DomatarException
  {
    final String travelHstId = DomId.subHstId(TravelIds.HOME, actId);
    final String navHstId = DomId.subHstId("navigator", actId, prvId);
    final DomId rootId = new DomId(navHstId, "navigator", actId, "root");
    final DomId appId = TravelIds.subHost(actId, TravelIds.APP);
    final DomId tripsId = TravelIds.subHost(actId, TravelIds.TRIPS);

    if (HstDb.getHst(travelHstId) == null)
      HstDb.addHst(travelHstId, domain, prvId);

    ObjDb.addObjIfMissing(appId, TravelIds.HOME, "app",
        "Travel", "Flights, hotels, and a trip itinerary");
    ObjDb.reclassObj(appId, TravelIds.HOME, "app");

    addLnkIfMissing(rootId, appId,
        TravelIds.HOME, "app",
        "Travel", "Flights, hotels, and a trip itinerary",
        "navigator", "app", null, 16);

    ObjDb.addObjIfMissing(tripsId, TravelIds.HOME, "trips",
        "Trips", "Booked trips");
    addLnkIfMissing(appId, tripsId,
        TravelIds.HOME, "trips",
        "Trips", "Booked trips",
        "navigator", "container", null, 1);

    TravelCache.ensure(actId);
    writeUserDescriptors(appId);
  }

  private static void writeUserDescriptors(final DomId appId) throws DomatarException
  {
    ClsInstall.ensureClssContainer(appId,
        "Class descriptors for Travel", TravelIds.HOME, 9);
    SrvInstall.ensureSrvsContainer(appId,
        "Service descriptors for Travel", TravelIds.HOME, 10);
    SrvInstall.upsertSrvObj(appId, TravelIds.HOME, "app",
        "Travel app entry point", TravelFacade.serviceJson());
    ClsInstall.upsertClsImplementing(appId, TravelIds.HOME, "app",
        "Travel app entry point", TravelFacade.classJson());
    writeBare(appId, "trips", "This user's trips");
    writeBare(appId, "trip", "A booked trip");
    writeBare(appId, "cache", "Latest search results for this user");
  }

  private static void writeBare(final DomId appId, final String clsId,
                                final String description) throws DomatarException
  {
    SrvInstall.upsertSrvObj(appId, TravelIds.HOME, clsId, description,
        bareSrv(clsId, description));
    ClsInstall.upsertClsImplementing(appId, TravelIds.HOME, clsId, description,
        bareClass(clsId, TravelIds.HOME + "." + clsId));
  }

  private static String bareSrv(final String srvId, final String description)
      throws DomatarException
  {
    final JsonHashMap root = new JsonHashMap();

    root.put("SrvAppId", TravelIds.HOME);
    root.put("SrvId", srvId);
    root.put("Description", description);
    root.put("Msgs", new JsonArrayList());
    return Json.toJson(root);
  }

  private static String bareClass(final String clsId, final String implementsSrv)
      throws DomatarException
  {
    final JsonHashMap root = new JsonHashMap();
    final JsonList impl = new JsonArrayList();

    impl.add(implementsSrv);
    root.put("ClsAppId", TravelIds.HOME);
    root.put("ClsId", clsId);
    root.put("Implements", impl);
    root.put("MsgPolicy", new JsonArrayList());
    return Json.toJson(root);
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
