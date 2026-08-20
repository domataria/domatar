package com.domatar.util;

public interface DomatarMsgClient
{
  public String send (DomId domId, String document) throws DomatarException;

  public JsonMsg send (DomId domId, JsonMsg jsonMsg) throws DomatarException;

  public DomId getSrcId();
}
