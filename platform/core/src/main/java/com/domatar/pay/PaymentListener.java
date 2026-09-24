package com.domatar.pay;

import com.domatar.core.Context;
import com.domatar.core.Trust;
import com.domatar.db.DbConnection;
import com.domatar.db.OpLogDb;
import com.domatar.db.PayDb;
import com.domatar.log.OpLog;
import com.domatar.log.OpLogAdmit;
import com.domatar.log.OpLogDeny;
import com.domatar.log.OpLogListener;
import com.domatar.util.DomId;
import com.domatar.util.DomatarException;
import com.domatar.util.DomatarMsgClient;
import com.domatar.util.Json;
import com.domatar.util.JsonMsg;
import com.domatar.util.Obj;
import com.domatar.util.Rights;

/**
 * Draws once on the first admit of a priced visit, and rebates when
 * that visit is compensated.
 */
public final class PaymentListener implements OpLogListener
{
  @Override
  public boolean beforeAdmit(final OpLogAdmit admit) throws DomatarException
  {
    if (drawAmount(admit) == null)
      return true;
    return identified(admit);
  }

  @Override
  public boolean joinFirstAdmit(final OpLogAdmit admit) throws DomatarException
  {
    return drawAmount(admit) != null && identified(admit);
  }

  @Override
  public void onFirstAdmit(final DbConnection conn, final OpLogAdmit admit)
      throws DomatarException
  {
    final Long amount = drawAmount(admit);
    if (amount == null)
      return;

    final Context ctx = admit.client.stampedContext();
    final DomId dst = admit.inMsg.getDstId();
    final int rows = PayDb.drawOn(
        conn, dst.hstId, dst.actId, ctx.actId, amount.longValue(), OpLog.nowMs());
    if (rows != 1)
      throw new OpLogDeny();

    final String body = Json.toJson(PaymentSlot.drawn(amount.longValue(), dst.actId, ctx.actId));
    final long expires = OpLog.nowMs() + OpLog.paymentTtlMs();
    OpLogDb.attachOn(conn, dst.hstId, ctx.contextId, dst.toString(),
        admit.inMsg.getOperation(), PaymentSlot.SLOT, body, expires);
    OpLogDb.recomputeAttachExpiresAtOn(conn, dst.hstId, ctx.contextId,
        dst.toString(), admit.inMsg.getOperation());
  }

  @Override
  public void onCompensated(final String origContextId, final String origMsgName,
      final DomatarMsgClient client) throws DomatarException
  {
    Payment.rebateVisit(origContextId, origMsgName, client);
  }

  @Override
  public String reservedSlot()
  {
    return PaymentSlot.SLOT;
  }

  private static Long drawAmount(final OpLogAdmit admit) throws DomatarException
  {
    if (admit == null || admit.rights != Rights.PRICED || admit.inMsg == null)
      return null;
    if (Payment.isExempt(admit.inMsg.getOperation()))
      return null;

    final long price = Payment.listPrice(admit.inMsg, admit.obj);
    if (price <= 0L)
      return null;
    return Long.valueOf(price);
  }

  private static boolean identified(final OpLogAdmit admit) throws DomatarException
  {
    if (admit.client == null || admit.inMsg == null)
      return false;

    final Context ctx = admit.client.stampedContext();
    final DomId dst = admit.inMsg.getDstId();
    return ctx != null && ctx.trust == Trust.ACCOUNT && ctx.actId != null
        && dst != null && dst.actId != null;
  }
}
