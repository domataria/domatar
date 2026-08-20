/*
 * Copyright (c) 2024 Domatar
 */

package com.bookstore.objimpl;

import java.util.List;

import com.domatar.core.Auth;
import com.domatar.db.LnkDb;
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
 * Handler for class (bookstore, catalog).
 *
 * The catalog is a singleton on the central bookstore host (prv2).
 * It stores cross-prv links to listing objects on sellers' sub-hosts.
 *
 * Operations: Register (listing), Deregister, GetCatalog, UserRegister (no-op).
 * Spec: Spec-Bookstore.txt PART 6.3
 */
public class CatalogImpl extends ObjImpl
{
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

    // obj may be null when the caller pre-set ClsAppId/ClsId in the envelope,
    // because the dispatch layer skips ObjDb.getObj in that case. Use the
    // DstId from the message as the authoritative catalog DomId.
    final DomId catalogDomId = (obj != null) ? obj.domId : inMsg.getDstId();

    if ("Register".equals(opr))
      register(opr, inMsg, outMsg, catalogDomId);
    else if ("Deregister".equals(opr))
      deregister(opr, inMsg, outMsg, catalogDomId);
    else if ("GetCatalog".equals(opr))
      getCatalog(opr, inMsg, outMsg, catalogDomId, msgClient);
    else if ("UserRegister".equals(opr))
      userRegister(opr, outMsg);
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

  private void register(final String opr, final JsonMsg inMsg, final JsonMsg outMsg,
                        final DomId catalogDomId)
      throws DomatarException
  {
    final ObjAttrs in = inMsg.getAttrs();

    final String listingDomIdStr = in.getAttr("ListingDomId");
    final String title           = in.getAttr("Title");
    final String author          = in.getAttr("Author");
    final String sellerActId     = in.getAttr("SellerActId");

    if (listingDomIdStr == null || sellerActId == null)
    {
      outMsg.addError(opr, "Missing ListingDomId or SellerActId");
      return;
    }

    final DomId listingDomId = new DomId(listingDomIdStr);

    // Cross-prv link: catalog -> listing.
    // LnkObjName = Title, LnkObjDesc = Author (for offline fallback in GetCatalog).
    // Val = SellerActId, SeqNum = listing timestamp for chronological ordering.
    LnkDb.addLnk(new Lnk(catalogDomId, listingDomId,
                          "bookstore", "listing",
                          title  != null ? title  : "",
                          author != null ? author : "",
                          "bookstore", "listing",
                          sellerActId, System.currentTimeMillis()));

    final ObjAttrs out = new ObjAttrs();
    out.addAttr("Registered", "True");
    outMsg.addResponseBody(opr, out);
  }

  private void deregister(final String opr, final JsonMsg inMsg, final JsonMsg outMsg,
                          final DomId catalogDomId)
      throws DomatarException
  {
    final String listingDomIdStr = inMsg.getAttrs().getAttr("ListingDomId");

    if (listingDomIdStr == null)
    {
      outMsg.addError(opr, "Missing ListingDomId");
      return;
    }

    final DomId listingDomId = new DomId(listingDomIdStr);

    LnkDb.deleteLnks(catalogDomId, listingDomId, "bookstore", "listing", null, null);

    final ObjAttrs out = new ObjAttrs();
    out.addAttr("Deregistered", "True");
    outMsg.addResponseBody(opr, out);
  }

  private void getCatalog(final String opr, final JsonMsg inMsg, final JsonMsg outMsg,
                          final DomId catalogDomId, final DomatarMsgClient msgClient)
      throws DomatarException
  {
    final DomId  srcId   = inMsg.getSrcId();
    final String myActId = srcId != null ? srcId.actId : null;

    final List<Lnk> lnks = LnkDb.getLnks(catalogDomId, "bookstore", "listing",
                                          null, null, 5000, false);

    final JsonList listings = new JsonArrayList(lnks.size());

    for (final Lnk lnk : lnks)
    {
      final ObjAttrs entry = new ObjAttrs();
      entry.addAttr("DomId",       lnk.lnkDomId.toString());
      entry.addAttr("Title",       lnk.lnkObjName);
      entry.addAttr("Author",      lnk.lnkObjDesc);
      entry.addAttr("SellerActId", lnk.val);

      // Attempt a remote Open to get full listing details (Price, Condition,
      // SellerName, ListedAt, Sold). Fall back to lnk metadata on failure.
      try
      {
        final JsonMsg openMsg = new JsonMsg();
        openMsg.addRequestBody("GetLnks", null);
        final JsonMsg resp = msgClient.send(lnk.lnkDomId, openMsg);

        final ObjAttrs listingAttrs = resp != null ? resp.getAttrs() : null;

        // The Open protocol nests the object's stored attrs one level deep
        // under the "Attrs" key in the response envelope.
        final ObjAttrs nestedAttrs = listingAttrs != null ? listingAttrs.getObjAttrs("Attrs") : null;
        final boolean  hasNested   = nestedAttrs  != null && nestedAttrs.toMap() != null;

        if (hasNested)
        {
          final String sold = nestedAttrs.getAttr("Sold");
          if ("true".equals(sold))
            continue; // skip sold listings

          entry.addAttr("Price",       nestedAttrs.getAttr("Price"));
          entry.addAttr("Condition",   nestedAttrs.getAttr("Condition"));
          entry.addAttr("SellerName",  nestedAttrs.getAttr("SellerName"));
          entry.addAttr("ListedAt",    nestedAttrs.getAttr("ListedAt"));
          entry.addAttr("Available",   "true");
        }
        else
        {
          entry.addAttr("Available", "unknown");
        }
      }
      catch (DomatarException e)
      {
        entry.addAttr("Available", "unknown");
      }

      listings.add(entry.toMap());
    }

    final ObjAttrs out = new ObjAttrs();
    out.addAttr("Listings", listings);
    if (myActId != null)
      out.addAttr("MyActId", myActId);
    outMsg.addResponseBody(opr, out);
  }

  /** No-op placeholder for future per-user catalog registration. */
  private void userRegister(final String opr, final JsonMsg outMsg) throws DomatarException
  {
    final ObjAttrs out = new ObjAttrs();
    out.addAttr("Registered", "True");
    outMsg.addResponseBody(opr, out);
  }
}
