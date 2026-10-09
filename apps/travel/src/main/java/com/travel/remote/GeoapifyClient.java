/*
 * Copyright (c) 2024 Domatar
 */

package com.travel.remote;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.domatar.util.DomatarException;
import com.domatar.util.Json;
import com.domatar.util.JsonList;
import com.domatar.util.JsonMap;
import com.travel.objimpl.TravelErrors;

/**
 * Geoapify geocoding and places. The key is a query parameter.
 */
public final class GeoapifyClient
{
  public static final String HOTEL_CATEGORY = "accommodation.hotel";
  public static final String SIGHT_ATTRACTION = "tourism.attraction";
  public static final String SIGHT_MUSEUM = "entertainment.museum";

  private static final String GEOCODE_URL =
      "https://api.geoapify.com/v1/geocode/search";
  private static final String PLACES_URL =
      "https://api.geoapify.com/v2/places";
  private static final int PAGE = 500;
  private static final int MAX_PAGES = 40;

  private final TravelHttp http;
  private final String apiKey;

  public GeoapifyClient(final TravelHttp http, final String apiKey)
  {
    this.http = http;
    this.apiKey = apiKey;
  }

  public City geocode(final String city) throws DomatarException
  {
    requireKey();

    final String url = GEOCODE_URL
        + "?text=" + enc(city == null ? "" : city)
        + "&type=city&format=geojson&apiKey=" + enc(apiKey);
    final JsonList features = features(http.get(url));

    if (features == null || features.isEmpty())
      return null;

    final Object first = features.get(0);

    if (!(first instanceof JsonMap))
      return null;

    return cityOf((JsonMap) first);
  }

  /**
   * Hotels near the city. Null when the city does not geocode.
   * Places with no street are dropped.
   */
  public List<Place> hotels(final String city) throws DomatarException
  {
    final City located = geocode(city);

    if (located == null)
      return null;

    final List<Place> rows = searchCategory(HOTEL_CATEGORY, located, city);
    final List<Place> kept = new ArrayList<Place>();

    for (final Place place : rows)
    {
      if (place.street != null && !place.street.isEmpty())
        kept.add(place);
    }
    return kept;
  }

  /**
   * Attractions and museums, deduped by place id. Null when the
   * city does not geocode. The first category wins.
   */
  public List<Place> sights(final String city) throws DomatarException
  {
    final City located = geocode(city);

    if (located == null)
      return null;

    final Map<String, Place> byId = new LinkedHashMap<String, Place>();

    addSights(byId, searchCategory(SIGHT_ATTRACTION, located, city));
    addSights(byId, searchCategory(SIGHT_MUSEUM, located, city));
    return new ArrayList<Place>(byId.values());
  }

  private static void addSights(final Map<String, Place> byId, final List<Place> rows)
  {
    for (final Place place : rows)
    {
      if (place.placeId == null || place.placeId.isEmpty())
        continue;
      if (!byId.containsKey(place.placeId))
        byId.put(place.placeId, place);
    }
  }

  private List<Place> searchCategory(final String category, final City located,
                                     final String requestedCity)
      throws DomatarException
  {
    final List<Place> rows = new ArrayList<Place>();
    final String filter = filter(located);

    for (int page = 0; page < MAX_PAGES; page++)
    {
      final int offset = page * PAGE;
      final String url = PLACES_URL
          + "?categories=" + enc(category)
          + "&filter=" + filter
          + "&limit=" + PAGE
          + "&offset=" + offset
          + "&apiKey=" + enc(apiKey);
      final JsonList features = features(http.get(url));
      final int count = features == null ? 0 : features.size();

      if (features != null)
      {
        for (final Object item : features)
        {
          if (!(item instanceof JsonMap))
            continue;

          final Place place = placeOf((JsonMap) item, category, requestedCity);

          if (place != null)
            rows.add(place);
        }
      }

      if (count < PAGE)
        break;
    }
    return rows;
  }

  private static String filter(final City located)
  {
    if (located.placeId != null && !located.placeId.isEmpty())
      return "place:" + enc(located.placeId);

    return "circle:"
        + String.format(Locale.US, "%.6f", Double.valueOf(located.lon))
        + ","
        + String.format(Locale.US, "%.6f", Double.valueOf(located.lat))
        + ",8000";
  }

  private void requireKey() throws DomatarException
  {
    if (apiKey == null || apiKey.isBlank())
      throw new DomatarException(TravelErrors.notConfigured("TRAVEL_GEOAPIFY_KEY"));
  }

  private static JsonList features(final String body) throws DomatarException
  {
    if (body == null || body.isBlank())
      return null;

    final JsonMap root = Json.parseMap(body);

    return root.getList("features");
  }

  private static City cityOf(final JsonMap feature) throws DomatarException
  {
    final JsonMap props = feature.getMap("properties");

    if (props == null)
      return null;

    Double lat = number(props, "lat");
    Double lon = number(props, "lon");

    if (lat == null || lon == null)
    {
      final JsonMap geometry = feature.getMap("geometry");
      final JsonList coordinates = geometry == null ? null : geometry.getList("coordinates");

      if (coordinates != null && coordinates.size() >= 2
          && coordinates.get(0) instanceof Number
          && coordinates.get(1) instanceof Number)
      {
        lon = Double.valueOf(((Number) coordinates.get(0)).doubleValue());
        lat = Double.valueOf(((Number) coordinates.get(1)).doubleValue());
      }
    }

    if (lat == null || lon == null)
      return null;

    return new City(textOrNull(props, "place_id"), lat.doubleValue(), lon.doubleValue());
  }

  private static Place placeOf(final JsonMap feature, final String category,
                               final String requestedCity) throws DomatarException
  {
    final JsonMap props = feature.getMap("properties");

    if (props == null)
      return null;

    final String name = firstText(props, "name", "address_line1");

    if (name.isEmpty())
      return null;

    String street = text(props, "street");
    final String house = text(props, "housenumber");

    if (!house.isEmpty())
      street = street.isEmpty() ? house : house + " " + street;
    if (street.isEmpty())
      street = text(props, "address_line1");

    String city = text(props, "city");

    if (city.isEmpty())
      city = requestedCity == null ? "" : requestedCity;

    return new Place(text(props, "place_id"), name, street, city, category);
  }

  private static String firstText(final JsonMap props, final String a, final String b)
      throws DomatarException
  {
    final String first = text(props, a);

    if (!first.isEmpty())
      return first;
    return text(props, b);
  }

  private static String text(final JsonMap props, final String key) throws DomatarException
  {
    final String value = props.getString(key);

    return value == null ? "" : value.trim();
  }

  private static String textOrNull(final JsonMap props, final String key)
      throws DomatarException
  {
    final String value = text(props, key);

    return value.isEmpty() ? null : value;
  }

  private static Double number(final JsonMap props, final String key) throws DomatarException
  {
    final Number value = props.getNumber(key);

    return value == null ? null : Double.valueOf(value.doubleValue());
  }

  private static String enc(final String value)
  {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  public static final class City
  {
    public final String placeId;
    public final double lat;
    public final double lon;

    public City(final String placeId, final double lat, final double lon)
    {
      this.placeId = placeId;
      this.lat = lat;
      this.lon = lon;
    }
  }

  public static final class Place
  {
    public final String placeId;
    public final String name;
    public final String street;
    public final String city;
    public final String category;

    public Place(final String placeId, final String name, final String street,
                 final String city, final String category)
    {
      this.placeId = placeId;
      this.name = name;
      this.street = street;
      this.city = city;
      this.category = category;
    }
  }
}
