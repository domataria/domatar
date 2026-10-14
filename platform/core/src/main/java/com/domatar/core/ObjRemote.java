/*
 * Copyright (c) 2024 Domatar
 */

package com.domatar.core;

import com.domatar.util.Obj;
import com.domatar.util.DomId;
import com.domatar.util.DomatarMsgClient;

/**
 * Unused. {@link #getObj} always returns null. Load a row with
 * {@link com.domatar.db.ObjDb#getObj} (null when absent) or
 * {@code new Obj(domId, msgClient)} (sends GetObj).
 */
public class ObjRemote
{
  public static Obj getObj(final DomId domId, final DomatarMsgClient msgHandler)
  {
    return null;
  }
}
