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

  /**
   * True when two display names are the same after hyphen characters
   * are folded to '-'. Models often emit U+2011 in a copied flight name.
   */
  public static boolean same(final String left, final String right)
  {
    if (left == null || right == null)
      return false;
    return foldHyphens(left).equals(foldHyphens(right));
  }

  private static String foldHyphens(final String value)
  {
    final StringBuilder folded = new StringBuilder(value.length());

    for (int i = 0; i < value.length(); i++)
    {
      final char c = value.charAt(i);

      if (c == '\u2010' || c == '\u2011' || c == '\u2012' || c == '\u2013'
          || c == '\u2014' || c == '\u2015' || c == '\u2212'
          || c == '\uFE63' || c == '\uFF0D')
        folded.append('-');
      else
        folded.append(c);
    }
    return folded.toString();
  }

  private static String text(final String value)
  {
    return value == null ? "" : value;
  }
}
