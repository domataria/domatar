/*
 * Copyright (c) 2024 Domatar
 */

package com.travel.objimpl;

import java.time.Instant;
import java.time.format.DateTimeParseException;

import com.domatar.db.ObjDb;
import com.domatar.util.DomId;
import com.domatar.util.DomatarException;
import com.domatar.util.Json;
import com.domatar.util.JsonArrayList;
import com.domatar.util.JsonList;
import com.domatar.util.JsonMap;
import com.domatar.util.Obj;
import com.domatar.util.ObjAttrs;

/**
 * One unlinked cache object per user. Flights, Hotels, and Sights
 * are JSON strings. A new search replaces only that kind.
 */
public final class TravelCache
{
  public static final int PAGE_SIZE = 12;

  private TravelCache()
  {
  }

  public static void ensure(final String actId) throws DomatarException
  {
    final DomId id = TravelIds.subHost(actId, TravelIds.CACHE);

    if (ObjDb.getObj(id) != null)
      return;

    final ObjAttrs attrs = new ObjAttrs();

    attrs.addAttr("Flights", "");
    attrs.addAttr("Hotels", "");
    attrs.addAttr("Sights", "");
    ObjDb.addObjIfMissing(id, TravelIds.HOME, TravelIds.CACHE,
        "Cache", "Search cache", attrs);
  }

  public static void replaceHotels(final String actId, final String checkIn,
                                   final int nights, final JsonList hotels)
      throws DomatarException
  {
    for (final Object item : hotels)
    {
      if (!(item instanceof JsonMap))
        continue;

      final JsonMap row = (JsonMap) item;

      row.put("CheckIn", checkIn);
      row.put("Nights", Integer.valueOf(nights));
    }
    write(actId, "Hotels", hotels);
  }

  public static void replaceSights(final String actId, final JsonList sights)
      throws DomatarException
  {
    write(actId, "Sights", sights);
  }

  public static void replaceFlights(final String actId, final JsonList flights)
      throws DomatarException
  {
    write(actId, "Flights", flights);
  }

  /**
   * The cached flight with this Name. row is null when the name is
   * unknown or the offer is expired. expired is true only for the
   * second case, so the caller can choose offerExpired.
   */
  public static FlightHit findFlight(final String actId, final String name)
      throws DomatarException
  {
    final JsonList flights = read(actId, "Flights");

    for (final Object item : flights)
    {
      if (!(item instanceof JsonMap))
        continue;

      final JsonMap row = (JsonMap) item;

      if (!TravelNames.same(name, row.getString("Name")))
        continue;
      if (expired(row.getString("ExpiresAt")))
        return new FlightHit(null, true);
      return new FlightHit(row, false);
    }
    return new FlightHit(null, false);
  }

  public static JsonMap findHotel(final String actId, final String name)
      throws DomatarException
  {
    return findNamed(actId, "Hotels", name);
  }

  public static JsonMap findSight(final String actId, final String name)
      throws DomatarException
  {
    return findNamed(actId, "Sights", name);
  }

  public static Page pageHotels(final String actId, final int page)
      throws DomatarException
  {
    return page(actId, "Hotels", page);
  }

  public static Page pageSights(final String actId, final int page)
      throws DomatarException
  {
    return page(actId, "Sights", page);
  }

  private static void write(final String actId, final String key, final JsonList rows)
      throws DomatarException
  {
    ensure(actId);

    final Obj row = ObjDb.getObj(TravelIds.subHost(actId, TravelIds.CACHE));

    row.attrs.addAttr(key, Json.toJson(rows));
    ObjDb.modifyObj(row);
  }

  private static JsonList read(final String actId, final String key) throws DomatarException
  {
    ensure(actId);

    final Obj row = ObjDb.getObj(TravelIds.subHost(actId, TravelIds.CACHE));
    final String raw = row.attrs.getAttr(key);

    if (raw == null || raw.isBlank())
      return new JsonArrayList();
    return Json.parseList(raw);
  }

  private static JsonMap findNamed(final String actId, final String key, final String name)
      throws DomatarException
  {
    for (final Object item : read(actId, key))
    {
      if (!(item instanceof JsonMap))
        continue;

      final JsonMap row = (JsonMap) item;

      if (TravelNames.same(name, row.getString("Name")))
        return row;
    }
    return null;
  }

  private static boolean expired(final String expiresAt)
  {
    if (expiresAt == null || expiresAt.isBlank())
      return true;

    try
    {
      return !Instant.now().isBefore(Instant.parse(expiresAt));
    }
    catch (final DateTimeParseException e)
    {
      return true;
    }
  }

  private static Page page(final String actId, final String key, final int page)
      throws DomatarException
  {
    final JsonList all = read(actId, key);

    final int shown = page < 1 ? 1 : page;
    final int from = (shown - 1) * PAGE_SIZE;
    final JsonList slice = new JsonArrayList();

    if (from < all.size())
    {
      final int to = Math.min(from + PAGE_SIZE, all.size());

      for (int i = from; i < to; i++)
        slice.add(all.get(i));
    }
    return new Page(all.size(), shown, slice);
  }

  public static final class FlightHit
  {
    public final JsonMap row;
    public final boolean expired;

    public FlightHit(final JsonMap row, final boolean expired)
    {
      this.row = row;
      this.expired = expired;
    }
  }

  public static final class Page
  {
    public final int total;
    public final int page;
    public final JsonList rows;

    public Page(final int total, final int page, final JsonList rows)
    {
      this.total = total;
      this.page = page;
      this.rows = rows;
    }
  }
}
