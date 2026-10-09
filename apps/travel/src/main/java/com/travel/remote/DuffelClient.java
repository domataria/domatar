/*
 * Copyright (c) 2024 Domatar
 */

package com.travel.remote;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.domatar.util.DomatarException;
import com.domatar.util.Json;
import com.domatar.util.JsonArrayList;
import com.domatar.util.JsonHashMap;
import com.domatar.util.JsonList;
import com.domatar.util.JsonMap;
import com.travel.objimpl.TravelErrors;
import com.travel.objimpl.TravelPassenger;

/**
 * Duffel Flights v2 offer requests. Only a duffel_test_ token
 * is sent. ZZ offers are listed before the others.
 */
// London to Paris on 2026-11-07 returned rows in test mode. Not a guarantee.
public final class DuffelClient
{
  static final String OFFER_REQUESTS =
      "https://api.duffel.com/air/offer_requests?return_offers=true";
  static final String ORDERS = "https://api.duffel.com/air/orders";
  static final String CANCELLATIONS = "https://api.duffel.com/air/order_cancellations";
  static final String SUGGESTIONS = "https://api.duffel.com/places/suggestions?query=";

  private static final String VERSION = "v2";
  private static final int EXPIRY_MINUTES = 30;

  private final TravelHttp http;
  private final String token;

  public DuffelClient(final TravelHttp http, final String token)
  {
    this.http = http;
    this.token = token;
  }

  /**
   * Airport IATA for a city name Duffel recognizes. Null when the
   * suggestions list has no airport. Blank input does not call Duffel.
   */
  public String airportFor(final String query) throws DomatarException
  {
    if (query == null || query.isBlank())
      return null;

    requireToken();

    final String body = http.get(
        SUGGESTIONS + URLEncoder.encode(query.trim(), StandardCharsets.UTF_8),
        headers());

    return firstAirport(body);
  }

  public List<Offer> search(final String originIata, final String destIata,
                            final String depart, final String returnDate)
      throws DomatarException
  {
    requireToken();

    final String body = http.post(OFFER_REQUESTS,
        requestBody(originIata, destIata, depart, returnDate), headers());

    return sortZzFirst(parseOffers(body, originIata, destIata, Instant.now()));
  }

  public Order createOrder(final String offerId, final String passengerId,
                           final TravelPassenger passenger, final String amount,
                           final String currency) throws DomatarException
  {
    requireToken();

    final String body = http.post(ORDERS,
        orderBody(offerId, passengerId, passenger, amount, currency), headers());
    final JsonMap data = data(body);
    final String orderId = text(data, "id");

    if (orderId.isEmpty())
      throw new DomatarException("order id missing");
    return new Order(orderId, text(data, "booking_reference"));
  }

  public void cancelOrder(final String orderId) throws DomatarException
  {
    requireToken();

    final String created = http.post(CANCELLATIONS, cancelBody(orderId), headers());
    final String cancellationId = text(data(created), "id");

    if (cancellationId.isEmpty())
      throw new DomatarException("cancellation id missing");

    http.post(CANCELLATIONS + "/" + cancellationId + "/actions/confirm",
        emptyData(), headers());
  }

  private Map<String, String> headers()
  {
    final Map<String, String> headers = new LinkedHashMap<String, String>();

    headers.put("Authorization", "Bearer " + token);
    headers.put("Duffel-Version", VERSION);
    headers.put("Accept", "application/json");
    headers.put("Content-Type", "application/json");
    return headers;
  }

  private void requireToken() throws DomatarException
  {
    if (token == null || token.isBlank())
      throw new DomatarException(TravelErrors.notConfigured("TRAVEL_DUFFEL_TOKEN"));
    if (!token.startsWith("duffel_test_"))
      throw new DomatarException(TravelErrors.liveDuffelToken());
  }

  private static String requestBody(final String originIata, final String destIata,
                                    final String depart, final String returnDate)
      throws DomatarException
  {
    final JsonList slices = new JsonArrayList();

    slices.add(slice(originIata, destIata, depart));
    slices.add(slice(destIata, originIata, returnDate));

    final JsonMap passenger = new JsonHashMap();

    passenger.put("type", "adult");

    final JsonList passengers = new JsonArrayList();

    passengers.add(passenger);

    final JsonMap data = new JsonHashMap();

    data.put("slices", slices);
    data.put("passengers", passengers);
    data.put("cabin_class", "economy");

    final JsonMap root = new JsonHashMap();

    root.put("data", data);
    return Json.toJson(root);
  }

  private static String orderBody(final String offerId, final String passengerId,
                                  final TravelPassenger passenger, final String amount,
                                  final String currency) throws DomatarException
  {
    final JsonMap person = new JsonHashMap();

    person.put("id", passengerId);
    person.put("title", passenger.title);
    person.put("gender", passenger.gender);
    person.put("given_name", passenger.given);
    person.put("family_name", passenger.family);
    person.put("born_on", passenger.bornOn);
    person.put("email", passenger.email);
    person.put("phone_number", passenger.phone);

    final JsonList passengers = new JsonArrayList();
    final JsonList offers = new JsonArrayList();
    final JsonMap payment = new JsonHashMap();
    final JsonList payments = new JsonArrayList();

    passengers.add(person);
    offers.add(offerId);
    payment.put("type", "balance");
    payment.put("amount", amount == null ? "" : amount);
    payment.put("currency", currency == null ? "" : currency);
    payments.add(payment);

    final JsonMap data = new JsonHashMap();

    data.put("type", "instant");
    data.put("selected_offers", offers);
    data.put("passengers", passengers);
    data.put("payments", payments);

    final JsonMap root = new JsonHashMap();

    root.put("data", data);
    return Json.toJson(root);
  }

  private static String cancelBody(final String orderId) throws DomatarException
  {
    final JsonMap data = new JsonHashMap();

    data.put("order_id", orderId);
    final JsonMap root = new JsonHashMap();

    root.put("data", data);
    return Json.toJson(root);
  }

  private static String emptyData() throws DomatarException
  {
    final JsonMap root = new JsonHashMap();

    root.put("data", new JsonHashMap());
    return Json.toJson(root);
  }

  private static String firstAirport(final String body) throws DomatarException
  {
    if (body == null || body.isBlank())
      return null;

    final JsonList data = Json.parseMap(body).getList("data");

    if (data == null)
      return null;

    for (final Object item : data)
    {
      if (!(item instanceof JsonMap))
        continue;

      final JsonMap place = (JsonMap) item;
      final String nested = airportCode(place.getList("airports"),
          text(place, "iata_code"));

      if (nested != null)
        return nested;
      if ("airport".equals(text(place, "type")))
      {
        final String code = airportCode(text(place, "iata_code"));

        if (code != null)
          return code;
      }
    }
    return null;
  }

  private static String airportCode(final JsonList airports, final String cityCode)
      throws DomatarException
  {
    if (airports == null)
      return null;

    final String city = airportCode(cityCode);
    String international = null;
    String withIcao = null;
    String any = null;

    for (final Object item : airports)
    {
      if (!(item instanceof JsonMap))
        continue;

      final JsonMap airport = (JsonMap) item;
      final String code = airportCode(text(airport, "iata_code"));

      if (code == null)
        continue;
      if (city != null && city.equals(code))
        return code;
      if (any == null)
        any = code;

      final String icao = text(airport, "icao_code");

      if (icao == null || icao.isBlank())
        continue;
      if (withIcao == null)
        withIcao = code;

      final String name = text(airport, "name");

      if (international == null && name != null
          && name.toLowerCase(Locale.ROOT).contains("international"))
        international = code;
    }
    if (international != null)
      return international;
    if (withIcao != null)
      return withIcao;
    return any;
  }

  private static String airportCode(final String code)
  {
    if (code == null || code.length() != 3)
      return null;

    for (int i = 0; i < code.length(); i++)
    {
      final char c = code.charAt(i);

      if ((c < 'A' || c > 'Z') && (c < 'a' || c > 'z'))
        return null;
    }
    return code.toUpperCase(Locale.ROOT);
  }

  private static JsonMap data(final String body) throws DomatarException
  {
    if (body == null || body.isBlank())
      throw new DomatarException("empty Duffel response");

    final JsonMap data = Json.parseMap(body).getMap("data");

    if (data == null)
      throw new DomatarException("Duffel response has no data");
    return data;
  }

  private static JsonMap slice(final String origin, final String destination,
                               final String date)
  {
    final JsonMap slice = new JsonHashMap();

    slice.put("origin", origin);
    slice.put("destination", destination);
    slice.put("departure_date", date);
    return slice;
  }

  private static List<Offer> parseOffers(final String body, final String originIata,
                                         final String destIata, final Instant now)
      throws DomatarException
  {
    final List<Offer> offers = new ArrayList<Offer>();

    if (body == null || body.isBlank())
      return offers;

    final JsonMap root = Json.parseMap(body);
    final JsonMap data = root.getMap("data");

    if (data == null)
      return offers;

    final String passengerId = adultId(data.getList("passengers"));
    final JsonList rows = data.getList("offers");

    if (rows == null)
      return offers;

    for (final Object item : rows)
    {
      if (!(item instanceof JsonMap))
        continue;

      final Offer offer = offerOf((JsonMap) item, passengerId, originIata, destIata, now);

      if (offer != null)
        offers.add(offer);
    }
    return offers;
  }

  private static List<Offer> sortZzFirst(final List<Offer> offers)
  {
    final List<Offer> zz = new ArrayList<Offer>();
    final List<Offer> rest = new ArrayList<Offer>();

    for (final Offer offer : offers)
    {
      if ("ZZ".equalsIgnoreCase(offer.iata))
        zz.add(offer);
      else
        rest.add(offer);
    }
    zz.addAll(rest);
    return zz;
  }

  private static String adultId(final JsonList passengers) throws DomatarException
  {
    if (passengers == null)
      return "";

    String first = "";

    for (final Object item : passengers)
    {
      if (!(item instanceof JsonMap))
        continue;

      final JsonMap passenger = (JsonMap) item;
      final String id = text(passenger, "id");

      if (first.isEmpty())
        first = id;
      if ("adult".equals(text(passenger, "type")) && !id.isEmpty())
        return id;
    }
    return first;
  }

  private static Offer offerOf(final JsonMap offer, final String passengerId,
                               final String originIata, final String destIata,
                               final Instant now) throws DomatarException
  {
    final String offerId = text(offer, "id");

    if (offerId.isEmpty())
      return null;

    final JsonMap owner = offer.getMap("owner");
    final String airline = owner == null ? "" : text(owner, "name");
    final String iata = owner == null ? "" : text(owner, "iata_code");
    String expires = text(offer, "expires_at");

    if (expires.isEmpty())
      expires = now.plus(Duration.ofMinutes(EXPIRY_MINUTES)).toString();

    String origin = originIata == null ? "" : originIata;
    String destination = destIata == null ? "" : destIata;
    String departAt = "";
    String arriveAt = "";
    String cabin = "economy";
    final JsonList slices = offer.getList("slices");

    if (slices != null && !slices.isEmpty() && slices.get(0) instanceof JsonMap)
    {
      final JsonMap slice = (JsonMap) slices.get(0);

      origin = placeCode(slice.get("origin"), origin);
      destination = placeCode(slice.get("destination"), destination);

      final JsonList segments = slice.getList("segments");

      if (segments != null && !segments.isEmpty())
      {
        if (segments.get(0) instanceof JsonMap)
        {
          final JsonMap first = (JsonMap) segments.get(0);

          departAt = text(first, "departing_at");
          final String cabinClass = cabinOf(first);

          if (!cabinClass.isEmpty())
            cabin = cabinClass;
        }

        final Object lastItem = segments.get(segments.size() - 1);

        if (lastItem instanceof JsonMap)
          arriveAt = text((JsonMap) lastItem, "arriving_at");
      }
    }

    return new Offer(offerId, airline, iata, origin, destination, departAt, arriveAt,
        amount(offer), text(offer, "total_currency"), cabin, expires, passengerId);
  }

  private static String cabinOf(final JsonMap segment) throws DomatarException
  {
    final JsonList passengers = segment.getList("passengers");

    if (passengers == null || passengers.isEmpty() || !(passengers.get(0) instanceof JsonMap))
      return "";
    return text((JsonMap) passengers.get(0), "cabin_class");
  }

  private static String placeCode(final Object place, final String fallback)
      throws DomatarException
  {
    if (place instanceof String)
    {
      final String value = ((String) place).trim();

      return value.isEmpty() ? fallback : value;
    }
    if (place instanceof JsonMap)
    {
      final String code = text((JsonMap) place, "iata_code");

      return code.isEmpty() ? fallback : code;
    }
    return fallback;
  }

  private static String amount(final JsonMap offer)
  {
    final Object value = offer.get("total_amount");

    return value == null ? "" : String.valueOf(value);
  }

  private static String text(final JsonMap map, final String key) throws DomatarException
  {
    final String value = map.getString(key);

    return value == null ? "" : value.trim();
  }

  public static final class Order
  {
    public final String orderId;
    public final String bookingReference;

    public Order(final String orderId, final String bookingReference)
    {
      this.orderId = orderId;
      this.bookingReference = bookingReference;
    }
  }

  public static final class Offer
  {
    public final String offerId;
    public final String airline;
    public final String iata;
    public final String origin;
    public final String destination;
    public final String departAt;
    public final String arriveAt;
    public final String price;
    public final String currency;
    public final String cabin;
    public final String expiresAt;
    public final String passengerId;

    public Offer(final String offerId, final String airline, final String iata,
                 final String origin, final String destination, final String departAt,
                 final String arriveAt, final String price, final String currency,
                 final String cabin, final String expiresAt, final String passengerId)
    {
      this.offerId = offerId;
      this.airline = airline;
      this.iata = iata;
      this.origin = origin;
      this.destination = destination;
      this.departAt = departAt;
      this.arriveAt = arriveAt;
      this.price = price;
      this.currency = currency;
      this.cabin = cabin;
      this.expiresAt = expiresAt;
      this.passengerId = passengerId;
    }
  }
}
