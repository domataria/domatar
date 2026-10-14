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
  public void serviceJson_hasSevenMessages() throws Exception
  {
    final JsonMap root = Json.parseMap(TravelFacade.serviceJson());
    final JsonList msgs = root.getList("Msgs");
    final Set<String> names = new LinkedHashSet<String>();

    for (final Object item : msgs)
      names.add(((JsonMap) item).getString("Name"));

    assertEquals(7, names.size());
    assertTrue(names.contains("SearchFlights"));
    assertTrue(names.contains("SearchHotels"));
    assertTrue(names.contains("SearchSights"));
    assertTrue(names.contains("BookTrip"));
    assertTrue(names.contains("CancelTrip"));
    assertTrue(names.contains("GetTrips"));
    assertTrue(names.contains("GetTrip"));
  }

  @Test
  public void serviceJson_bookTripCompensatesCancelTrip() throws Exception
  {
    assertEquals("CancelTrip", field("BookTrip", "Compensates"));
    assertEquals(null, field("CancelTrip", "Compensates"));
    assertEquals("Write", sideEffect("CancelTrip"));
    assertEquals("CancelTrip", policyCompensates("BookTrip"));
    assertEquals(null, policyCompensates("CancelTrip"));
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
  public void serviceJson_pageIsOptional() throws Exception
  {
    assertEquals("String?", parmType("SearchHotels", "Page"));
    assertEquals("String?", parmType("SearchSights", "Page"));
    assertEquals("String", parmType("SearchHotels", "City"));
    assertEquals("String", parmType("SearchSights", "City"));
  }

  @Test
  public void serviceJson_hasNoCost() throws Exception
  {
    assertFalse(TravelFacade.serviceJson().contains("Cost"));
    assertFalse(TravelFacade.classJson().contains("Cost"));
  }

  private static String sideEffect(final String name) throws Exception
  {
    return field(name, "SideEffect");
  }

  private static String field(final String name, final String key) throws Exception
  {
    final JsonList msgs = Json.parseMap(TravelFacade.serviceJson()).getList("Msgs");

    for (final Object item : msgs)
    {
      final JsonMap msg = (JsonMap) item;

      if (name.equals(msg.getString("Name")))
        return msg.getString(key);
    }
    return null;
  }

  private static String parmType(final String message, final String parm) throws Exception
  {
    final JsonList msgs = Json.parseMap(TravelFacade.serviceJson()).getList("Msgs");

    for (final Object item : msgs)
    {
      final JsonMap msg = (JsonMap) item;

      if (!message.equals(msg.getString("Name")))
        continue;

      final JsonList parms = msg.getList("Parms");

      for (final Object raw : parms)
      {
        final JsonMap row = (JsonMap) raw;

        if (parm.equals(row.getString("Name")))
          return row.getString("Type");
      }
    }
    return null;
  }

  private static String policyCompensates(final String name) throws Exception
  {
    final JsonList policy = Json.parseMap(TravelFacade.classJson()).getList("MsgPolicy");

    for (final Object item : policy)
    {
      final JsonMap msg = (JsonMap) item;

      if (name.equals(msg.getString("Name")))
        return msg.getString("Compensates");
    }
    return null;
  }
}
