/*
 * Copyright (c) 2024 Domatar
 */

package com.travel.objimpl;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Flight city names the demo uses. A three-letter code is IATA
 * and is not looked up.
 */
public final class TravelAirports
{
  private static final Map<String, String> CITIES = new HashMap<String, String>();

  static
  {
    CITIES.put("paris", "CDG");
    CITIES.put("london", "LHR");
    CITIES.put("new york", "JFK");
  }

  private TravelAirports()
  {
  }

  public static String iata(final String cityOrCode)
  {
    if (cityOrCode == null)
      return null;

    final String trimmed = cityOrCode.trim();

    if (trimmed.length() == 3 && isLatin(trimmed))
      return trimmed.toUpperCase(Locale.ROOT);

    return CITIES.get(trimmed.toLowerCase(Locale.ROOT));
  }

  private static boolean isLatin(final String value)
  {
    for (int i = 0; i < value.length(); i++)
    {
      final char c = value.charAt(i);

      if ((c < 'A' || c > 'Z') && (c < 'a' || c > 'z'))
        return false;
    }
    return true;
  }
}
