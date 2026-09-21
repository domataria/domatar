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

  /**
   * Workshop MockNetworkWui only. Handlers keep calling {@link #get()}.
   * Null when the singleton is not a {@link MockCanton}.
   */
  public static MockCanton mockOrNull()
  {
    final CantonClient c = INSTANCE;

    return (c instanceof MockCanton) ? (MockCanton) c : null;
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
