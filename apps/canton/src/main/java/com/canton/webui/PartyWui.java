/*
 * Copyright (c) 2024 Domatar
 */

package com.canton.webui;

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
 * Routes Party operations to the caller's (canton, party) object.
 */
@WebServlet("/PartyWui/*")
public class PartyWui extends DomatarServlet
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

    final String actId = srcAct.actId;
    final DomId partyId = new DomId(DomId.subHstId("canton", actId),
                                     "canton", actId, "party");
    final ObjAttrs attrs = new ObjAttrs();

    if ("GetParty".equals(opr))
    {
    }
    else if ("BindParty".equals(opr))
    {
      String party = getParam(req, "PartyId");
      if (party == null)
        party = "";
      attrs.addAttr("PartyId", party);
    }
    else
    {
      msg.addError(opr, "Unknown action");
      return msg;
    }

    msg.addRequestHead(srcDomId, partyId, context);
    msg.addRequestBody(opr, attrs);
    return msg;
  }
}
