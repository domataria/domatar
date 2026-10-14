/*
 * Copyright (c) 2024 Domatar
 */

package com.domatar.util;

/**
 * Object handler. {@link #handleMsg} returns the response as a JSON
 * string. {@code obj} may be null when the message carries a class
 * envelope; {@link ObjImpl} loads the row.
 */
public interface DomatarInterface
{
  public String handleMsg(String msg, Obj obj, String contextPath, String contextRealPath, DomatarMsgClient msgClient)
      throws DomatarException;
}
