/*
 * Copyright (c) 2024 Domatar
 */

package com.travel.objimpl;

import java.nio.charset.StandardCharsets;

import com.domatar.crypto.KeyOps;

/**
 * Displayed hotel price. Stable for a Geoapify place id.
 * Not a live rate and not charged.
 */
public final class TravelPrice
{
  public static final String CURRENCY = "EUR";
  public static final int    LOW      = 95;
  public static final int    SPAN     = 386;

  private TravelPrice()
  {
  }

  public static int nightlyPrice(final String placeId)
  {
    final String id = placeId == null ? "" : placeId;
    final byte[] dig = KeyOps.sha256(id.getBytes(StandardCharsets.UTF_8));
    final int u = ((dig[0] & 0xff) << 8) | (dig[1] & 0xff);

    return LOW + (u % SPAN);
  }

  public static int stayTotal(final int nightly, final int nights)
  {
    return nightly * nights;
  }
}
