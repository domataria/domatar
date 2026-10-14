/*
 * Copyright (c) 2024 Domatar
 */

package com.domatar.util;

import java.util.Map;

/**
 * JSON object as a {@link java.util.Map}. The typed getters cast; a
 * wrong value type throws {@link ClassCastException}. Absent keys
 * return null from {@link java.util.Map#get}, and the cast of null is
 * null.
 */
public interface JsonMap extends Map<String, Object>
{
  public default Boolean getBoolean(final String key)
  {
    return (Boolean)get(key);
  }

  public default Number getNumber(final String key)
  {
    return (Number)get(key);
  }

  public default String getString(final String key)
  {
    return (String)get(key);
  }

  public default JsonList getList(final String key)
  {
    return (JsonList)get(key);
  }

  public default JsonMap getMap(final String key)
  {
    return (JsonMap)get(key);
  }
}
