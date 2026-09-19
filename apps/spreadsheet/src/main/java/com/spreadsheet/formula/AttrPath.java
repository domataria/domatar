/*
 * Copyright (c) 2024 Domatar
 */

package com.spreadsheet.formula;

import java.util.List;
import java.util.Map;

import com.domatar.util.DomatarException;
import com.domatar.util.Json;

/**
 * Walks a JSON Attr value along a suffix path after the root attrName.
 * Suffix grammar: { '[' digits ']' | '.' attrName }
 * Indices are 0-based. Missing step returns null (caller maps to #N/A).
 */
public final class AttrPath
{
  private AttrPath() {}

  /**
   * @param rootValue  the Attr string (scalar or JSON object/array)
   * @param suffix     e.g. "[2].Name" or ".Holders[0]"; empty = root as-is
   * @return formatted leaf, or null if the path does not resolve
   */
  public static String walk(final String rootValue, final String suffix)
  {
    if (suffix == null || suffix.isEmpty())
      return rootValue;

    Object cur;
    try
    {
      cur = coerce(rootValue);
    }
    catch (final DomatarException e)
    {
      return null;
    }

    int i = 0;
    while (i < suffix.length())
    {
      final char ch = suffix.charAt(i);
      try
      {
        if (ch == '[')
        {
          int j = i + 1;
          while (j < suffix.length() && Character.isDigit(suffix.charAt(j)))
            j++;
          if (j == i + 1 || j >= suffix.length() || suffix.charAt(j) != ']')
            return null;
          final int idx = Integer.parseInt(suffix.substring(i + 1, j));
          cur = coerce(cur);
          if (!(cur instanceof List))
            return null;
          final List<?> list = (List<?>) cur;
          if (idx < 0 || idx >= list.size())
            return null;
          cur = list.get(idx);
          i = j + 1;
        }
        else if (ch == '.')
        {
          int j = i + 1;
          while (j < suffix.length()
                 && (Character.isLetterOrDigit(suffix.charAt(j))
                     || suffix.charAt(j) == '_'))
            j++;
          if (j == i + 1)
            return null;
          final String key = suffix.substring(i + 1, j);
          cur = coerce(cur);
          if (!(cur instanceof Map))
            return null;
          final Map<?, ?> map = (Map<?, ?>) cur;
          if (!map.containsKey(key))
            return null;
          cur = map.get(key);
          i = j;
        }
        else
          return null;
      }
      catch (final NumberFormatException | DomatarException e)
      {
        return null;
      }
    }

    return format(cur);
  }

  static String rootName(final String path)
  {
    if (path == null || path.isEmpty())
      return "";
    int end = 0;
    while (end < path.length())
    {
      final char ch = path.charAt(end);
      if (ch == '[' || ch == '.')
        break;
      end++;
    }
    return path.substring(0, end);
  }

  private static Object coerce(final Object v) throws DomatarException
  {
    if (v == null)
      return null;
    if (!(v instanceof String))
      return v;
    final String s = ((String) v).trim();
    if (s.isEmpty())
      return s;
    final char c = s.charAt(0);
    if (c != '{' && c != '[')
      return s;
    return Json.parse(s);
  }

  private static String format(final Object v)
  {
    if (v == null)
      return null;
    if (v instanceof String)
      return (String) v;
    if (v instanceof Number || v instanceof Boolean)
      return String.valueOf(v);
    try
    {
      return Json.toJson(v);
    }
    catch (final DomatarException e)
    {
      return null;
    }
  }
}
