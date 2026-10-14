/*
 * Copyright (c) 2024 Domatar
 */

package com.domatar.util;

import java.util.List;

/**
 * JSON array as a {@link java.util.List}. The typed getters cast; a
 * wrong element type throws {@link ClassCastException}.
 */
public interface JsonList extends List<Object>
{
  public default Boolean getBoolean(final int i)
  {
    return (Boolean)get(i);
  }

  public default Number getNumber(final int i)
  {
    return (Number)get(i);
  }

  public default String getString(final int i)
  {
    return (String)get(i);
  }

  public default JsonList getList(final int i)
  {
    return (JsonList)get(i);
  }

  public default JsonMap getMap(final int i)
  {
    return (JsonMap)get(i);
  }
}
