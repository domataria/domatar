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
 * Routes Money myaccounts operations to the caller's (money, myaccounts) object.
 *
 * Supported actions: GetMyAccounts.
 * (RegisterAccount is called internally by AccountsImpl, not directly from UI.)
 * Spec: Spec-Money.txt PART 9
 *
 * Note: addClsId() is intentionally NOT called.
 */
@WebServlet("/MoneyMyAccountsWui/*")
public class MoneyMyAccountsWui extends DomatarServlet
{
  private static final long serialVersionUID = 6501928374650192837L;

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

    final String   actId        = srcAct.actId;
    final DomId    myAccountsId = new DomId(DomId.subHstId("money", actId), "money", actId, "myaccounts");
    final ObjAttrs attrs        = new ObjAttrs();

    if ("GetMyAccounts".equals(opr))
    {
      // no additional attrs
    }
    else
    {
      msg.addError(opr, "Unknown action");
      return msg;
    }

    msg.addRequestHead(srcDomId, myAccountsId, context);
    msg.addRequestBody(opr, attrs);
    return msg;
  }
}
