/*
 * Copyright (c) 2024 Domatar
 */

package com.aiagent.objimpl;

import com.domatar.core.Auth;
import com.domatar.core.Context;
import com.domatar.util.JsonMsg;
import com.domatar.util.Obj;
import com.domatar.util.ObjImpl;
import com.domatar.util.DomatarException;
import com.domatar.util.DomatarMsgClient;

/**
 * Authorization gate for individual message rows.
 * Spec-AIAgent.txt PART 8.3.
 *
 * Registered in ImplMap under (aiagent, msg).
 *
 * MsgImpl has no operations of its own. Open and GetObj are inherited
 * from ObjImpl unchanged — msg rows are inert storage. This class exists
 * purely to enforce per-row authorization: the chat content is sensitive,
 * so even a verified user on a peer prv must not read someone else's
 * messages by addressing a msg-* DomId directly (e.g. via the Navigator).
 *
 * Authorization: verified-only + owner-match. Same shape as ConvImpl.
 */
public class MsgImpl extends ObjImpl
{
  @Override
  public boolean hasRights(final JsonMsg inMsg, final Obj obj,
                           final DomatarMsgClient msgClient)
      throws DomatarException
  {
    if (!Auth.isVerified(msgClient))
      return false;

    final String actId = Auth.actId(msgClient);

    return actId != null && obj != null && obj.domId != null
        && actId.equals(obj.domId.actId);
  }
}
