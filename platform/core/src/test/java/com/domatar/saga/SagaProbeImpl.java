package com.domatar.saga;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import com.domatar.core.HttpClient;
import com.domatar.log.OpLog;
import com.domatar.util.DomatarException;
import com.domatar.util.DomatarMsgClient;
import com.domatar.util.JsonHashMap;
import com.domatar.util.JsonMsg;
import com.domatar.util.Obj;
import com.domatar.util.ObjAttrs;
import com.domatar.util.ObjImpl;

/**
 * Test-only Ping / UndoPing. UndoPing is idempotent on effect.OrigContextId.
 */
public class SagaProbeImpl extends ObjImpl
{
  static final AtomicInteger APPLY = new AtomicInteger();
  static final Set<String> SEEN = ConcurrentHashMap.newKeySet();
  static volatile Boolean skipVisitDuringApply;

  static void reset()
  {
    APPLY.set(0);
    SEEN.clear();
    skipVisitDuringApply = null;
  }

  @Override
  public String handleMsg(final String msg, final Obj obj,
      final String contextPath, final String contextRealPath,
      final DomatarMsgClient msgClient) throws DomatarException
  {
    final JsonMsg inMsg = new JsonMsg(msg);
    final String opr = inMsg.getOperation();

    if ("Compensate".equals(opr))
      return super.handleMsg(msg, obj, contextPath, contextRealPath, msgClient);

    final JsonMsg outMsg = new JsonMsg();

    if ("Ping".equals(opr))
    {
      if (msgClient instanceof HttpClient)
      {
        final HttpClient client = (HttpClient) msgClient;
        final String ctx = client.inboundContext() != null
            ? client.inboundContext().contextId : null;
        if (ctx != null)
        {
          final JsonHashMap effect = new JsonHashMap();
          effect.put("OrigContextId", ctx);
          client.attach(ctx, "Ping", SagaSlot.SLOT,
              SagaSlot.applied("UndoPing", effect),
              OpLog.nowMs() + OpLog.sagaTtlMs());
        }
      }
      outMsg.addResponseBody(opr, new ObjAttrs());
      return outMsg.toString();
    }

    if ("UndoPing".equals(opr))
    {
      skipVisitDuringApply = Boolean.valueOf(OpLog.isSkipVisit());
      final String orig = inMsg.getAttr("OrigContextId");
      if (orig != null && SEEN.add(orig))
        APPLY.incrementAndGet();
      outMsg.addResponseBody(opr, new ObjAttrs());
      return outMsg.toString();
    }

    return super.handleMsg(msg, obj, contextPath, contextRealPath, msgClient);
  }
}
