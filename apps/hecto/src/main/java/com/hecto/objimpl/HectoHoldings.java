/*
 * Copyright (c) 2024 Domatar
 */

package com.hecto.objimpl;

import java.util.Map;

/**
 * A Hecto fund is a holding whose registrar is Hecto. Canton Coin
 * and other administrators are not. The registrar party matches
 * DemoPortfolio.HECTO_REG. This class does not load the Canton app:
 * each app JAR has its own class loader.
 */
public final class HectoHoldings
{
  public static final String HECTO_REG =
      "Hecto::12208c1a9f0e4d7b2a6c5e8f1d3b0a7c4e9f2d5b8a1c6e3f0d9b7a4c1e8f5d2b0a11";

  private HectoHoldings()
  {
  }

  public static boolean isHectoHolding(final Map<?, ?> payload)
  {
    if (payload == null)
      return false;
    final Object admin = payload.get("InstrumentAdmin");
    return HECTO_REG.equals(admin);
  }
}
