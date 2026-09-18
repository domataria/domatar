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
 * Routes Active-container operations to the caller's (canton, contracts) object.
 */
@WebServlet("/ContractsWui/*")
public class ContractsWui extends DomatarServlet
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
    final DomId activeId = new DomId(DomId.subHstId("canton", actId),
                                      "canton", actId, "active");
    final ObjAttrs attrs = new ObjAttrs();

    if ("Sync".equals(opr) || "GetContracts".equals(opr) || "GetLnks".equals(opr))
    {
    }
    else if ("Create".equals(opr))
    {
      String templateId = getParam(req, "TemplateId");
      if (templateId == null || templateId.isEmpty())
        templateId = "Iou";
      put(attrs, "TemplateId", templateId);
      put(attrs, "Issuer", getParam(req, "Issuer"));
      put(attrs, "Owner", getParam(req, "Owner"));
      put(attrs, "Amount", getParam(req, "Amount"));
      put(attrs, "Currency", getParam(req, "Currency"));
    }
    else
    {
      msg.addError(opr, "Unknown action");
      return msg;
    }

    msg.addRequestHead(srcDomId, activeId, context);
    msg.addRequestBody(opr, attrs);
    return msg;
  }

  private static void put(final ObjAttrs attrs, final String name,
                          final String value) throws DomatarException
  {
    if (value != null)
      attrs.addAttr(name, value);
  }
}
