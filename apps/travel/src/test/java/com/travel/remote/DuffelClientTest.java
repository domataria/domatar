/*
 * Copyright (c) 2024 Domatar
 */

package com.travel.remote;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.domatar.util.DomatarException;
import com.travel.objimpl.TravelErrors;
import com.travel.objimpl.TravelPassenger;

public class DuffelClientTest
{
  @Test
  public void liveToken_doesNotCallHttp()
  {
    final FakeTravelHttp http = new FakeTravelHttp();
    final DuffelClient client = new DuffelClient(http, "duffel_live_secret");
    final DomatarException ex = assertThrows(DomatarException.class,
        () -> client.search("LHR", "JFK", "2026-11-07", "2026-11-10"));

    assertEquals(TravelErrors.liveDuffelToken(), ex.getMessage());
    assertEquals(0, http.postCount);
  }

  @Test
  public void missingToken_configuredSentence()
  {
    final FakeTravelHttp http = new FakeTravelHttp();
    final DuffelClient missing = new DuffelClient(http, null);
    final DuffelClient blank = new DuffelClient(http, "  ");

    assertEquals(TravelErrors.notConfigured("TRAVEL_DUFFEL_TOKEN"),
        assertThrows(DomatarException.class,
            () -> missing.search("LHR", "JFK", "2026-11-07", "2026-11-10")).getMessage());
    assertEquals(TravelErrors.notConfigured("TRAVEL_DUFFEL_TOKEN"),
        assertThrows(DomatarException.class,
            () -> blank.search("LHR", "JFK", "2026-11-07", "2026-11-10")).getMessage());
    assertEquals(0, http.postCount);
  }

  @Test
  public void parsesZzOwnerAndPrice() throws Exception
  {
    final FakeTravelHttp http = new FakeTravelHttp();
    final Instant before = Instant.now();

    http.putPost(DuffelClient.OFFER_REQUESTS, sample());

    final DuffelClient client = new DuffelClient(http, "duffel_test_unit");
    final List<DuffelClient.Offer> offers =
        client.search("LHR", "JFK", "2026-11-07", "2026-11-10");

    assertEquals(2, offers.size());
    assertEquals("ZZ", offers.get(0).iata);
    assertEquals("Duffel Airways", offers.get(0).airline);
    assertEquals("45.00", offers.get(0).price);
    assertEquals("GBP", offers.get(0).currency);
    assertEquals("pas_adult", offers.get(0).passengerId);
    assertEquals("off_zz", offers.get(0).offerId);
    assertEquals("LHR", offers.get(0).origin);
    assertEquals("JFK", offers.get(0).destination);
    assertEquals("economy", offers.get(0).cabin);
    assertTrue(Instant.parse(offers.get(0).expiresAt).isAfter(before));
    assertEquals("BA", offers.get(1).iata);
    assertEquals("200.00", offers.get(1).price);
    assertTrue(http.lastPostBody.contains("\"cabin_class\" : \"economy\""));
    assertTrue(http.lastPostBody.contains("LHR"));
    assertTrue(http.lastPostBody.contains("JFK"));
    assertEquals("v2", http.lastPostHeaders.get("Duffel-Version"));
    assertEquals("Bearer duffel_test_unit", http.lastPostHeaders.get("Authorization"));
  }

  @Test
  public void emptyOffers_isEmptyListNotAnError() throws Exception
  {
    final FakeTravelHttp http = new FakeTravelHttp();

    http.putPost(DuffelClient.OFFER_REQUESTS,
        "{\"data\":{\"passengers\":[{\"id\":\"pas_adult\",\"type\":\"adult\"}],\"offers\":[]}}");

    final List<DuffelClient.Offer> offers =
        new DuffelClient(http, "duffel_test_unit")
            .search("LHR", "JFK", "2026-11-07", "2026-11-10");

    assertEquals(0, offers.size());
  }

  @Test
  public void createOrder_postsSelectedOffer() throws Exception
  {
    final FakeTravelHttp http = new FakeTravelHttp();

    http.putPost(DuffelClient.ORDERS,
        "{\"data\":{\"id\":\"ord_1\",\"booking_reference\":\"ABC123\"}}");

    final DuffelClient.Order order = new DuffelClient(http, "duffel_test_unit")
        .createOrder("off_zz", "pas_adult",
            TravelPassenger.fromUsrName("David Boxenhorn"), "45.00", "GBP");

    assertEquals("ord_1", order.orderId);
    assertEquals("ABC123", order.bookingReference);
    assertTrue(http.lastPostBody.contains("off_zz"));
    assertTrue(http.lastPostBody.contains("pas_adult"));
    assertFalse(http.lastPostBody.contains("4242"));
    assertFalse(http.lastPostBody.contains("card"));
    assertEquals("v2", http.lastPostHeaders.get("Duffel-Version"));
  }

  @Test
  public void airportFor_prefersCityCode() throws Exception
  {
    final FakeTravelHttp http = new FakeTravelHttp();

    http.put(DuffelClient.SUGGESTIONS,
        "{\"data\":["
        + "{\"type\":\"city\",\"name\":\"Boston\",\"iata_code\":\"BOS\","
        + "\"airports\":["
        + "{\"iata_code\":\"BNH\",\"name\":\"Boston Harbor Seaplane Base\"},"
        + "{\"iata_code\":\"BOS\",\"name\":\"Logan International Airport\",\"icao_code\":\"KBOS\"}"
        + "]}"
        + "]}");

    final String code = new DuffelClient(http, "duffel_test_unit").airportFor("Boston");

    assertEquals("BOS", code);
    assertEquals(1, http.getCount);
    assertEquals(0, http.postCount);
    assertTrue(http.lastGetUrl.contains("query=Boston"));
    assertEquals("v2", http.lastGetHeaders.get("Duffel-Version"));
  }

  @Test
  public void airportFor_prefersInternational() throws Exception
  {
    final FakeTravelHttp http = new FakeTravelHttp();

    http.put(DuffelClient.SUGGESTIONS,
        "{\"data\":["
        + "{\"type\":\"city\",\"name\":\"Chicago\",\"iata_code\":\"CHI\","
        + "\"airports\":["
        + "{\"iata_code\":\"DPA\",\"name\":\"DuPage Airport\",\"icao_code\":\"KDPA\"},"
        + "{\"iata_code\":\"MDW\",\"name\":\"Chicago Midway International Airport\",\"icao_code\":\"KMDW\"}"
        + "]}"
        + "]}");

    assertEquals("MDW", new DuffelClient(http, "duffel_test_unit").airportFor("Chicago"));
  }

  @Test
  public void airportFor_skipsCityCode() throws Exception
  {
    final FakeTravelHttp http = new FakeTravelHttp();

    http.put(DuffelClient.SUGGESTIONS,
        "{\"data\":["
        + "{\"type\":\"city\",\"name\":\"Nowhere\",\"iata_code\":\"XYZ\"},"
        + "{\"type\":\"airport\",\"iata_code\":\"bos\",\"name\":\"Logan International Airport\"}"
        + "]}");

    assertEquals("BOS", new DuffelClient(http, "duffel_test_unit").airportFor("Boston"));
  }

  @Test
  public void airportFor_emptyIsNull() throws Exception
  {
    final FakeTravelHttp http = new FakeTravelHttp();

    http.put(DuffelClient.SUGGESTIONS, "{\"data\":[]}");

    assertNull(new DuffelClient(http, "duffel_test_unit").airportFor("NotAPlace"));
  }

  @Test
  public void airportFor_blankDoesNotCall() throws Exception
  {
    final FakeTravelHttp http = new FakeTravelHttp();

    assertNull(new DuffelClient(http, "duffel_test_unit").airportFor("  "));
    assertEquals(0, http.getCount);
  }

  private static String sample()
  {
    return "{"
        + "\"data\":{"
        + "\"passengers\":[{\"id\":\"pas_adult\",\"type\":\"adult\"}],"
        + "\"offers\":["
        + offer("off_ba", "British Airways", "BA", "200.00", "\"expires_at\":\"2026-12-01T00:00:00Z\"")
        + ","
        + offer("off_zz", "Duffel Airways", "ZZ", "45.00", "")
        + "]}}";
  }

  private static String offer(final String id, final String airline, final String iata,
                              final String price, final String extra)
  {
    final String expires = extra.isEmpty() ? "" : "," + extra;

    return "{\"id\":\"" + id + "\""
        + expires
        + ",\"total_amount\":\"" + price + "\",\"total_currency\":\"GBP\""
        + ",\"owner\":{\"name\":\"" + airline + "\",\"iata_code\":\"" + iata + "\"}"
        + ",\"slices\":[{\"origin\":{\"iata_code\":\"LHR\"},\"destination\":{\"iata_code\":\"JFK\"}"
        + ",\"segments\":[{\"departing_at\":\"2026-11-07T09:00:00\""
        + ",\"arriving_at\":\"2026-11-07T12:00:00\""
        + ",\"passengers\":[{\"cabin_class\":\"economy\"}]}]}]}";
  }
}
