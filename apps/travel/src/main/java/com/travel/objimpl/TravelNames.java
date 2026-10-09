/*
 * Copyright (c) 2024 Domatar
 */

package com.travel.objimpl;

import java.util.Set;

/**
 * Display names unique inside one user's current search cache.
 */
public final class TravelNames
{
  private TravelNames()
  {
  }

  public static String hotelName(final String placeName, final String street,
                                 final Set<String> already)
  {
    return uniquePlace(placeName, street, already);
  }

  public static String sightName(final String placeName, final String street,
                                 final Set<String> already)
  {
    return uniquePlace(placeName, street, already);
  }

  public static String flightName(final String airline, final String origin,
                                  final String dest, final String departAt,
                                  final Set<String> already)
  {
    final String line = text(airline) + " " + text(origin) + "-" + text(dest)
        + " " + text(departAt);

    if (already.add(line))
      return line;

    int n = 2;
    String named = line + " (" + n + ")";

    while (!already.add(named))
    {
      n++;
      named = line + " (" + n + ")";
    }
    return named;
  }

  private static String uniquePlace(final String placeName, final String street,
                                    final Set<String> already)
  {
    final String base = text(placeName);

    if (already.add(base))
      return base;

    final String withStreet = base + " \u2014 " + text(street);

    if (already.add(withStreet))
      return withStreet;

    int n = 2;
    String named = withStreet + " (" + n + ")";

    while (!already.add(named))
    {
      n++;
      named = withStreet + " (" + n + ")";
    }
    return named;
  }

  private static String text(final String value)
  {
    return value == null ? "" : value;
  }
}
