package com.domatar.util;

import java.util.LinkedHashMap;

public class JsonHashMap extends LinkedHashMap<String, Object> implements JsonMap
{
  private static final long serialVersionUID = -1299723788678993353L;

  public JsonHashMap()
  {
    super();
  }

  public JsonHashMap(int size)
  {
    super(size);
  }

  public JsonHashMap(String json) throws DomatarException
  {
    super();

    JsonStr jsonStr = new JsonStr(json);

    jsonStr.parseMap(this);
  }

  @Override
  public String toString()
  {
    try
    {
      return Json.toJson(this);
    }
    catch (DomatarException e)
    {
      e.printStackTrace();
      return "Error: Malformed JSON";
    }
  }
}
