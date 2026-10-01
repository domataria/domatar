/*
 * Copyright (c) 2024 Domatar
 */

package com.hecto.objimpl;

import java.math.BigDecimal;

import com.domatar.db.LnkDb;
import com.domatar.db.ObjDb;
import com.domatar.log.OpLog;
import com.domatar.saga.SagaSlot;
import com.domatar.util.DomId;
import com.domatar.util.DomatarException;
import com.domatar.util.DomatarMsgClient;
import com.domatar.util.JsonHashMap;
import com.domatar.util.JsonMsg;
import com.domatar.util.Lnk;
import com.domatar.util.Obj;
import com.domatar.util.ObjAttrs;
import com.domatar.util.ObjImpl;
import com.domatar.util.Rights;

/**
 * Per-user record of Hecto subscriptions. Post refuses a second
 * delivery of the same message in one operation.
 */
public class HectoBookingImpl extends ObjImpl
{
  static final String UNPOST_KEY = "SagaUnpostKey";

  public static void ensure(final String actId) throws DomatarException
  {
    final DomId id = HectoIds.booking(actId);
    final Obj existing = ObjDb.getObj(id);

    if (existing == null)
    {
      final ObjAttrs attrs = new ObjAttrs();

      attrs.addAttr("NetDelta", "0");
      attrs.addAttr("LastDelta", "0");
      ObjDb.addObjIfMissing(id, HectoIds.HOME, HectoIds.BOOKING,
          "Booking", "Record of Hecto subscriptions", attrs);
    }
    else if (existing.attrs != null && existing.attrs.getAttr("NetDelta") == null)
    {
      existing.attrs.addAttr("NetDelta", "0");
      if (existing.attrs.getAttr("LastDelta") == null)
        existing.attrs.addAttr("LastDelta", "0");
      ObjDb.modifyObj(existing);
    }

    final DomId app = HectoIds.subHost(actId, "app-hecto");

    if (ObjDb.getObj(app) != null
        && LnkDb.getLnk(app, id, "navigator", "container") == null)
    {
      LnkDb.addLnk(new Lnk(app, id,
          HectoIds.HOME, HectoIds.BOOKING,
          "Booking", "Record of Hecto subscriptions",
          "navigator", "container",
          null, 1));
    }
  }

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
    final Obj target = HectoAuth.requireObj(inMsg, obj);

    if (target == null)
    {
      outMsg.addError(opr, "Obj not found");
      return outMsg.toString();
    }

    if ("GetBooking".equals(opr))
      outMsg.addResponseBody(opr, snapshot(target));
    else if ("Post".equals(opr))
      post(inMsg, target, msgClient, outMsg);
    else if ("Unpost".equals(opr))
      unpost(inMsg, target, outMsg);
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
    if (inMsg != null && "Post".equals(inMsg.getOperation())
        && msgClient != null && msgClient.alreadyEntered())
      return Rights.DENY;
    return Rights.ADMIT;
  }

  private static void post(final JsonMsg inMsg, final Obj booking,
                           final DomatarMsgClient msgClient, final JsonMsg outMsg)
      throws DomatarException
  {
    final String delta = inMsg.getAttr("Delta");
    final BigDecimal change = HectoAmounts.parse(delta, "Delta");
    final String net = HectoAmounts.add(attr(booking, "NetDelta", "0"), delta);

    booking.attrs.addAttr("NetDelta", net);
    booking.attrs.addAttr("LastDelta", change.toPlainString());
    if ("true".equals(inMsg.getAttr("RecordUndo")))
    {
      final String ctx = msgClient != null ? msgClient.contextId() : null;

      if (ctx != null && !ctx.isEmpty())
        booking.attrs.addAttr("LastContextId", ctx);
      final String undoMsg = inMsg.getAttr("UndoMsgName");
      booking.attrs.addAttr("LastMsgName",
          undoMsg != null && !undoMsg.isEmpty() ? undoMsg : "Subscribe");
    }
    ObjDb.modifyObj(booking);
    attachPost(msgClient, delta);
    outMsg.addResponseBody("Post", snapshot(booking));
  }

  private static void unpost(final JsonMsg inMsg, final Obj booking,
                             final JsonMsg outMsg) throws DomatarException
  {
    final String orig = inMsg.getAttr("OrigContextId");
    final String delta = inMsg.getAttr("Delta");

    if (orig == null || orig.isEmpty() || delta == null || delta.isEmpty())
    {
      outMsg.addError("Unpost", "Missing Delta or OrigContextId");
      return;
    }
    if (orig.equals(attr(booking, UNPOST_KEY, "")))
    {
      outMsg.addResponseBody("Unpost", snapshot(booking));
      return;
    }

    final String net = HectoAmounts.add(attr(booking, "NetDelta", "0"), HectoAmounts.negate(delta));

    booking.attrs.addAttr("NetDelta", net);
    booking.attrs.addAttr("LastDelta", HectoAmounts.negate(delta));
    booking.attrs.addAttr(UNPOST_KEY, orig);
    ObjDb.modifyObj(booking);
    outMsg.addResponseBody("Unpost", snapshot(booking));
  }

  private static void attachPost(final DomatarMsgClient msgClient, final String delta)
      throws DomatarException
  {
    if (msgClient == null || msgClient.contextId() == null)
      return;

    final JsonHashMap effect = new JsonHashMap();

    effect.put("Delta", delta);
    effect.put("OrigContextId", msgClient.contextId());
    msgClient.attach(SagaSlot.SLOT, SagaSlot.applied("Unpost", effect),
        OpLog.nowMs() + OpLog.sagaTtlMs());
  }

  private static ObjAttrs snapshot(final Obj booking) throws DomatarException
  {
    final ObjAttrs out = new ObjAttrs();

    out.addAttr("NetDelta", attr(booking, "NetDelta", "0"));
    out.addAttr("LastDelta", attr(booking, "LastDelta", "0"));
    out.addAttr("LastContextId", attr(booking, "LastContextId", ""));
    out.addAttr("LastMsgName", attr(booking, "LastMsgName", ""));
    return out;
  }

  private static String attr(final Obj obj, final String name, final String fallback)
      throws DomatarException
  {
    if (obj == null || obj.attrs == null)
      return fallback;
    final String value = obj.attrs.getAttr(name);
    return value != null ? value : fallback;
  }
}
