package com.domatar.log;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import com.domatar.core.Context;
import com.domatar.core.DomatarConfig;
import com.domatar.core.HandlerClient;
import com.domatar.core.Trust;
import com.domatar.crypto.Provenance;
import com.domatar.db.DbConnection;
import com.domatar.db.OpLogDb;
import com.domatar.util.DomatarException;
import com.domatar.util.DomatarMsgClient;
import com.domatar.util.JsonMsg;
import com.domatar.util.Obj;
import com.domatar.util.ObjImpl;
import com.domatar.util.Rights;

/**
 * Operation-log facade: skipVisit nesting, visit TTL, and the admit
 * listeners. Feature packages register; dispatch does not name them.
 */
public final class OpLog
{
  private static final CopyOnWriteArrayList<OpLogListener> LISTENERS =
      new CopyOnWriteArrayList<OpLogListener>();

  private static final ThreadLocal<Integer> SKIP = new ThreadLocal<>();

  private OpLog() {}

  /**
   * Nested skip flag for in-process Compensates (Saga) and tests.
   * {@code true} increments; {@code false} decrements and clears at 0.
   */
  public static void skipVisit(final boolean skip)
  {
    if (skip)
    {
      final Integer n = SKIP.get();
      SKIP.set(n == null ? 1 : n + 1);
      return;
    }

    final Integer n = SKIP.get();
    if (n == null)
      return;

    if (n <= 1)
      SKIP.remove();
    else
      SKIP.set(n - 1);
  }

  public static boolean isSkipVisit()
  {
    final Integer n = SKIP.get();
    return n != null && n > 0;
  }

  public static long visitTtlMs()
  {
    return DomatarConfig.getVisitTtlMs();
  }

  public static long sagaTtlMs()
  {
    return DomatarConfig.getSagaTtlMs();
  }

  public static long paymentTtlMs()
  {
    return DomatarConfig.getPaymentTtlMs();
  }

  public static long nowMs()
  {
    return System.currentTimeMillis();
  }

  /** Directory carve-out: class envelope {@code hst}/{@code hsts}. */
  public static boolean isDirectory(final JsonMsg inMsg)
  {
    if (inMsg == null)
      return false;

    try
    {
      return "hst".equals(inMsg.getClsAppId())
          && "hsts".equals(inMsg.getClsId());
    }
    catch (final DomatarException e)
    {
      return false;
    }
  }

  /**
   * After hasRights admits: snapshot (no-op if already taken) then upsert.
   * Returns thisAdmitIsFirst (false if skipped).
   */
  public static boolean admitIfNeeded(final HandlerClient client, final JsonMsg inMsg,
      final Obj obj, final Provenance prov) throws DomatarException
  {
    if (client == null)
      return false;

    if (isSkipVisit() || isDirectory(inMsg)
        || prov == null || prov.isUnsignedHttp())
    {
      client.setAdmitFirst(false);
      return false;
    }

    final Context ctx = client.stampedContext();
    if (ctx == null || ctx.contextId == null)
    {
      client.setAdmitFirst(false);
      return false;
    }

    final String dstDomId = inMsg.getDstId().toString();
    final String msgName = inMsg.getOperation();
    client.snapshotPriors(ctx.contextId, dstDomId, msgName);

    final String callerDomId = callerDomId(client, prov);
    final String actId = ctx.trust == Trust.ACCOUNT ? ctx.actId : null;
    final String trust = ctx.trust.name();
    final String hstId = inMsg.getDstId().hstId;

    client.setAdmitFirst(OpLogDb.upsertVisit(
        hstId, ctx.contextId, dstDomId, msgName,
        actId, callerDomId, trust, nowMs(), visitTtlMs()));
    return client.admitWasFirst();
  }

  private static String callerDomId(final HandlerClient client, final Provenance prov)
      throws DomatarException
  {
    if (prov == null || prov.isEmpty())
      return client.getSrcId().toString();

    return prov.path().last().srcDomId;
  }

  /** Replaces an existing listener of the same class. */
  public static void register(final OpLogListener listener)
  {
    if (listener == null)
      return;

    for (int i = 0; i < LISTENERS.size(); i++)
    {
      if (LISTENERS.get(i).getClass() == listener.getClass())
      {
        LISTENERS.set(i, listener);
        return;
      }
    }
    LISTENERS.add(listener);
  }

  /**
   * rights() first, unless a listener authorizes the call (Compensate).
   * Then listener vetoes, then the visit. A joined first admit shares
   * one transaction; {@link OpLogDeny} rolls it back.
   */
  public static boolean admit(final ObjImpl impl, final HandlerClient client,
      final JsonMsg inMsg, final Obj obj, final Provenance prov)
      throws DomatarException
  {
    final OpLogAdmit admit = new OpLogAdmit(client, inMsg, obj, prov);
    boolean authorized = false;

    for (final OpLogListener listener : LISTENERS)
    {
      final Boolean answer = listener.authorize(admit);
      if (Boolean.FALSE.equals(answer))
        return false;
      if (Boolean.TRUE.equals(answer))
        authorized = true;
    }

    if (!authorized)
    {
      admit.rights = impl.rights(inMsg, obj, client);
      if (admit.rights == Rights.DENY)
        return false;
    }

    for (final OpLogListener listener : LISTENERS)
    {
      if (!listener.beforeAdmit(admit))
        return false;
    }

    final List<OpLogListener> joined = new ArrayList<OpLogListener>();
    for (final OpLogListener listener : LISTENERS)
    {
      if (listener.joinFirstAdmit(admit))
        joined.add(listener);
    }

    if (joined.isEmpty())
    {
      admitIfNeeded(client, inMsg, obj, prov);
      return true;
    }

    final Context ctx = client.stampedContext();
    if (ctx == null || ctx.contextId == null)
    {
      admitIfNeeded(client, inMsg, obj, prov);
      return true;
    }

    final String dstDomId = inMsg.getDstId().toString();
    final String msgName = inMsg.getOperation();
    final String hstId = inMsg.getDstId().hstId;
    final String callerDomId = callerDomId(client, prov);
    final String actId = ctx.trust == Trust.ACCOUNT ? ctx.actId : null;
    final String trust = ctx.trust.name();
    final long now = nowMs();

    try
    {
      OpLogDb.inTransaction(new OpLogDb.TxWork()
      {
        @Override
        public void run(final DbConnection conn) throws DomatarException
        {
          admit.firstAdmit = OpLogDb.upsertVisitOn(
              conn, hstId, ctx.contextId, dstDomId, msgName,
              actId, callerDomId, trust, now, visitTtlMs());
          if (!admit.firstAdmit)
            return;

          for (final OpLogListener listener : joined)
            listener.onFirstAdmit(conn, admit);
        }
      });
    }
    catch (final OpLogDeny denied)
    {
      client.setAdmitFirst(false);
      return false;
    }

    client.setAdmitFirst(admit.firstAdmit);
    return true;
  }

  public static void handled(final DomatarMsgClient client, final JsonMsg inMsg,
      final Obj obj, final String reply) throws DomatarException
  {
    for (final OpLogListener listener : LISTENERS)
      listener.onHandled(client, inMsg, obj, reply);
  }

  public static void compensated(final String origContextId, final String origMsgName,
      final DomatarMsgClient client) throws DomatarException
  {
    for (final OpLogListener listener : LISTENERS)
      listener.onCompensated(origContextId, origMsgName, client);
  }

  public static boolean isReservedSlot(final String slot)
  {
    if (slot == null)
      return false;

    for (final OpLogListener listener : LISTENERS)
    {
      if (slot.equals(listener.reservedSlot()))
        return true;
    }
    return false;
  }
}
