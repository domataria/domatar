package com.domatar.saga;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

import com.domatar.core.Auth;
import com.domatar.core.ClsResolver;
import com.domatar.core.Context;
import com.domatar.db.OpLogDb;
import com.domatar.log.OpDst;
import com.domatar.log.OpLog;
import com.domatar.log.OpMsg;
import com.domatar.pay.Payment;
import com.domatar.util.DomId;
import com.domatar.util.DomatarException;
import com.domatar.util.DomatarMsgClient;
import com.domatar.util.JsonMap;
import com.domatar.util.JsonMsg;
import com.domatar.util.Obj;
import com.domatar.util.ObjAttrs;
import com.domatar.util.ObjImpl;

/**
 * Platform Compensate control plane: admit, cascade, in-process apply.
 */
public final class Compensate
{
  @FunctionalInterface
  interface ChildSender
  {
    JsonMsg send(DomatarMsgClient client, DomId dst, JsonMsg msg)
        throws DomatarException;
  }

  static ChildSender childSender = Compensate::defaultSendChild;

  static Function<String, String> compensatesLookup;

  private Compensate() {}

  static void resetTestHooks()
  {
    childSender = Compensate::defaultSendChild;
    compensatesLookup = null;
  }

  /**
   * Spec 5.2. ContextId is not Auth. Missing saga slot counts as not done.
   */
  public static boolean admit(final String origMsgName, final OpDst visit,
      final JsonMap sagaSlot, final String verdictActId,
      final String inboundSrcDomId)
  {
    if (origMsgName == null || origMsgName.isEmpty()
        || "Compensate".equals(origMsgName))
      return false;

    if (visit == null)
      return true;

    if (!Objects.equals(visit.actId, verdictActId))
      return false;

    if (SagaSlot.isDone(SagaSlot.status(sagaSlot)))
      return true;

    return inboundSrcDomId != null
        && inboundSrcDomId.equals(visit.callerDomId);
  }

  public static boolean admitInbound(final JsonMsg inMsg, final DomatarMsgClient client)
      throws DomatarException
  {
    if (!Auth.isPlatformClient(client) || inMsg == null)
      return false;

    final String origMsgName = inMsg.getAttr("OrigMsgName");
    if (origMsgName == null || origMsgName.isEmpty())
      return false;

    final String origContextId = inMsg.getAttr("OrigContextId");
    final DomId dst = inMsg.getDstId();
    final OpDst visit = dst == null ? null
        : OpLogDb.getVisit(dst.hstId, origContextId, dst.toString(), origMsgName);
    final JsonMap slot = SagaSlot.parse(
        client.attachment(origContextId, origMsgName, SagaSlot.SLOT));
    return admit(origMsgName, visit, slot, Auth.actId(client),
        Auth.callerDomId(client));
  }

  public static String run(final ObjImpl impl, final JsonMsg inMsg,
      final Obj obj, final DomatarMsgClient client) throws DomatarException
  {
    if (!Auth.isPlatformClient(client))
    {
      return reply(CompensateResult.failed(
          inMsg != null ? inMsg.getAttr("OrigContextId") : null,
          inMsg != null && inMsg.getDstId() != null
              ? inMsg.getDstId().toString() : null,
          inMsg != null ? inMsg.getAttr("OrigMsgName") : null,
          "client"));
    }

    final String origContextId = inMsg.getAttr("OrigContextId");
    final String origMsgName = inMsg.getAttr("OrigMsgName");
    final DomId dstId = inMsg.getDstId();
    final String dstDomId = dstId != null ? dstId.toString() : null;
    final String hstId = dstId != null ? dstId.hstId : null;

    if ("Compensate".equals(origMsgName))
      return reply(CompensateResult.denied(origContextId, dstDomId, origMsgName,
          "compensate"));

    final OpDst visit = (hstId == null || origContextId == null || origMsgName == null)
        ? null
        : OpLogDb.getVisit(hstId, origContextId, dstDomId, origMsgName);
    if (visit == null)
      return reply(CompensateResult.noop(origContextId, dstDomId, origMsgName,
          "no visit"));

    JsonMap slot = SagaSlot.parse(
        client.attachment(origContextId, origMsgName, SagaSlot.SLOT));
    if (SagaSlot.isDone(SagaSlot.status(slot)))
    {
      if (SagaSlot.STATUS_COMPENSATED.equals(SagaSlot.status(slot)))
        Payment.rebateVisit(origContextId, origMsgName, client);
      return reply(CompensateResult.noop(origContextId, dstDomId, origMsgName,
          "already"));
    }

    slot = SagaSlot.withStatus(slot, SagaSlot.STATUS_COMPENSATING);
    final long exp = OpLog.nowMs() + OpLog.sagaTtlMs();
    client.attach(origContextId, origMsgName, SagaSlot.SLOT, slot, exp);

    final List<CompensateResult> children = new ArrayList<>();
    boolean anyChildFailed = false;
    final List<OpMsg> edges = client.outMsgs(origContextId);
    for (int i = edges.size() - 1; i >= 0; i--)
    {
      final OpMsg edge = edges.get(i);
      if ("Compensate".equals(edge.outMsgName))
        continue;

      final JsonMsg child = new JsonMsg();
      final ObjAttrs attrs = new ObjAttrs();
      attrs.addAttr("OrigContextId", origContextId);
      attrs.addAttr("OrigMsgName", edge.outMsgName);
      child.addRequestBody("Compensate", attrs);

      CompensateResult childRes;
      try
      {
        final JsonMsg ack = childSender.send(client, new DomId(edge.dstDomId),
            child);
        if (ack != null && ack.isFailure())
        {
          childRes = CompensateResult.denied(origContextId, edge.dstDomId,
              edge.outMsgName, ack.getErrorMsg());
        }
        else
        {
          childRes = CompensateResult.fromMsg(ack);
          if (childRes.outcome == null || childRes.outcome.isEmpty())
            childRes = CompensateResult.failed(origContextId, edge.dstDomId,
                edge.outMsgName, "child");
        }
      }
      catch (final DomatarException e)
      {
        childRes = CompensateResult.failed(origContextId, edge.dstDomId,
            edge.outMsgName, "child");
      }

      children.add(childRes);
      if (CompensateResult.OUTCOME_DENIED.equals(childRes.outcome)
          || CompensateResult.OUTCOME_FAILED.equals(childRes.outcome))
        anyChildFailed = true;
    }

    if (anyChildFailed)
    {
      slot = SagaSlot.withStatus(slot, SagaSlot.STATUS_FAILED);
      client.attach(origContextId, origMsgName, SagaSlot.SLOT, slot, exp);
      final CompensateResult failed = CompensateResult.failed(origContextId,
          dstDomId, origMsgName, "child");
      failed.children.addAll(children);
      return reply(failed);
    }

    final String compensatesName = lookupCompensates(obj, origMsgName);
    if (compensatesName == null || compensatesName.isEmpty()
        || "Compensate".equals(compensatesName)
        || lookupCompensates(obj, compensatesName) != null)
    {
      slot = SagaSlot.withStatus(slot, SagaSlot.STATUS_IRREVERSIBLE);
      client.attach(origContextId, origMsgName, SagaSlot.SLOT, slot, exp);
      final CompensateResult irr = CompensateResult.irreversible(origContextId,
          dstDomId, origMsgName, "irreversible");
      irr.children.addAll(children);
      return reply(irr);
    }

    final CompensateResult applyRes = invokeCompensates(impl, obj, client,
        inMsg, compensatesName, slot, origContextId, dstDomId, origMsgName);
    if (applyRes != null)
    {
      applyRes.children.addAll(children);
      return reply(applyRes);
    }

    slot = SagaSlot.withStatus(slot, SagaSlot.STATUS_COMPENSATED);
    client.attach(origContextId, origMsgName, SagaSlot.SLOT, slot, exp);
    Payment.rebateVisit(origContextId, origMsgName, client);
    final CompensateResult ok = new CompensateResult(origContextId, dstDomId,
        origMsgName, CompensateResult.OUTCOME_COMPENSATED, "",
        new ArrayList<>(children));
    return reply(ok);
  }

  public static void autoAttachIfNeeded(final DomatarMsgClient client,
      final JsonMsg inMsg, final Obj obj, final String retMsg)
      throws DomatarException
  {
    if (client == null || inMsg == null || retMsg == null)
      return;

    final JsonMsg ret = new JsonMsg(retMsg);
    if (ret.getErrorMsg() != null)
      return;

    final String msgName = inMsg.getOperation();
    if (msgName == null || "Compensate".equals(msgName))
      return;

    final String compensates = lookupCompensates(obj, msgName);
    if (compensates == null || compensates.isEmpty())
      return;

    final String ctx = client.contextId();
    if (ctx == null)
      return;

    if (SagaSlot.parse(client.attachment(ctx, msgName, SagaSlot.SLOT)) != null)
      return;

    client.attach(ctx, msgName, SagaSlot.SLOT,
        SagaSlot.applied(compensates, null),
        OpLog.nowMs() + OpLog.sagaTtlMs());
  }

  private static CompensateResult invokeCompensates(final ObjImpl impl,
      final Obj obj, final DomatarMsgClient client, final JsonMsg inMsg,
      final String compensatesName, final JsonMap slot,
      final String origContextId, final String dstDomId,
      final String origMsgName) throws DomatarException
  {
    OpLog.skipVisit(true);
    try
    {
      final JsonMsg apply = new JsonMsg();
      final DomId dst = inMsg.getDstId();
      final Context stamped = Auth.stampedContext(client);
      if (dst != null && stamped != null)
        apply.addRequestHead(client.getSrcId(), dst, stamped);
      if (obj != null && obj.clsAppId != null && obj.clsId != null)
        apply.addClsId(obj.clsAppId, obj.clsId);
      apply.addRequestBody(compensatesName, effectAttrs(slot));
      if (stamped != null)
        apply.setContext(stamped);

      if (!impl.hasRights(apply, obj, client))
      {
        final JsonMap failed = SagaSlot.withStatus(slot, SagaSlot.STATUS_FAILED);
        client.attach(origContextId, origMsgName, SagaSlot.SLOT, failed,
            OpLog.nowMs() + OpLog.sagaTtlMs());
        return CompensateResult.denied(origContextId, dstDomId, origMsgName,
            "denied");
      }

      final String applyRet = impl.handleMsg(apply.toString(), obj,
          Auth.contextPath(client), Auth.contextRealPath(client), client);
      final JsonMsg applyMsg = new JsonMsg(applyRet);
      if (applyMsg.isFailure() || applyMsg.getErrorMsg() != null)
      {
        final JsonMap failed = SagaSlot.withStatus(slot, SagaSlot.STATUS_FAILED);
        client.attach(origContextId, origMsgName, SagaSlot.SLOT, failed,
            OpLog.nowMs() + OpLog.sagaTtlMs());
        return CompensateResult.failed(origContextId, dstDomId, origMsgName,
            applyMsg.getErrorMsg());
      }
      return null;
    }
    finally
    {
      OpLog.skipVisit(false);
    }
  }

  private static ObjAttrs effectAttrs(final JsonMap slot)
      throws DomatarException
  {
    if (slot == null)
      return new ObjAttrs();

    final Object effect = slot.get("effect");
    if (effect instanceof JsonMap)
      return new ObjAttrs((JsonMap) effect);
    if (effect instanceof String)
    {
      final String s = ((String) effect).trim();
      if (!s.isEmpty())
        return new ObjAttrs(s);
    }
    return new ObjAttrs();
  }

  private static String lookupCompensates(final Obj obj, final String msgName)
      throws DomatarException
  {
    if (msgName == null || msgName.isEmpty())
      return null;
    if (compensatesLookup != null)
      return compensatesLookup.apply(msgName);

    if (obj == null)
      return null;
    return ClsResolver.compensates(obj.clsAppId, obj.clsId, msgName,
        clsHint(obj));
  }

  private static DomId clsHint(final Obj obj)
  {
    if (obj == null || obj.domId == null || obj.clsAppId == null
        || obj.clsId == null)
      return null;
    try
    {
      return new DomId(obj.domId.hstId, obj.clsAppId, obj.domId.actId,
          obj.clsId + "Cls");
    }
    catch (final DomatarException e)
    {
      return null;
    }
  }

  private static JsonMsg defaultSendChild(final DomatarMsgClient client,
      final DomId dst, final JsonMsg msg) throws DomatarException
  {
    return client.send(dst, msg);
  }

  private static String reply(final CompensateResult result)
      throws DomatarException
  {
    final JsonMsg out = new JsonMsg();
    out.addResponseBody("Compensate", new ObjAttrs(result.toMap()));
    return out.toString();
  }
}
