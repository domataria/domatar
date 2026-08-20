/*
 * Copyright (c) 2024 Domatar
 */

package com.money.webui;

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
 * Routes Money profile operations to the caller's (money, profile) object.
 *
 * Supported actions: GetProfile, SetRole.
 * Spec: Spec-Money.txt PART 9
 *
 * Note: addClsId() is intentionally NOT called to avoid the obj=null
 * dispatch issue (Spec-Spreadsheet.txt PART 9.3).
 */
@WebServlet("/MoneyProfileWui/*")
public class MoneyProfileWui extends DomatarServlet
{
  private static final long serialVersionUID = 4812736450192837465L;

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

    final String   actId     = srcAct.actId;
    final DomId    profileId = new DomId(DomId.subHstId("money", actId), "money", actId, "profile");
    final ObjAttrs attrs     = new ObjAttrs();

    if ("GetProfile".equals(opr))
    {
      // no additional attrs
    }
    else if ("SetRole".equals(opr))
    {
      attrs.addAttr("Role",       getParam(req, "Role"));
      attrs.addAttr("BankName",   getParam(req, "BankName"));
      attrs.addAttr("TotalFunds", getParam(req, "TotalFunds"));
    }
    else
    {
      msg.addError(opr, "Unknown action");
      return msg;
    }

    msg.addRequestHead(srcDomId, profileId, context);
    msg.addRequestBody(opr, attrs);
    return msg;
  }
}
