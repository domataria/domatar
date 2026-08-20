/*
 * Copyright (c) 2024 Domatar
 */

package com.money.objimpl;

import java.math.BigDecimal;

import com.domatar.core.Auth;
import com.domatar.db.ActDb;
import com.domatar.db.LnkDb;
import com.domatar.db.ObjDb;
import com.domatar.util.Act;
import com.domatar.util.JsonMsg;
import com.domatar.util.Lnk;
import com.domatar.util.Obj;
import com.domatar.util.ObjAttrs;
import com.domatar.util.ObjImpl;
import com.domatar.util.DomId;
import com.domatar.util.DomatarException;
import com.domatar.util.DomatarMsgClient;

/**
 * Handler for class (money, account).
 *
 * THE CRITICAL OBJECT: lives on the bank's host (hstId = money~<bankActId>)
 * but its actId is the customer's actId.
 *
 * hasRights: granted to EITHER
 *   a) the owning customer  (caller.actId == account.actId == customerActId)
 *   b) the hosting bank     (caller.actId == hstId.substring("money~".length()))
 * Both require Auth.isVerified.
 *
 * Individual operations further restrict who may call them (see Spec Part 7).
 * Spec: Spec-Money.txt PART 6.4
 */
public class AccountImpl extends ObjImpl
{
  private static final String MONEY_PREFIX = "money" + DomId.HOST_SEP;

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

    final DomId acctId = inMsg.getDstId();

    if ("GetAccount".equals(opr))
      getAccount(opr, outMsg, acctId);
    else if ("Deposit".equals(opr))
      deposit(opr, inMsg, outMsg, acctId, obj);
    else if ("Withdraw".equals(opr))
      withdraw(opr, inMsg, outMsg, acctId, obj);
    else if ("Transfer".equals(opr))
      transfer(opr, inMsg, outMsg, acctId, msgClient);
    else if ("BankTransfer".equals(opr))
      bankTransfer(opr, inMsg, outMsg, acctId, msgClient);
    else
      return super.handleMsg(msg, obj, contextPath, contextRealPath, msgClient);

    return outMsg.toString();
  }

  /**
   * Dual-party hasRights: customer (actId match) OR hosting bank (hstId match).
   */
  @Override
  public boolean hasRights(final JsonMsg inMsg, final Obj obj,
                           final DomatarMsgClient msgClient)
      throws DomatarException
  {
    if (!Auth.isVerified(inMsg))
      return false;
    if (obj == null)
      return false;
    final String callerActId   = inMsg.getSrcId().actId;
    final String customerActId = obj.domId.actId;
    final String bankActId     = obj.domId.hstId.startsWith(MONEY_PREFIX)
                                 ? obj.domId.hstId.substring(MONEY_PREFIX.length())
                                 : "";
    return callerActId.equals(customerActId) || callerActId.equals(bankActId);
  }

  // ---------------------------------------------------------------------------

  private void getAccount(final String opr, final JsonMsg outMsg, final DomId acctId)
      throws DomatarException
  {
    final Obj acct = ObjDb.getObj(acctId);
    if (acct == null)
    {
      outMsg.addError(opr, "Account not found");
      return;
    }
    final ObjAttrs out = new ObjAttrs();
    out.addAttr("CustomerActId", acct.attrs.getAttr("CustomerActId"));
    out.addAttr("CustomerName",  acct.attrs.getAttr("CustomerName"));
    out.addAttr("Balance",       acct.attrs.getAttr("Balance"));
    out.addAttr("BankActId",     acct.attrs.getAttr("BankActId"));
    out.addAttr("BankName",      acct.attrs.getAttr("BankName"));
    outMsg.addResponseBody(opr, out);
  }

  /** Bank deposits funds into a customer account. Bank-only operation. */
  private void deposit(final String opr, final JsonMsg inMsg, final JsonMsg outMsg,
                       final DomId acctId, final Obj acctObj)
      throws DomatarException
  {
    if (!isBankCaller(inMsg, acctId))
    {
      outMsg.addError(opr, "Only the bank may deposit funds");
      return;
    }
    final BigDecimal amount = parsePositiveAmount(opr, inMsg, outMsg);
    if (amount == null)
      return;

    final Obj acct = ObjDb.getObj(acctId);
    if (acct == null)
    {
      outMsg.addError(opr, "Account not found");
      return;
    }

    final BigDecimal newBalance = BankImpl.dec(acct.attrs.getAttr("Balance")).add(amount);
    acct.attrs.addAttr("Balance", newBalance.toPlainString());
    ObjDb.modifyObj(acct);

    adjustBankAvailable(acctId, amount.negate());

    final ObjAttrs out = new ObjAttrs();
    out.addAttr("Balance", newBalance.toPlainString());
    outMsg.addResponseBody(opr, out);
  }

  /** Bank withdraws funds from a customer account. Bank-only operation. */
  private void withdraw(final String opr, final JsonMsg inMsg, final JsonMsg outMsg,
                        final DomId acctId, final Obj acctObj)
      throws DomatarException
  {
    if (!isBankCaller(inMsg, acctId))
    {
      outMsg.addError(opr, "Only the bank may withdraw funds");
      return;
    }
    final BigDecimal amount = parsePositiveAmount(opr, inMsg, outMsg);
    if (amount == null)
      return;

    final Obj acct = ObjDb.getObj(acctId);
    if (acct == null)
    {
      outMsg.addError(opr, "Account not found");
      return;
    }

    final BigDecimal balance = BankImpl.dec(acct.attrs.getAttr("Balance"));
    if (amount.compareTo(balance) > 0)
    {
      outMsg.addError(opr, "Insufficient funds (balance: " + balance.toPlainString() + ")");
      return;
    }

    final BigDecimal newBalance = balance.subtract(amount);
    acct.attrs.addAttr("Balance", newBalance.toPlainString());
    ObjDb.modifyObj(acct);

    // Add back to bank's AvailableFunds.
    adjustBankAvailable(acctId, amount);

    final ObjAttrs out = new ObjAttrs();
    out.addAttr("Balance", newBalance.toPlainString());
    outMsg.addResponseBody(opr, out);
  }

  /**
   * Customer pays another customer at the same bank.
   * Auto-creates the payee's account if it doesn't yet exist.
   * Customer-only operation.
   */
  private void transfer(final String opr, final JsonMsg inMsg, final JsonMsg outMsg,
                        final DomId acctId, final DomatarMsgClient msgClient)
      throws DomatarException
  {
    if (!isCustomerCaller(inMsg, acctId))
    {
      outMsg.addError(opr, "Only the account owner may transfer funds");
      return;
    }
    final BigDecimal amount = parsePositiveAmount(opr, inMsg, outMsg);
    if (amount == null)
      return;

    final ObjAttrs in            = inMsg.getAttrs();
    String toCustomerActId = in.getAttr("ToCustomerActId");
    String toCustomerName  = in.getAttr("ToCustomerName");
    if (toCustomerActId == null || toCustomerActId.isEmpty())
    {
      outMsg.addError(opr, "Missing ToCustomerActId");
      return;
    }

    // Resolve usrId → actId: users type their login usrId in the Pay form,
    // but accounts and hosts are keyed on actId.
    final Act toAct = ActDb.getActByUsrId(toCustomerActId);
    if (toAct == null)
    {
      outMsg.addError(opr, "User not found: " + toCustomerActId);
      return;
    }
    if (toCustomerName == null || toCustomerName.isEmpty())
      toCustomerName = toAct.usrName;
    toCustomerActId = toAct.actId;

    if (toCustomerName == null || toCustomerName.isEmpty())
      toCustomerName = toCustomerActId;

    final Obj acct = ObjDb.getObj(acctId);
    if (acct == null)
    {
      outMsg.addError(opr, "Account not found");
      return;
    }

    final BigDecimal fromBalance = BankImpl.dec(acct.attrs.getAttr("Balance"));
    if (amount.compareTo(fromBalance) > 0)
    {
      outMsg.addError(opr, "Insufficient funds (balance: " + fromBalance.toPlainString() + ")");
      return;
    }

    // Locate or create destination account (same host as source).
    final String hstId     = acctId.hstId;
    final String bankActId = hstId.startsWith(MONEY_PREFIX)
                             ? hstId.substring(MONEY_PREFIX.length()) : "";
    final DomId  dstAcctId = new DomId(hstId, "money", toCustomerActId,
                                       "acct-" + toCustomerActId);
    Obj dstAcct = ObjDb.getObj(dstAcctId);

    if (dstAcct == null)
    {
      final DomId   bankId   = new DomId(hstId, "money", bankActId, "bank");
      final Obj     bank     = ObjDb.getObj(bankId);
      final String  bankName = (bank != null) ? bank.attrs.getAttr("Name") : bankActId;

      final ObjAttrs dstAttrs = new ObjAttrs();
      dstAttrs.addAttr("CustomerActId", toCustomerActId);
      dstAttrs.addAttr("CustomerName",  toCustomerName);
      dstAttrs.addAttr("Balance",       "0.00");
      dstAttrs.addAttr("BankActId",     bankActId);
      dstAttrs.addAttr("BankName",      bankName);
      dstAcct = new Obj(dstAcctId, "money", "account",
                        toCustomerName, "Money account", dstAttrs);
      ObjDb.addObj(dstAcct);

      final DomId accountsDomId = new DomId(hstId, "money", bankActId, "accounts");
      LnkDb.addLnk(new Lnk(accountsDomId, dstAcctId,
                            "money", "account",
                            toCustomerName, "Money account",
                            "money", "account",
                            toCustomerActId, System.currentTimeMillis()));

      AccountsImpl.sendRegisterAccount(dstAcctId, bankName,
                                       toCustomerActId, msgClient);
    }

    final BigDecimal newFromBalance = fromBalance.subtract(amount);
    acct.attrs.addAttr("Balance", newFromBalance.toPlainString());
    ObjDb.modifyObj(acct);

    final BigDecimal newToBalance = BankImpl.dec(dstAcct.attrs.getAttr("Balance"))
                                            .add(amount);
    dstAcct.attrs.addAttr("Balance", newToBalance.toPlainString());
    ObjDb.modifyObj(dstAcct);
    // bank.AvailableFunds is unchanged (internal transfer).

    final ObjAttrs out = new ObjAttrs();
    out.addAttr("FromBalance", newFromBalance.toPlainString());
    out.addAttr("ToBalance",   newToBalance.toPlainString());
    outMsg.addResponseBody(opr, out);
  }

  /**
   * Customer moves money from this bank to their account at another bank.
   * Sends a Credit message cross-host to the destination bank's accounts object.
   * Customer-only operation.
   */
  private void bankTransfer(final String opr, final JsonMsg inMsg, final JsonMsg outMsg,
                             final DomId acctId, final DomatarMsgClient msgClient)
      throws DomatarException
  {
    if (!isCustomerCaller(inMsg, acctId))
    {
      outMsg.addError(opr, "Only the account owner may perform a bank transfer");
      return;
    }
    final BigDecimal amount = parsePositiveAmount(opr, inMsg, outMsg);
    if (amount == null)
      return;

    final String destBankActId = inMsg.getAttrs().getAttr("DestBankActId");
    if (destBankActId == null || destBankActId.isEmpty())
    {
      outMsg.addError(opr, "Missing DestBankActId");
      return;
    }

    final Obj acct = ObjDb.getObj(acctId);
    if (acct == null)
    {
      outMsg.addError(opr, "Account not found");
      return;
    }

    final BigDecimal balance = BankImpl.dec(acct.attrs.getAttr("Balance"));
    if (amount.compareTo(balance) > 0)
    {
      outMsg.addError(opr, "Insufficient funds (balance: " + balance.toPlainString() + ")");
      return;
    }

    final BigDecimal newBalance = balance.subtract(amount);
    acct.attrs.addAttr("Balance", newBalance.toPlainString());
    ObjDb.modifyObj(acct);

    // Add back to source bank's AvailableFunds.
    adjustBankAvailable(acctId, amount);

    // Send Credit to the destination bank's accounts object.
    final DomId    destAccountsDomId = new DomId(DomId.subHstId("money", destBankActId),
                                                  "money", destBankActId, "accounts");
    final ObjAttrs creditAttrs       = new ObjAttrs();
    creditAttrs.addAttr("CustomerActId", acct.attrs.getAttr("CustomerActId"));
    creditAttrs.addAttr("CustomerName",  acct.attrs.getAttr("CustomerName"));
    creditAttrs.addAttr("Amount",        amount.toPlainString());

    final JsonMsg creditMsg  = new JsonMsg();
    creditMsg.addRequestBody("Credit", creditAttrs);
    final JsonMsg creditResp = msgClient.send(destAccountsDomId, creditMsg);

    if (creditResp != null && "Failure".equals(creditResp.getError()))
    {
      // Rollback local deduction.
      acct.attrs.addAttr("Balance", balance.toPlainString());
      ObjDb.modifyObj(acct);
      adjustBankAvailable(acctId, amount.negate());
      outMsg.addError(opr, "Destination bank rejected the transfer: "
                     + creditResp.getErrorMsg());
      return;
    }

    final ObjAttrs out = new ObjAttrs();
    out.addAttr("Balance", newBalance.toPlainString());
    outMsg.addResponseBody(opr, out);
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private boolean isBankCaller(final JsonMsg inMsg, final DomId acctId)
      throws DomatarException
  {
    final String bankActId = acctId.hstId.startsWith(MONEY_PREFIX)
                             ? acctId.hstId.substring(MONEY_PREFIX.length()) : "";
    return inMsg.getSrcId().actId.equals(bankActId);
  }

  private boolean isCustomerCaller(final JsonMsg inMsg, final DomId acctId)
      throws DomatarException
  {
    return inMsg.getSrcId().actId.equals(acctId.actId);
  }

  private BigDecimal parsePositiveAmount(final String opr, final JsonMsg inMsg,
                                         final JsonMsg outMsg)
      throws DomatarException
  {
    final String amountStr = inMsg.getAttrs().getAttr("Amount");
    if (amountStr == null || amountStr.isEmpty())
    {
      outMsg.addError(opr, "Missing Amount");
      return null;
    }
    BigDecimal amount;
    try
    {
      amount = new BigDecimal(amountStr);
    }
    catch (NumberFormatException e)
    {
      outMsg.addError(opr, "Amount must be a valid decimal number");
      return null;
    }
    if (amount.compareTo(BigDecimal.ZERO) <= 0)
    {
      outMsg.addError(opr, "Amount must be greater than zero");
      return null;
    }
    return amount;
  }

  /** Adjusts bank.AvailableFunds by delta (positive = add, negative = subtract). */
  private void adjustBankAvailable(final DomId acctId, final BigDecimal delta)
      throws DomatarException
  {
    final String     hstId     = acctId.hstId;
    final String     bankActId = hstId.startsWith(MONEY_PREFIX)
                                 ? hstId.substring(MONEY_PREFIX.length()) : "";
    final DomId      bankId    = new DomId(hstId, "money", bankActId, "bank");
    final Obj        bank      = ObjDb.getObj(bankId);

    if (bank == null)
      return;

    final BigDecimal avail = BankImpl.dec(bank.attrs.getAttr("AvailableFunds"));
    bank.attrs.addAttr("AvailableFunds", avail.add(delta).toPlainString());
    ObjDb.modifyObj(bank);
  }
}
