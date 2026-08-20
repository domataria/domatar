/*
 * Copyright (c) 2024 Domatar
 */

package com.bookstore.objimpl;

import java.time.Instant;
import java.util.List;

import com.domatar.core.Auth;
import com.domatar.db.LnkDb;
import com.domatar.db.ObjDb;
import com.domatar.util.IdGen;
import com.domatar.util.JsonArrayList;
import com.domatar.util.JsonList;
import com.domatar.util.JsonMsg;
import com.domatar.util.Lnk;
import com.domatar.util.Obj;
import com.domatar.util.ObjAttrs;
import com.domatar.util.ObjImpl;
import com.domatar.util.DomId;
import com.domatar.util.DomatarException;
import com.domatar.util.DomatarMsgClient;

/**
 * Handler for class (bookstore, forsale).
 *
 * Operations: ListBook, DelistBook, GetForSale, UpdatePrice, MarkSold.
 * Spec: Spec-Bookstore.txt PART 6.1
 */
public class ForSaleImpl extends ObjImpl
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

    if ("ListBook".equals(opr))
      listBook(opr, inMsg, outMsg, msgClient);
    else if ("DelistBook".equals(opr))
      delistBook(opr, inMsg, outMsg, msgClient);
    else if ("GetForSale".equals(opr))
      getForSale(opr, inMsg, outMsg);
    else if ("UpdatePrice".equals(opr))
      updatePrice(opr, inMsg, outMsg);
    else if ("MarkSold".equals(opr))
      markSold(opr, inMsg, outMsg, msgClient);
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

  private void listBook(final String opr, final JsonMsg inMsg, final JsonMsg outMsg,
                        final DomatarMsgClient msgClient)
      throws DomatarException
  {
    final DomId forSaleId = inMsg.getDstId();

    final ObjAttrs in = inMsg.getAttrs();

    final String title       = in.getAttr("Title");
    final String author      = in.getAttr("Author");
    final String isbn        = in.getAttr("ISBN");
    final String price       = in.getAttr("Price");
    final String condition   = in.getAttr("Condition");
    final String description = in.getAttr("Description");
    final String sellerActId = in.getAttr("SellerActId");
    final String sellerName  = in.getAttr("SellerName");

    if (title == null || author == null || price == null || condition == null
        || sellerActId == null)
    {
      outMsg.addError(opr, "Missing required field");
      return;
    }

    final String objId = IdGen.createIdFromCurTime("listing");

    final DomId listingId = new DomId(forSaleId.hstId, "bookstore", forSaleId.actId, objId);

    final ObjAttrs attrs = new ObjAttrs();
    attrs.addAttr("Title",       title);
    attrs.addAttr("Author",      author);
    if (isbn != null)
      attrs.addAttr("ISBN", isbn);
    attrs.addAttr("Price",       price);
    attrs.addAttr("Condition",   condition);
    if (description != null)
      attrs.addAttr("Description", description);
    attrs.addAttr("SellerActId", sellerActId);
    attrs.addAttr("SellerName",  sellerName != null ? sellerName : sellerActId);
    attrs.addAttr("ListedAt",    Instant.now().toString());
    attrs.addAttr("Sold",        "false");

    ObjDb.addObj(new Obj(listingId, "bookstore", "listing", title, author, attrs));

    LnkDb.addLnk(new Lnk(forSaleId, listingId,
                          "bookstore", "listing",
                          title, author,
                          "bookstore", "listing",
                          sellerActId, System.currentTimeMillis()));

    final DomId catId = new DomId(CAT_HST_ID, CAT_APP_ID, CAT_ACT_ID, CAT_OBJ_ID);
    final JsonMsg regMsg = new JsonMsg();
    final ObjAttrs regAttrs = new ObjAttrs();
    regAttrs.addAttr("ListingDomId", listingId.toString());
    regAttrs.addAttr("Title",        title);
    regAttrs.addAttr("Author",       author);
    regAttrs.addAttr("Price",        price);
    regAttrs.addAttr("Condition",    condition);
    regAttrs.addAttr("SellerActId",  sellerActId);
    regAttrs.addAttr("SellerName",   sellerName != null ? sellerName : sellerActId);
    regMsg.addRequestBody("Register", regAttrs);
    regMsg.addClsId(CAT_APP_ID, "catalog");
    msgClient.send(catId, regMsg);

    final ObjAttrs out = new ObjAttrs();
    out.addAttr("ListingId", objId);
    outMsg.addResponseBody(opr, out);
  }

  private void delistBook(final String opr, final JsonMsg inMsg, final JsonMsg outMsg,
                          final DomatarMsgClient msgClient)
      throws DomatarException
  {
    final DomId forSaleId = inMsg.getDstId();

    final String listingId = inMsg.getAttrs().getAttr("ListingId");

    if (listingId == null)
    {
      outMsg.addError(opr, "Missing ListingId");
      return;
    }

    final DomId listingDomId = new DomId(forSaleId.hstId, "bookstore", forSaleId.actId, listingId);

    final Obj listing = ObjDb.getObj(listingDomId);
    if (listing == null)
    {
      outMsg.addError(opr, "Listing not found");
      return;
    }

    listing.attrs.addAttr("Sold", "delisted");
    ObjDb.modifyObj(listing);

    LnkDb.deleteLnks(forSaleId, listingDomId, "bookstore", "listing", null, null);

    deregisterFromCatalog(listingDomId, msgClient);

    final ObjAttrs out = new ObjAttrs();
    out.addAttr("Delisted", "True");
    outMsg.addResponseBody(opr, out);
  }

  private void updatePrice(final String opr, final JsonMsg inMsg, final JsonMsg outMsg)
      throws DomatarException
  {
    final DomId forSaleId = inMsg.getDstId();

    final String listingId = inMsg.getAttrs().getAttr("ListingId");
    String       price     = inMsg.getAttrs().getAttr("Price");

    if (listingId == null || price == null)
    {
      outMsg.addError(opr, "Missing ListingId or Price");
      return;
    }

    price = price.trim();
    if (price.isEmpty())
    {
      outMsg.addError(opr, "Price is required");
      return;
    }

    try
    {
      final double amount = Double.parseDouble(price);
      if (amount < 0)
      {
        outMsg.addError(opr, "Price must be greater than or equal to zero");
        return;
      }
    }
    catch (NumberFormatException e)
    {
      outMsg.addError(opr, "Invalid price");
      return;
    }

    final DomId listingDomId = new DomId(forSaleId.hstId, "bookstore", forSaleId.actId, listingId);

    final Obj listing = ObjDb.getObj(listingDomId);
    if (listing == null)
    {
      outMsg.addError(opr, "Listing not found");
      return;
    }

    final String sold = listing.attrs.getAttr("Sold");
    if ("true".equals(sold) || "delisted".equals(sold))
    {
      outMsg.addError(opr, "Listing is no longer active");
      return;
    }

    listing.attrs.addAttr("Price", price);
    ObjDb.modifyObj(listing);

    final ObjAttrs out = new ObjAttrs();
    out.addAttr("ListingId", listingId);
    out.addAttr("Price",     price);
    outMsg.addResponseBody(opr, out);
  }

  private void getForSale(final String opr, final JsonMsg inMsg, final JsonMsg outMsg)
      throws DomatarException
  {
    final DomId forSaleId = inMsg.getDstId();

    final List<Lnk> lnks = LnkDb.getLnks(forSaleId, "bookstore", "listing",
                                          null, null, 1000, false);

    final JsonList listings = new JsonArrayList(lnks.size());

    for (final Lnk lnk : lnks)
    {
      final Obj listing = ObjDb.getObj(lnk.lnkDomId);
      if (listing == null)
        continue;

      final ObjAttrs entry = new ObjAttrs();
      entry.addAttr("DomId",     lnk.lnkDomId.toString());
      entry.addAttr("ListingId", lnk.lnkDomId.objId);
      entry.addAttr("Title",     listing.attrs.getAttr("Title"));
      entry.addAttr("Author",    listing.attrs.getAttr("Author"));
      entry.addAttr("Price",     listing.attrs.getAttr("Price"));
      entry.addAttr("Condition", listing.attrs.getAttr("Condition"));
      entry.addAttr("ListedAt",  listing.attrs.getAttr("ListedAt"));
      entry.addAttr("Sold",      listing.attrs.getAttr("Sold"));
      listings.add(entry.toMap());
    }

    final ObjAttrs out = new ObjAttrs();
    out.addAttr("Listings", listings);
    outMsg.addResponseBody(opr, out);
  }

  /**
   * Called by LibraryImpl after a purchase.  Sets Sold="true", removes the
   * forsale lnk, and notifies the central catalog to deregister the listing.
   */
  private void markSold(final String opr, final JsonMsg inMsg, final JsonMsg outMsg,
                        final DomatarMsgClient msgClient)
      throws DomatarException
  {
    final DomId forSaleId = inMsg.getDstId();

    final String listingId       = inMsg.getAttrs().getAttr("ListingId");
    final String listingDomIdStr = inMsg.getAttrs().getAttr("ListingDomId");

    if (listingId == null)
    {
      outMsg.addError(opr, "Missing ListingId");
      return;
    }

    final DomId listingDomId = listingDomIdStr != null
        ? new DomId(listingDomIdStr)
        : new DomId(forSaleId.hstId, "bookstore", forSaleId.actId, listingId);

    final Obj listing = ObjDb.getObj(listingDomId);
    if (listing == null)
    {
      outMsg.addError(opr, "Listing not found");
      return;
    }

    listing.attrs.addAttr("Sold", "true");
    ObjDb.modifyObj(listing);

    LnkDb.deleteLnks(forSaleId, listingDomId, "bookstore", "listing", null, null);

    deregisterFromCatalog(listingDomId, msgClient);

    final ObjAttrs out = new ObjAttrs();
    out.addAttr("MarkedSold", "True");
    outMsg.addResponseBody(opr, out);
  }

  private void deregisterFromCatalog(final DomId listingDomId, final DomatarMsgClient msgClient)
      throws DomatarException
  {
    final DomId catId = new DomId(CAT_HST_ID, CAT_APP_ID, CAT_ACT_ID, CAT_OBJ_ID);
    final JsonMsg deregMsg = new JsonMsg();
    final ObjAttrs deregAttrs = new ObjAttrs();
    deregAttrs.addAttr("ListingDomId", listingDomId.toString());
    deregMsg.addRequestBody("Deregister", deregAttrs);
    deregMsg.addClsId(CAT_APP_ID, "catalog");
    msgClient.send(catId, deregMsg);
  }
}
