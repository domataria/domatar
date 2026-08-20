/*
 * Copyright (c) 2024 Domatar
 */

package com.bookstore.objimpl;

import java.util.List;

import com.domatar.core.Auth;
import com.domatar.db.LnkDb;
import com.domatar.db.ObjDb;
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
 * LLM-native facade for the Bookstore app.
 *
 * Handles class (bookstore, app) — the app-bookstore entry-point object.
 * Exposes the user's for-sale listings, purchased library, shopping cart,
 * and the global catalog via single named operations.
 *
 * Operations: GetForSale(), GetLibrary(), GetCart(), GetCatalog().
 *
 * Spec: Spec-LLM-Oriented-Msgs.txt — BOOKSTORE section.
 */
public class BookstoreAppImpl extends ObjImpl
{
  private static final String CAT_HST_ID = "bookstore";
  private static final String CAT_APP_ID = "bookstore";
  private static final String CAT_ACT_ID = "bookstore@bookstore";
  private static final String CAT_OBJ_ID = "catalog";

  @Override
  public String handleMsg(final String msg, final Obj obj, final String contextPath,
                          final String contextRealPath, final DomatarMsgClient msgClient)
      throws DomatarException
  {
    final JsonMsg inMsg  = new JsonMsg(msg);
    final String  opr    = inMsg.getOperation();
    final JsonMsg outMsg = new JsonMsg();

    if (!hasRights(inMsg, obj, msgClient))
      return notAuthorized(inMsg);

    final DomId appDomId = inMsg.getDstId();

    if ("GetForSale".equals(opr))
      getForSale(opr, outMsg, appDomId);
    else if ("GetLibrary".equals(opr))
      getLibrary(opr, outMsg, appDomId);
    else if ("GetCart".equals(opr))
      getCart(opr, outMsg, appDomId, msgClient);
    else if ("GetCatalog".equals(opr))
      getCatalog(opr, outMsg, msgClient);
    else
      return super.handleMsg(msg, obj, contextPath, contextRealPath, msgClient);

    return outMsg.toString();
  }

  @Override
  public boolean hasRights(final JsonMsg inMsg, final Obj obj, final DomatarMsgClient msgClient)
      throws DomatarException
  {
    return Auth.isVerified(inMsg);
  }

  // ---------------------------------------------------------------------------

  private void getForSale(final String opr, final JsonMsg outMsg, final DomId appDomId)
      throws DomatarException
  {
    final DomId forSaleId = new DomId(appDomId.hstId, "bookstore", appDomId.actId, "forsale");
    final List<Lnk> lnks = LnkDb.getLnks(forSaleId, "bookstore", "listing",
                                          null, null, 1000, false);

    final JsonList listings = new JsonArrayList(lnks.size());
    for (final Lnk lnk : lnks)
    {
      final Obj listing = ObjDb.getObj(lnk.lnkDomId);
      if (listing == null)
        continue;

      final JsonMap entry = new JsonHashMap(7);
      entry.put("ListingId",   lnk.lnkDomId.objId);
      entry.put("Title",       safeGet(listing.attrs, "Title"));
      entry.put("Author",      safeGet(listing.attrs, "Author"));
      entry.put("Price",       safeGet(listing.attrs, "Price"));
      entry.put("Condition",   safeGet(listing.attrs, "Condition"));
      entry.put("ISBN",        safeGet(listing.attrs, "ISBN"));
      entry.put("Description", safeGet(listing.attrs, "Description"));
      listings.add(entry);
    }

    final ObjAttrs out = new ObjAttrs();
    out.addAttr("Listings", listings);
    outMsg.addResponseBody(opr, out);
  }

  private void getLibrary(final String opr, final JsonMsg outMsg, final DomId appDomId)
      throws DomatarException
  {
    final DomId libraryId = new DomId(appDomId.hstId, "bookstore", appDomId.actId, "library");
    final List<Lnk> lnks = LnkDb.getLnks(libraryId, "bookstore", "book",
                                          null, null, 1000, false);

    final JsonList books = new JsonArrayList(lnks.size());
    for (final Lnk lnk : lnks)
    {
      final Obj book = ObjDb.getObj(lnk.lnkDomId);
      if (book == null)
        continue;

      final JsonMap entry = new JsonHashMap(6);
      entry.put("BookId",      lnk.lnkDomId.objId);
      entry.put("Title",       safeGet(book.attrs, "Title"));
      entry.put("Author",      safeGet(book.attrs, "Author"));
      entry.put("ISBN",        safeGet(book.attrs, "ISBN"));
      entry.put("Price",       safeGet(book.attrs, "Price"));
      entry.put("Condition",   safeGet(book.attrs, "Condition"));
      entry.put("Description", safeGet(book.attrs, "Description"));
      books.add(entry);
    }

    final ObjAttrs out = new ObjAttrs();
    out.addAttr("Books", books);
    outMsg.addResponseBody(opr, out);
  }

  /**
   * Reads cart links from LnkDb and resolves each listing's live details via
   * msgClient so cross-provider listings (different database) are fetched
   * correctly.  Falls back to the title/author cached in the link when the
   * remote listing is unreachable.
   */
  private void getCart(final String opr, final JsonMsg outMsg, final DomId appDomId,
                       final DomatarMsgClient msgClient)
      throws DomatarException
  {
    final DomId cartId = new DomId(appDomId.hstId, "bookstore", appDomId.actId, "cart");
    final List<Lnk> lnks = LnkDb.getLnks(cartId, "bookstore", "listing",
                                          null, null, 1000, false);

    final JsonList items = new JsonArrayList(lnks.size());
    for (final Lnk lnk : lnks)
    {
      final ObjAttrs live = fetchListingAttrs(lnk.lnkDomId, msgClient);

      final JsonMap entry = new JsonHashMap(5);
      entry.put("ListingDomId", lnk.lnkDomId.toString());
      entry.put("Title",       live != null ? safeGet(live, "Title")  : lnk.lnkObjName);
      entry.put("Author",      live != null ? safeGet(live, "Author") : lnk.lnkObjDesc);
      entry.put("Price",       live != null ? safeGet(live, "Price")  : null);
      entry.put("SellerActId", lnk.val);
      items.add(entry);
    }

    final ObjAttrs out = new ObjAttrs();
    out.addAttr("Items", items);
    outMsg.addResponseBody(opr, out);
  }

  private static ObjAttrs fetchListingAttrs(final DomId listingDomId,
                                            final DomatarMsgClient msgClient)
  {
    try
    {
      final JsonMsg openMsg = new JsonMsg();
      openMsg.addRequestBody("GetObj", null);
      final JsonMsg resp = msgClient.send(listingDomId, openMsg);

      final ObjAttrs listingAttrs = resp != null ? resp.getAttrs() : null;
      if (listingAttrs == null)
        return null;
      final ObjAttrs nested = listingAttrs.getObjAttrs("Attrs");
      return (nested != null && nested.toMap() != null) ? nested : null;
    }
    catch (DomatarException e)
    {
      return null;
    }
  }

  /**
   * Delegates to the central catalog object via msgClient so the result
   * includes listings from all providers (cross-host).
   */
  private void getCatalog(final String opr, final JsonMsg outMsg,
                          final DomatarMsgClient msgClient)
      throws DomatarException
  {
    final DomId  catId = new DomId(CAT_HST_ID, CAT_APP_ID, CAT_ACT_ID, CAT_OBJ_ID);
    final JsonMsg req  = new JsonMsg();
    req.addRequestBody("GetCatalog", null);
    req.addClsId(CAT_APP_ID, "catalog");

    try
    {
      final JsonMsg resp = msgClient.send(catId, req);
      if (resp == null)
      {
        outMsg.addError(opr, "Catalog unavailable");
        return;
      }
      final String err = resp.getError();
      if (err != null && !"".equals(err))
      {
        outMsg.addError(opr, err);
        return;
      }
      final ObjAttrs respAttrs = resp.getAttrs();
      outMsg.addResponseBody(opr, respAttrs != null ? respAttrs : new ObjAttrs());
    }
    catch (Exception e)
    {
      outMsg.addError(opr, "Catalog error: " + e.getMessage());
    }
  }

  // ---------------------------------------------------------------------------

  private static String safeGet(final ObjAttrs attrs, final String key)
  {
    try
    {
      return attrs.getAttr(key);
    }
    catch (Exception e)
    {
      return null;
    }
  }
}
