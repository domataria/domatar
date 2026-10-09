/*
 * Copyright (c) 2024 Domatar
 */

package com.travel.objimpl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

public class TravelNamesTest
{
  @Test
  public void hotelStreetSuffix()
  {
    final Set<String> already = new LinkedHashSet<String>();

    assertEquals("Le Meurice",
        TravelNames.hotelName("Le Meurice", "228 Rue de Rivoli", already));
    assertEquals("Le Meurice \u2014 228 Rue de Rivoli",
        TravelNames.hotelName("Le Meurice", "228 Rue de Rivoli", already));
    assertEquals("Louvre",
        TravelNames.sightName("Louvre", "Rue de Rivoli", already));
  }

  @Test
  public void flightNumericSuffix()
  {
    final Set<String> already = new LinkedHashSet<String>();
    final String first = TravelNames.flightName(
        "Duffel Airways", "LHR", "JFK", "2026-11-01T08:00", already);
    final String second = TravelNames.flightName(
        "Duffel Airways", "LHR", "JFK", "2026-11-01T08:00", already);

    assertEquals("Duffel Airways LHR-JFK 2026-11-01T08:00", first);
    assertTrue(second.endsWith(" (2)"));
  }
}
