/*
 * Copyright (c) 2024 Domatar
 */

package com.bookstore.objimpl;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.domatar.core.Auth;
import com.domatar.db.LnkDb;
import com.domatar.db.ObjDb;
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
 * Handler for class (bookstore, cart).
 *
 * The cart stores cross-prv links directly to listing objects so that
 * GetCart and BuyCartItems always read live listing data (price, condition, etc.).
 *
 * Operations: GetCart, AddToCart, RemoveFromCart, BuyCartItems.
 * Spec: Spec-Bookstore.txt PART 6.4
 */
public class CartImpl extends ObjImpl
{
  @Override
  public String handleMsg(final String msg, final Obj obj, final String contextPath,
                          final String contextRealPath, final DomatarMsgClient msgClient)
      throws DomatarException
  {
    final JsonMsg inMsg  = new JsonMsg(msg);
    final String  opr    = inMsg.getOperation();

    if ("Compensate".equals(opr))
      return super.handleMsg(msg, obj, contextPath, contextRealPath, msgClient);

    final JsonMsg outMsg = new JsonMsg();

    if (!hasRights(inMsg, obj, msgClient))
      return notAuthorized(inMsg);

    if ("GetCart".equals(opr))
      getCart(opr, inMsg, outMsg, msgClient);
    else if ("AddToCart".equals(opr))
      addToCart(opr, inMsg, outMsg);
    else if ("RemoveFromCart".equals(opr))
      removeFromCart(opr, inMsg, outMsg);
    else if ("BuyCartItems".equals(opr))
      buyCartItems(opr, inMsg, outMsg, msgClient);
    else
      return super.handleMsg(msg, obj, contextPath, contextRealPath, msgClient);

    return outMsg.toString();
  }

  @Override
  public boolean hasRights(final JsonMsg inMsg, final Obj obj, final DomatarMsgClient msgClient)
      throws DomatarException
  {
    return Auth.isVerified(msgClient);
  }

  // ---------------------------------------------------------------------------

  /** Idempotently create the cart container if it does not yet exist. */
  private static void ensureCartExists(final DomId cartId) throws DomatarException
  {
    if (ObjDb.getObj(cartId) == null)
      ObjDb.addObjIfMissing(cartId, "bookstore", "cart",
                            "My Shopping Cart", "Books saved for purchase");
  }

  private void getCart(final String opr, final JsonMsg inMsg, final JsonMsg outMsg,
                       final DomatarMsgClient msgClient)
      throws DomatarException
  {
    final DomId cartId = inMsg.getDstId();
    ensureCartExists(cartId);

    final Map<String, Lnk> listingLnks = new LinkedHashMap<>();

    for (final Lnk lnk : LnkDb.getLnks(cartId, "bookstore", "listing", null, null, 1000, false))
      listingLnks.putIfAbsent(lnk.lnkDomId.toString(), lnk);

    // Legacy carts stored local cartitem objects; migrate them on read.
    for (final Lnk lnk : LnkDb.getLnks(cartId, "bookstore", "cartitem", null, null, 1000, false))
    {
      final Obj item = ObjDb.getObj(lnk.lnkDomId);
      if (item == null)
        continue;

      final String listingDomIdStr = item.attrs.getAttr("ListingDomId");
      if (listingDomIdStr == null || listingLnks.containsKey(listingDomIdStr))
        continue;

      listingLnks.put(listingDomIdStr,
                      new Lnk(cartId, new DomId(listingDomIdStr),
                              "bookstore", "listing",
                              item.attrs.getAttr("Title"),
                              item.attrs.getAttr("Author"),
                              "bookstore", "listing",
                              item.attrs.getAttr("SellerActId"),
                              lnk.seqNum));
    }

    final JsonList items = new JsonArrayList(listingLnks.size());

    for (final Lnk lnk : listingLnks.values())
    {
      final ObjAttrs live = fetchListingAttrs(lnk.lnkDomId, msgClient);
      items.add(buildCartEntry(lnk, live).toMap());
    }

    final ObjAttrs out = new ObjAttrs();
    out.addAttr("Items", items);
    outMsg.addResponseBody(opr, out);
  }

  private void addToCart(final String opr, final JsonMsg inMsg, final JsonMsg outMsg)
      throws DomatarException
  {
    final DomId    cartId = inMsg.getDstId();
    ensureCartExists(cartId);
    final ObjAttrs in     = inMsg.getAttrs();

    final String listingDomIdStr = in.getAttr("ListingDomId");
    final String title           = in.getAttr("Title");
    final String sellerActId     = in.getAttr("SellerActId");

    if (listingDomIdStr == null || title == null || sellerActId == null)
    {
      outMsg.addError(opr, "Missing required field");
      return;
    }

    final DomId listingDomId = new DomId(listingDomIdStr);

    if (LnkDb.getLnk(cartId, listingDomId, "bookstore", "listing") != null)
    {
      final ObjAttrs out = new ObjAttrs();
      out.addAttr("ListingDomId", listingDomIdStr);
      out.addAttr("AlreadyInCart", "true");
      outMsg.addResponseBody(opr, out);
      return;
    }

    final String author = in.getAttr("Author");

    LnkDb.addLnk(new Lnk(cartId, listingDomId,
                          "bookstore", "listing",
                          title, author != null ? author : "",
                          "bookstore", "listing",
                          sellerActId, System.currentTimeMillis()));

    removeLegacyCartItem(cartId, listingDomIdStr);

    final ObjAttrs out = new ObjAttrs();
    out.addAttr("ListingDomId", listingDomIdStr);
    outMsg.addResponseBody(opr, out);
  }

  private void removeFromCart(final String opr, final JsonMsg inMsg, final JsonMsg outMsg)
      throws DomatarException
  {
    final DomId    cartId = inMsg.getDstId();
    final ObjAttrs in     = inMsg.getAttrs();

    String listingDomIdStr = in.getAttr("ListingDomId");
    if (listingDomIdStr == null)
    {
      // Legacy clients sent CartItemId for a local cartitem object.
      final String cartItemId = in.getAttr("CartItemId");
      if (cartItemId == null)
      {
        outMsg.addError(opr, "Missing ListingDomId");
        return;
      }

      final DomId itemId = new DomId(cartId.hstId, "bookstore", cartId.actId, cartItemId);
      final Obj   item   = ObjDb.getObj(itemId);
      if (item != null)
        listingDomIdStr = item.attrs.getAttr("ListingDomId");

      ObjDb.deleteObj(itemId);
      LnkDb.deleteLnks(cartId, itemId, "bookstore", "cartitem", null, null);
    }

    if (listingDomIdStr != null)
    {
      final DomId listingDomId = new DomId(listingDomIdStr);
      LnkDb.deleteLnks(cartId, listingDomId, "bookstore", "listing", null, null);
      removeLegacyCartItem(cartId, listingDomIdStr);
    }

    final ObjAttrs out = new ObjAttrs();
    out.addAttr("Removed", "true");
    outMsg.addResponseBody(opr, out);
  }

  private void buyCartItems(final String opr, final JsonMsg inMsg, final JsonMsg outMsg,
                            final DomatarMsgClient msgClient)
      throws DomatarException
  {
    final DomId    cartId           = inMsg.getDstId();
    final ObjAttrs in               = inMsg.getAttrs();
    String         listingDomIdsCsv = in.getAttr("ListingDomIds");
    if (listingDomIdsCsv == null || listingDomIdsCsv.trim().isEmpty())
      listingDomIdsCsv = in.getAttr("CartItemIds"); // legacy param name

    if (listingDomIdsCsv == null || listingDomIdsCsv.trim().isEmpty())
    {
      outMsg.addError(opr, "No items selected");
      return;
    }

    final DomId         libraryId     = new DomId(cartId.hstId, "bookstore", cartId.actId, "library");
    final String[]      listingIdList = listingDomIdsCsv.split(",");
    int                 bought        = 0;
    final StringBuilder errors        = new StringBuilder();

    for (String listingDomIdStr : listingIdList)
    {
      listingDomIdStr = listingDomIdStr.trim();
      if (listingDomIdStr.isEmpty())
        continue;

      final DomId    listingDomId = resolveListingDomId(cartId, listingDomIdStr);
      final ObjAttrs live         = fetchListingAttrs(listingDomId, msgClient);
      final String   title        = live != null ? live.getAttr("Title") : listingDomId.objId;

      if (live == null)
      {
        appendError(errors, title, "listing unavailable");
        continue;
      }

      final String sold = live.getAttr("Sold");
      if ("true".equals(sold) || "delisted".equals(sold))
      {
        appendError(errors, title, "listing no longer available");
        continue;
      }

      final JsonMsg  buyMsg   = new JsonMsg();
      final ObjAttrs buyAttrs = new ObjAttrs();
      buyAttrs.addAttr("ListingDomId", listingDomId.toString());
      buyAttrs.addAttr("Title",        live.getAttr("Title"));
      buyAttrs.addAttr("Author",       live.getAttr("Author"));
      buyAttrs.addAttr("ISBN",         live.getAttr("ISBN"));
      buyAttrs.addAttr("Price",        live.getAttr("Price"));
      buyAttrs.addAttr("Condition",    live.getAttr("Condition"));
      buyAttrs.addAttr("Description",  live.getAttr("Description"));
      buyAttrs.addAttr("SellerActId",  live.getAttr("SellerActId"));
      buyAttrs.addAttr("SellerName",   live.getAttr("SellerName"));
      buyMsg.addRequestBody("BuyBook", buyAttrs);
      buyMsg.addClsId("bookstore", "library");

      final JsonMsg buyResp = msgClient.send(libraryId, buyMsg);

      if (buyResp != null && !"Failure".equals(buyResp.getError()))
      {
        LnkDb.deleteLnks(cartId, listingDomId, "bookstore", "listing", null, null);
        removeLegacyCartItem(cartId, listingDomId.toString());
        bought++;
      }
      else
      {
        appendError(errors, title, "purchase failed");
      }
    }

    final ObjAttrs out = new ObjAttrs();
    out.addAttr("Bought", String.valueOf(bought));
    if (errors.length() > 0)
      out.addAttr("Errors", errors.toString());
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
      return listingAttrs != null ? listingAttrs.getObjAttrs("Attrs") : null;
    }
    catch (DomatarException e)
    {
      return null;
    }
  }

  private static ObjAttrs buildCartEntry(final Lnk lnk, final ObjAttrs live)
      throws DomatarException
  {
    final ObjAttrs entry = new ObjAttrs();
    entry.addAttr("ListingDomId", lnk.lnkDomId.toString());
    entry.addAttr("AddedAt",      Instant.ofEpochMilli(lnk.seqNum).toString());

    if (live != null)
    {
      final String sold = live.getAttr("Sold");
      if ("true".equals(sold) || "delisted".equals(sold))
        entry.addAttr("Available", "false");
      else
        entry.addAttr("Available", "true");

      entry.addAttr("Title",       live.getAttr("Title"));
      entry.addAttr("Author",      live.getAttr("Author"));
      entry.addAttr("Price",       live.getAttr("Price"));
      entry.addAttr("Condition",   live.getAttr("Condition"));
      entry.addAttr("SellerActId", live.getAttr("SellerActId"));
      entry.addAttr("SellerName",  live.getAttr("SellerName"));
    }
    else
    {
      entry.addAttr("Available",   "unknown");
      entry.addAttr("Title",       lnk.lnkObjName);
      entry.addAttr("Author",      lnk.lnkObjDesc);
      entry.addAttr("SellerActId", lnk.val);
      entry.addAttr("SellerName",  lnk.val);
    }

    return entry;
  }

  /** Legacy carts linked to local cartitem objects instead of listings. */
  private static void removeLegacyCartItem(final DomId cartId, final String listingDomIdStr)
      throws DomatarException
  {
    for (final Lnk lnk : LnkDb.getLnks(cartId, "bookstore", "cartitem", null, null, 1000, false))
    {
      final Obj item = ObjDb.getObj(lnk.lnkDomId);
      if (item == null)
        continue;

      if (!listingDomIdStr.equals(item.attrs.getAttr("ListingDomId")))
        continue;

      ObjDb.deleteObj(lnk.lnkDomId);
      LnkDb.deleteLnks(cartId, lnk.lnkDomId, "bookstore", "cartitem", null, null);
    }
  }

  /**
   * Accept either a full listing DomId or a legacy cartitem objId from old carts.
   */
  private static DomId resolveListingDomId(final DomId cartId, final String idStr)
      throws DomatarException
  {
    if (idStr.contains("."))
      return new DomId(idStr);

    final DomId itemId = new DomId(cartId.hstId, "bookstore", cartId.actId, idStr);
    final Obj   item   = ObjDb.getObj(itemId);
    if (item != null)
    {
      final String listingDomIdStr = item.attrs.getAttr("ListingDomId");
      if (listingDomIdStr != null)
        return new DomId(listingDomIdStr);
    }

    throw new DomatarException("Listing not found for cart item " + idStr);
  }

  private static void appendError(final StringBuilder errors, final String title,
                                  final String reason)
  {
    if (errors.length() > 0)
      errors.append("; ");
    errors.append(title != null ? title : "item").append(": ").append(reason);
  }
}
