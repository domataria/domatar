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
 * Routes Iou handle operations to a (canton, iou) object.
 */
@WebServlet("/IouWui/*")
public class IouWui extends DomatarServlet
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

    final String contractDomIdStr = getParam(req, "ContractDomId");
    if (contractDomIdStr == null || contractDomIdStr.isEmpty())
    {
      msg.addError(opr, "Missing ContractDomId");
      return msg;
    }

    final DomId dst;
    try
    {
      dst = new DomId(contractDomIdStr);
    }
    catch (final Exception e)
    {
      msg.addError(opr, "Invalid ContractDomId: " + contractDomIdStr);
      return msg;
    }

    final ObjAttrs attrs = new ObjAttrs();

    if ("GetIou".equals(opr) || "GetObj".equals(opr) || "GetLnks".equals(opr)
        || "Settle".equals(opr) || "Archive".equals(opr))
    {
    }
    else if ("Transfer".equals(opr))
    {
      String newOwner = getParam(req, "NewOwner");
      if (newOwner == null)
        newOwner = "";
      attrs.addAttr("NewOwner", newOwner);
    }
    else
    {
      msg.addError(opr, "Unknown action");
      return msg;
    }

    msg.addRequestHead(srcDomId, dst, context);
    msg.addRequestBody(opr, attrs);
    return msg;
  }
}
