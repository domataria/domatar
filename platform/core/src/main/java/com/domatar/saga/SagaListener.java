package com.domatar.saga;

import com.domatar.log.OpLogAdmit;
import com.domatar.log.OpLogListener;
import com.domatar.util.DomatarException;
import com.domatar.util.DomatarMsgClient;
import com.domatar.util.JsonMsg;
import com.domatar.util.Obj;

/**
 * Compensate is admitted by the platform rule, not by the class
 * {@code hasRights}. After a successful handler, the saga slot is
 * attached here.
 */
public final class SagaListener implements OpLogListener
{
  @Override
  public Boolean authorize(final OpLogAdmit admit) throws DomatarException
  {
    if (admit == null || admit.inMsg == null)
      return null;
    if (!"Compensate".equals(admit.inMsg.getOperation()))
      return null;
    return Boolean.valueOf(Compensate.admitInbound(admit.inMsg, admit.client));
  }

  @Override
  public void onHandled(final DomatarMsgClient client, final JsonMsg inMsg,
      final Obj obj, final String reply) throws DomatarException
  {
    Compensate.autoAttachIfNeeded(client, inMsg, obj, reply);
  }
}
