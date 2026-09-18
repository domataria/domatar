/*
 * Copyright (c) 2024 Domatar
 */

package com.canton.ledger;

import com.domatar.util.DomatarException;

/**
 * Process-wide CantonClient. Production uses MockCanton with the
 * default JSON file. Handlers call get() only.
 */
public final class CantonClients
{
  private static volatile CantonClient INSTANCE;

  static
  {
    try
    {
      INSTANCE = new MockCanton();
    }
    catch (final DomatarException e)
    {
      throw new ExceptionInInitializerError(e);
    }
  }

  private CantonClients()
  {
  }

  public static CantonClient get()
  {
    return INSTANCE;
  }

  static synchronized void replaceForTest(final CantonClient c)
  {
    try
    {
      INSTANCE = (c != null) ? c : new MockCanton(null);
    }
    catch (final DomatarException e)
    {
      throw new IllegalStateException(e);
    }
  }
}
