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
 * Routes ForSale operations to the caller's forsale container object.
 *
 * Supported actions: ListBook, DelistBook, GetForSale, UpdatePrice.
 * Spec: Spec-Bookstore.txt PART 7.1
 */
@WebServlet("/ForSaleWui/*")
public class ForSaleWui extends DomatarServlet
{
  private static final long serialVersionUID = 2837461920374651092L;

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

    final String actId    = srcAct.actId;
    final DomId  forSaleId = new DomId(DomId.subHstId("bookstore", actId), "bookstore", actId, "forsale");

    final ObjAttrs attrs = new ObjAttrs();

    if ("ListBook".equals(opr))
    {
      attrs.addAttr("Title",       getParam(req, "Title"));
      attrs.addAttr("Author",      getParam(req, "Author"));
      attrs.addAttr("ISBN",        getParam(req, "ISBN"));
      attrs.addAttr("Price",       getParam(req, "Price"));
      attrs.addAttr("Condition",   getParam(req, "Condition"));
      attrs.addAttr("Description", getParam(req, "Description"));
      attrs.addAttr("SellerActId", actId);
      attrs.addAttr("SellerName",  srcAct.usrName);
    }
    else if ("DelistBook".equals(opr))
    {
      attrs.addAttr("ListingId", getParam(req, "ListingId"));
    }
    else if ("UpdatePrice".equals(opr))
    {
      attrs.addAttr("ListingId", getParam(req, "ListingId"));
      attrs.addAttr("Price",       getParam(req, "Price"));
    }
    else if ("GetForSale".equals(opr))
    {
      // no additional attrs
    }
    else
    {
      msg.addError(opr, "Unknown action");
      return msg;
    }

    msg.addRequestHead(srcDomId, forSaleId, context);
    msg.addClsId("bookstore", "forsale");
    msg.addRequestBody(opr, attrs);

    return msg;
  }
}
