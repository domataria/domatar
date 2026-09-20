/*
 * Copyright (c) 2024 Domatar
 */

package com.spreadsheet.formula;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

import org.junit.jupiter.api.Test;

public class FormulaRoundTest
{
  private static String eval(final String raw)
  {
    return eval(raw, Collections.emptyMap());
  }

  private static String eval(final String raw, final Map<String, String> cells)
  {
    return new FormulaEvaluator(cells, new HashSet<String>(), null).evalRaw(raw);
  }

  @Test
  public void parsesRoundCall()
  {
    final FormulaParser.Node n = FormulaParser.parse("=ROUND($B6/$B7,2)");
    assertTrue(n instanceof FormulaParser.CallNode);
    final FormulaParser.CallNode c = (FormulaParser.CallNode) n;
    assertEquals("ROUND", c.name);
    assertEquals(2, c.args.size());
    assertTrue(c.args.get(0) instanceof FormulaParser.BinaryOpNode);
    assertTrue(c.args.get(1) instanceof FormulaParser.NumberNode);
  }

  @Test
  public void nameIsCaseInsensitive()
  {
    final FormulaParser.Node n = FormulaParser.parse("=round(1.239,2)");
    assertTrue(n instanceof FormulaParser.CallNode);
    assertEquals("ROUND", ((FormulaParser.CallNode) n).name);
    assertEquals("1.24", eval("=Round(1.239,2)"));
  }

  @Test
  public void roundsHalfUpTwoPlaces()
  {
    assertEquals("0.29",
        eval("=ROUND(0.2931943801033308461318122504694446,2)"));
    assertEquals("1.23", eval("=ROUND(1.234,2)"));
    assertEquals("1.24", eval("=ROUND(1.235,2)"));
  }

  @Test
  public void roundsCellDivision()
  {
    final Map<String, String> cells = new HashMap<String, String>();
    cells.put("B6", "2072.745");
    cells.put("B7", "7069.525");
    assertEquals("0.29", eval("=ROUND($B6/$B7,2)", cells));
  }

  @Test
  public void negativeDigits()
  {
    assertEquals("1200", eval("=ROUND(1234,-2)"));
  }

  @Test
  public void wrongArityIsErr()
  {
    assertEquals(FormulaEvaluator.ERR_ERR, eval("=ROUND(1.2)"));
    assertEquals(FormulaEvaluator.ERR_ERR, eval("=ROUND(1,2,3)"));
  }

  @Test
  public void unknownFunctionIsErr()
  {
    assertEquals(FormulaEvaluator.ERR_ERR, eval("=FOO(1,2)"));
  }

  @Test
  public void nonNumericIsValue()
  {
    assertEquals(FormulaEvaluator.ERR_VALUE, eval("=ROUND(\"x\",2)"));
    assertEquals(FormulaEvaluator.ERR_VALUE, eval("=ROUND(1.2,1.5)"));
  }

  @Test
  public void identWithoutParenIsErr()
  {
    assertTrue(FormulaParser.parse("=ROUND") instanceof FormulaParser.ErrorNode);
  }
}
