/*
 * Copyright (c) 2024 Domatar
 */

package com.navigator.objimpl;

import com.domatar.core.Auth;
import com.domatar.util.JsonMsg;
import com.domatar.util.Obj;
import com.domatar.util.ObjImpl;
import com.domatar.util.DomatarException;
import com.domatar.util.DomatarMsgClient;

/**
 * Handler for class (navigator, app).
 *
 * Represents one installed application in a user's Navigator tree
 * (e.g. the "Quippin" node that sits under root). Inherits Open and
 * GetObj from ObjImpl. The only override is hasRights: verified-only,
 * same as NavRootImpl. (Owner-match is a PART 14 TODO.)
 */
public class NavAppImpl extends ObjImpl
{
  @Override
  public boolean hasRights(final JsonMsg inMsg, final Obj obj, final DomatarMsgClient msgClient)
      throws DomatarException
  {
    return Auth.isVerified(inMsg);
  }
}
