/*
 * Copyright (c) 2024 Domatar
 */

package com.canton.objimpl;

import java.util.Collections;
import java.util.List;

import com.canton.ledger.Amounts;
import com.canton.ledger.CantonClients;
import com.canton.ledger.Contract;
import com.domatar.core.Auth;
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
 * Priced adjustments of a holding the caller can see. The ledger
 * submit stays one command. Undo is a second command, plus the
 * booking this call posted.
 */
public class DeskImpl extends ObjImpl
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
        quote(opr, payer, inMsg, outMsg);
      else if ("Credits".equals(opr))
        credits(opr, payer, outMsg);
      else if ("AdjustHolding".equals(opr))
        adjust(payer, inMsg, msgClient, outMsg, true);
      else if ("ReverseAdjust".equals(opr))
        reverse(inMsg, outMsg);
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
    if (inMsg != null && "AdjustHolding".equals(inMsg.getOperation())
        && !isOwner(inMsg, obj, msgClient))
    {
      ensureSeed(Auth.actId(msgClient));
      return Rights.PRICED;
    }
    return Rights.ADMIT;
  }

  private static boolean isOwner(final JsonMsg inMsg, final Obj obj,
                                 final DomatarMsgClient msgClient)
      throws DomatarException
  {
    final String caller = Auth.actId(msgClient);
    final String dest = CantonAuth.destActId(inMsg, obj);
    return caller != null && caller.equals(dest);
  }

  private static void ensureSeed(final String payer) throws DomatarException
  {
    final DomId desk = CantonIds.desk();

    if (desk == null || payer == null)
      return;
    if (PayDb.remaining(desk.hstId, desk.actId, payer) == null)
      PayDb.credit(desk.hstId, desk.actId, payer, CantonIds.SEED, OpLog.nowMs());
  }

  private static void quote(final String opr, final String payer, final JsonMsg inMsg,
                            final JsonMsg outMsg) throws DomatarException
  {
    final Contract live = live(payer, inMsg.getAttr("ContractId"));
    final ObjAttrs out = new ObjAttrs();

    out.addAttr("ContractId", live.contractId);
    out.addAttr("Amount", live.payload.get("Amount"));
    out.addAttr("Symbol", value(live.payload.get("Symbol")));
    out.addAttr("Cost", Long.toString(CantonIds.COST));
    outMsg.addResponseBody(opr, out);
  }

  private static void credits(final String opr, final String payer, final JsonMsg outMsg)
      throws DomatarException
  {
    ensureSeed(payer);
    final ObjAttrs out = new ObjAttrs();

    out.addAttr("Remaining", Long.toString(remaining(payer)));
    out.addAttr("Cost", Long.toString(CantonIds.COST));
    outMsg.addResponseBody(opr, out);
  }

  private static void refill(final String opr, final String payer, final JsonMsg outMsg)
      throws DomatarException
  {
    final DomId desk = requireDesk();
    final long have = remaining(payer);

    if (have < CantonIds.SEED)
      PayDb.credit(desk.hstId, desk.actId, payer, CantonIds.SEED - have, OpLog.nowMs());

    final ObjAttrs out = new ObjAttrs();

    out.addAttr("Remaining", Long.toString(remaining(payer)));
    outMsg.addResponseBody(opr, out);
  }

  private void adjust(final String payer, final JsonMsg inMsg,
                      final DomatarMsgClient msgClient, final JsonMsg outMsg,
                      final boolean recordUndo) throws DomatarException
  {
    final String contractId = inMsg.getAttr("ContractId");
    final String delta = inMsg.getAttr("Delta");

    Amounts.parse(delta, "Delta");
    final String party = HandleSync.boundParty(payer);
    final Contract before = live(payer, contractId);

    CantonClients.get().submitExercise(party, before.contractId, "Adjust",
        Collections.singletonMap("Delta", delta));
    try
    {
      final JsonMsg posted = sendPost(payer, delta, recordUndo, msgClient);

      if (failed(posted))
      {
        CantonClients.get().submitExercise(party, before.contractId, "Adjust",
            Collections.singletonMap("Delta", Amounts.negate(delta)));
        HandleSync.sync(payer);
        outMsg.addError("AdjustHolding",
            posted.getErrorMsg() != null ? posted.getErrorMsg() : "Booking was refused");
        return;
      }
    }
    catch (final DomatarException e)
    {
      CantonClients.get().submitExercise(party, before.contractId, "Adjust",
          Collections.singletonMap("Delta", Amounts.negate(delta)));
      HandleSync.sync(payer);
      throw e;
    }

    HandleSync.sync(payer);
    if (recordUndo)
      attachAdjust(msgClient, payer, before.contractId, delta);
    replyHolding("AdjustHolding", payer, before.contractId, outMsg);
  }

  private void applyTwice(final String payer, final JsonMsg inMsg,
                          final DomatarMsgClient msgClient, final JsonMsg outMsg)
      throws DomatarException
  {
    final String contractId = inMsg.getAttr("ContractId");
    final String delta = inMsg.getAttr("Delta");

    Amounts.parse(delta, "Delta");
    final String party = HandleSync.boundParty(payer);
    final Contract before = live(payer, contractId);

    CantonClients.get().submitExercise(party, before.contractId, "Adjust",
        Collections.singletonMap("Delta", delta));
    HandleSync.sync(payer);

    final JsonMsg first = sendPost(payer, delta, false, msgClient);
    final JsonMsg second = sendPost(payer, delta, false, msgClient);
    final ObjAttrs out = holdingAttrs(payer, before.contractId);

    out.addAttr("FirstPost", failed(first)
        ? text(first, "refused") : "posted");
    out.addAttr("SecondPost", failed(second)
        ? text(second, "refused") : "posted");
    outMsg.addResponseBody("ApplyTwice", out);
  }

  private void reverse(final JsonMsg inMsg, final JsonMsg outMsg) throws DomatarException
  {
    final String actId = inMsg.getAttr("ActId");
    final String contractId = inMsg.getAttr("ContractId");
    final String delta = inMsg.getAttr("Delta");
    final String orig = inMsg.getAttr("OrigContextId");

    if (actId == null || contractId == null || delta == null || orig == null
        || actId.isEmpty() || contractId.isEmpty() || delta.isEmpty() || orig.isEmpty())
    {
      outMsg.addError("ReverseAdjust", "Missing ActId, ContractId, Delta, or OrigContextId");
      return;
    }

    final Obj booking = ObjDb.getObj(CantonIds.booking(actId));

    if (booking != null && booking.attrs != null
        && orig.equals(booking.attrs.getAttr(REVERSE_KEY)))
    {
      replyHolding("ReverseAdjust", actId, contractId, outMsg);
      return;
    }

    final String party = HandleSync.boundParty(actId);

    CantonClients.get().submitExercise(party, contractId, "Adjust",
        Collections.singletonMap("Delta", Amounts.negate(delta)));
    if (booking != null && booking.attrs != null)
    {
      booking.attrs.addAttr(REVERSE_KEY, orig);
      ObjDb.modifyObj(booking);
    }
    HandleSync.sync(actId);
    replyHolding("ReverseAdjust", actId, contractId, outMsg);
  }

  private static void echo(final DomatarMsgClient msgClient, final JsonMsg outMsg)
      throws DomatarException
  {
    final DomId desk = requireDesk();
    final JsonMsg again = new JsonMsg();

    again.addClsId("canton", CantonIds.DESK);
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
    BookingImpl.ensure(payer);

    final JsonMsg post = new JsonMsg();
    final ObjAttrs attrs = new ObjAttrs();

    attrs.addAttr("Delta", delta);
    if (recordUndo)
    {
      attrs.addAttr("RecordUndo", "true");
      attrs.addAttr("UndoMsgName", "AdjustHolding");
    }
    post.addClsId("canton", CantonIds.BOOKING);
    post.addRequestBody("Post", attrs);
    return msgClient.send(CantonIds.booking(payer), post);
  }

  private static void attachAdjust(final DomatarMsgClient msgClient, final String payer,
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
    msgClient.attach(SagaSlot.SLOT, SagaSlot.applied("ReverseAdjust", effect),
        OpLog.nowMs() + OpLog.sagaTtlMs());
  }

  private static void replyHolding(final String opr, final String payer,
                                   final String contractId, final JsonMsg outMsg)
      throws DomatarException
  {
    outMsg.addResponseBody(opr, holdingAttrs(payer, contractId));
  }

  private static ObjAttrs holdingAttrs(final String payer, final String contractId)
      throws DomatarException
  {
    final Contract live = live(payer, contractId);
    final Obj booking = ObjDb.getObj(CantonIds.booking(payer));
    final ObjAttrs out = new ObjAttrs();

    out.addAttr("ContractId", live.contractId);
    out.addAttr("Amount", live.payload.get("Amount"));
    out.addAttr("NetDelta", booking != null && booking.attrs != null
        && booking.attrs.getAttr("NetDelta") != null
        ? booking.attrs.getAttr("NetDelta") : "0");
    out.addAttr("Remaining", Long.toString(remaining(payer)));
    return out;
  }

  private static Contract live(final String payer, final String contractId)
      throws DomatarException
  {
    if (contractId == null || contractId.isEmpty())
      throw new DomatarException("Missing ContractId");

    final String party = HandleSync.boundParty(payer);
    final List<Contract> acs = CantonClients.get().queryAcs(party);

    for (final Contract c : acs)
    {
      if (contractId.equals(c.contractId))
        return c;
    }
    throw new DomatarException("Contract no longer visible");
  }

  private static long remaining(final String payer) throws DomatarException
  {
    final DomId desk = requireDesk();
    final Long value = PayDb.remaining(desk.hstId, desk.actId, payer);
    return value == null ? 0L : value.longValue();
  }

  private static DomId requireDesk() throws DomatarException
  {
    final DomId desk = CantonIds.desk();

    if (desk == null)
      throw new DomatarException("Canton desk is not installed");
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
