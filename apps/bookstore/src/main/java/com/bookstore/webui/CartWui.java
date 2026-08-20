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
 * Routes Shopping Cart operations to the caller's cart container object.
 *
 * Supported actions: GetCart, AddToCart, RemoveFromCart, BuyCartItems.
 * Spec: Spec-Bookstore.txt PART 7.4
 */
@WebServlet("/CartWui/*")
public class CartWui extends DomatarServlet
{
  private static final long serialVersionUID = 5647382910283746512L;

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

    final String actId  = srcAct.actId;
    final DomId  cartId = new DomId(DomId.subHstId("bookstore", actId), "bookstore", actId, "cart");

    final ObjAttrs attrs = new ObjAttrs();

    if ("GetCart".equals(opr))
    {
      // no additional attrs
    }
    else if ("AddToCart".equals(opr))
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
    else if ("RemoveFromCart".equals(opr))
    {
      attrs.addAttr("ListingDomId", getParam(req, "ListingDomId"));
      attrs.addAttr("CartItemId",   getParam(req, "CartItemId")); // legacy
    }
    else if ("BuyCartItems".equals(opr))
    {
      attrs.addAttr("ListingDomIds", getParam(req, "ListingDomIds"));
      attrs.addAttr("CartItemIds",   getParam(req, "CartItemIds")); // legacy
    }
    else
    {
      msg.addError(opr, "Unknown action");
      return msg;
    }

    msg.addRequestHead(srcDomId, cartId, context);
    msg.addClsId("bookstore", "cart");
    msg.addRequestBody(opr, attrs);

    return msg;
  }
}
