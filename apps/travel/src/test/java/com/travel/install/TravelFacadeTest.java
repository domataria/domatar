/*
 * Copyright (c) 2024 Domatar
 */

package com.travel.install;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.domatar.util.Json;
import com.domatar.util.JsonList;
import com.domatar.util.JsonMap;

public class TravelFacadeTest
{
  @Test
  public void serviceJson_hasSixMessages() throws Exception
  {
    final JsonMap root = Json.parseMap(TravelFacade.serviceJson());
    final JsonList msgs = root.getList("Msgs");
    final Set<String> names = new LinkedHashSet<String>();

    for (final Object item : msgs)
      names.add(((JsonMap) item).getString("Name"));

    assertEquals(6, names.size());
    assertTrue(names.contains("SearchFlights"));
    assertTrue(names.contains("SearchHotels"));
    assertTrue(names.contains("SearchSights"));
    assertTrue(names.contains("BookTrip"));
    assertTrue(names.contains("GetTrips"));
    assertTrue(names.contains("GetTrip"));
  }

  @Test
  public void serviceJson_bookTripIsWrite() throws Exception
  {
    assertEquals("Write", sideEffect("BookTrip"));
  }

  @Test
  public void serviceJson_searchesAreRead() throws Exception
  {
    assertEquals("Read", sideEffect("SearchFlights"));
    assertEquals("Read", sideEffect("SearchHotels"));
    assertEquals("Read", sideEffect("SearchSights"));
    assertEquals("Read", sideEffect("GetTrips"));
    assertEquals("Read", sideEffect("GetTrip"));
  }

  @Test
  public void serviceJson_hasNoCost() throws Exception
  {
    assertFalse(TravelFacade.serviceJson().contains("Cost"));
    assertFalse(TravelFacade.classJson().contains("Cost"));
  }

  private static String sideEffect(final String name) throws Exception
  {
    final JsonList msgs = Json.parseMap(TravelFacade.serviceJson()).getList("Msgs");

    for (final Object item : msgs)
    {
      final JsonMap msg = (JsonMap) item;

      if (name.equals(msg.getString("Name")))
        return msg.getString("SideEffect");
    }
    return null;
  }
}
