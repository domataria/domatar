/*
 * Copyright (c) 2024 Domatar
 */

package com.travel.remote;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.domatar.util.DomatarException;
import com.travel.objimpl.TravelErrors;

public class GeoapifyClientTest
{
  @Test
  public void geocode_unknownWhenFeaturesEmpty() throws Exception
  {
    final FakeTravelHttp http = new FakeTravelHttp();

    http.put("https://api.geoapify.com/v1/geocode/search", "{\"features\":[]}");

    final GeoapifyClient client = new GeoapifyClient(http, "test-key");

    assertNull(client.geocode("Nope"));
  }

  @Test
  public void hotels_pagesUntilShort() throws Exception
  {
    final FakeTravelHttp http = new FakeTravelHttp();
    final String places = "https://api.geoapify.com/v2/places"
        + "?categories=accommodation.hotel&filter=place:pid&limit=500&offset=";

    http.put("https://api.geoapify.com/v1/geocode/search", city("pid"));
    http.put(places + "0", features(500, "h"));
    http.put(places + "500", features(3, "t"));

    final GeoapifyClient client = new GeoapifyClient(http, "test-key");
    final List<GeoapifyClient.Place> hotels = client.hotels("Paris");

    assertEquals(503, hotels.size());
    assertEquals("Hotel h0", hotels.get(0).name);
    assertEquals("Rue h0", hotels.get(0).street);
  }

  @Test
  public void sights_dedupesPlaceId() throws Exception
  {
    final FakeTravelHttp http = new FakeTravelHttp();

    http.put("https://api.geoapify.com/v1/geocode/search", city("pid"));
    http.put("https://api.geoapify.com/v2/places?categories=tourism.attraction",
        onePlace("same", "Louvre", "tourism.attraction"));
    http.put("https://api.geoapify.com/v2/places?categories=entertainment.museum",
        onePlace("same", "Louvre Museum", "entertainment.museum"));

    final GeoapifyClient client = new GeoapifyClient(http, "test-key");
    final List<GeoapifyClient.Place> sights = client.sights("Paris");

    assertEquals(1, sights.size());
    assertEquals("same", sights.get(0).placeId);
    assertEquals("tourism.attraction", sights.get(0).category);
  }

  @Test
  public void missingKey_isTheConfiguredSentence()
  {
    final FakeTravelHttp http = new FakeTravelHttp();
    final GeoapifyClient client = new GeoapifyClient(http, null);
    final DomatarException ex = assertThrows(DomatarException.class,
        () -> client.hotels("Paris"));

    assertEquals(TravelErrors.notConfigured("TRAVEL_GEOAPIFY_KEY"), ex.getMessage());
  }

  private static String city(final String placeId)
  {
    return "{\"features\":[{\"properties\":{\"place_id\":\"" + placeId
        + "\",\"lat\":48.8566,\"lon\":2.3522,\"city\":\"Paris\"}}]}";
  }

  private static String onePlace(final String id, final String name, final String category)
  {
    return "{\"features\":[{\"properties\":{\"place_id\":\"" + id
        + "\",\"name\":\"" + name + "\",\"street\":\"Rue de Rivoli\",\"city\":\"Paris\"}}]}";
  }

  private static String features(final int count, final String prefix)
  {
    final StringBuilder buf = new StringBuilder("{\"features\":[");

    for (int i = 0; i < count; i++)
    {
      if (i > 0)
        buf.append(',');
      buf.append("{\"properties\":{\"place_id\":\"")
          .append(prefix).append(i)
          .append("\",\"name\":\"Hotel ").append(prefix).append(i)
          .append("\",\"street\":\"Rue ").append(prefix).append(i)
          .append("\",\"city\":\"Paris\"}}");
    }
    buf.append("]}");
    return buf.toString();
  }
}
