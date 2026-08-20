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
 * Routes Money account operations to a specific (money, account) object.
 *
 * The full DomId of the target account is passed as the "AccountDomId"
 * request parameter.  The account may live on a different host (bank's host).
 *
 * Supported actions: GetAccount, Deposit, Withdraw, Transfer, BankTransfer.
 * Spec: Spec-Money.txt PART 9
 *
 * Note: addClsId() is intentionally NOT called.
 */
@WebServlet("/MoneyAccountWui/*")
public class MoneyAccountWui extends DomatarServlet
{
  private static final long serialVersionUID = 3746501928374650192L;

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

    final String accountDomIdStr = getParam(req, "AccountDomId");
    if (accountDomIdStr == null || accountDomIdStr.isEmpty())
    {
      msg.addError(opr, "Missing AccountDomId");
      return msg;
    }

    DomId accountDomId;
    try
    {
      accountDomId = new DomId(accountDomIdStr);
    }
    catch (Exception e)
    {
      msg.addError(opr, "Invalid AccountDomId: " + accountDomIdStr);
      return msg;
    }

    final ObjAttrs attrs = new ObjAttrs();

    if ("GetAccount".equals(opr))
    {
      // no additional attrs
    }
    else if ("Deposit".equals(opr))
    {
      attrs.addAttr("Amount", getParam(req, "Amount"));
    }
    else if ("Withdraw".equals(opr))
    {
      attrs.addAttr("Amount", getParam(req, "Amount"));
    }
    else if ("Transfer".equals(opr))
    {
      attrs.addAttr("ToCustomerActId", getParam(req, "ToCustomerActId"));
      attrs.addAttr("ToCustomerName",  getParam(req, "ToCustomerName"));
      attrs.addAttr("Amount",          getParam(req, "Amount"));
      attrs.addAttr("Description",     getParam(req, "Description"));
    }
    else if ("BankTransfer".equals(opr))
    {
      attrs.addAttr("DestBankActId", getParam(req, "DestBankActId"));
      attrs.addAttr("Amount",        getParam(req, "Amount"));
    }
    else
    {
      msg.addError(opr, "Unknown action");
      return msg;
    }

    msg.addRequestHead(srcDomId, accountDomId, context);
    msg.addRequestBody(opr, attrs);
    return msg;
  }
}
