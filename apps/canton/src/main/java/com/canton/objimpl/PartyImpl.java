/*
 * Copyright (c) 2024 Domatar
 */

package com.canton.objimpl;

import com.domatar.db.ObjDb;
import com.domatar.util.JsonMsg;
import com.domatar.util.Obj;
import com.domatar.util.ObjAttrs;
import com.domatar.util.ObjImpl;
import com.domatar.util.DomatarException;
import com.domatar.util.DomatarMsgClient;

public class PartyImpl extends ObjImpl
{
  @Override
  public String handleMsg(final String msg, final Obj obj, final String contextPath,
                          final String contextRealPath, final DomatarMsgClient msgClient)
      throws DomatarException
  {
    final JsonMsg inMsg = new JsonMsg(msg);
    final String opr = inMsg.getOperation();

    if ("Compensate".equals(opr))
      return super.handleMsg(msg, obj, contextPath, contextRealPath, msgClient);

    final JsonMsg outMsg = new JsonMsg();

    if (!hasRights(inMsg, obj, msgClient))
      return notAuthorized(inMsg);

    final Obj target = CantonAuth.requireObj(inMsg, obj);
    if (target == null)
    {
      outMsg.addError(opr, "Obj not found");
      return outMsg.toString();
    }

    if ("GetParty".equals(opr))
      getParty(opr, target, outMsg);
    else if ("BindParty".equals(opr))
      bindParty(opr, inMsg, target, outMsg);
    else
      return super.handleMsg(msg, obj, contextPath, contextRealPath, msgClient);

    return outMsg.toString();
  }

  @Override
  public boolean hasRights(final JsonMsg inMsg, final Obj obj,
                           final DomatarMsgClient msgClient)
      throws DomatarException
  {
    return CantonAuth.isVerifiedOwner(inMsg, obj);
  }

  private static void getParty(final String opr, final Obj obj,
                               final JsonMsg outMsg) throws DomatarException
  {
    String partyId = obj.attrs != null ? obj.attrs.getAttr("PartyId") : "";
    if (partyId == null)
      partyId = "";

    final ObjAttrs out = new ObjAttrs();
    out.addAttr("PartyId", partyId);
    outMsg.addResponseBody(opr, out);
  }

  private static void bindParty(final String opr, final JsonMsg inMsg,
                                final Obj obj, final JsonMsg outMsg)
      throws DomatarException
  {
    String partyId = inMsg.getAttr("PartyId");
    if (partyId == null)
      partyId = "";

    obj.attrs.addAttr("PartyId", partyId);
    ObjDb.modifyObj(obj);

    final ObjAttrs out = new ObjAttrs();
    out.addAttr("PartyId", partyId);
    outMsg.addResponseBody(opr, out);
  }
}
