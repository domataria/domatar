package com.domatar.util;

public interface DomatarInterface
{
  public String handleMsg(String msg, Obj obj, String contextPath, String contextRealPath, DomatarMsgClient msgClient)
      throws DomatarException;
}
