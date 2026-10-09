/*
 * Copyright (c) 2024 Domatar
 */

package com.travel.objimpl;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

public class TravelPassengerTest
{
  @Test
  public void twoWords()
  {
    final TravelPassenger passenger = TravelPassenger.fromUsrName("David Boxenhorn");

    assertEquals("David", passenger.given);
    assertEquals("Boxenhorn", passenger.family);
    assertEquals("mr", passenger.title);
    assertEquals("m", passenger.gender);
    assertEquals("1990-01-01", passenger.bornOn);
    assertEquals("+442080160508", passenger.phone);
    assertEquals("travel-demo@example.com", passenger.email);
  }

  @Test
  public void oneWordFallback()
  {
    final TravelPassenger passenger = TravelPassenger.fromUsrName("davidb");

    assertEquals("Demo", passenger.given);
    assertEquals("Traveler", passenger.family);
    assertEquals("mr", passenger.title);
  }
}
