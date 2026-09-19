/*
 * Copyright (c) 2024 Domatar
 */

package com.spreadsheet.formula;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;

import com.domatar.util.JsonMsg;
import com.domatar.util.ObjAttrs;
import com.domatar.util.DomId;
import com.domatar.util.DomatarException;
import com.domatar.util.DomatarMsgClient;

/**
 * Evaluates a FormulaParser AST to a String result.
 * (Spec-Spreadsheet.txt PART 7.3–7.7)
 *
 * Error tokens: #CIRC #REF #N/A #VALUE #DIV0 #ERR
 *
 * Usage:
 *   FormulaEvaluator ev = new FormulaEvaluator(cellValues, visitedRefs, msgClient);
 *   String result = ev.eval(FormulaParser.parse(rawString));
 *
 * cellValues : map of cellRef (e.g. "A1") -> raw string for all cells in sheet.
 * visitedRefs: caller-managed set for circular-reference detection; pass a fresh
 *              HashSet for a stand-alone evaluation, or share one across the
 *              topological-sort pass to detect cycles.
 */
public class FormulaEvaluator
{
  // Error tokens (Spec PART 7.6)
  public static final String ERR_CIRC  = "#CIRC";
  public static final String ERR_REF   = "#REF";
  public static final String ERR_NA    = "#N/A";
  public static final String ERR_VALUE = "#VALUE";
  public static final String ERR_DIV0  = "#DIV0";
  public static final String ERR_ERR   = "#ERR";

  private final Map<String, String> cellRaws;  // cellRef -> raw
  private final Set<String>         visiting;  // for cycle detection
  private final DomatarMsgClient    msgClient;

  public FormulaEvaluator(final Map<String, String> cellRaws,
                          final Set<String>         visiting,
                          final DomatarMsgClient    msgClient)
  {
    this.cellRaws  = cellRaws;
    this.visiting  = visiting;
    this.msgClient = msgClient;
  }

  // -------------------------------------------------------------------------
  // Public API
  // -------------------------------------------------------------------------

  /** Evaluate a raw string to a display value. */
  public String evalRaw(final String raw)
  {
    if (raw == null || raw.isEmpty())
      return "";
    final FormulaParser.Node node = FormulaParser.parse(raw);
    return eval(node);
  }

  /** Evaluate a pre-parsed AST node to a String result. */
  public String eval(final FormulaParser.Node node)
  {
    if (node instanceof FormulaParser.ErrorNode)
      return ((FormulaParser.ErrorNode) node).error;

    if (node instanceof FormulaParser.NumberNode)
      return SpreadsheetNumbers.format(((FormulaParser.NumberNode) node).value);

    if (node instanceof FormulaParser.StringNode)
      return ((FormulaParser.StringNode) node).value;

    if (node instanceof FormulaParser.UnaryMinusNode)
    {
      final String inner = eval(((FormulaParser.UnaryMinusNode) node).operand);
      if (isError(inner))
        return inner;
      final BigDecimal n = SpreadsheetNumbers.parseForArithmetic(inner);
      if (n == null)
        return ERR_VALUE;
      return SpreadsheetNumbers.format(n.negate());
    }

    if (node instanceof FormulaParser.CellRefNode)
      return evalCellRef((FormulaParser.CellRefNode) node);

    if (node instanceof FormulaParser.ObjAttrNode)
      return evalObjAttr((FormulaParser.ObjAttrNode) node);

    if (node instanceof FormulaParser.BinaryOpNode)
      return evalBinaryOp((FormulaParser.BinaryOpNode) node);

    return ERR_ERR;
  }

  // -------------------------------------------------------------------------
  // Node evaluators
  // -------------------------------------------------------------------------

  private String evalCellRef(final FormulaParser.CellRefNode node)
  {
    final String ref = node.cellRef;

    if (!cellRaws.containsKey(ref))
      return ""; // empty cell — not an error, just blank

    if (visiting.contains(ref))
      return ERR_CIRC;

    visiting.add(ref);
    try
    {
      final String raw = cellRaws.get(ref);
      return evalRaw(raw);
    }
    finally
    {
      visiting.remove(ref);
    }
  }

  private String evalObjAttr(final FormulaParser.ObjAttrNode node)
  {
    if (msgClient == null)
      return ERR_NA;

    try
    {
      final DomId targetId = new DomId(node.domIdStr);

      final JsonMsg openMsg = new JsonMsg();
      openMsg.addRequestBody("GetLnks", null);
      // Do NOT set ClsId — let the dispatcher load the object (Spec PART 9.3).
      final JsonMsg resp = msgClient.send(targetId, openMsg);

      if (resp == null)
        return ERR_NA;

      final String err = resp.getError();
      if ("Failure".equals(err))
        return ERR_NA;

      // Open response shape: Attrs -> { DomId, ClsAppId, ClsId, ObjName, ObjDesc,
      //   Attrs: { <obj own attrs> }, Lnks: [...] }
      // The object's own attributes are nested one level under "Attrs".
      final ObjAttrs outerAttrs = resp.getAttrs();
      if (outerAttrs == null)
        return ERR_NA;

      final ObjAttrs innerAttrs = outerAttrs.getObjAttrs("Attrs");
      final ObjAttrs lookup = (innerAttrs != null) ? innerAttrs : outerAttrs;

      // Try exact match, then first-letter-capitalised (e.g. "text" -> "Text").
      String val = lookup.getAttr(node.attrName);
      if (val == null && node.attrName.length() > 0)
      {
        final String cap = Character.toUpperCase(node.attrName.charAt(0))
                           + node.attrName.substring(1);
        val = lookup.getAttr(cap);
      }
      if (val == null)
        return ERR_NA;
      if (node.path == null || node.path.equals(node.attrName))
        return val;
      final String walked = AttrPath.walk(val, node.path.substring(node.attrName.length()));
      return walked != null ? walked : ERR_NA;
    }
    catch (DomatarException e)
    {
      return ERR_NA;
    }
  }

  private String evalBinaryOp(final FormulaParser.BinaryOpNode node)
  {
    final String lStr = eval(node.left);
    final String rStr = eval(node.right);

    // Propagate first error encountered (left-to-right per spec).
    if (isError(lStr))
      return lStr;
    if (isError(rStr))
      return rStr;

    final char op = node.op;

    if (op == '+')
    {
      // Try numeric first; fall back to string concatenation.
      final BigDecimal lNum = SpreadsheetNumbers.parseForArithmetic(lStr);
      final BigDecimal rNum = SpreadsheetNumbers.parseForArithmetic(rStr);
      if (lNum != null && rNum != null)
        return SpreadsheetNumbers.format(lNum.add(rNum));
      return lStr + rStr;
    }

    final BigDecimal lNum = SpreadsheetNumbers.parseForArithmetic(lStr);
    final BigDecimal rNum = SpreadsheetNumbers.parseForArithmetic(rStr);

    if (lNum == null || rNum == null)
      return ERR_VALUE;

    switch (op)
    {
      case '-':
        return SpreadsheetNumbers.format(lNum.subtract(rNum));
      case '*':
        return SpreadsheetNumbers.format(lNum.multiply(rNum));
      case '/':
        if (rNum.compareTo(SpreadsheetNumbers.ZERO) == 0)
          return ERR_DIV0;
        return SpreadsheetNumbers.format(SpreadsheetNumbers.divide(lNum, rNum));
      default:
        return ERR_ERR;
    }
  }

  // -------------------------------------------------------------------------
  // Helpers
  // -------------------------------------------------------------------------

  private static boolean isError(final String s)
  {
    return s != null && s.startsWith("#");
  }
}
