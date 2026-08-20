/*
 * Copyright (c) 2024 Domatar
 */

package com.money.objimpl;

import java.math.BigDecimal;
import java.util.List;

import com.domatar.core.Auth;
import com.domatar.db.ActDb;
import com.domatar.db.LnkDb;
import com.domatar.db.ObjDb;
import com.domatar.util.Act;
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
 * Handler for class (money, accounts).
 *
 * The accounts container lives on the bank's host and holds links to
 * all customer account objects at this bank.
 *
 * hasRights: bank user only for most operations.
 * hasRights for Credit: any authenticated Domatar user (called cross-host
 * by another bank's BankTransfer operation).
 *
 * Spec: Spec-Money.txt PART 6.3
 */
public class AccountsImpl extends ObjImpl
{
  @Override
  public String handleMsg(final String msg, final Obj obj, final String contextPath,
                          final String contextRealPath, final DomatarMsgClient msgClient)
      throws DomatarException
  {
    final JsonMsg inMsg  = new JsonMsg(msg);
    final String  opr    = inMsg.getOperation();
    final JsonMsg outMsg = new JsonMsg();

    final DomId accountsDomId = inMsg.getDstId();

    if ("Credit".equals(opr))
    {
      // Credit is open to any authenticated user (cross-bank call).
      if (!Auth.isVerified(inMsg))
        return notAuthorized(inMsg);
      credit(opr, inMsg, outMsg, accountsDomId, msgClient);
    }
    else
    {
      // All other operations: bank user only.
      if (!hasRights(inMsg, obj, msgClient))
        return notAuthorized(inMsg);

      if ("GetAccounts".equals(opr))
        getAccounts(opr, outMsg, accountsDomId);
      else if ("CreateAccount".equals(opr))
        createAccount(opr, inMsg, outMsg, accountsDomId, msgClient);
      else
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
    // Only the bank user (actId == accounts.actId == bankActId) may access.
    return inMsg.getSrcId().actId.equals(obj.domId.actId);
  }

  // ---------------------------------------------------------------------------

  private void getAccounts(final String opr, final JsonMsg outMsg,
                            final DomId accountsDomId)
      throws DomatarException
  {
    final List<Lnk> lnks = LnkDb.getLnks(accountsDomId, "money", "account",
                                          null, null, 10000, false);

    final JsonList list = new JsonArrayList(lnks.size());
    for (final Lnk lnk : lnks)
    {
      final Obj acct = ObjDb.getObj(lnk.lnkDomId);
      if (acct == null)
        continue;

      final ObjAttrs entry = new ObjAttrs();
      entry.addAttr("AccountDomId",  lnk.lnkDomId.toString());
      entry.addAttr("CustomerActId", acct.attrs.getAttr("CustomerActId"));
      entry.addAttr("CustomerName",  acct.attrs.getAttr("CustomerName"));
      entry.addAttr("Balance",       acct.attrs.getAttr("Balance"));
      list.add(entry.toMap());
    }

    final ObjAttrs out = new ObjAttrs();
    out.addAttr("Accounts", list);
    outMsg.addResponseBody(opr, out);
  }

  private void createAccount(final String opr, final JsonMsg inMsg,
                              final JsonMsg outMsg, final DomId accountsDomId,
                              final DomatarMsgClient msgClient)
      throws DomatarException
  {
    final ObjAttrs in          = inMsg.getAttrs();
    String customerActId = in.getAttr("CustomerActId");
    String customerName  = in.getAttr("CustomerName");
    String initialBalStr = in.getAttr("InitialBalance");

    if (customerActId == null || customerActId.isEmpty())
    {
      outMsg.addError(opr, "Missing CustomerActId");
      return;
    }

    // Resolve usrId → actId.
    final Act custAct = ActDb.getActByUsrId(customerActId);
    if (custAct == null)
    {
      outMsg.addError(opr, "User not found: " + customerActId);
      return;
    }
    if (customerName == null || customerName.isEmpty())
      customerName = custAct.usrName;
    customerActId = custAct.actId;

    if (customerName == null || customerName.isEmpty())
      customerName = customerActId;
    if (initialBalStr == null || initialBalStr.isEmpty())
      initialBalStr = "0.00";

    BigDecimal initialBal;
    try
    {
      initialBal = new BigDecimal(initialBalStr);
    }
    catch (NumberFormatException e)
    {
      outMsg.addError(opr, "InitialBalance must be a valid decimal number");
      return;
    }
    if (initialBal.compareTo(BigDecimal.ZERO) < 0)
    {
      outMsg.addError(opr, "InitialBalance cannot be negative");
      return;
    }

    final String bankActId = accountsDomId.actId;
    final String hstId     = accountsDomId.hstId;   // money~<bankActId>

    final DomId acctDomId = new DomId(hstId, "money", customerActId,
                                      "acct-" + customerActId);
    if (ObjDb.getObj(acctDomId) != null)
    {
      outMsg.addError(opr, "Account already exists for " + customerActId);
      return;
    }

    final DomId bankId = new DomId(hstId, "money", bankActId, "bank");
    final Obj   bank   = ObjDb.getObj(bankId);
    if (bank == null)
    {
      outMsg.addError(opr, "Bank object not found");
      return;
    }

    final BigDecimal available = BankImpl.dec(bank.attrs.getAttr("AvailableFunds"));
    if (initialBal.compareTo(available) > 0)
    {
      outMsg.addError(opr, "InitialBalance exceeds AvailableFunds ("
                     + available.toPlainString() + ")");
      return;
    }

    bank.attrs.addAttr("AvailableFunds",
                       available.subtract(initialBal).toPlainString());
    ObjDb.modifyObj(bank);

    // hstId  = money~<bankActId>  (bank's host)
    // actId  = customerActId       (customer is the logical owner)
    final ObjAttrs acctAttrs = new ObjAttrs();
    acctAttrs.addAttr("CustomerActId", customerActId);
    acctAttrs.addAttr("CustomerName",  customerName);
    acctAttrs.addAttr("Balance",       initialBal.toPlainString());
    acctAttrs.addAttr("BankActId",     bankActId);
    acctAttrs.addAttr("BankName",      bank.attrs.getAttr("Name"));
    ObjDb.addObj(new Obj(acctDomId, "money", "account",
                         customerName, "Money account", acctAttrs));

    LnkDb.addLnk(new Lnk(accountsDomId, acctDomId,
                          "money", "account",
                          customerName, "Money account",
                          "money", "account",
                          customerActId, System.currentTimeMillis()));

    // Fire-and-forget: notify customer's myaccounts.
    sendRegisterAccount(acctDomId, bank.attrs.getAttr("Name"),
                        customerActId, msgClient);

    final ObjAttrs out = new ObjAttrs();
    out.addAttr("AccountDomId", acctDomId.toString());
    outMsg.addResponseBody(opr, out);
  }

  /**
   * Credit — called cross-host by another bank's BankTransfer.
   * Finds or creates the customer's account at this bank and adds Amount.
   * Does NOT touch bank.AvailableFunds (funds arrive from outside).
   */
  private void credit(final String opr, final JsonMsg inMsg, final JsonMsg outMsg,
                      final DomId accountsDomId, final DomatarMsgClient msgClient)
      throws DomatarException
  {
    final ObjAttrs in    = inMsg.getAttrs();
    String customerActId = in.getAttr("CustomerActId");
    String customerName  = in.getAttr("CustomerName");
    final String amountStr = in.getAttr("Amount");

    if (customerActId == null || customerActId.isEmpty()
        || amountStr == null  || amountStr.isEmpty())
    {
      outMsg.addError(opr, "Missing CustomerActId or Amount");
      return;
    }

    BigDecimal amount;
    try
    {
      amount = new BigDecimal(amountStr);
    }
    catch (NumberFormatException e)
    {
      outMsg.addError(opr, "Amount must be a valid decimal number");
      return;
    }
    if (amount.compareTo(BigDecimal.ZERO) <= 0)
    {
      outMsg.addError(opr, "Amount must be greater than zero");
      return;
    }

    // Resolve usrId → actId (the caller may have passed a usrId).
    final Act custAct = ActDb.getActByUsrId(customerActId);
    if (custAct == null)
    {
      outMsg.addError(opr, "User not found: " + customerActId);
      return;
    }
    if (customerName == null || customerName.isEmpty())
      customerName = custAct.usrName;
    customerActId = custAct.actId;

    if (customerName == null || customerName.isEmpty())
      customerName = customerActId;

    final String bankActId  = accountsDomId.actId;
    final String hstId      = accountsDomId.hstId;

    final DomId acctDomId = new DomId(hstId, "money", customerActId,
                                      "acct-" + customerActId);
    Obj acct = ObjDb.getObj(acctDomId);

    if (acct == null)
    {
      final DomId   bankId   = new DomId(hstId, "money", bankActId, "bank");
      final Obj     bank     = ObjDb.getObj(bankId);
      final String  bankName = (bank != null) ? bank.attrs.getAttr("Name") : bankActId;

      final ObjAttrs acctAttrs = new ObjAttrs();
      acctAttrs.addAttr("CustomerActId", customerActId);
      acctAttrs.addAttr("CustomerName",  customerName);
      acctAttrs.addAttr("Balance",       "0.00");
      acctAttrs.addAttr("BankActId",     bankActId);
      acctAttrs.addAttr("BankName",      bankName);
      acct = new Obj(acctDomId, "money", "account",
                     customerName, "Money account", acctAttrs);
      ObjDb.addObj(acct);

      LnkDb.addLnk(new Lnk(accountsDomId, acctDomId,
                            "money", "account",
                            customerName, "Money account",
                            "money", "account",
                            customerActId, System.currentTimeMillis()));

      sendRegisterAccount(acctDomId, bankName, customerActId, msgClient);
    }

    final BigDecimal newBalance = BankImpl.dec(acct.attrs.getAttr("Balance"))
                                          .add(amount);
    acct.attrs.addAttr("Balance", newBalance.toPlainString());
    ObjDb.modifyObj(acct);

    final ObjAttrs out = new ObjAttrs();
    out.addAttr("Balance", newBalance.toPlainString());
    outMsg.addResponseBody(opr, out);
  }

  // ---------------------------------------------------------------------------

  /**
   * Fire-and-forget: tell the customer's myaccounts about this new account.
   * Errors are silently swallowed — the customer may not have Money installed.
   */
  static void sendRegisterAccount(final DomId acctDomId, final String bankName,
                                   final String customerActId,
                                   final DomatarMsgClient msgClient)
  {
    try
    {
      final DomId    myAccountsDomId = new DomId(DomId.subHstId("money", customerActId),
                                                  "money", customerActId, "myaccounts");
      final ObjAttrs regAttrs        = new ObjAttrs();
      regAttrs.addAttr("AccountDomId", acctDomId.toString());
      regAttrs.addAttr("BankName",     bankName != null ? bankName : "");

      final JsonMsg regMsg = new JsonMsg();
      regMsg.addRequestBody("RegisterAccount", regAttrs);
      msgClient.send(myAccountsDomId, regMsg);
    }
    catch (Exception ignored)
    {
      /* fire-and-forget */
    }
  }
}
