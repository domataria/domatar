/*
 * Copyright (c) 2024 Domatar
 */

package com.canton.ledger;

import java.math.BigDecimal;

import com.domatar.util.DomatarException;

/**
 * Decimal strings for holding amounts. Not binary floats.
 */
public final class Amounts
{
  private Amounts()
  {
  }

  public static String add(final String current, final String delta)
      throws DomatarException
  {
    final BigDecimal change = parse(delta, "Delta");

    if (change.compareTo(BigDecimal.ZERO) == 0)
      throw new DomatarException("Delta must not be zero");

    final String base = current == null || current.isEmpty() ? "0" : current;
    return parse(base, "Amount").add(change).toPlainString();
  }

  public static String negate(final String delta) throws DomatarException
  {
    return parse(delta, "Delta").negate().toPlainString();
  }

  public static BigDecimal parse(final String raw, final String name)
      throws DomatarException
  {
    if (raw == null || raw.isEmpty())
      throw new DomatarException(name + " is required");

    try
    {
      return new BigDecimal(raw.trim());
    }
    catch (final NumberFormatException e)
    {
      throw new DomatarException(name + " must be a decimal");
    }
  }
}
