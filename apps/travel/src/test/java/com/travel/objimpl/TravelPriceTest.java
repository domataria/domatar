/*
 * Copyright (c) 2024 Domatar
 */

package com.travel.objimpl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import com.domatar.crypto.KeyOps;

public class TravelPriceTest
{
  @Test
  public void lowBound()
  {
    final String id = placeIdWithRemainder(0);

    assertEquals(95, TravelPrice.nightlyPrice(id));
  }

  @Test
  public void highBound()
  {
    final String id = placeIdWithRemainder(385);

    assertEquals(480, TravelPrice.nightlyPrice(id));
  }

  @Test
  public void stableForSamePlaceId()
  {
    final int first = TravelPrice.nightlyPrice("place-42");
    final int second = TravelPrice.nightlyPrice("place-42");

    assertEquals(first, second);
    assertTrue(first >= 95 && first <= 480);
    assertEquals(first * 3, TravelPrice.stayTotal(first, 3));
  }

  private static String placeIdWithRemainder(final int want)
  {
    for (int i = 0; i < 100000; i++)
    {
      final String id = Integer.toString(i);
      final byte[] dig = KeyOps.sha256(id.getBytes(StandardCharsets.UTF_8));
      final int u = ((dig[0] & 0xff) << 8) | (dig[1] & 0xff);

      if ((u % 386) == want)
        return id;
    }
    throw new AssertionError("no place id for remainder " + want);
  }
}
