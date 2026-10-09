/*
 * Copyright (c) 2024 Domatar
 */

package com.travel.objimpl;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.domatar.core.Auth;
import com.domatar.db.ActDb;
import com.domatar.db.LnkDb;
import com.domatar.db.ObjDb;
import com.domatar.util.Act;
import com.domatar.util.DomId;
import com.domatar.util.DomatarException;
import com.domatar.util.DomatarMsgClient;
import com.domatar.util.IdGen;
import com.domatar.util.JsonArrayList;
import com.domatar.util.JsonHashMap;
import com.domatar.util.JsonList;
import com.domatar.util.JsonMap;
import com.domatar.util.JsonMsg;
import com.domatar.util.Lnk;
import com.domatar.util.Obj;
import com.domatar.util.ObjAttrs;
import com.domatar.util.ObjImpl;
import com.travel.remote.DuffelClient;
import com.travel.remote.GeoapifyClient;
import com.travel.remote.TravelHttp;
import com.travel.remote.TravelHttpClient;

/**
 * Facade. Search calls Duffel and Geoapify. BookTrip creates one
 * Duffel test order and one trip.
 */
public class TravelAppImpl extends ObjImpl
{
  private static final int MAX_OFFERS = 8;

  private static final String[] TRIP_FIELDS = {
      "Title", "Origin", "Destination", "Depart", "Return",
      "FlightName", "Airline", "FlightPrice", "FlightCurrency",
      "DuffelOrderId", "BookingReference",
      "HotelName", "HotelStreet", "CheckIn", "Nights", "NightlyPrice",
      "HotelTotal", "HotelCurrency", "Day1", "Day2", "Day3", "BookedAt"
  };

  private static final TravelHttp HTTP = new TravelHttpClient();

  private static TravelHttp testHttp;
  private static String testDuffelToken;

  static void setClientsForTest(final TravelHttp http, final String token)
  {
    testHttp = http;
    testDuffelToken = token;
  }

  static boolean isOwner(final JsonMsg inMsg, final Obj obj,
                         final DomatarMsgClient msgClient)
      throws DomatarException
  {
    if (!Auth.isVerified(msgClient))
      return false;

    final String actId = Auth.actId(msgClient);

    if (actId == null)
      return false;

    final Obj row = ownerRow(inMsg, obj);

    if (row == null || row.domId == null || row.domId.actId == null)
      return false;
    return actId.equals(row.domId.actId);
  }

  /**
   * A class envelope arrives with obj null. Load the destination row
   * so the owner check still sees obj.domId.actId.
   */
  private static Obj ownerRow(final JsonMsg inMsg, final Obj obj)
      throws DomatarException
  {
    if (obj != null && obj.domId != null && obj.domId.actId != null)
      return obj;

    final DomId dst = inMsg != null ? inMsg.getDstId() : null;

    if (dst == null)
      return obj;

    final Obj loaded = ObjDb.getObj(dst);

    return loaded != null ? loaded : obj;
  }

  static String unauthorized(final String opr) throws DomatarException
  {
    final JsonMsg out = new JsonMsg();

    out.addError(opr, "Unauthorized");
    return out.toString();
  }

  static String unknown(final String opr) throws DomatarException
  {
    final JsonMsg out = new JsonMsg();

    out.addError(opr, "Unknown operation");
    return out.toString();
  }

  @Override
  public boolean hasRights(final JsonMsg inMsg, final Obj obj,
                           final DomatarMsgClient msgClient)
      throws DomatarException
  {
    if (inMsg != null && "Compensate".equals(inMsg.getOperation()))
      return super.hasRights(inMsg, obj, msgClient);
    return isOwner(inMsg, obj, msgClient);
  }

  @Override
  public String handleMsg(final String msg, final Obj obj, final String contextPath,
                          final String contextRealPath, final DomatarMsgClient msgClient)
      throws DomatarException
  {
    final JsonMsg inMsg = new JsonMsg(msg);
    final String opr = inMsg.getOperation();

    if ("Compensate".equals(opr))
      return super.handleMsg(msg, obj, contextPath, contextRealPath, msgClient);

    if (!hasRights(inMsg, obj, msgClient))
      return unauthorized(opr);

    switch (opr)
    {
      case "SearchFlights":
        return searchFlights(inMsg, msgClient);
      case "SearchHotels":
        return searchHotels(inMsg, msgClient);
      case "SearchSights":
        return searchSights(inMsg, msgClient);
      case "BookTrip":
        return bookTrip(inMsg, msgClient);
      case "GetTrips":
        return getTrips(msgClient);
      case "GetTrip":
        return getTrip(inMsg, msgClient);
      default:
        return unknown(opr);
    }
  }

  private String searchFlights(final JsonMsg inMsg, final DomatarMsgClient msgClient)
      throws DomatarException
  {
    final String opr = "SearchFlights";
    final String depart = inMsg.getAttr("Depart");
    final String returnDate = inMsg.getAttr("Return");

    if (!isDate(depart) || !isDate(returnDate))
      return failure(opr, TravelErrors.badDate());

    final String originName = inMsg.getAttr("Origin");
    final String destName = inMsg.getAttr("Destination");
    final DuffelClient duffel = new DuffelClient(HTTP, System.getenv("TRAVEL_DUFFEL_TOKEN"));
    final String origin;
    final String destination;

    try
    {
      origin = airport(duffel, originName);
      destination = airport(duffel, destName);
    }
    catch (final DomatarException e)
    {
      return failure(opr, e.getMessage() != null
          ? e.getMessage()
          : TravelErrors.notConfigured("TRAVEL_DUFFEL_TOKEN"));
    }

    if (origin == null)
      return failure(opr, TravelErrors.unknownCity(originName == null ? "" : originName));
    if (destination == null)
      return failure(opr, TravelErrors.unknownCity(destName == null ? "" : destName));

    final List<DuffelClient.Offer> offers;

    try
    {
      offers = duffel.search(origin, destination, depart, returnDate);
    }
    catch (final DomatarException e)
    {
      return failure(opr, e.getMessage() != null
          ? e.getMessage()
          : TravelErrors.notConfigured("TRAVEL_DUFFEL_TOKEN"));
    }

    final JsonList stored = new JsonArrayList();
    final Set<String> names = new LinkedHashSet<String>();

    for (final DuffelClient.Offer offer : offers)
    {
      if (stored.size() >= MAX_OFFERS)
        break;

      final JsonMap row = new JsonHashMap();

      row.put("Name", TravelNames.flightName(
          offer.airline, offer.origin, offer.destination, offer.departAt, names));
      row.put("OfferId", offer.offerId);
      row.put("Airline", offer.airline);
      row.put("Origin", offer.origin);
      row.put("Destination", offer.destination);
      row.put("DepartAt", offer.departAt);
      row.put("ArriveAt", offer.arriveAt);
      row.put("Price", offer.price);
      row.put("Currency", offer.currency);
      row.put("Cabin", offer.cabin);
      row.put("ExpiresAt", offer.expiresAt);
      row.put("PassengerId", offer.passengerId);
      row.put("Depart", depart);
      row.put("Return", returnDate);
      stored.add(row);
    }

    TravelCache.replaceFlights(Auth.actId(msgClient), stored);

    final JsonList outOffers = new JsonArrayList();

    for (final Object item : stored)
    {
      if (!(item instanceof JsonMap))
        continue;

      final JsonMap row = (JsonMap) item;
      final JsonMap outRow = new JsonHashMap();

      outRow.put("Name", row.getString("Name"));
      outRow.put("Airline", row.getString("Airline"));
      outRow.put("Origin", row.getString("Origin"));
      outRow.put("Destination", row.getString("Destination"));
      outRow.put("DepartAt", row.getString("DepartAt"));
      outRow.put("ArriveAt", row.getString("ArriveAt"));
      outRow.put("Price", row.getString("Price"));
      outRow.put("Currency", row.getString("Currency"));
      outRow.put("Cabin", row.getString("Cabin"));
      outOffers.add(outRow);
    }
    return flightsOk(opr, stored.size(), outOffers);
  }

  private String searchHotels(final JsonMsg inMsg, final DomatarMsgClient msgClient)
      throws DomatarException
  {
    final String opr = "SearchHotels";
    final String missing = missingGeoapify();

    if (missing != null)
      return failure(opr, missing);

    final String checkIn = inMsg.getAttr("CheckIn");
    final String nightsRaw = inMsg.getAttr("Nights");

    if (!isDate(checkIn))
      return failure(opr, TravelErrors.badDate());

    final int nights = nights(nightsRaw);

    if (nights < 1)
      return failure(opr, TravelErrors.badNights());

    final String city = inMsg.getAttr("City");
    final List<GeoapifyClient.Place> places;

    try
    {
      places = new GeoapifyClient(HTTP, System.getenv("TRAVEL_GEOAPIFY_KEY")).hotels(city);
    }
    catch (final DomatarException e)
    {
      return failure(opr, e.getMessage() != null ? e.getMessage() : missingGeoapify());
    }

    if (places == null)
      return failure(opr, TravelErrors.unknownCity(city == null ? "" : city));

    final JsonList stored = new JsonArrayList();
    final Set<String> names = new LinkedHashSet<String>();

    for (final GeoapifyClient.Place place : places)
    {
      final int nightly = TravelPrice.nightlyPrice(place.placeId);
      final JsonMap row = new JsonHashMap();

      row.put("Name", TravelNames.hotelName(place.name, place.street, names));
      row.put("Street", place.street);
      row.put("City", place.city);
      row.put("PlaceId", place.placeId);
      row.put("NightlyPrice", Integer.valueOf(nightly));
      stored.add(row);
    }

    final String actId = Auth.actId(msgClient);

    TravelCache.replaceHotels(actId, checkIn, nights, stored);

    final TravelCache.Page slice = TravelCache.pageHotels(actId, page(inMsg));
    final JsonList hotels = new JsonArrayList();

    for (final Object item : slice.rows)
    {
      if (!(item instanceof JsonMap))
        continue;

      final JsonMap row = (JsonMap) item;
      final int nightly = number(row, "NightlyPrice");
      final int stayNights = number(row, "Nights");
      final JsonMap outRow = new JsonHashMap();

      outRow.put("Name", row.getString("Name"));
      outRow.put("Street", row.getString("Street"));
      outRow.put("City", row.getString("City"));
      outRow.put("NightlyPrice", Integer.valueOf(nightly));
      outRow.put("Currency", TravelPrice.CURRENCY);
      outRow.put("Nights", Integer.valueOf(stayNights));
      outRow.put("TotalPrice", Integer.valueOf(TravelPrice.stayTotal(nightly, stayNights)));
      hotels.add(outRow);
    }
    return ok(opr, slice.total, slice.page, "Hotels", hotels);
  }

  private String searchSights(final JsonMsg inMsg, final DomatarMsgClient msgClient)
      throws DomatarException
  {
    final String opr = "SearchSights";
    final String missing = missingGeoapify();

    if (missing != null)
      return failure(opr, missing);

    final String city = inMsg.getAttr("City");
    final List<GeoapifyClient.Place> places;

    try
    {
      places = new GeoapifyClient(HTTP, System.getenv("TRAVEL_GEOAPIFY_KEY")).sights(city);
    }
    catch (final DomatarException e)
    {
      return failure(opr, e.getMessage() != null ? e.getMessage() : missingGeoapify());
    }

    if (places == null)
      return failure(opr, TravelErrors.unknownCity(city == null ? "" : city));

    final JsonList stored = new JsonArrayList();
    final Set<String> names = new LinkedHashSet<String>();

    for (final GeoapifyClient.Place place : places)
    {
      final JsonMap row = new JsonHashMap();

      row.put("Name", TravelNames.sightName(place.name, place.street, names));
      row.put("Street", place.street);
      row.put("City", place.city);
      row.put("Category", place.category);
      stored.add(row);
    }

    final String actId = Auth.actId(msgClient);

    TravelCache.replaceSights(actId, stored);

    final TravelCache.Page slice = TravelCache.pageSights(actId, page(inMsg));
    final JsonList sights = new JsonArrayList();

    for (final Object item : slice.rows)
    {
      if (!(item instanceof JsonMap))
        continue;

      final JsonMap row = (JsonMap) item;
      final JsonMap outRow = new JsonHashMap();

      outRow.put("Name", row.getString("Name"));
      outRow.put("Street", row.getString("Street"));
      outRow.put("City", row.getString("City"));
      outRow.put("Category", row.getString("Category"));
      sights.add(outRow);
    }
    return ok(opr, slice.total, slice.page, "Sights", sights);
  }

  private static String missingGeoapify()
  {
    final String key = System.getenv("TRAVEL_GEOAPIFY_KEY");

    if (key == null || key.isBlank())
      return TravelErrors.notConfigured("TRAVEL_GEOAPIFY_KEY");
    return null;
  }

  private static String airport(final DuffelClient duffel, final String name)
      throws DomatarException
  {
    final String known = TravelAirports.iata(name);

    if (known != null)
      return known;
    return duffel.airportFor(name);
  }

  private static boolean isDate(final String value)
  {
    if (value == null || !value.matches("\\d{4}-\\d{2}-\\d{2}"))
      return false;

    try
    {
      LocalDate.parse(value);
      return true;
    }
    catch (final DateTimeParseException e)
    {
      return false;
    }
  }

  private static int nights(final String raw)
  {
    if (raw == null || !raw.matches("[1-9]\\d*"))
      return -1;

    try
    {
      return Integer.parseInt(raw);
    }
    catch (final NumberFormatException e)
    {
      return -1;
    }
  }

  private static int page(final JsonMsg inMsg) throws DomatarException
  {
    final String raw = inMsg.getAttr("Page");

    if (raw == null || raw.isBlank())
      return 1;

    try
    {
      return Integer.parseInt(raw.trim());
    }
    catch (final NumberFormatException e)
    {
      return 1;
    }
  }

  private static int number(final JsonMap row, final String key) throws DomatarException
  {
    final Number value = row.getNumber(key);

    return value == null ? 0 : value.intValue();
  }

  private static String flightsOk(final String opr, final int total, final JsonList offers)
      throws DomatarException
  {
    final JsonMsg out = new JsonMsg();
    final ObjAttrs attrs = new ObjAttrs();

    attrs.addAttr("Total", Integer.valueOf(total));
    attrs.addAttr("Offers", offers);
    out.addResponseBody(opr, attrs);
    return out.toString();
  }

  private static String ok(final String opr, final int total, final int page,
                           final String listName, final JsonList rows)
      throws DomatarException
  {
    final JsonMsg out = new JsonMsg();
    final ObjAttrs attrs = new ObjAttrs();

    attrs.addAttr("Total", Integer.valueOf(total));
    attrs.addAttr("Page", Integer.valueOf(page));
    attrs.addAttr(listName, rows);
    out.addResponseBody(opr, attrs);
    return out.toString();
  }

  private static String failure(final String opr, final String message)
      throws DomatarException
  {
    final JsonMsg out = new JsonMsg();

    out.addError(opr, message);
    return out.toString();
  }

  private String bookTrip(final JsonMsg inMsg, final DomatarMsgClient msgClient)
      throws DomatarException
  {
    final String opr = "BookTrip";
    final String missing = missingParm(inMsg,
        "Title", "FlightName", "HotelName", "Day1", "Day2", "Day3");

    if (missing != null)
      return failure(opr, "Missing " + missing);

    final String actId = Auth.actId(msgClient);
    final String title = inMsg.getAttr("Title").trim();

    if (tripByTitle(actId, title) != null)
      return failure(opr, TravelErrors.tripExists(title));

    final TravelCache.FlightHit flight = TravelCache.findFlight(actId, inMsg.getAttr("FlightName"));

    if (flight.expired)
      return failure(opr, TravelErrors.offerExpired());
    if (flight.row == null)
      return failure(opr, TravelErrors.unknownFlight());

    final JsonMap hotel = TravelCache.findHotel(actId, inMsg.getAttr("HotelName"));

    if (hotel == null)
      return failure(opr, TravelErrors.unknownHotel());

    final String day1 = inMsg.getAttr("Day1").trim();
    final String day2 = inMsg.getAttr("Day2").trim();
    final String day3 = inMsg.getAttr("Day3").trim();

    if (unknownDay(actId, day1) || unknownDay(actId, day2) || unknownDay(actId, day3))
      return failure(opr, TravelErrors.unknownSight());

    final TravelHttp http = testHttp != null ? testHttp : HTTP;
    final String token = testHttp != null ? testDuffelToken : System.getenv("TRAVEL_DUFFEL_TOKEN");
    final DuffelClient client = new DuffelClient(http, token);
    final Act act = ActDb.getAct(actId);
    final DuffelClient.Order order;

    try
    {
      order = client.createOrder(
          text(flight.row, "OfferId"),
          text(flight.row, "PassengerId"),
          TravelPassenger.fromUsrName(act == null ? null : act.usrName),
          text(flight.row, "Price"),
          text(flight.row, "Currency"));
    }
    catch (final DomatarException e)
    {
      return failure(opr, duffelFailure(e));
    }

    final DomId tripsId = TravelIds.subHost(actId, TravelIds.TRIPS);
    final DomId tripId = TravelIds.subHost(actId, IdGen.getId("trip"));
    boolean inserted = false;

    try
    {
      ObjDb.addObjIfMissing(tripsId, TravelIds.HOME, "trips", "Trips", "Booked trips");

      final int nightly = number(hotel, "NightlyPrice");
      final int stayNights = number(hotel, "Nights");
      final ObjAttrs attrs = new ObjAttrs();

      attrs.addAttr("Title", title);
      attrs.addAttr("Origin", text(flight.row, "Origin"));
      attrs.addAttr("Destination", text(flight.row, "Destination"));
      attrs.addAttr("Depart", departOf(flight.row));
      attrs.addAttr("Return", text(flight.row, "Return"));
      attrs.addAttr("FlightName", text(flight.row, "Name"));
      attrs.addAttr("Airline", text(flight.row, "Airline"));
      attrs.addAttr("FlightPrice", text(flight.row, "Price"));
      attrs.addAttr("FlightCurrency", text(flight.row, "Currency"));
      attrs.addAttr("DuffelOrderId", order.orderId);
      attrs.addAttr("BookingReference", order.bookingReference);
      attrs.addAttr("HotelName", text(hotel, "Name"));
      attrs.addAttr("HotelStreet", text(hotel, "Street"));
      attrs.addAttr("CheckIn", text(hotel, "CheckIn"));
      attrs.addAttr("Nights", String.valueOf(stayNights));
      attrs.addAttr("NightlyPrice", String.valueOf(nightly));
      attrs.addAttr("HotelTotal", String.valueOf(TravelPrice.stayTotal(nightly, stayNights)));
      attrs.addAttr("HotelCurrency", TravelPrice.CURRENCY);
      attrs.addAttr("Day1", day1);
      attrs.addAttr("Day2", day2);
      attrs.addAttr("Day3", day3);
      attrs.addAttr("BookedAt", Instant.now().toString());
      ObjDb.addObj(new Obj(tripId, TravelIds.HOME, "trip", title,
          text(flight.row, "Destination"), attrs));
      inserted = true;
      LnkDb.addLnk(new Lnk(tripsId, tripId,
          TravelIds.HOME, "trip",
          title, text(flight.row, "Destination"),
          TravelIds.HOME, "trip",
          null, System.currentTimeMillis()));
      return tripOk(opr, attrs);
    }
    catch (final DomatarException e)
    {
      if (inserted)
        deleteTrip(tripsId, tripId);
      try
      {
        client.cancelOrder(order.orderId);
      }
      catch (final DomatarException ignored)
      {
      }

      final String message = e.getMessage();

      return failure(opr, message != null && !message.isBlank() ? message : "Could not save the trip");
    }
  }

  private String getTrips(final DomatarMsgClient msgClient) throws DomatarException
  {
    final JsonList rows = new JsonArrayList();

    for (final Obj trip : trips(Auth.actId(msgClient)))
    {
      final JsonMap row = new JsonHashMap();

      row.put("Title", attr(trip, "Title"));
      row.put("Destination", attr(trip, "Destination"));
      row.put("Depart", attr(trip, "Depart"));
      row.put("Return", attr(trip, "Return"));
      row.put("HotelName", attr(trip, "HotelName"));
      rows.add(row);
    }

    final JsonMsg out = new JsonMsg();
    final ObjAttrs attrs = new ObjAttrs();

    attrs.addAttr("Trips", rows);
    out.addResponseBody("GetTrips", attrs);
    return out.toString();
  }

  private String getTrip(final JsonMsg inMsg, final DomatarMsgClient msgClient)
      throws DomatarException
  {
    final String title = inMsg.getAttr("Title");

    if (title == null || title.isBlank())
      return failure("GetTrip", "Missing Title");

    final Obj trip = tripByTitle(Auth.actId(msgClient), title.trim());

    if (trip == null)
      return failure("GetTrip", "Unknown trip: " + title.trim());
    return tripOk("GetTrip", copyTrip(trip));
  }

  private static String missingParm(final JsonMsg inMsg, final String... keys)
      throws DomatarException
  {
    for (final String key : keys)
    {
      final String value = inMsg.getAttr(key);

      if (value == null || value.isBlank())
        return key;
    }
    return null;
  }

  private static boolean unknownDay(final String actId, final String day)
      throws DomatarException
  {
    boolean any = false;

    for (final String part : day.split(","))
    {
      final String name = part.trim();

      if (name.isEmpty())
        continue;
      any = true;
      if (TravelCache.findSight(actId, name) == null)
        return true;
    }
    return !any;
  }

  private static String departOf(final JsonMap flight) throws DomatarException
  {
    final String depart = text(flight, "Depart");

    if (!depart.isEmpty())
      return depart;

    final String at = text(flight, "DepartAt");

    return at.length() >= 10 ? at.substring(0, 10) : at;
  }

  private static String text(final JsonMap row, final String key) throws DomatarException
  {
    final String value = row.getString(key);

    return value == null ? "" : value;
  }

  private static String duffelFailure(final DomatarException e)
  {
    final String message = e.getMessage();

    if (message == null || message.isBlank())
      return TravelErrors.duffelRejected("HTTP");
    if (message.equals(TravelErrors.notConfigured("TRAVEL_DUFFEL_TOKEN"))
        || message.equals(TravelErrors.liveDuffelToken()))
      return message;
    return TravelErrors.duffelRejected(message);
  }

  private static List<Obj> trips(final String actId) throws DomatarException
  {
    final List<Lnk> lnks = LnkDb.getLnks(
        TravelIds.subHost(actId, TravelIds.TRIPS),
        TravelIds.HOME, "trip", null, null, 1000, false);
    final List<Obj> rows = new ArrayList<Obj>();

    for (final Lnk lnk : lnks)
    {
      final Obj row = ObjDb.getObj(lnk.lnkDomId);

      if (row != null)
        rows.add(row);
    }
    rows.sort((a, b) -> bookedAt(b).compareTo(bookedAt(a)));
    return rows;
  }

  private static Obj tripByTitle(final String actId, final String title)
      throws DomatarException
  {
    for (final Obj trip : trips(actId))
    {
      if (title.equalsIgnoreCase(attr(trip, "Title")))
        return trip;
    }
    return null;
  }

  private static ObjAttrs copyTrip(final Obj trip) throws DomatarException
  {
    final ObjAttrs attrs = new ObjAttrs();

    for (final String key : TRIP_FIELDS)
      attrs.addAttr(key, attr(trip, key));
    return attrs;
  }

  private static String attr(final Obj trip, final String key) throws DomatarException
  {
    if (trip == null || trip.attrs == null)
      return "";

    final String value = trip.attrs.getAttr(key);

    return value == null ? "" : value;
  }

  private static String bookedAt(final Obj trip)
  {
    try
    {
      return attr(trip, "BookedAt");
    }
    catch (final DomatarException e)
    {
      return "";
    }
  }

  private static String tripOk(final String opr, final ObjAttrs attrs)
      throws DomatarException
  {
    final JsonMsg out = new JsonMsg();

    out.addResponseBody(opr, attrs);
    return out.toString();
  }

  private static void deleteTrip(final DomId tripsId, final DomId tripId)
  {
    try
    {
      LnkDb.deleteLnks(tripsId, tripId, TravelIds.HOME, "trip", null, null);
    }
    catch (final DomatarException ignored)
    {
    }
    try
    {
      ObjDb.deleteObj(tripId);
    }
    catch (final DomatarException ignored)
    {
    }
  }
}
