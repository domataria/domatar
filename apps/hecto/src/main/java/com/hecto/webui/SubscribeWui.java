/*
 * Copyright (c) 2024 Domatar
 */

package com.hecto.webui;

import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;

import com.domatar.core.Context;
import com.domatar.servlet.DomatarServlet;
import com.domatar.util.Act;
import com.domatar.util.DomId;
import com.domatar.util.DomatarException;
import com.domatar.util.DomatarMsgClient;
import com.domatar.util.JsonMsg;
import com.domatar.util.ObjAttrs;

/**
 * Routes subscriptions through this user's Hecto facade, so undo is
 * sent by the same object that forwarded Subscribe.
 */
@WebServlet("/SubscribeWui/*")
public class SubscribeWui extends DomatarServlet
{
  private static final long serialVersionUID = 1L;

  @Override
  protected JsonMsg getMsg(final HttpServletRequest req,
                           final DomId srcDomId,
                           final Context context,
                           final Act srcAct,
                           final DomatarMsgClient msgClient) throws DomatarException
  {
    final JsonMsg msg = new JsonMsg();
    final String opr = getParam(req, "Action");

    if (opr == null)
    {
      msg.addError(opr, "Missing action");
      return msg;
    }

    if (!"Quote".equals(opr) && !"Credits".equals(opr) && !"GetBooking".equals(opr)
        && !"Subscribe".equals(opr) && !"Undo".equals(opr)
        && !"ApplyTwice".equals(opr) && !"Echo".equals(opr) && !"Refill".equals(opr))
    {
      msg.addError(opr, "Unknown action");
      return msg;
    }

    final String actId = srcAct.actId;
    final DomId app = new DomId(DomId.subHstId("hecto", actId),
        "hecto", actId, "app-hecto");
    final ObjAttrs attrs = new ObjAttrs();

    if ("Subscribe".equals(opr) || "ApplyTwice".equals(opr) || "Quote".equals(opr))
      put(attrs, "ContractId", getParam(req, "ContractId"));
    if ("Subscribe".equals(opr) || "ApplyTwice".equals(opr))
      put(attrs, "Delta", getParam(req, "Delta"));
    msg.addRequestHead(srcDomId, app, context);
    msg.addClsId("hecto", "app");
    msg.addRequestBody(opr, attrs);
    return msg;
  }

  private static void put(final ObjAttrs attrs, final String name, final String value)
      throws DomatarException
  {
    if (value != null && !value.isEmpty())
      attrs.addAttr(name, value);
  }
}
