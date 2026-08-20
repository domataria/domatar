package com.domatar.util;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class JsonObj
{
  Map<String, Object> map;

  public JsonObj()
  {
    map = new HashMap<String, Object>();
  }

  public JsonObj(final Map<String, Object> map)
  {
    this.map = map;
  }

  public void add(final String key, final Object value) throws DomatarException
  {
    if (!(value instanceof String || value instanceof List || value instanceof Map))
      throw new DomatarException("Illegal value of Json Object");

    map.put(key, value);
  }
}
