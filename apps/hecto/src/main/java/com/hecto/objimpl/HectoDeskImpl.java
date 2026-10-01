/*
 * Copyright (c) 2024 Domatar
 */

package com.hecto.objimpl;

import com.domatar.core.Auth;
import com.domatar.core.ClsResolver;
import com.domatar.db.ObjDb;
import com.domatar.db.PayDb;
import com.domatar.log.OpLog;
import com.domatar.saga.SagaSlot;
import com.domatar.util.DomId;
import com.domatar.util.DomatarException;
import com.domatar.util.DomatarMsgClient;
import com.domatar.util.JsonHashMap;
import com.domatar.util.JsonMsg;
import com.domatar.util.Obj;
import com.domatar.util.ObjAttrs;
import com.domatar.util.ObjImpl;
import com.domatar.util.Rights;

/**
 * Priced subscriptions of a Hecto holding. The ledger submit stays
 * one command. Undo is a second command, plus the booking this
 * call posted.
 */
public class HectoDeskImpl extends ObjImpl
{
  static final String REVERSE_KEY = "SagaReverseKey";

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
    final String payer = Auth.actId(msgClient);

    if (payer == null)
    {
      outMsg.addError(opr, "Not authorized");
      return outMsg.toString();
    }

    try
    {
      if ("Quote".equals(opr))
        quote(opr, payer, inMsg, msgClient, outMsg);
      else if ("Credits".equals(opr))
        credits(opr, payer, outMsg);
      else if ("Subscribe".equals(opr))
        subscribe(payer, inMsg, msgClient, outMsg);
      else if ("ReverseSubscribe".equals(opr))
        reverse(inMsg, msgClient, outMsg);
      else if ("ApplyTwice".equals(opr))
        applyTwice(payer, inMsg, msgClient, outMsg);
      else if ("Echo".equals(opr))
        echo(msgClient, outMsg);
      else if ("Refill".equals(opr))
        refill(opr, payer, outMsg);
      else
        return super.handleMsg(msg, obj, contextPath, contextRealPath, msgClient);
    }
    catch (final DomatarException e)
    {
      outMsg.addError(opr, e.getMessage());
    }
    return outMsg.toString();
  }

  @Override
  public boolean hasRights(final JsonMsg inMsg, final Obj obj,
                           final DomatarMsgClient msgClient)
      throws DomatarException
  {
    return Auth.isVerified(msgClient) && Auth.actId(msgClient) != null;
  }

  @Override
  public Rights rights(final JsonMsg inMsg, final Obj obj,
                       final DomatarMsgClient msgClient)
      throws DomatarException
  {
    if (msgClient != null && msgClient.inOwnPath())
      return Rights.DENY;
    if (!hasRights(inMsg, obj, msgClient))
      return Rights.DENY;
    if (inMsg != null && msgClient != null && "Echo".equals(inMsg.getOperation())
        && msgClient.alreadyEntered())
      return Rights.DENY;
    if (inMsg != null && "Subscribe".equals(inMsg.getOperation())
        && !isOwner(inMsg, obj, msgClient))
    {
      if (!hectoHolding(inMsg, msgClient))
        return Rights.ADMIT;
      ensureSeed(Auth.actId(msgClient));
      resolveDeskPrice();
      return Rights.PRICED;
    }
    return Rights.ADMIT;
  }

  private static boolean isOwner(final JsonMsg inMsg, final Obj obj,
                                 final DomatarMsgClient msgClient)
      throws DomatarException
  {
    final String caller = Auth.actId(msgClient);
    final String dest = HectoAuth.destActId(inMsg, obj);
    return caller != null && caller.equals(dest);
  }

  private static boolean hectoHolding(final JsonMsg inMsg,
                                      final DomatarMsgClient msgClient)
  {
    try
    {
      final String contractId = inMsg != null ? inMsg.getAttr("ContractId") : null;
      final String payer = Auth.actId(msgClient);

      if (contractId == null || contractId.isEmpty() || payer == null)
        return false;
      syncHandles(payer, msgClient);
      final ObjAttrs live = readHolding(payer, contractId, msgClient);
      return HectoHoldings.isHectoHolding(live.toMap());
    }
    catch (final DomatarException e)
    {
      return false;
    }
  }

  private static void resolveDeskPrice() throws DomatarException
  {
    final DomId desk = HectoIds.desk();

    if (desk == null)
      return;
    ClsResolver.resolve(
        new DomId(desk.hstId, HectoIds.HOME, desk.actId, HectoIds.DESK + "Cls"),
        HectoIds.HOME, HectoIds.DESK);
  }

  private static void ensureSeed(final String payer) throws DomatarException
  {
    final DomId desk = HectoIds.desk();

    if (desk == null || payer == null)
      return;
    if (PayDb.remaining(desk.hstId, desk.actId, payer) == null)
      PayDb.credit(desk.hstId, desk.actId, payer, HectoIds.SEED, OpLog.nowMs());
  }

  private static void quote(final String opr, final String payer, final JsonMsg inMsg,
                            final DomatarMsgClient msgClient, final JsonMsg outMsg)
      throws DomatarException
  {
    syncHandles(payer, msgClient);
    final ObjAttrs live = readHolding(payer, inMsg.getAttr("ContractId"), msgClient);

    if (!HectoHoldings.isHectoHolding(live.toMap()))
      throw new DomatarException("Not a Hecto holding");

    final ObjAttrs out = new ObjAttrs();

    out.addAttr("ContractId", live.getAttr("ContractId"));
    out.addAttr("Amount", live.getAttr("Amount"));
    out.addAttr("Symbol", value(live.getAttr("Symbol")));
    out.addAttr("Cost", Long.toString(HectoIds.COST));
    outMsg.addResponseBody(opr, out);
  }

  private static void credits(final String opr, final String payer, final JsonMsg outMsg)
      throws DomatarException
  {
    ensureSeed(payer);
    final ObjAttrs out = new ObjAttrs();

    out.addAttr("Remaining", Long.toString(remaining(payer)));
    out.addAttr("Cost", Long.toString(HectoIds.COST));
    outMsg.addResponseBody(opr, out);
  }

  private static void refill(final String opr, final String payer, final JsonMsg outMsg)
      throws DomatarException
  {
    final DomId desk = requireDesk();
    final long have = remaining(payer);

    if (have < HectoIds.SEED)
      PayDb.credit(desk.hstId, desk.actId, payer, HectoIds.SEED - have, OpLog.nowMs());

    final ObjAttrs out = new ObjAttrs();

    out.addAttr("Remaining", Long.toString(remaining(payer)));
    outMsg.addResponseBody(opr, out);
  }

  private void subscribe(final String payer, final JsonMsg inMsg,
                         final DomatarMsgClient msgClient, final JsonMsg outMsg)
      throws DomatarException
  {
    final String contractId = inMsg.getAttr("ContractId");
    final String delta = inMsg.getAttr("Delta");

    HectoAmounts.parse(delta, "Delta");
    syncHandles(payer, msgClient);
    final ObjAttrs before = readHolding(payer, contractId, msgClient);

    if (!HectoHoldings.isHectoHolding(before.toMap()))
      throw new DomatarException("Not a Hecto holding");

    exerciseAdjust(payer, contractId, delta, msgClient);
    try
    {
      final JsonMsg posted = sendPost(payer, delta, true, msgClient);

      if (failed(posted))
      {
        exerciseAdjust(payer, contractId, HectoAmounts.negate(delta), msgClient);
        outMsg.addError("Subscribe",
            posted.getErrorMsg() != null ? posted.getErrorMsg() : "Booking was refused");
        return;
      }
    }
    catch (final DomatarException e)
    {
      exerciseAdjust(payer, contractId, HectoAmounts.negate(delta), msgClient);
      throw e;
    }

    attachSubscribe(msgClient, payer, contractId, delta);
    replyHolding("Subscribe", payer, contractId, msgClient, outMsg);
  }

  private void applyTwice(final String payer, final JsonMsg inMsg,
                          final DomatarMsgClient msgClient, final JsonMsg outMsg)
      throws DomatarException
  {
    final String contractId = inMsg.getAttr("ContractId");
    final String delta = inMsg.getAttr("Delta");

    HectoAmounts.parse(delta, "Delta");
    syncHandles(payer, msgClient);
    final ObjAttrs before = readHolding(payer, contractId, msgClient);

    if (!HectoHoldings.isHectoHolding(before.toMap()))
      throw new DomatarException("Not a Hecto holding");

    exerciseAdjust(payer, contractId, delta, msgClient);

    final JsonMsg first = sendPost(payer, delta, false, msgClient);
    final JsonMsg second = sendPost(payer, delta, false, msgClient);
    final ObjAttrs out = holdingAttrs(payer, contractId, msgClient);

    out.addAttr("FirstPost", failed(first) ? text(first, "refused") : "posted");
    out.addAttr("SecondPost", failed(second) ? text(second, "refused") : "posted");
    outMsg.addResponseBody("ApplyTwice", out);
  }

  private void reverse(final JsonMsg inMsg, final DomatarMsgClient msgClient,
                       final JsonMsg outMsg) throws DomatarException
  {
    final String actId = inMsg.getAttr("ActId");
    final String contractId = inMsg.getAttr("ContractId");
    final String delta = inMsg.getAttr("Delta");
    final String orig = inMsg.getAttr("OrigContextId");

    if (actId == null || contractId == null || delta == null || orig == null
        || actId.isEmpty() || contractId.isEmpty() || delta.isEmpty() || orig.isEmpty())
    {
      outMsg.addError("ReverseSubscribe",
          "Missing ActId, ContractId, Delta, or OrigContextId");
      return;
    }

    final Obj booking = ObjDb.getObj(HectoIds.booking(actId));

    if (booking != null && booking.attrs != null
        && orig.equals(booking.attrs.getAttr(REVERSE_KEY)))
    {
      replyHolding("ReverseSubscribe", actId, contractId, msgClient, outMsg);
      return;
    }

    exerciseAdjust(actId, contractId, HectoAmounts.negate(delta), msgClient);
    if (booking != null && booking.attrs != null)
    {
      booking.attrs.addAttr(REVERSE_KEY, orig);
      ObjDb.modifyObj(booking);
    }
    replyHolding("ReverseSubscribe", actId, contractId, msgClient, outMsg);
  }

  private static void echo(final DomatarMsgClient msgClient, final JsonMsg outMsg)
      throws DomatarException
  {
    final DomId desk = requireDesk();
    final JsonMsg again = new JsonMsg();

    again.addClsId(HectoIds.HOME, HectoIds.DESK);
    again.addRequestBody("Echo", new ObjAttrs());

    final JsonMsg resp = msgClient.send(desk, again);
    final ObjAttrs out = new ObjAttrs();

    out.addAttr("Stopped", "cycle");
    out.addAttr("Detail", text(resp, "refused"));
    outMsg.addResponseBody("Echo", out);
  }

  private static JsonMsg sendPost(final String payer, final String delta,
                                  final boolean recordUndo, final DomatarMsgClient msgClient)
      throws DomatarException
  {
    HectoBookingImpl.ensure(payer);

    final JsonMsg post = new JsonMsg();
    final ObjAttrs attrs = new ObjAttrs();

    attrs.addAttr("Delta", delta);
    if (recordUndo)
    {
      attrs.addAttr("RecordUndo", "true");
      attrs.addAttr("UndoMsgName", "Subscribe");
    }
    post.addClsId(HectoIds.HOME, HectoIds.BOOKING);
    post.addRequestBody("Post", attrs);
    return msgClient.send(HectoIds.booking(payer), post);
  }

  private static void attachSubscribe(final DomatarMsgClient msgClient, final String payer,
                                      final String contractId, final String delta)
      throws DomatarException
  {
    if (msgClient == null || msgClient.contextId() == null)
      return;

    final JsonHashMap effect = new JsonHashMap();

    effect.put("ActId", payer);
    effect.put("ContractId", contractId);
    effect.put("Delta", delta);
    effect.put("OrigContextId", msgClient.contextId());
    msgClient.attach(SagaSlot.SLOT, SagaSlot.applied("ReverseSubscribe", effect),
        OpLog.nowMs() + OpLog.sagaTtlMs());
  }

  private static void replyHolding(final String opr, final String payer,
                                   final String contractId,
                                   final DomatarMsgClient msgClient,
                                   final JsonMsg outMsg)
      throws DomatarException
  {
    outMsg.addResponseBody(opr, holdingAttrs(payer, contractId, msgClient));
  }

  private static ObjAttrs holdingAttrs(final String payer, final String contractId,
                                       final DomatarMsgClient msgClient)
      throws DomatarException
  {
    final ObjAttrs live = readHolding(payer, contractId, msgClient);
    final Obj booking = ObjDb.getObj(HectoIds.booking(payer));
    final ObjAttrs out = new ObjAttrs();

    out.addAttr("ContractId", live.getAttr("ContractId"));
    out.addAttr("Amount", live.getAttr("Amount"));
    out.addAttr("Symbol", value(live.getAttr("Symbol")));
    out.addAttr("NetDelta", booking != null && booking.attrs != null
        && booking.attrs.getAttr("NetDelta") != null
        ? booking.attrs.getAttr("NetDelta") : "0");
    out.addAttr("Remaining", Long.toString(remaining(payer)));
    return out;
  }

  private static ObjAttrs readHolding(final String payer, final String contractId,
                                      final DomatarMsgClient msgClient)
      throws DomatarException
  {
    if (contractId == null || contractId.isEmpty())
      throw new DomatarException("Missing ContractId");
    if (msgClient == null)
      throw new DomatarException("Contract no longer visible");

    final DomId handle = new DomId(DomId.subHstId("canton", payer),
        "canton", payer, contractId);
    final JsonMsg req = new JsonMsg();

    req.addClsId("canton", "contract");
    req.addRequestBody("GetContract", new ObjAttrs());

    final JsonMsg resp = msgClient.send(handle, req);

    if (failed(resp) || resp.getAttrs() == null)
      throw new DomatarException(text(resp, "Contract no longer visible"));
    return resp.getAttrs();
  }

  private static void exerciseAdjust(final String payer, final String contractId,
                                     final String delta, final DomatarMsgClient msgClient)
      throws DomatarException
  {
    final DomId handle = new DomId(DomId.subHstId("canton", payer),
        "canton", payer, contractId);
    final JsonMsg req = new JsonMsg();
    final ObjAttrs attrs = new ObjAttrs();

    attrs.addAttr("Choice", "Adjust");
    attrs.addAttr("Delta", delta);
    req.addClsId("canton", "contract");
    req.addRequestBody("Exercise", attrs);

    final JsonMsg resp = msgClient.send(handle, req);

    if (failed(resp))
      throw new DomatarException(text(resp, "Adjust was refused"));
  }

  private static void syncHandles(final String actId, final DomatarMsgClient msgClient)
      throws DomatarException
  {
    if (msgClient == null)
      return;

    final DomId active = new DomId(DomId.subHstId("canton", actId), "canton", actId, "active");
    final JsonMsg req = new JsonMsg();

    req.addClsId("canton", "contracts");
    req.addRequestBody("Sync", new ObjAttrs());

    final JsonMsg resp = msgClient.send(active, req);

    if (failed(resp))
      throw new DomatarException(text(resp, "Canton sync failed"));
  }

  private static long remaining(final String payer) throws DomatarException
  {
    final DomId desk = requireDesk();
    final Long value = PayDb.remaining(desk.hstId, desk.actId, payer);
    return value == null ? 0L : value.longValue();
  }

  private static DomId requireDesk() throws DomatarException
  {
    final DomId desk = HectoIds.desk();

    if (desk == null)
      throw new DomatarException("Hecto desk is not installed");
    return desk;
  }

  private static boolean failed(final JsonMsg resp) throws DomatarException
  {
    if (resp == null)
      return true;
    if (resp.isFailure())
      return true;
    final String err = resp.getError();
    return err != null && !err.isEmpty() && !"Success".equals(err);
  }

  private static String text(final JsonMsg resp, final String fallback)
      throws DomatarException
  {
    if (resp == null)
      return fallback;
    final String msg = resp.getErrorMsg();
    return msg != null && !msg.isEmpty() ? msg : fallback;
  }

  private static String value(final String raw)
  {
    return raw != null ? raw : "";
  }
}
