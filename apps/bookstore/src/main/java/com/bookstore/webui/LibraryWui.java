/*
 * Copyright (c) 2024 Domatar
 */

package com.bookstore.webui;

import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;

import com.domatar.core.Context;
import com.domatar.servlet.DomatarServlet;
import com.domatar.util.Act;
import com.domatar.util.JsonMsg;
import com.domatar.util.ObjAttrs;
import com.domatar.util.DomId;
import com.domatar.util.DomatarException;
import com.domatar.util.DomatarMsgClient;

/**
 * Routes Library operations to the caller's library container object.
 *
 * Supported actions: BuyBook, GetLibrary, RemoveBook.
 * Spec: Spec-Bookstore.txt PART 7.2
 */
@WebServlet("/LibraryWui/*")
public class LibraryWui extends DomatarServlet
{
  private static final long serialVersionUID = 9182736450192837461L;

  @Override
  protected JsonMsg getMsg(final HttpServletRequest req,
                           final DomId srcDomId,
                           final Context context,
                           final Act srcAct,
                           final DomatarMsgClient msgClient) throws DomatarException
  {
    final JsonMsg msg = new JsonMsg();
    final String  opr = getParam(req, "Action");

    if (opr == null)
    {
      msg.addError(opr, "Missing action");
      return msg;
    }

    final String actId     = srcAct.actId;
    final DomId  libraryId = new DomId(DomId.subHstId("bookstore", actId), "bookstore", actId, "library");

    final ObjAttrs attrs = new ObjAttrs();

    if ("BuyBook".equals(opr))
    {
      attrs.addAttr("ListingDomId", getParam(req, "ListingDomId"));
      attrs.addAttr("Title",        getParam(req, "Title"));
      attrs.addAttr("Author",       getParam(req, "Author"));
      attrs.addAttr("ISBN",         getParam(req, "ISBN"));
      attrs.addAttr("Price",        getParam(req, "Price"));
      attrs.addAttr("Condition",    getParam(req, "Condition"));
      attrs.addAttr("Description",  getParam(req, "Description"));
      attrs.addAttr("SellerActId",  getParam(req, "SellerActId"));
      attrs.addAttr("SellerName",   getParam(req, "SellerName"));
    }
    else if ("GetLibrary".equals(opr))
    {
      // no additional attrs
    }
    else if ("RemoveBook".equals(opr))
    {
      attrs.addAttr("BookId", getParam(req, "BookId"));
    }
    else
    {
      msg.addError(opr, "Unknown action");
      return msg;
    }

    msg.addRequestHead(srcDomId, libraryId, context);
    msg.addClsId("bookstore", "library");
    msg.addRequestBody(opr, attrs);

    return msg;
  }
}
