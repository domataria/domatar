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
 * Routes Money accounts-container operations to the caller's (money, accounts)
 * object.
 *
 * Supported actions: GetAccounts, CreateAccount.
 * (Credit is called internally by AccountImpl.BankTransfer, not from the UI.)
 * Spec: Spec-Money.txt PART 9
 *
 * Note: addClsId() is intentionally NOT called.
 */
@WebServlet("/MoneyAccountsWui/*")
public class MoneyAccountsWui extends DomatarServlet
{
  private static final long serialVersionUID = 1928374650192837465L;

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

    final String   actId      = srcAct.actId;
    final DomId    accountsId = new DomId(DomId.subHstId("money", actId), "money", actId, "accounts");
    final ObjAttrs attrs      = new ObjAttrs();

    if ("GetAccounts".equals(opr))
    {
      // no additional attrs
    }
    else if ("CreateAccount".equals(opr))
    {
      attrs.addAttr("CustomerActId",  getParam(req, "CustomerActId"));
      attrs.addAttr("CustomerName",   getParam(req, "CustomerName"));
      attrs.addAttr("InitialBalance", getParam(req, "InitialBalance"));
    }
    else
    {
      msg.addError(opr, "Unknown action");
      return msg;
    }

    msg.addRequestHead(srcDomId, accountsId, context);
    msg.addRequestBody(opr, attrs);
    return msg;
  }
}
