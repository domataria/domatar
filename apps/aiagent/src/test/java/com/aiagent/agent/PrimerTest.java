/*
 * Copyright (c) 2024 Domatar
 */

package com.aiagent.agent;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.domatar.core.Context;

public class PrimerTest
{
  @Test
  public void primerStatesCopyAskAndPaymentRules()
  {
    final String primer = Primer.build(
        new Context("act-demo", null, null, null, null, null));

    assertTrue(primer.contains("copy it unchanged"));
    assertTrue(primer.contains("Do not guess a city or a date"));
    assertTrue(primer.contains("Do not add a payment"));
    assertTrue(primer.contains("listApps"));
    assertTrue(primer.contains("useApp"));
    assertTrue(primer.contains("act-demo"));
    assertFalse(primer.contains("Duffel"));
    assertFalse(primer.contains("Geoapify"));
    assertFalse(primer.contains("Travel"));
  }
}
