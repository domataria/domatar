/*
 * Copyright (c) 2024 Domatar
 */

package com.money.objimpl;

import java.math.BigDecimal;

import com.domatar.core.Auth;
import com.domatar.db.ObjDb;
import com.domatar.util.JsonMsg;
import com.domatar.util.Obj;
import com.domatar.util.ObjAttrs;
import com.domatar.util.ObjImpl;
import com.domatar.util.DomId;
import com.domatar.util.DomatarException;
import com.domatar.util.DomatarMsgClient;

/**
 * Handler for class (money, bank).
 *
 * Manages the bank's fund pool.
 * hasRights: bank user only (Auth.isVerified && caller.actId == bank.actId)
 * Spec: Spec-Money.txt PART 6.2
 */
public class BankImpl extends ObjImpl
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

    if ("GetBank".equals(opr))
      getBank(opr, outMsg, inMsg.getDstId());
    else if ("SetFunds".equals(opr))
      setFunds(opr, inMsg, outMsg, inMsg.getDstId());
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
    if (obj == null)
      return false;
    // Only the bank user (actId == bank.actId) may access this object.
    return inMsg.getSrcId().actId.equals(obj.domId.actId);
  }

  // ---------------------------------------------------------------------------

  private void getBank(final String opr, final JsonMsg outMsg, final DomId bankId)
      throws DomatarException
  {
    final Obj bank = ObjDb.getObj(bankId);
    if (bank == null)
    {
      outMsg.addError(opr, "Bank not found");
      return;
    }

    final ObjAttrs out = new ObjAttrs();
    out.addAttr("Name",           bank.attrs.getAttr("Name"));
    out.addAttr("TotalFunds",     bank.attrs.getAttr("TotalFunds"));
    out.addAttr("AvailableFunds", bank.attrs.getAttr("AvailableFunds"));
    outMsg.addResponseBody(opr, out);
  }

  private void setFunds(final String opr, final JsonMsg inMsg, final JsonMsg outMsg,
                        final DomId bankId)
      throws DomatarException
  {
    final String newTotalStr = inMsg.getAttrs().getAttr("TotalFunds");
    if (newTotalStr == null || newTotalStr.isEmpty())
    {
      outMsg.addError(opr, "Missing TotalFunds");
      return;
    }

    BigDecimal newTotal;
    try
    {
      newTotal = new BigDecimal(newTotalStr);
    }
    catch (NumberFormatException e)
    {
      outMsg.addError(opr, "TotalFunds must be a valid decimal number");
      return;
    }

    if (newTotal.compareTo(BigDecimal.ZERO) < 0)
    {
      outMsg.addError(opr, "TotalFunds cannot be negative");
      return;
    }

    final Obj bank = ObjDb.getObj(bankId);
    if (bank == null)
    {
      outMsg.addError(opr, "Bank not found");
      return;
    }

    final BigDecimal currentTotal     = dec(bank.attrs.getAttr("TotalFunds"));
    final BigDecimal currentAvailable = dec(bank.attrs.getAttr("AvailableFunds"));
    final BigDecimal distributed      = currentTotal.subtract(currentAvailable);

    if (newTotal.compareTo(distributed) < 0)
    {
      outMsg.addError(opr, "TotalFunds cannot be reduced below the amount already distributed ("
                     + distributed.toPlainString() + ")");
      return;
    }

    final BigDecimal newAvailable = newTotal.subtract(distributed);
    bank.attrs.addAttr("TotalFunds",     newTotal.toPlainString());
    bank.attrs.addAttr("AvailableFunds", newAvailable.toPlainString());
    ObjDb.modifyObj(bank);

    final ObjAttrs out = new ObjAttrs();
    out.addAttr("TotalFunds",     newTotal.toPlainString());
    out.addAttr("AvailableFunds", newAvailable.toPlainString());
    outMsg.addResponseBody(opr, out);
  }

  // ---------------------------------------------------------------------------

  static BigDecimal dec(final String s)
  {
    if (s == null || s.isEmpty())
      return BigDecimal.ZERO;

    try
    {
      final String ds = s.replace(",", "");
      return new BigDecimal(ds);
    }
    catch (NumberFormatException e)
    {
      return BigDecimal.ZERO;
    }
  }
}
