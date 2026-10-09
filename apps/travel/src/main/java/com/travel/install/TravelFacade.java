/*
 * Copyright (c) 2024 Domatar
 */

package com.travel.install;

import com.domatar.util.DomatarException;
import com.domatar.util.Json;
import com.domatar.util.JsonArrayList;
import com.domatar.util.JsonHashMap;
import com.domatar.util.JsonList;
import com.travel.objimpl.TravelIds;

/**
 * Structured (travel, app) service document. Prices stay in search
 * results, so this document has no Cost field.
 */
public final class TravelFacade
{
  private TravelFacade()
  {
  }

  public static String serviceJson() throws DomatarException
  {
    final JsonHashMap root = new JsonHashMap();
    final JsonList msgs = new JsonArrayList();

    root.put("SrvAppId", TravelIds.HOME);
    root.put("SrvId", "app");
    root.put("Description",
        "Travel app. Search flights, hotels, and sights, then "
        + "book one trip. Call the searches first. BookTrip only "
        + "accepts Names returned by those searches. Do not invent "
        + "flight numbers, hotel names, or sight names.");
    msgs.add(described("SearchFlights", "Read",
        parms(
            parm("Origin", "City name or IATA code of the departure airport."),
            parm("Destination", "City name or IATA code of the arrival airport."),
            parm("Depart", "Departure date, YYYY-MM-DD."),
            parm("Return", "Return date, YYYY-MM-DD.")),
        "Search flights. All four parameters are required. A city name "
        + "is resolved to an airport. A three-letter code is an IATA code. "
        + "Copy Name into BookTrip. If Offers is empty, do not invent a flight."));
    msgs.add(described("SearchHotels", "Read",
        parms(
            parm("City", "City to search."),
            parm("CheckIn", "Check-in date, YYYY-MM-DD."),
            parm("Nights", "Number of nights. A positive integer."),
            parm("Page", "Page number, starting at 1. Defaults to 1.")),
        "Search hotels. Page defaults to 1. NightlyPrice is a displayed "
        + "euro price, not a live hotel rate. Copy Name into BookTrip. "
        + "A Total larger than this page means further pages exist."));
    msgs.add(described("SearchSights", "Read",
        parms(
            parm("City", "City to search."),
            parm("Page", "Page number, starting at 1. Defaults to 1.")),
        "Search sights. Page defaults to 1. Sights are not reserved. "
        + "Day1, Day2, and Day3 must be Names from this list."));
    msgs.add(described("BookTrip", "Write",
        parms(
            parm("Title", "Title for the new trip."),
            parm("FlightName", "Name from SearchFlights."),
            parm("HotelName", "Name from SearchHotels."),
            parm("Day1", "Sight Names for day 1, separated by commas."),
            parm("Day2", "Sight Names for day 2, separated by commas."),
            parm("Day3", "Sight Names for day 3, separated by commas.")),
        "Book one trip. Search first and pass Names from those searches "
        + "unchanged. This books the flight and records the hotel and "
        + "the itinerary. Do not call BookTrip twice for the same Title."));
    msgs.add(described("GetTrips", "Read", null,
        "List this user's trips, newest first."));
    msgs.add(described("GetTrip", "Read",
        parms(parm("Title", "Trip title returned by GetTrips.")),
        "Read one trip by Title."));
    root.put("Msgs", msgs);
    return Json.toJson(root);
  }

  public static String classJson() throws DomatarException
  {
    final JsonHashMap root = new JsonHashMap();
    final JsonList impl = new JsonArrayList();
    final JsonList policy = new JsonArrayList();

    impl.add("travel.app");
    root.put("ClsAppId", TravelIds.HOME);
    root.put("ClsId", "app");
    root.put("Implements", impl);
    policy.add(policy("SearchFlights", "Read"));
    policy.add(policy("SearchHotels", "Read"));
    policy.add(policy("SearchSights", "Read"));
    policy.add(policy("BookTrip", "Write"));
    policy.add(policy("GetTrips", "Read"));
    policy.add(policy("GetTrip", "Read"));
    root.put("MsgPolicy", policy);
    return Json.toJson(root);
  }

  private static JsonHashMap policy(final String name, final String sideEffect)
  {
    final JsonHashMap p = new JsonHashMap();

    p.put("Srv", "travel.app");
    p.put("Name", name);
    p.put("SideEffect", sideEffect);
    p.put("Auth", "Owner");
    return p;
  }

  private static JsonHashMap described(final String name, final String sideEffect,
                                       final JsonList parms, final String description)
  {
    final JsonHashMap m = new JsonHashMap();

    m.put("Name", name);
    m.put("SideEffect", sideEffect);
    m.put("Description", description);
    if (parms != null)
      m.put("Parms", parms);
    return m;
  }

  private static JsonList parms(final JsonHashMap... items)
  {
    final JsonList list = new JsonArrayList();

    for (final JsonHashMap item : items)
      list.add(item);
    return list;
  }

  private static JsonHashMap parm(final String name, final String description)
  {
    final JsonHashMap p = new JsonHashMap();

    p.put("Name", name);
    p.put("Type", "String");
    p.put("Description", description);
    return p;
  }
}
