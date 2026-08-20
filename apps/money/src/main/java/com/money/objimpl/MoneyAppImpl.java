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
 * LLM-native facade for the Money app.
 *
 * Handles class (money, app) — the app-money entry-point object.
 * Lets the LLM answer "what's my banking role?" and "what are my account
 * balances?" with direct, named operations rather than navigating the
 * profile / myaccounts sub-object graph.
 *
 * Operations: GetProfile(), GetMyAccounts(), GetAccount(AccountDomId),
 *             GetBank(), GetAccounts(), Transfer(Amount,ToActId).
 *
 * Spec: Spec-LLM-Oriented-Msgs.txt — MONEY section.
 */
public class MoneyAppImpl extends ObjImpl
{
  @Override
  public String handleMsg(final String msg, final Obj obj, final String contextPath,
                          final String contextRealPath, final DomatarMsgClient msgClient)
      throws DomatarException
  {
    final JsonMsg inMsg  = new JsonMsg(msg);
    final String  opr    = inMsg.getOperation();
    final JsonMsg outMsg = new JsonMsg();

    if (!hasRights(inMsg, obj, msgClient))
      return notAuthorized(inMsg);

    final DomId appDomId = inMsg.getDstId();

    if ("GetProfile".equals(opr))
      getProfile(opr, outMsg, appDomId);
    else if ("GetMyAccounts".equals(opr))
      getMyAccounts(opr, outMsg, appDomId, msgClient);
    else if ("GetAccount".equals(opr))
      getAccount(opr, inMsg, outMsg, msgClient);
    else if ("GetBank".equals(opr))
      getBank(opr, outMsg, appDomId);
    else if ("GetAccounts".equals(opr))
      getAccounts(opr, outMsg, appDomId, msgClient);
    else if ("Transfer".equals(opr))
      transfer(opr, inMsg, outMsg, appDomId, msgClient);
    else
      return super.handleMsg(msg, obj, contextPath, contextRealPath, msgClient);

    return outMsg.toString();
  }

  @Override
  public boolean hasRights(final JsonMsg inMsg, final Obj obj,
                           final DomatarMsgClient msgClient)
      throws DomatarException
  {
    if (!Auth.isVerified(inMsg))
      return false;
    // Allow access: owner check is deferred to sub-object operations.
    return true;
  }

  // ---------------------------------------------------------------------------

  private void getProfile(final String opr, final JsonMsg outMsg,
                          final DomId appDomId)
      throws DomatarException
  {
    final DomId profileId = new DomId(appDomId.hstId, "money", appDomId.actId, "profile");
    final Obj   profile   = ObjDb.getObj(profileId);

    String role = profile != null ? safeGet(profile.attrs, "Role") : "";
    if (role == null)
      role = "";

    final DomId   bankId  = new DomId(appDomId.hstId, "money", appDomId.actId, "bank");
    final boolean hasBank = ObjDb.getObj(bankId) != null;

    final ObjAttrs out = new ObjAttrs();
    out.addAttr("Role",    role);
    out.addAttr("HasBank", hasBank ? "true" : "false");
    outMsg.addResponseBody(opr, out);
  }

  private void getMyAccounts(final String opr, final JsonMsg outMsg,
                              final DomId appDomId,
                              final DomatarMsgClient msgClient)
      throws DomatarException
  {
    final DomId     myAccountsId = new DomId(appDomId.hstId, "money", appDomId.actId, "myaccounts");
    final List<Lnk> lnks         = LnkDb.getLnks(myAccountsId, "money", "account",
                                                  null, null, 10000, false);

    final JsonList list = new JsonArrayList(lnks.size());
    for (final Lnk lnk : lnks)
    {
      final ObjAttrs entry = new ObjAttrs();
      entry.addAttr("AccountDomId", lnk.lnkDomId.toString());
      entry.addAttr("BankActId",    lnk.lnkDomId != null ? lnk.lnkDomId.actId : null);
      entry.addAttr("BankName",     lnk.lnkObjName);

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
            final String bn = attrs.getAttr("BankName");
            if (b  != null)
              balance = b;
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

  /**
   * Returns balance and metadata for a single account identified by its DomId string.
   * Delegates to AccountImpl.GetAccount via msgClient.
   */
  private void getAccount(final String opr, final JsonMsg inMsg, final JsonMsg outMsg,
                          final DomatarMsgClient msgClient)
      throws DomatarException
  {
    final String accountDomIdStr = inMsg.getAttrs().getAttr("AccountDomId");
    if (accountDomIdStr == null || accountDomIdStr.isEmpty())
    {
      outMsg.addError(opr, "Missing AccountDomId");
      return;
    }

    final DomId   accountId = new DomId(accountDomIdStr);
    final JsonMsg req       = new JsonMsg();
    req.addRequestBody("GetAccount", null);
    req.addClsId("money", "account");

    try
    {
      final JsonMsg resp = msgClient.send(accountId, req);
      if (resp == null)
      {
        outMsg.addError(opr, "Account unavailable");
        return;
      }
      final String err = resp.getError();
      if (err != null && !"".equals(err))
      {
        outMsg.addError(opr, err);
        return;
      }
      final ObjAttrs respAttrs = resp.getAttrs();
      outMsg.addResponseBody(opr, respAttrs != null ? respAttrs : new ObjAttrs());
    }
    catch (Exception e)
    {
      outMsg.addError(opr, "GetAccount error: " + e.getMessage());
    }
  }

  /**
   * Returns the caller's bank details. Only meaningful if the caller has a bank
   * (Role=Bank). Returns an error if no bank object exists.
   */
  private void getBank(final String opr, final JsonMsg outMsg, final DomId appDomId)
      throws DomatarException
  {
    final DomId bankId = new DomId(appDomId.hstId, "money", appDomId.actId, "bank");
    final Obj   bank   = ObjDb.getObj(bankId);

    if (bank == null)
    {
      outMsg.addError(opr, "No bank found; user does not have a bank role");
      return;
    }

    final ObjAttrs out = new ObjAttrs();
    out.addAttr("Name",           safeGet(bank.attrs, "Name"));
    out.addAttr("TotalFunds",     safeGet(bank.attrs, "TotalFunds"));
    out.addAttr("AvailableFunds", safeGet(bank.attrs, "AvailableFunds"));
    outMsg.addResponseBody(opr, out);
  }

  /**
   * Returns all customer accounts at the caller's bank.
   * Only meaningful if the caller has a bank (Role=Bank).
   * Fetches live balances via msgClient for each account.
   */
  private void getAccounts(final String opr, final JsonMsg outMsg,
                           final DomId appDomId, final DomatarMsgClient msgClient)
      throws DomatarException
  {
    final DomId     accountsId = new DomId(appDomId.hstId, "money", appDomId.actId, "accounts");
    final List<Lnk> lnks       = LnkDb.getLnks(accountsId, "money", "account",
                                               null, null, 10000, false);

    final JsonList list = new JsonArrayList(lnks.size());
    for (final Lnk lnk : lnks)
    {
      final ObjAttrs entry = new ObjAttrs();
      entry.addAttr("AccountDomId",  lnk.lnkDomId.toString());
      entry.addAttr("CustomerActId", lnk.val);
      entry.addAttr("CustomerName",  lnk.lnkObjName);

      String balance = "?";
      try
      {
        final JsonMsg req = new JsonMsg();
        req.addRequestBody("GetAccount", null);
        req.addClsId("money", "account");
        final JsonMsg resp = msgClient.send(lnk.lnkDomId, req);
        if (resp != null && !"Failure".equals(resp.getError()))
        {
          final ObjAttrs attrs = resp.getAttrs();
          if (attrs != null)
          {
            final String b = attrs.getAttr("Balance");
            if (b != null)
              balance = b;
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

  /**
   * Transfers funds from the caller's default account to a recipient.
   * Finds the caller's first linked account and delegates to AccountImpl.Transfer.
   */
  private void transfer(final String opr, final JsonMsg inMsg, final JsonMsg outMsg,
                        final DomId appDomId, final DomatarMsgClient msgClient)
      throws DomatarException
  {
    final String amount  = inMsg.getAttrs().getAttr("Amount");
    final String toActId = inMsg.getAttrs().getAttr("ToActId");

    if (amount == null || amount.isEmpty())
    {
      outMsg.addError(opr, "Missing Amount");
      return;
    }
    if (toActId == null || toActId.isEmpty())
    {
      outMsg.addError(opr, "Missing ToActId");
      return;
    }

    final DomId     myAccountsId = new DomId(appDomId.hstId, "money", appDomId.actId, "myaccounts");
    final List<Lnk> lnks         = LnkDb.getLnks(myAccountsId, "money", "account",
                                                  null, null, 1, false);

    if (lnks.isEmpty())
    {
      outMsg.addError(opr, "No accounts found; open a bank account first");
      return;
    }

    final DomId accountId = lnks.get(0).lnkDomId;

    final JsonMsg    req      = new JsonMsg();
    final ObjAttrs   reqAttrs = new ObjAttrs();
    reqAttrs.addAttr("Amount",  amount);
    reqAttrs.addAttr("ToActId", toActId);
    req.addRequestBody("Transfer", reqAttrs);
    req.addClsId("money", "account");

    try
    {
      final JsonMsg resp = msgClient.send(accountId, req);
      if (resp == null)
      {
        outMsg.addError(opr, "Transfer failed: no response");
        return;
      }
      final String err = resp.getError();
      if (err != null && !"".equals(err))
      {
        outMsg.addError(opr, err);
        return;
      }
      final ObjAttrs respAttrs = resp.getAttrs();
      outMsg.addResponseBody(opr, respAttrs != null ? respAttrs : new ObjAttrs());
    }
    catch (Exception e)
    {
      outMsg.addError(opr, "Transfer error: " + e.getMessage());
    }
  }

  // ---------------------------------------------------------------------------

  private static String safeGet(final ObjAttrs attrs, final String key)
  {
    try   { return attrs.getAttr(key); }
    catch (Exception e) { return null; }
  }
}
