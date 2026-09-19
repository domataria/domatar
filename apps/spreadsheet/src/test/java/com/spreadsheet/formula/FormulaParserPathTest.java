/*
 * Copyright (c) 2024 Domatar
 */

package com.spreadsheet.formula;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

public class FormulaParserPathTest
{
  private static final String DOM =
      "canton-A9hXcT6IM~Dl1ypTT0GAft2na2LNOpCC.canton."
      + "A9hXcT6IM~Dl1ypTT0GAft2na2LNOpCC.00d1a8c3e7b24f90e5c1a6b8d3f0e7a4c9b2d5f1e8a3c6b0d7f4e1a9c5b2d8";

  @Test
  public void parsesIndexAndField()
  {
    final FormulaParser.Node n =
        FormulaParser.parse("=[" + DOM + "]Allocations[2].Name");
    assertTrue(n instanceof FormulaParser.ObjAttrNode);
    final FormulaParser.ObjAttrNode a = (FormulaParser.ObjAttrNode) n;
    assertEquals(DOM, a.domIdStr);
    assertEquals("Allocations", a.attrName);
    assertEquals("Allocations[2].Name", a.path);
  }

  @Test
  public void parsesBareAttr()
  {
    final FormulaParser.Node n = FormulaParser.parse("=[" + DOM + "]Nav");
    final FormulaParser.ObjAttrNode a = (FormulaParser.ObjAttrNode) n;
    assertEquals("Nav", a.attrName);
    assertEquals("Nav", a.path);
  }

  @Test
  public void trailingDotIsError()
  {
    final FormulaParser.Node n =
        FormulaParser.parse("=[" + DOM + "]Allocations[2].");
    assertTrue(n instanceof FormulaParser.ErrorNode);
  }

  @Test
  public void emptyIndexIsError()
  {
    final FormulaParser.Node n =
        FormulaParser.parse("=[" + DOM + "]Allocations[]");
    assertTrue(n instanceof FormulaParser.ErrorNode);
  }
}
