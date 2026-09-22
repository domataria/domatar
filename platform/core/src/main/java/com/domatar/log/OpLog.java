package com.domatar.log;

import com.domatar.core.Context;
import com.domatar.core.DomatarConfig;
import com.domatar.core.HttpClient;
import com.domatar.core.Trust;
import com.domatar.crypto.Provenance;
import com.domatar.db.OpLogDb;
import com.domatar.util.DomatarException;
import com.domatar.util.JsonMsg;
import com.domatar.util.Obj;

/**
 * Operation-log facade: skipVisit nesting, visit TTL, saga TTL, and visit admit.
 */
public final class OpLog
{
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
  public static boolean admitIfNeeded(final HttpClient client, final JsonMsg inMsg,
      final Obj obj, final Provenance prov) throws DomatarException
  {
    if (client == null)
      return false;

    if (isSkipVisit() || isDirectory(inMsg)
        || prov == null || prov.isUnsignedHttp())
    {
      client.thisAdmitIsFirst = false;
      return false;
    }

    final Context ctx = client.inboundContext();
    if (ctx == null || ctx.contextId == null)
    {
      client.thisAdmitIsFirst = false;
      return false;
    }

    final String dstDomId = inMsg.getDstId().toString();
    final String msgName = inMsg.getOperation();
    client.snapshotPriors(ctx.contextId, dstDomId, msgName);

    final String callerDomId = callerDomId(client, prov);
    final String actId = ctx.trust == Trust.ACCOUNT ? ctx.actId : null;
    final String trust = ctx.trust.name();
    final String hstId = inMsg.getDstId().hstId;

    client.thisAdmitIsFirst = OpLogDb.upsertVisit(
        hstId, ctx.contextId, dstDomId, msgName,
        actId, callerDomId, trust, nowMs(), visitTtlMs());
    return client.thisAdmitIsFirst;
  }

  private static String callerDomId(final HttpClient client, final Provenance prov)
      throws DomatarException
  {
    if (prov.isEmpty())
      return client.getSrcId().toString();

    return prov.path().last().srcDomId;
  }
}
