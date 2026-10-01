/*
 * Copyright (c) 2024 Domatar
 */

package com.hecto.objimpl;

import com.domatar.core.Auth;
import com.domatar.db.ObjDb;
import com.domatar.util.DomId;
import com.domatar.util.DomatarException;
import com.domatar.util.DomatarMsgClient;
import com.domatar.util.JsonMsg;
import com.domatar.util.Obj;

/**
 * Owner check for the Hecto facade and booking. The desk admits any
 * verified caller.
 */
final class HectoAuth
{
  private HectoAuth()
  {
  }

  static boolean isVerifiedOwner(final JsonMsg inMsg, final Obj obj,
                                 final DomatarMsgClient msgClient)
      throws DomatarException
  {
    if (!Auth.isVerified(msgClient))
      return false;
    final String actId = Auth.actId(msgClient);
    if (actId == null)
      return false;
    final String dest = destActId(inMsg, obj);
    return dest != null && actId.equals(dest);
  }

  static String destActId(final JsonMsg inMsg, final Obj obj)
      throws DomatarException
  {
    if (obj != null && obj.domId != null && obj.domId.actId != null)
      return obj.domId.actId;
    final DomId dst = inMsg != null ? inMsg.getDstId() : null;
    return dst != null ? dst.actId : null;
  }

  static Obj requireObj(final JsonMsg inMsg, final Obj obj)
      throws DomatarException
  {
    if (obj != null)
      return obj;
    final DomId dst = inMsg != null ? inMsg.getDstId() : null;
    return dst != null ? ObjDb.getObj(dst) : null;
  }
}
