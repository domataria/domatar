/*
 * Copyright (c) 2024 Domatar
 */

package com.money.objimpl;

import java.util.List;

import com.domatar.core.Auth;
import com.domatar.db.LnkDb;
import com.domatar.db.ObjDb;
import com.domatar.util.JsonArrayList;
import com.domatar.util.JsonList;
import com.domatar.util.JsonMsg;
import com.domatar.util.Lnk;
import com.domatar.util.Obj;
import com.domatar.util.ObjAttrs;
import com.domatar.util.ObjImpl;
import com.domatar.util.DomId;
import com.domatar.util.DomatarException;
import com.domatar.util.DomatarMsgClient;

/**
 * Handler for class (money, myaccounts).
 *
 * A customer-side index of all their accounts across all banks.
 * Holds cross-host links to (money, account) objects on bank hosts.
 *
 * hasRights (GetMyAccounts): owner only.
 * hasRights (RegisterAccount): any authenticated user (called by banks).
 *
 * Spec: Spec-Money.txt PART 6.5
 */
public class MyAccountsImpl extends ObjImpl
{
  @Override
  public String handleMsg(final String msg, final Obj obj, final String contextPath,
                          final String contextRealPath, final DomatarMsgClient msgClient)
      throws DomatarException
  {
    final JsonMsg inMsg  = new JsonMsg(msg);
    final String  opr    = inMsg.getOperation();
    final JsonMsg outMsg = new JsonMsg();

    final DomId myAccountsDomId = inMsg.getDstId();

    if ("RegisterAccount".equals(opr))
    {
      // Open to any authenticated user (a bank calling on the customer's behalf).
      if (!Auth.isVerified(inMsg))
        return notAuthorized(inMsg);
      registerAccount(opr, inMsg, outMsg, myAccountsDomId);
    }
    else if ("GetMyAccounts".equals(opr))
    {
      // Owner only.
      if (!hasRights(inMsg, obj, msgClient))
        return notAuthorized(inMsg);
      getMyAccounts(opr, outMsg, myAccountsDomId, msgClient);
    }
    else
    {
      if (!hasRights(inMsg, obj, msgClient))
        return notAuthorized(inMsg);
      return super.handleMsg(msg, obj, contextPath, contextRealPath, msgClient);
    }

    return outMsg.toString();
  }

  @Override
  public boolean hasRights(final JsonMsg inMsg, final Obj obj,
                           final DomatarMsgClient msgClient)
      throws DomatarException
  {
    if (!Auth.isVerified(inMsg))
      return false;
    if (obj == null)
      return false;
    return inMsg.getSrcId().actId.equals(obj.domId.actId);
  }

  // ---------------------------------------------------------------------------

  private void getMyAccounts(final String opr, final JsonMsg outMsg,
                              final DomId myAccountsDomId,
                              final DomatarMsgClient msgClient)
      throws DomatarException
  {
    final List<Lnk> lnks = LnkDb.getLnks(myAccountsDomId, "money", "account",
                                          null, null, 10000, false);

    final JsonList list = new JsonArrayList(lnks.size());
    for (final Lnk lnk : lnks)
    {
      final ObjAttrs entry = new ObjAttrs();
      entry.addAttr("AccountDomId", lnk.lnkDomId.toString());
      entry.addAttr("BankName",     lnk.lnkObjName); // stored in link name

      // Fetch live balance cross-host.
      String balance = "?";
      try
      {
        final JsonMsg getMsg = new JsonMsg();
        getMsg.addRequestBody("GetAccount", null);
        final JsonMsg resp = msgClient.send(lnk.lnkDomId, getMsg);
        if (resp != null && !"Failure".equals(resp.getError()))
        {
          final ObjAttrs attrs = resp.getAttrs();
          if (attrs != null)
          {
            final String b  = attrs.getAttr("Balance");
            if (b != null)
              balance = b;
            final String bn = attrs.getAttr("BankName");
            if (bn != null)
              entry.addAttr("BankName", bn);
          }
        }
      }
      catch (Exception ignored)
      {
      }

      entry.addAttr("Balance", balance);
      list.add(entry.toMap());
    }

    final ObjAttrs out = new ObjAttrs();
    out.addAttr("Accounts", list);
    outMsg.addResponseBody(opr, out);
  }

  private void registerAccount(final String opr, final JsonMsg inMsg,
                                final JsonMsg outMsg, final DomId myAccountsDomId)
      throws DomatarException
  {
    final String accountDomIdStr = inMsg.getAttrs().getAttr("AccountDomId");
    String bankName = inMsg.getAttrs().getAttr("BankName");

    if (accountDomIdStr == null || accountDomIdStr.isEmpty())
    {
      outMsg.addError(opr, "Missing AccountDomId");
      return;
    }
    if (bankName == null)
      bankName = "";

    DomId accountDomId;
    try
    {
      accountDomId = new DomId(accountDomIdStr);
    }
    catch (Exception e)
    {
      outMsg.addError(opr, "Invalid AccountDomId: " + accountDomIdStr);
      return;
    }

    if (LnkDb.getLnk(myAccountsDomId, accountDomId, "money", "account") == null)
    {
      LnkDb.addLnk(new Lnk(myAccountsDomId, accountDomId,
                            "money", "account",
                            bankName, "Money account",
                            "money", "account",
                            bankName, System.currentTimeMillis()));
    }

    final ObjAttrs out = new ObjAttrs();
    out.addAttr("Registered", "True");
    outMsg.addResponseBody(opr, out);
  }
}
