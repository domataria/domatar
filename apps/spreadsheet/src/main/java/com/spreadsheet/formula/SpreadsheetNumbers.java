/*
 * Copyright (c) 2024 Domatar
 */

package com.spreadsheet.formula;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

/**
 * Arbitrary-precision spreadsheet numbers.
 *
 * Values are stored and computed with {@link BigDecimal} (unscaled
 * {@link java.math.BigInteger} + scale) so integer and decimal arithmetic
 * avoids IEEE-754 floating-point error.
 */
public final class SpreadsheetNumbers
{
  public static final BigDecimal ZERO = BigDecimal.ZERO;

  private static final MathContext DIV_CONTEXT =
      new MathContext(34, RoundingMode.HALF_UP);

  private SpreadsheetNumbers() {}

  /** Parse a display/raw cell value; returns null if not numeric. */
  public static BigDecimal parse(final String s)
  {
    if (s == null || s.isEmpty())
      return null;

    final String cleaned = s.replace(",", "").trim();
    if (cleaned.isEmpty())
      return null;

    try
    {
      return new BigDecimal(cleaned);
    }
    catch (NumberFormatException e)
    {
      return null;
    }
  }

  /** Parse for arithmetic; blank cells count as zero. */
  public static BigDecimal parseOrZero(final String s)
  {
    if (s == null || s.isEmpty())
      return ZERO;

    return parse(s);
  }

  /**
   * Parse a cell value for use in formulas. Blank cells are zero; non-numeric
   * text returns null so '+' can fall back to string concatenation.
   */
  public static BigDecimal parseForArithmetic(final String s)
  {
    if (s == null || s.isEmpty())
      return ZERO;

    return parse(s);
  }

  public static BigDecimal divide(final BigDecimal left, final BigDecimal right)
  {
    return left.divide(right, DIV_CONTEXT);
  }

  /** Format for cell display: plain decimal, no trailing zeros. */
  public static String format(final BigDecimal value)
  {
    if (value == null)
      return "";

    final BigDecimal stripped = value.stripTrailingZeros();
    if (stripped.compareTo(ZERO) == 0)
      return "0";

    if (stripped.scale() <= 0)
      return stripped.toBigInteger().toString();

    return stripped.toPlainString();
  }
}
