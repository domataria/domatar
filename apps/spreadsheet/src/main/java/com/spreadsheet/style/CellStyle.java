/*
 * Copyright (c) 2024 Domatar
 */

package com.spreadsheet.style;

/**
 * Whole-cell presentation flags. Orthogonal to Raw / Value.
 */
public final class CellStyle
{
  private CellStyle() {}

  public static boolean parse(final String s)
  {
    if (s == null)
      return false;
    final String t = s.trim();
    return "true".equalsIgnoreCase(t) || "1".equals(t);
  }

  public static String attr(final boolean bold)
  {
    return bold ? "true" : "false";
  }
}
