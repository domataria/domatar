/*
 * Copyright (c) 2024 Domatar
 */

package com.spreadsheet.formula;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Recursive-descent parser for the Spreadsheet formula language.
 * (Spec-Spreadsheet.txt PART 7.2)
 *
 * Input  : the raw cell string, which must start with '=' for a formula.
 * Output : a Node AST root, ready for FormulaEvaluator.
 *
 * Grammar:
 *   formula     = '=' expr
 *   expr        = term { ('+' | '-') term }
 *   term        = factor { ('*' | '/') factor }
 *   factor      = '(' expr ')'
 *               | unaryMinus
 *               | number
 *               | quotedString
 *               | cellRef       ($ColRow)
 *               | objAttr       ([domId]path)
 *               | call          (NAME(expr, …))
 *   path        = attrName { '[' digits ']' | '.' attrName }
 *   unaryMinus  = '-' factor
 */
public class FormulaParser
{
  // -------------------------------------------------------------------------
  // AST node types
  // -------------------------------------------------------------------------

  public interface Node {}

  public static class NumberNode implements Node
  {
    public final BigDecimal value;

    public NumberNode(final BigDecimal v)
    {
      this.value = v;
    }
  }

  public static class StringNode implements Node
  {
    public final String value;

    public StringNode(final String v)
    {
      this.value = v;
    }
  }

  /** Reference to another cell in the same sheet: $A1, $B12 */
  public static class CellRefNode implements Node
  {
    public final String cellRef; // e.g. "A1"

    public CellRefNode(final String ref)
    {
      this.cellRef = ref;
    }
  }

  /** [hstId.appId.actId.objId]path  path = attrName { [n] | .field } */
  public static class ObjAttrNode implements Node
  {
    public final String domIdStr;
    public final String attrName;
    public final String path;

    public ObjAttrNode(final String domId, final String attr)
    {
      this(domId, attr, attr);
    }

    public ObjAttrNode(final String domId, final String attr, final String path)
    {
      this.domIdStr = domId;
      this.attrName = attr;
      this.path = path;
    }
  }

  public static class BinaryOpNode implements Node
  {
    public final char op;  // '+' '-' '*' '/'
    public final Node left;
    public final Node right;

    public BinaryOpNode(final char op, final Node left, final Node right)
    {
      this.op    = op;
      this.left  = left;
      this.right = right;
    }
  }

  public static class UnaryMinusNode implements Node
  {
    public final Node operand;

    public UnaryMinusNode(final Node n)
    {
      this.operand = n;
    }
  }

  /** Function call: ROUND(expr, digits). Name is stored upper-cased. */
  public static class CallNode implements Node
  {
    public final String     name;
    public final List<Node> args;

    public CallNode(final String name, final List<Node> args)
    {
      this.name = name;
      this.args = Collections.unmodifiableList(new ArrayList<>(args));
    }
  }

  /** Sentinel for a parse error; carries the error token string. */
  public static class ErrorNode implements Node
  {
    public final String error;

    public ErrorNode(final String e)
    {
      this.error = e;
    }
  }

  // -------------------------------------------------------------------------
  // Parser state
  // -------------------------------------------------------------------------

  private final String src;
  private int pos;

  private FormulaParser(final String src, final int startPos)
  {
    this.src = src;
    this.pos = startPos;
  }

  // -------------------------------------------------------------------------
  // Public entry point
  // -------------------------------------------------------------------------

  /**
   * Parse a raw cell value.
   * If raw starts with '=', parse the formula expression.
   * Otherwise return a literal NumberNode or StringNode.
   * Returns an ErrorNode on syntax error.
   */
  public static Node parse(final String raw)
  {
    if (raw == null || raw.isEmpty())
      return new StringNode("");

    if (raw.startsWith("="))
    {
      final FormulaParser p = new FormulaParser(raw, 1);
      final Node n = p.parseExpr();
      p.skipWs();
      if (p.pos < p.src.length())
        return new ErrorNode("#ERR"); // leftover input
      return n;
    }

    if (raw.startsWith("\""))
    {
      if (raw.endsWith("\"") && raw.length() >= 2)
        return new StringNode(raw.substring(1, raw.length() - 1));
      return new ErrorNode("#ERR");
    }

    final BigDecimal numeric = SpreadsheetNumbers.parse(raw);
    if (numeric != null)
      return new NumberNode(numeric);

    return new StringNode(raw); // plain text
  }

  // -------------------------------------------------------------------------
  // Grammar productions
  // -------------------------------------------------------------------------

  private Node parseExpr()
  {
    Node left = parseTerm();

    while (true)
    {
      skipWs();
      if (pos < src.length() && (src.charAt(pos) == '+' || src.charAt(pos) == '-'))
      {
        final char op = src.charAt(pos++);
        final Node right = parseTerm();
        left = new BinaryOpNode(op, left, right);
      }
      else
        break;
    }

    return left;
  }

  private Node parseTerm()
  {
    Node left = parseFactor();

    while (true)
    {
      skipWs();
      if (pos < src.length() && (src.charAt(pos) == '*' || src.charAt(pos) == '/'))
      {
        final char op = src.charAt(pos++);
        final Node right = parseFactor();
        left = new BinaryOpNode(op, left, right);
      }
      else
        break;
    }

    return left;
  }

  private Node parseFactor()
  {
    skipWs();

    if (pos >= src.length())
      return new ErrorNode("#ERR");

    final char c = src.charAt(pos);

    // Parenthesised expression
    if (c == '(')
    {
      pos++;
      final Node inner = parseExpr();
      skipWs();
      if (pos < src.length() && src.charAt(pos) == ')')
        pos++;
      return inner;
    }

    // Unary minus
    if (c == '-')
    {
      pos++;
      final Node operand = parseFactor();
      return new UnaryMinusNode(operand);
    }

    // Object attribute reference: [domId]path
    if (c == '[')
      return parseObjAttr();

    // Cell reference: $ColRow
    if (c == '$')
      return parseCellRef();

    // Quoted string: "..."
    if (c == '"')
      return parseQuotedString();

    // Function call: NAME(...)
    if (Character.isLetter(c))
      return parseCall();

    // Number
    if (Character.isDigit(c) || c == '.')
      return parseNumber();

    return new ErrorNode("#ERR");
  }

  private Node parseCellRef()
  {
    pos++; // skip '$'
    final StringBuilder col = new StringBuilder();
    while (pos < src.length() && Character.isLetter(src.charAt(pos)))
      col.append(Character.toUpperCase(src.charAt(pos++)));

    final StringBuilder row = new StringBuilder();
    while (pos < src.length() && Character.isDigit(src.charAt(pos)))
      row.append(src.charAt(pos++));

    if (col.length() == 0 || row.length() == 0)
      return new ErrorNode("#REF");

    return new CellRefNode(col.toString() + row.toString());
  }

  private Node parseObjAttr()
  {
    pos++; // skip '['
    final StringBuilder domIdBuf = new StringBuilder();
    while (pos < src.length() && src.charAt(pos) != ']')
      domIdBuf.append(src.charAt(pos++));

    if (pos < src.length())
      pos++; // skip ']'

    final StringBuilder pathBuf = new StringBuilder();
    if (!appendAttrName(pathBuf))
      return new ErrorNode("#ERR");

    while (pos < src.length())
    {
      final char ch = src.charAt(pos);
      if (ch == '[')
      {
        pathBuf.append('[');
        pos++;
        final int digitStart = pos;
        while (pos < src.length() && Character.isDigit(src.charAt(pos)))
          pathBuf.append(src.charAt(pos++));
        if (pos == digitStart || pos >= src.length() || src.charAt(pos) != ']')
          return new ErrorNode("#ERR");
        pathBuf.append(']');
        pos++;
      }
      else if (ch == '.')
      {
        pathBuf.append('.');
        pos++;
        if (!appendAttrName(pathBuf))
          return new ErrorNode("#ERR");
      }
      else
        break;
    }

    if (domIdBuf.length() == 0)
      return new ErrorNode("#ERR");

    final String path = pathBuf.toString();
    return new ObjAttrNode(domIdBuf.toString(), AttrPath.rootName(path), path);
  }

  private boolean appendAttrName(final StringBuilder buf)
  {
    final int start = buf.length();
    if (pos >= src.length() || !Character.isLetter(src.charAt(pos)))
      return false;
    buf.append(src.charAt(pos++));
    while (pos < src.length()
           && (Character.isLetterOrDigit(src.charAt(pos)) || src.charAt(pos) == '_'))
      buf.append(src.charAt(pos++));
    return buf.length() > start;
  }

  private Node parseCall()
  {
    final int start = pos;
    pos++;
    while (pos < src.length()
           && (Character.isLetterOrDigit(src.charAt(pos)) || src.charAt(pos) == '_'))
      pos++;
    final String name = src.substring(start, pos).toUpperCase(Locale.ROOT);

    skipWs();
    if (pos >= src.length() || src.charAt(pos) != '(')
      return new ErrorNode("#ERR");
    pos++;

    final List<Node> args = new ArrayList<>();
    skipWs();
    if (pos < src.length() && src.charAt(pos) != ')')
    {
      args.add(parseExpr());
      while (true)
      {
        skipWs();
        if (pos >= src.length() || src.charAt(pos) != ',')
          break;
        pos++;
        args.add(parseExpr());
      }
    }

    skipWs();
    if (pos >= src.length() || src.charAt(pos) != ')')
      return new ErrorNode("#ERR");
    pos++;
    return new CallNode(name, args);
  }

  private Node parseQuotedString()
  {
    pos++; // skip opening '"'
    final StringBuilder sb = new StringBuilder();
    while (pos < src.length() && src.charAt(pos) != '"')
      sb.append(src.charAt(pos++));
    if (pos < src.length())
      pos++; // skip closing '"'
    return new StringNode(sb.toString());
  }

  private Node parseNumber()
  {
    final int start = pos;
    while (pos < src.length())
    {
      final char ch = src.charAt(pos);
      if (Character.isDigit(ch) || ch == '.')
        pos++;
      else
        break;
    }

    final BigDecimal value = SpreadsheetNumbers.parse(src.substring(start, pos));
    if (value != null)
      return new NumberNode(value);

    return new ErrorNode("#ERR");
  }

  private void skipWs()
  {
    while (pos < src.length() && Character.isWhitespace(src.charAt(pos)))
      pos++;
  }
}
