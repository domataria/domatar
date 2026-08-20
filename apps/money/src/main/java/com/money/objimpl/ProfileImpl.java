/*
 * Copyright (c) 2024 Domatar
 */

package com.money.objimpl;

import com.money.install.MoneyInstall;
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
 * Handler for class (money, profile).
 *
 * Stores the user's chosen role: Bank, Customer, or "" (unset).
 * SetRole("Bank") lazily creates the bank + accounts objects and
 * the Navigator sub-tree for the bank view.
 *
 * hasRights: owner only (Auth.isVerified && caller.actId == profile.actId)
 * Spec: Spec-Money.txt PART 6.1
 */
public class ProfileImpl extends ObjImpl
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

    if ("GetProfile".equals(opr))
      getProfile(opr, outMsg, inMsg.getDstId());
    else if ("SetRole".equals(opr))
      setRole(opr, inMsg, outMsg, inMsg.getDstId(), msgClient);
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
    return inMsg.getSrcId().actId.equals(obj.domId.actId);
  }

  // ---------------------------------------------------------------------------

  private void getProfile(final String opr, final JsonMsg outMsg,
                          final DomId profileId)
      throws DomatarException
  {
    final Obj profile = ObjDb.getObj(profileId);
    String role = (profile != null) ? profile.attrs.getAttr("Role") : "";
    if (role == null)
      role = "";

    final DomId    bankId  = new DomId(profileId.hstId, "money", profileId.actId, "bank");
    final boolean  hasBank = ObjDb.getObj(bankId) != null;

    final ObjAttrs out = new ObjAttrs();
    out.addAttr("Role",    role);
    out.addAttr("HasBank", hasBank ? "true" : "false");
    outMsg.addResponseBody(opr, out);
  }

  private void setRole(final String opr, final JsonMsg inMsg, final JsonMsg outMsg,
                       final DomId profileId, final DomatarMsgClient msgClient)
      throws DomatarException
  {
    String role = inMsg.getAttrs().getAttr("Role");
    if (role == null)
      role = "";

    if (!"Bank".equals(role) && !"Customer".equals(role) && !"".equals(role))
    {
      outMsg.addError(opr, "Role must be 'Bank', 'Customer', or ''");
      return;
    }

    final Obj profile = ObjDb.getObj(profileId);
    if (profile == null)
    {
      outMsg.addError(opr, "Profile not found");
      return;
    }
    profile.attrs.addAttr("Role", role);
    ObjDb.modifyObj(profile);

    // Lazily initialise bank infrastructure when setting Bank role.
    if ("Bank".equals(role))
    {
      final String actId      = profileId.actId;
      final String hstId      = profileId.hstId;              // money~<actId>

      final DomId bankId     = new DomId(hstId, "money", actId, "bank");
      final DomId accountsId = new DomId(hstId, "money", actId, "accounts");
      final DomId appMoneyId = new DomId(hstId, "money", actId, "app-money");

      if (ObjDb.getObj(bankId) == null)
      {
        final ObjAttrs in = inMsg.getAttrs();
        String bankName   = in.getAttr("BankName");
        String totalFunds = in.getAttr("TotalFunds");

        if (bankName == null || bankName.isEmpty())
          bankName = "My Bank";
        if (totalFunds == null || totalFunds.isEmpty())
          totalFunds = "0.00";

        // Validate TotalFunds is a non-negative number.
        try
        {
          new java.math.BigDecimal(totalFunds);
        }
        catch (NumberFormatException e)
        {
          outMsg.addError(opr, "TotalFunds must be a valid decimal number");
          return;
        }

        final ObjAttrs bankAttrs = new ObjAttrs();
        bankAttrs.addAttr("Name",           bankName);
        bankAttrs.addAttr("TotalFunds",     totalFunds);
        bankAttrs.addAttr("AvailableFunds", totalFunds);
        ObjDb.addObj(new Obj(bankId, "money", "bank",
                             bankName, "Money bank", bankAttrs));

        ObjDb.addObjIfMissing(accountsId, "money", "accounts",
                              "Accounts", "Customer accounts");
      }

      // Always ensure links exist (idempotent) even if bank obj was
      // created by a previous partially-failed attempt.
      MoneyInstall.addLnkIfMissing(appMoneyId, bankId,
                                   "money", "bank",
                                   inMsg.getAttrs().getAttr("BankName") != null
                                       ? inMsg.getAttrs().getAttr("BankName") : "My Bank",
                                   "Money bank",
                                   "money", "bank", null, 4);
      MoneyInstall.addLnkIfMissing(bankId, accountsId,
                                   "money", "accounts",
                                   "Accounts", "Customer accounts",
                                   "money", "accounts", null, 1);
    }

    final ObjAttrs out = new ObjAttrs();
    out.addAttr("Role", role);
    outMsg.addResponseBody(opr, out);
  }
}
