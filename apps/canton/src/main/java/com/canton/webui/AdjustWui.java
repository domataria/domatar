/*
 * Copyright (c) 2024 Domatar
 */

package com.canton.webui;

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
 * Routes desk probes through this user's Canton facade, so undo is
 * sent by the same object that forwarded the adjustment.
 */
@WebServlet("/AdjustWui/*")
public class AdjustWui extends DomatarServlet
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
        && !"AdjustHolding".equals(opr) && !"Undo".equals(opr)
        && !"ApplyTwice".equals(opr) && !"Echo".equals(opr) && !"Refill".equals(opr))
    {
      msg.addError(opr, "Unknown action");
      return msg;
    }

    final String actId = srcAct.actId;
    final DomId app = new DomId(DomId.subHstId("canton", actId),
        "canton", actId, "app-canton");
    final ObjAttrs attrs = new ObjAttrs();

    put(attrs, "ContractId", getParam(req, "ContractId"));
    put(attrs, "Delta", getParam(req, "Delta"));
    msg.addRequestHead(srcDomId, app, context);
    msg.addClsId("canton", "app");
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
