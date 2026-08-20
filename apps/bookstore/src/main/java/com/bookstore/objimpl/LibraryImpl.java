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
 * Handler for class (bookstore, library).
 *
 * Operations: BuyBook, GetLibrary, RemoveBook.
 * Spec: Spec-Bookstore.txt PART 6.2
 */
public class LibraryImpl extends ObjImpl
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

    if ("BuyBook".equals(opr))
      buyBook(opr, inMsg, outMsg, msgClient);
    else if ("GetLibrary".equals(opr))
      getLibrary(opr, inMsg, outMsg);
    else if ("RemoveBook".equals(opr))
      removeBook(opr, inMsg, outMsg);
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

  private void buyBook(final String opr, final JsonMsg inMsg, final JsonMsg outMsg,
                       final DomatarMsgClient msgClient)
      throws DomatarException
  {
    final DomId libraryId = inMsg.getDstId();

    final ObjAttrs in = inMsg.getAttrs();

    final String listingDomIdStr = in.getAttr("ListingDomId");
    final String title           = in.getAttr("Title");
    final String author          = in.getAttr("Author");
    final String isbn            = in.getAttr("ISBN");
    final String price           = in.getAttr("Price");
    final String condition       = in.getAttr("Condition");
    final String description     = in.getAttr("Description");
    final String sellerActId     = in.getAttr("SellerActId");
    final String sellerName      = in.getAttr("SellerName");

    if (listingDomIdStr == null || title == null || sellerActId == null)
    {
      outMsg.addError(opr, "Missing required field");
      return;
    }

    if (sellerActId.equals(libraryId.actId))
    {
      outMsg.addError(opr, "Cannot buy your own listing");
      return;
    }

    final String objId  = IdGen.createIdFromCurTime("book");
    final DomId  bookId = new DomId(libraryId.hstId, "bookstore", libraryId.actId, objId);

    final ObjAttrs attrs = new ObjAttrs();
    attrs.addAttr("Title",       title);
    attrs.addAttr("Author",      author != null ? author : "");
    if (isbn != null)
      attrs.addAttr("ISBN", isbn);
    attrs.addAttr("Price",       price     != null ? price     : "");
    attrs.addAttr("Condition",   condition != null ? condition : "");
    if (description != null)
      attrs.addAttr("Description", description);
    attrs.addAttr("SellerActId",  sellerActId);
    attrs.addAttr("SellerName",   sellerName != null ? sellerName : sellerActId);
    attrs.addAttr("PurchasedAt",  Instant.now().toString());
    attrs.addAttr("ListingDomId", listingDomIdStr);

    ObjDb.addObj(new Obj(bookId, "bookstore", "book", title, author != null ? author : "", attrs));

    LnkDb.addLnk(new Lnk(libraryId, bookId,
                          "bookstore", "book",
                          title, author != null ? author : "",
                          "bookstore", "book",
                          sellerActId, System.currentTimeMillis()));

    final DomId sellerForSaleId = new DomId(DomId.subHstId("bookstore", sellerActId),
                                            "bookstore", sellerActId, "forsale");
    final DomId listingDomId    = new DomId(listingDomIdStr);

    final JsonMsg  markSoldMsg = new JsonMsg();
    final ObjAttrs msAttrs     = new ObjAttrs();
    msAttrs.addAttr("ListingId",    listingDomId.objId);
    msAttrs.addAttr("ListingDomId", listingDomIdStr);
    markSoldMsg.addRequestBody("MarkSold", msAttrs);
    markSoldMsg.addClsId("bookstore", "forsale");
    msgClient.send(sellerForSaleId, markSoldMsg);

    final ObjAttrs out = new ObjAttrs();
    out.addAttr("BookId", objId);
    outMsg.addResponseBody(opr, out);
  }

  private void removeBook(final String opr, final JsonMsg inMsg, final JsonMsg outMsg)
      throws DomatarException
  {
    final DomId    libraryId = inMsg.getDstId();
    final ObjAttrs in        = inMsg.getAttrs();
    final String   bookId    = in.getAttr("BookId");

    if (bookId == null)
    {
      outMsg.addError(opr, "Missing BookId");
      return;
    }

    final DomId bid = new DomId(libraryId.hstId, "bookstore", libraryId.actId, bookId);
    ObjDb.deleteObj(bid);
    LnkDb.deleteLnks(libraryId, bid, "bookstore", "book", null, null);

    final ObjAttrs out = new ObjAttrs();
    out.addAttr("Removed", "true");
    outMsg.addResponseBody(opr, out);
  }

  private void getLibrary(final String opr, final JsonMsg inMsg, final JsonMsg outMsg)
      throws DomatarException
  {
    final DomId libraryId = inMsg.getDstId();

    final List<Lnk> lnks = LnkDb.getLnks(libraryId, "bookstore", "book",
                                          null, null, 1000, false);

    final JsonList books = new JsonArrayList(lnks.size());

    for (final Lnk lnk : lnks)
    {
      final Obj book = ObjDb.getObj(lnk.lnkDomId);
      if (book == null)
        continue;

      final ObjAttrs entry = new ObjAttrs();
      entry.addAttr("BookId",      lnk.lnkDomId.objId);
      entry.addAttr("DomId",       lnk.lnkDomId.toString());
      entry.addAttr("Title",       book.attrs.getAttr("Title"));
      entry.addAttr("Author",      book.attrs.getAttr("Author"));
      entry.addAttr("Price",       book.attrs.getAttr("Price"));
      entry.addAttr("Condition",   book.attrs.getAttr("Condition"));
      entry.addAttr("SellerName",  book.attrs.getAttr("SellerName"));
      entry.addAttr("PurchasedAt", book.attrs.getAttr("PurchasedAt"));
      books.add(entry.toMap());
    }

    final ObjAttrs out = new ObjAttrs();
    out.addAttr("Books", books);
    outMsg.addResponseBody(opr, out);
  }
}
