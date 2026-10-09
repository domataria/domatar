/*
 * Copyright (c) 2024 Domatar
 */

package com.travel.objimpl;

import com.domatar.util.DomatarException;
import com.domatar.util.DomatarMsgClient;
import com.domatar.util.JsonMsg;
import com.domatar.util.Obj;
import com.domatar.util.ObjImpl;

/**
 * Trips container. The facade is the only entry point.
 */
public class TravelTripsImpl extends ObjImpl
{
  @Override
  public boolean hasRights(final JsonMsg inMsg, final Obj obj,
                           final DomatarMsgClient msgClient)
      throws DomatarException
  {
    if (inMsg != null && "Compensate".equals(inMsg.getOperation()))
      return super.hasRights(inMsg, obj, msgClient);
    return TravelAppImpl.isOwner(inMsg, obj, msgClient);
  }

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
      return TravelAppImpl.unauthorized(opr);

    return TravelAppImpl.unknown(opr);
  }
}
