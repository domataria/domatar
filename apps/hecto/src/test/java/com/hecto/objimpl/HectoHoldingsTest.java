/*
 * Copyright (c) 2024 Domatar
 */

package com.hecto.objimpl;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.canton.ledger.DemoPortfolio;

public class HectoHoldingsTest
{
  @Test
  public void acceptsHectx()
  {
    assertTrue(HectoHoldings.HECTO_REG.equals(DemoPortfolio.HECTO_REG));
    assertTrue(HectoHoldings.isHectoHolding(payload("HECTX", DemoPortfolio.HECTO_REG)));
  }

  @Test
  public void acceptsHectoAllocator()
  {
    assertTrue(HectoHoldings.isHectoHolding(payload("HECTO", DemoPortfolio.HECTO_REG)));
  }

  @Test
  public void rejectsCantonCoin()
  {
    assertFalse(HectoHoldings.isHectoHolding(payload("CC", DemoPortfolio.DSO)));
  }

  @Test
  public void rejectsUsyc()
  {
    assertFalse(HectoHoldings.isHectoHolding(payload("USYC", DemoPortfolio.HASHNOTE)));
  }

  private static Map<String, String> payload(final String symbol, final String admin)
  {
    final Map<String, String> payload = new LinkedHashMap<>();

    payload.put("InstrumentAdmin", admin);
    payload.put("Symbol", symbol);
    return payload;
  }
}
