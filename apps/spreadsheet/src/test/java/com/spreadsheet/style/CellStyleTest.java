/*
 * Copyright (c) 2024 Domatar
 */

package com.spreadsheet.style;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

public class CellStyleTest
{
  @Test
  public void parseTrueVariants()
  {
    assertTrue(CellStyle.parse("true"));
    assertTrue(CellStyle.parse("True"));
    assertTrue(CellStyle.parse("1"));
    assertTrue(CellStyle.parse(" true "));
  }

  @Test
  public void parseFalseVariants()
  {
    assertFalse(CellStyle.parse(null));
    assertFalse(CellStyle.parse(""));
    assertFalse(CellStyle.parse("false"));
    assertFalse(CellStyle.parse("0"));
    assertFalse(CellStyle.parse("yes"));
  }

  @Test
  public void attrRoundTrip()
  {
    assertEquals("true", CellStyle.attr(true));
    assertEquals("false", CellStyle.attr(false));
    assertTrue(CellStyle.parse(CellStyle.attr(true)));
    assertFalse(CellStyle.parse(CellStyle.attr(false)));
  }
}
