package com.domatar.pay;

import com.domatar.util.DomatarMsgClient;

/**
 * Payment helpers. Rebate is a no-op until Update-Payment.
 */
public final class Payment
{
  private Payment() {}

  /**
   * WHY: Update-Payment will increment pay_bal from slot "payment".
   * Saga does not read attachments here.
   */
  public static void rebateVisit(final String origContextId,
      final String origMsgName, final DomatarMsgClient client)
  {
  }
}
