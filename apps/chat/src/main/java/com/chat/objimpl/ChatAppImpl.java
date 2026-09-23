/*
 * Copyright (c) 2024 Domatar
 */

package com.chat.objimpl;

import com.domatar.core.Auth;
import com.domatar.util.JsonMsg;
import com.domatar.util.Obj;
import com.domatar.util.ObjImpl;
import com.domatar.util.DomatarException;
import com.domatar.util.DomatarMsgClient;

/** Minimal Chat app entry (Open / GetLnks via ObjImpl). */
public class ChatAppImpl extends ObjImpl
{
  @Override
  public boolean hasRights(final JsonMsg inMsg, final Obj obj,
                           final DomatarMsgClient msgClient)
      throws DomatarException
  {
    return Auth.isVerified(msgClient);
  }
}
