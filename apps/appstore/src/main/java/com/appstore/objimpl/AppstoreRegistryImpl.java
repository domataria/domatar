/*
 * Copyright (c) 2024 Domatar
 */

package com.appstore.objimpl;

import java.util.List;

import com.domatar.core.AppConfig;
import com.domatar.core.Auth;
import com.domatar.core.Context;
import com.domatar.core.DomatarConfig;
import com.domatar.db.HstDb;
import com.domatar.db.LnkDb;
import com.domatar.db.ObjDb;
import com.domatar.install.CatalogInstall;
import com.domatar.install.DirectoryRegister;
import com.domatar.install.UserInstallDispatch;
import com.domatar.util.Hst;
import com.domatar.util.IdGen;
import com.domatar.util.JsonArrayList;
import com.domatar.util.JsonHashMap;
import com.domatar.util.JsonList;
import com.domatar.util.JsonMap;
import com.domatar.util.JsonMsg;
import com.domatar.util.Lnk;
import com.domatar.util.Obj;
import com.domatar.util.ObjAttrs;
import com.domatar.util.ObjImpl;
import com.domatar.util.DomId;
import com.domatar.util.DomatarException;
import com.domatar.util.DomatarMsgClient;

/**
 * Handler for (appstore, registry) — global listings and offers on the
 * App Store central host (Spec-AppStore.txt PART 6 / 7).
 */
public class AppstoreRegistryImpl extends ObjImpl
{
  @Override
  public String handleMsg(final String msg,
                          final Obj obj,
                          final String contextPath,
                          final String contextRealPath,
                          final DomatarMsgClient msgClient) throws DomatarException
  {
    final JsonMsg inMsg  = new JsonMsg(msg);
    final String  opr    = inMsg.getOperation();

    if ("Compensate".equals(opr))
      return super.handleMsg(msg, obj, contextPath, contextRealPath, msgClient);

    final JsonMsg outMsg = new JsonMsg();

    if (!hasRights(inMsg, obj, msgClient))
      return notAuthorized(inMsg);

    if ("SearchApps".equals(opr))
      searchApps(opr, inMsg, outMsg, obj);
    else if ("GetListing".equals(opr))
      getListing(opr, inMsg, outMsg, obj, msgClient);
    else if ("RegisterOffer".equals(opr))
      registerOffer(opr, inMsg, outMsg, obj, msgClient);
    else if ("WithdrawOffer".equals(opr))
      withdrawOffer(opr, inMsg, outMsg, obj);
    else
      return super.handleMsg(msg, obj, contextPath, contextRealPath, msgClient);

    return outMsg.toString();
  }

  @Override
  public boolean hasRights(final JsonMsg inMsg, final Obj obj,
                           final DomatarMsgClient msgClient)
      throws DomatarException
  {
    final String op = inMsg.getOperation();

    if ("SearchApps".equals(op) || "GetListing".equals(op)
        || "RegisterOffer".equals(op) || "WithdrawOffer".equals(op))
      return Auth.isVerified(inMsg);

    return super.hasRights(inMsg, obj, msgClient);
  }

  // -------------------------------------------------------------------------

  private void searchApps(final String opr, final JsonMsg inMsg, final JsonMsg outMsg,
                          final Obj obj)
      throws DomatarException
  {
    final DomId  regId = registryId(inMsg, obj);
    final String query = inMsg.getAttr("Query");
    int          limit = 100;

    try
    {
      final String lim = inMsg.getAttr("Limit");

      if (lim != null && !lim.isEmpty())
        limit = Integer.parseInt(lim);
    }
    catch (final NumberFormatException ignored)
    {
      limit = 100;
    }

    if (limit < 1)
      limit = 1;
    if (limit > 500)
      limit = 500;

    final String q = (query != null) ? query.trim().toLowerCase() : "";

    final List<Lnk> listingLnks = LnkDb.getLnks(regId, "appstore", "listing",
                                                 null, null, 1000, false);
    final JsonList  listings    = new JsonArrayList();

    for (final Lnk lnk : listingLnks)
    {
      if (listings.size() >= limit)
        break;

      final Obj listing = ObjDb.getObj(lnk.lnkDomId);

      if (listing == null)
        continue;

      final ObjAttrs la      = listing.attrs != null ? listing.attrs : new ObjAttrs();
      final String   appId   = nz(la.getAttr("AppId"), listing.domId.objId);
      final String   appName = nz(la.getAttr("AppName"), appId);
      final String   appDesc = nz(la.getAttr("AppDesc"), "");
      final String   version = nz(la.getAttr("Version"), "");

      if (!q.isEmpty())
      {
        final String hay = (appId + " " + appName + " " + appDesc).toLowerCase();

        if (!hay.contains(q))
          continue;
      }

      final OfferScan scan = scanOffers(listing.domId);

      if (scan.activeCount == 0)
        continue;

      final JsonMap row = new JsonHashMap();

      row.put("AppId",      appId.startsWith("listing-") ? appId.substring(8) : appId);
      row.put("AppName",    appName);
      row.put("AppDesc",    appDesc);
      row.put("Version",    version);
      row.put("OfferCount", Integer.toString(scan.activeCount));
      row.put("OffersPreview", scan.preview);

      listings.add(row);
    }

    final ObjAttrs out = new ObjAttrs();

    out.addAttr("Listings", listings);
    outMsg.addResponseBody(opr, out);
  }

  private void getListing(final String opr, final JsonMsg inMsg, final JsonMsg outMsg,
                          final Obj obj, final DomatarMsgClient msgClient)
      throws DomatarException
  {
    final String appId = inMsg.getAttr("AppId");

    if (appId == null || appId.isEmpty())
    {
      outMsg.addError(opr, "Missing AppId");
      return;
    }

    final DomId regId     = registryId(inMsg, obj);
    final DomId listingId = listingId(regId, appId);
    final Obj   listing   = ObjDb.getObj(listingId);

    if (listing == null)
    {
      outMsg.addError(opr, "Listing not found: " + appId);
      return;
    }

    writeListingResponse(opr, outMsg, listing, inMsg);
  }

  private void registerOffer(final String opr, final JsonMsg inMsg, final JsonMsg outMsg,
                             final Obj obj, final DomatarMsgClient msgClient)
      throws DomatarException
  {
    final String appId         = inMsg.getAttr("AppId");
    final String prvId         = inMsg.getAttr("PrvId");
    final String domain        = inMsg.getAttr("Domain");
    final String publicDomain  = inMsg.getAttr("PublicDomain");
    final String browserOrigin = inMsg.getAttr("BrowserOrigin");
    final String prvActId      = inMsg.getAttr("PrvActId");

    if (appId == null || appId.isEmpty())
    {
      outMsg.addError(opr, "Missing AppId");
      return;
    }

    if (prvId == null || prvId.isEmpty() || domain == null || domain.isEmpty())
    {
      outMsg.addError(opr, "Missing PrvId or Domain");
      return;
    }

    if (!probeCatalog(prvId, prvActId, domain, appId, msgClient))
    {
      outMsg.addError(opr, "App \"" + appId + "\" is not in the provider catalog");
      return;
    }

    String appName = inMsg.getAttr("AppName");
    String appDesc = inMsg.getAttr("AppDesc");
    String version = inMsg.getAttr("Version");

    if (appName == null || appName.isEmpty() || appDesc == null || version == null)
    {
      final AppConfig cfg = AppConfig.load(appId,
          Thread.currentThread().getContextClassLoader());

      if (cfg != null)
      {
        if (appName == null || appName.isEmpty())
          appName = cfg.getAppName();
        if (appDesc == null)
          appDesc = cfg.getAppDesc();
        if (version == null || version.isEmpty())
          version = cfg.getVersion();
      }
    }

    if (appName == null || appName.isEmpty())
      appName = appId;
    if (appDesc == null)
      appDesc = "";
    if (version == null || version.isEmpty())
      version = "1.0";

    final DomId regId     = registryId(inMsg, obj);
    final DomId listingId = ensureListing(regId, appId, appName, appDesc, version, prvId);
    ensureOffer(listingId, prvId, domain, publicDomain, browserOrigin, version,
        prvActId, "Active");

    final Obj listing = ObjDb.getObj(listingId);

    writeListingResponse(opr, outMsg, listing, inMsg);
  }

  private void withdrawOffer(final String opr, final JsonMsg inMsg, final JsonMsg outMsg,
                             final Obj obj)
      throws DomatarException
  {
    final String appId = inMsg.getAttr("AppId");
    final String prvId = inMsg.getAttr("PrvId");

    if (appId == null || appId.isEmpty() || prvId == null || prvId.isEmpty())
    {
      outMsg.addError(opr, "Missing AppId or PrvId");
      return;
    }

    final DomId regId     = registryId(inMsg, obj);
    final DomId listingId = listingId(regId, appId);
    final Obj   listing   = ObjDb.getObj(listingId);

    if (listing == null)
    {
      outMsg.addError(opr, "No offer");
      return;
    }

    final DomId offerDomId = offerId(listingId, prvId);
    final Obj   offer      = ObjDb.getObj(offerDomId);

    if (offer == null)
    {
      outMsg.addError(opr, "No offer");
      return;
    }

    final ObjAttrs attrs = offer.attrs != null ? offer.attrs : new ObjAttrs();

    attrs.addAttr("Status",  "Withdrawn");
    attrs.addAttr("Version", IdGen.getCurTimeBase64());

    final Obj updated = new Obj(offerDomId, "appstore", "offer",
        offer.objName, offer.objDesc, attrs);

    ObjDb.modifyObj(updated);
    writeListingResponse(opr, outMsg, listing, inMsg);
  }

  // -------------------------------------------------------------------------

  private void writeListingResponse(final String opr, final JsonMsg outMsg,
                                    final Obj listing, final JsonMsg inMsg)
      throws DomatarException
  {
    final ObjAttrs la      = listing.attrs != null ? listing.attrs : new ObjAttrs();
    String         appId   = la.getAttr("AppId");

    if (appId == null || appId.isEmpty())
    {
      final String oid = listing.domId.objId;

      appId = oid.startsWith("listing-") ? oid.substring(8) : oid;
    }

    final OfferScan scan = scanOffers(listing.domId);
    final ObjAttrs  out  = new ObjAttrs();

    out.addAttr("AppId",        appId);
    out.addAttr("AppName",      nz(la.getAttr("AppName"), appId));
    out.addAttr("AppDesc",      nz(la.getAttr("AppDesc"), ""));
    out.addAttr("Version",      nz(la.getAttr("Version"), ""));
    out.addAttr("BuilderPrvId", nz(la.getAttr("BuilderPrvId"), ""));
    out.addAttr("Offers",       scan.allOffers);

    final JsonList already = new JsonArrayList();
    final Context  ctx     = inMsg.getContext();

    if (ctx != null && ctx.actId != null)
    {
      final String hstId = UserInstallDispatch.userSubHstId(
          appId, ctx.actId, ctx.usrId, DomatarConfig.getPrvId());
      final Hst    hst   = (hstId != null) ? HstDb.getHst(hstId) : null;

      if (hst != null)
      {
        final JsonMap row = new JsonHashMap();

        row.put("PrvId",  hst.prvId);
        row.put("Domain", hst.domain);
        row.put("HstId",  hst.hstId);
        already.add(row);
      }
    }

    out.addAttr("AlreadyInstalled", already);
    outMsg.addResponseBody(opr, out);
  }

  private OfferScan scanOffers(final DomId listingId) throws DomatarException
  {
    final List<Lnk> offerLnks = LnkDb.getLnks(listingId, "appstore", "offer",
                                               null, null, 200, false);
    final JsonList  all       = new JsonArrayList();
    final JsonList  active    = new JsonArrayList();
    final JsonList  withdrawn = new JsonArrayList();
    final JsonList  preview   = new JsonArrayList();
    int             activeN   = 0;

    for (final Lnk lnk : offerLnks)
    {
      final Obj offer = ObjDb.getObj(lnk.lnkDomId);

      if (offer == null)
        continue;

      final ObjAttrs oa     = offer.attrs != null ? offer.attrs : new ObjAttrs();
      final String   status = nz(oa.getAttr("Status"), "Active");
      final JsonMap  row    = new JsonHashMap();

      row.put("PrvId",        nz(oa.getAttr("PrvId"), ""));
      row.put("Domain",       nz(oa.getAttr("Domain"), ""));
      row.put("PublicDomain", nz(oa.getAttr("PublicDomain"), ""));
      row.put("BrowserOrigin", nz(oa.getAttr("BrowserOrigin"), ""));
      row.put("AppVersion",   nz(oa.getAttr("AppVersion"), ""));
      row.put("Status",       status);
      row.put("PrvActId",     nz(oa.getAttr("PrvActId"), ""));

      if ("Active".equals(status))
      {
        active.add(row);
        activeN++;

        if (preview.size() < 3)
        {
          final JsonMap prev = new JsonHashMap();

          prev.put("PrvId",        oa.getAttr("PrvId"));
          prev.put("Domain",       oa.getAttr("Domain"));
          prev.put("PublicDomain", oa.getAttr("PublicDomain"));
          prev.put("BrowserOrigin", oa.getAttr("BrowserOrigin"));
          preview.add(prev);
        }
      }
      else
        withdrawn.add(row);
    }

    for (int i = 0; i < active.size(); i++)
      all.add(active.get(i));
    for (int i = 0; i < withdrawn.size(); i++)
      all.add(withdrawn.get(i));

    final OfferScan scan = new OfferScan();

    scan.activeCount = activeN;
    scan.preview     = preview;
    scan.allOffers   = all;
    return scan;
  }

  private DomId ensureListing(final DomId regId, final String appId,
                              final String appName, final String appDesc,
                              final String version, final String builderPrvId)
      throws DomatarException
  {
    final DomId    listingId = listingId(regId, appId);
    final Obj      existing  = ObjDb.getObj(listingId);
    final String   stamp     = IdGen.getCurTimeBase64();
    final ObjAttrs attrs     = new ObjAttrs();

    attrs.addAttr("AppId",    appId);
    attrs.addAttr("AppName",  appName);
    attrs.addAttr("AppDesc",  appDesc);
    attrs.addAttr("Version",  version);
    attrs.addAttr("UpdatedAt", stamp);

    if (existing == null)
    {
      attrs.addAttr("BuilderPrvId", builderPrvId);
      ObjDb.addObjIfMissing(listingId, "appstore", "listing", appName, appDesc, attrs);
    }
    else
    {
      final String builder = existing.attrs != null
          ? existing.attrs.getAttr("BuilderPrvId") : null;

      attrs.addAttr("BuilderPrvId",
          (builder != null && !builder.isEmpty()) ? builder : builderPrvId);
      ObjDb.modifyObj(new Obj(listingId, "appstore", "listing", appName, appDesc, attrs));
    }

    if (LnkDb.getLnk(regId, listingId, "appstore", "listing") == null)
      LnkDb.addLnk(new Lnk(regId, listingId,
                            "appstore", "listing",
                            appName, appDesc,
                            "appstore", "listing",
                            appId, 0));

    return listingId;
  }

  private void ensureOffer(final DomId listingId, final String prvId,
                           final String domain, final String publicDomain,
                           final String browserOrigin,
                           final String appVersion,
                           final String prvActId, final String status)
      throws DomatarException
  {
    final DomId    offerDomId = offerId(listingId, prvId);
    final String   stamp      = IdGen.getCurTimeBase64();
    final ObjAttrs attrs      = new ObjAttrs();

    attrs.addAttr("PrvId",      prvId);
    attrs.addAttr("Domain",     domain);
    attrs.addAttr("AppVersion", appVersion);
    attrs.addAttr("Status",     status);
    attrs.addAttr("Version",    stamp);

    if (publicDomain != null && !publicDomain.isEmpty())
      attrs.addAttr("PublicDomain", publicDomain);

    if (browserOrigin != null && !browserOrigin.isEmpty())
      attrs.addAttr("BrowserOrigin", browserOrigin);

    if (prvActId != null && !prvActId.isEmpty())
      attrs.addAttr("PrvActId", prvActId);

    final Obj existing = ObjDb.getObj(offerDomId);

    if (existing == null)
    {
      attrs.addAttr("RegisteredAt", stamp);
      ObjDb.addObjIfMissing(offerDomId, "appstore", "offer",
          prvId, "Offer on " + prvId, attrs);
    }
    else
    {
      final String regAt = existing.attrs != null
          ? existing.attrs.getAttr("RegisteredAt") : null;

      attrs.addAttr("RegisteredAt",
          (regAt != null && !regAt.isEmpty()) ? regAt : stamp);
      ObjDb.modifyObj(new Obj(offerDomId, "appstore", "offer",
          prvId, "Offer on " + prvId, attrs));
    }

    if (LnkDb.getLnk(listingId, offerDomId, "appstore", "offer") == null)
      LnkDb.addLnk(new Lnk(listingId, offerDomId,
                            "appstore", "offer",
                            prvId, "Offer on " + prvId,
                            "appstore", "offer",
                            prvId, 0));
  }

  /**
   * Verify the offering provider still has appId in its local catalog.
   * Local node: CatalogInstall. Remote: HasCatalogEntry to that provider's
   * app-catalog (requires PrvActId from the RegisterOffer body).
   */
  private boolean probeCatalog(final String prvId, final String prvActId,
                               final String domain, final String appId,
                               final DomatarMsgClient msgClient)
      throws DomatarException
  {
    if (prvId != null && prvId.equals(DomatarConfig.getPrvId()))
      return CatalogInstall.hasCatalogEntry(prvId, appId);

    if (prvActId == null || prvActId.isEmpty() || msgClient == null)
      return false;

    if (domain != null && !domain.isEmpty())
      DirectoryRegister.publishHst(
          DomId.subHstId("domatar", prvActId), domain, prvId, msgClient);

    final DomId catalogId = new DomId(
        DomId.subHstId("domatar", prvActId), "domatar", prvActId, "app-catalog");
    final JsonMsg req = new JsonMsg();
    final ObjAttrs attrs = new ObjAttrs();

    attrs.addAttr("AppId", appId);
    req.addRequestBody("HasCatalogEntry", attrs);
    req.addClsId("domatar", "catalog");

    try
    {
      final JsonMsg resp = msgClient.send(catalogId, req);

      if (resp == null || resp.isFailure())
        return false;

      final String present = resp.getAttr("Present");

      return "True".equals(present);
    }
    catch (final DomatarException e)
    {
      return false;
    }
  }

  private DomId registryId(final JsonMsg inMsg, final Obj obj) throws DomatarException
  {
    if (obj != null && obj.domId != null)
      return obj.domId;

    return inMsg.getDstId();
  }

  private static DomId listingId(final DomId regId, final String appId)
      throws DomatarException
  {
    return new DomId(regId.hstId, regId.appId, regId.actId, "listing-" + appId);
  }

  private static DomId offerId(final DomId listingId, final String prvId)
      throws DomatarException
  {
    return new DomId(listingId.hstId, listingId.appId, listingId.actId, "offer-" + prvId);
  }

  private static String nz(final String v, final String d)
  {
    return (v != null && !v.isEmpty()) ? v : d;
  }

  private static final class OfferScan
  {
    int      activeCount;
    JsonList preview;
    JsonList allOffers;
  }
}
