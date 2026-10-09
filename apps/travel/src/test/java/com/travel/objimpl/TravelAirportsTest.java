/*
 * Copyright (c) 2024 Domatar
 */

package com.travel.objimpl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

public class TravelAirportsTest
{
  @Test
  public void parisLondonNewYork()
  {
    assertEquals("CDG", TravelAirports.iata("Paris"));
    assertEquals("LHR", TravelAirports.iata("london"));
    assertEquals("JFK", TravelAirports.iata("New York"));
  }

  @Test
  public void iataPassthrough()
  {
    assertEquals("CDG", TravelAirports.iata("cdg"));
    assertEquals("LHR", TravelAirports.iata("LHR"));
    assertEquals("XYZ", TravelAirports.iata("xyz"));
  }

  @Test
  public void unknown()
  {
    assertNull(TravelAirports.iata("Berlin"));
    assertEquals("Unknown city: Berlin", TravelErrors.unknownCity("Berlin"));
  }
}
