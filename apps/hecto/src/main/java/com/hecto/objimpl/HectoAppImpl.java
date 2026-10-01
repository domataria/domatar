/*
 * Copyright (c) 2024 Domatar
 */

package com.hecto.objimpl;

import com.domatar.db.ObjDb;
import com.domatar.db.PayDb;
import com.domatar.util.DomId;
import com.domatar.util.DomatarException;
import com.domatar.util.DomatarMsgClient;
import com.domatar.util.JsonMsg;
import com.domatar.util.Obj;
import com.domatar.util.ObjAttrs;
import com.domatar.util.ObjImpl;
import com.domatar.util.Rights;

/**
 * User facade. Subscribe is priced on the desk, not on this object,
 * so this rights() stays ADMIT.
 */
public class HectoAppImpl extends ObjImpl
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

    if (!hasRights(inMsg, obj, msgClient))
      return notAuthorized(inMsg);

    final JsonMsg outMsg = new JsonMsg();
    final String actId = HectoAuth.destActId(inMsg, obj);

    if (actId == null)
    {
      outMsg.addError(opr, "Obj not found");
      return outMsg.toString();
    }

    if ("Quote".equals(opr))
      forwardDesk(opr, copyAttrs(inMsg, "ContractId"), msgClient, outMsg);
    else if ("Credits".equals(opr) || "Refill".equals(opr) || "Echo".equals(opr))
      forwardDesk(opr, null, msgClient, outMsg);
    else if ("Subscribe".equals(opr))
      subscribe(actId, inMsg, msgClient, outMsg);
    else if ("ApplyTwice".equals(opr))
      forwardDesk(opr, copyAttrs(inMsg, "ContractId", "Delta"), msgClient, outMsg);
    else if ("GetBooking".equals(opr))
      booking(actId, msgClient, outMsg);
    else if ("Undo".equals(opr))
      undo(actId, msgClient, outMsg);
    else
      return super.handleMsg(msg, obj, contextPath, contextRealPath, msgClient);

    return outMsg.toString();
  }

  @Override
  public boolean hasRights(final JsonMsg inMsg, final Obj obj,
                           final DomatarMsgClient msgClient)
      throws DomatarException
  {
    return HectoAuth.isVerifiedOwner(inMsg, obj, msgClient);
  }

  @Override
  public Rights rights(final JsonMsg inMsg, final Obj obj,
                       final DomatarMsgClient msgClient)
      throws DomatarException
  {
    final Rights base = super.rights(inMsg, obj, msgClient);

    if (base != Rights.ADMIT)
      return base;
    return hasRights(inMsg, obj, msgClient) ? Rights.ADMIT : Rights.DENY;
  }

  private static void subscribe(final String actId, final JsonMsg inMsg,
                                final DomatarMsgClient msgClient, final JsonMsg outMsg)
      throws DomatarException
  {
    final DomId desk = HectoIds.desk();

    if (desk == null)
    {
      outMsg.addError("Subscribe", "Hecto desk is not installed");
      return;
    }

    final Long remaining = PayDb.remaining(desk.hstId, desk.actId, actId);

    if (remaining != null && remaining.longValue() < HectoIds.COST)
    {
      outMsg.addError("Subscribe",
          "Not enough credits (" + remaining + " remaining, " + HectoIds.COST + " required)");
      return;
    }
    forwardDesk("Subscribe", copyAttrs(inMsg, "ContractId", "Delta"), msgClient, outMsg);
  }

  private static void booking(final String actId, final DomatarMsgClient msgClient,
                              final JsonMsg outMsg) throws DomatarException
  {
    HectoBookingImpl.ensure(actId);
    forward("GetBooking", "GetBooking", HectoIds.booking(actId), HectoIds.BOOKING,
        null, msgClient, outMsg);
  }

  private static void undo(final String actId, final DomatarMsgClient msgClient,
                           final JsonMsg outMsg) throws DomatarException
  {
    final DomId desk = HectoIds.desk();

    if (desk == null)
    {
      outMsg.addError("Undo", "Hecto desk is not installed");
      return;
    }

    HectoBookingImpl.ensure(actId);
    final Obj booking = ObjDb.getObj(HectoIds.booking(actId));
    final String ctx = booking != null && booking.attrs != null
        ? booking.attrs.getAttr("LastContextId") : null;
    final String name = booking != null && booking.attrs != null
        ? booking.attrs.getAttr("LastMsgName") : null;

    if (ctx == null || ctx.isEmpty() || name == null || name.isEmpty())
    {
      outMsg.addError("Undo", "Nothing to undo");
      return;
    }

    final ObjAttrs attrs = new ObjAttrs();

    attrs.addAttr("OrigContextId", ctx);
    attrs.addAttr("OrigMsgName", name);

    final JsonMsg req = new JsonMsg();

    req.addClsId(HectoIds.HOME, HectoIds.DESK);
    req.addRequestBody("Compensate", attrs);
    try
    {
      copyReply(outMsg, "Undo", msgClient.send(desk, req));
    }
    catch (final DomatarException e)
    {
      outMsg.addError("Undo", e.getMessage());
    }
  }

  private static void forwardDesk(final String opr, final ObjAttrs attrs,
                                  final DomatarMsgClient msgClient, final JsonMsg outMsg)
      throws DomatarException
  {
    final DomId desk = HectoIds.desk();

    if (desk == null)
    {
      outMsg.addError(opr, "Hecto desk is not installed");
      return;
    }
    forward(opr, opr, desk, HectoIds.DESK, attrs, msgClient, outMsg);
  }

  private static void forward(final String facadeOpr, final String innerOpr,
                              final DomId dst, final String clsId,
                              final ObjAttrs attrs,
                              final DomatarMsgClient msgClient,
                              final JsonMsg outMsg)
      throws DomatarException
  {
    try
    {
      final JsonMsg req = new JsonMsg();

      req.addRequestBody(innerOpr, attrs);
      if (clsId != null)
        req.addClsId(HectoIds.HOME, clsId);
      copyReply(outMsg, facadeOpr, msgClient.send(dst, req));
    }
    catch (final DomatarException e)
    {
      outMsg.addError(facadeOpr, e.getMessage());
    }
  }

  private static void copyReply(final JsonMsg outMsg, final String opr,
                                final JsonMsg resp)
      throws DomatarException
  {
    if (resp == null)
    {
      outMsg.addError(opr, "No response");
      return;
    }
    if (resp.isFailure() || (resp.getError() != null && !resp.getError().isEmpty()
        && !"Success".equals(resp.getError())))
    {
      String msg = resp.getErrorMsg();
      if (msg == null || msg.isEmpty())
        msg = resp.getError();
      outMsg.addError(opr, msg);
      return;
    }
    final ObjAttrs attrs = resp.getAttrs();
    outMsg.addResponseBody(opr, attrs != null ? attrs : new ObjAttrs());
  }

  private static ObjAttrs copyAttrs(final JsonMsg inMsg, final String... names)
      throws DomatarException
  {
    final ObjAttrs attrs = new ObjAttrs();

    if (inMsg == null || names == null)
      return attrs;
    for (final String name : names)
    {
      final String value = inMsg.getAttr(name);
      if (value != null)
        attrs.addAttr(name, value);
    }
    return attrs;
  }
}
