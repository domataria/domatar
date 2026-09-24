package com.domatar.pay;

import com.domatar.core.Auth;
import com.domatar.core.ClsResolver;
import com.domatar.db.PayDb;
import com.domatar.log.OpLog;
import com.domatar.util.DomId;
import com.domatar.util.DomatarException;
import com.domatar.util.DomatarMsgClient;
import com.domatar.util.JsonMsg;
import com.domatar.util.Obj;

/**
 * Payment helpers. Rebate stays empty until the compensated-visit phase.
 */
public final class Payment
{
  private Payment() {}

  public static boolean isExempt(final String msgName)
  {
    return "GetCls".equals(msgName) || "Compensate".equals(msgName);
  }

  /**
   * WHY: envelope dispatch often has no obj row, so Cost is read from
   * the class the message names when the obj does not carry one.
   */
  static long listPrice(final JsonMsg inMsg, final Obj obj) throws DomatarException
  {
    final String[] cls = classIds(inMsg, obj);
    if (cls == null || inMsg == null)
      return 0L;
    return ClsResolver.cost(cls[0], cls[1], inMsg.getOperation(), clsHint(obj));
  }

  /**
   * WHY: the compensated-visit listener calls this. Saga does not
   * read the payment slot. The write goes through PayDb, because
   * handler attach ignores slot "payment".
   */
  public static void rebateVisit(final String origContextId,
      final String origMsgName, final DomatarMsgClient client)
      throws DomatarException
  {
    if (origContextId == null || origMsgName == null || client == null)
      return;

    final String dotted = Auth.destinationDomId(client);
    if (dotted == null)
      return;

    final DomId dst;
    try
    {
      dst = new DomId(dotted);
    }
    catch (final DomatarException e)
    {
      return;
    }

    PayDb.rebate(dst.hstId, origContextId, dst.toString(), origMsgName, OpLog.nowMs());
  }

  private static String[] classIds(final JsonMsg inMsg, final Obj obj)
      throws DomatarException
  {
    if (obj != null && obj.clsAppId != null && !obj.clsAppId.isEmpty()
        && obj.clsId != null && !obj.clsId.isEmpty())
      return new String[] { obj.clsAppId, obj.clsId };

    if (inMsg == null)
      return null;

    final String clsAppId = inMsg.getClsAppId();
    final String clsId = inMsg.getClsId();
    if (clsAppId == null || clsAppId.isEmpty() || clsId == null || clsId.isEmpty())
      return null;
    return new String[] { clsAppId, clsId };
  }

  private static DomId clsHint(final Obj obj)
  {
    if (obj == null || obj.domId == null || obj.clsAppId == null || obj.clsId == null)
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
}
