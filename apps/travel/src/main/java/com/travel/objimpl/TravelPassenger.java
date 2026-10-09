/*
 * Copyright (c) 2024 Domatar
 */

package com.travel.objimpl;

/**
 * Workshop passenger for a Duffel test order. Trip rows do not
 * store these fields.
 */
public final class TravelPassenger
{
  public final String given;
  public final String family;
  public final String title;
  public final String gender;
  public final String bornOn;
  public final String phone;
  public final String email;

  private TravelPassenger(final String given, final String family)
  {
    this.given = given;
    this.family = family;
    this.title = "mr";
    this.gender = "m";
    this.bornOn = "1990-01-01";
    this.phone = "+442080160508";
    this.email = "travel-demo@example.com";
  }

  public static TravelPassenger fromUsrName(final String usrName)
  {
    final String[] words = usrName == null ? new String[0] : usrName.trim().split("\\s+");

    if (words.length == 2 && !words[0].isEmpty() && !words[1].isEmpty())
      return new TravelPassenger(words[0], words[1]);
    return new TravelPassenger("Demo", "Traveler");
  }
}
