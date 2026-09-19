/*
 * Copyright (c) 2024 Domatar
 */

package com.canton.objimpl;

import com.domatar.core.Auth;
import com.domatar.core.Context;
import com.domatar.db.ObjDb;
import com.domatar.util.DomId;
import com.domatar.util.JsonMsg;
import com.domatar.util.Obj;
import com.domatar.util.DomatarException;

/**
 * Shared owner check for Canton handlers.
 *
 * Spec PART 7: verified and req.actId == dest.actId. The request act is
 * the verified {@link Context#actId} (the logged-in user the agent acts
 * as), not the hop's SrcId object. Agent facade sends always set a class
 * envelope, so dispatch leaves obj null; dest actId then comes from DstId.
 */
final class CantonAuth
{
  private CantonAuth() {}

  static boolean isVerifiedOwner(final JsonMsg inMsg, final Obj obj)
      throws DomatarException
  {
    if (!Auth.isVerified(inMsg))
      return false;
    final Context ctx = inMsg.getContext();
    if (ctx == null || ctx.actId == null)
      return false;
    final String destActId = destActId(inMsg, obj);
    return destActId != null && ctx.actId.equals(destActId);
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
