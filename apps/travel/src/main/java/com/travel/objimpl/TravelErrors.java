/*
 * Copyright (c) 2024 Domatar
 */

package com.travel.objimpl;

/**
 * Facade error sentences. "Not implemented" is not one of these.
 */
public final class TravelErrors
{
  private TravelErrors()
  {
  }

  public static String notConfigured(final String envName)
  {
    return "Travel not configured: " + envName + " missing";
  }

  public static String liveDuffelToken()
  {
    return "Refusing live Duffel token";
  }

  public static String unknownCity(final String name)
  {
    return "Unknown city: " + name;
  }

  public static String unknownFlight()
  {
    return "Unknown flight; search again";
  }

  public static String offerExpired()
  {
    return "Offer expired; search again";
  }

  public static String unknownHotel()
  {
    return "Unknown hotel; search again";
  }

  public static String unknownSight()
  {
    return "Unknown sight; search again";
  }

  public static String tripExists(final String title)
  {
    return "Trip already exists: " + title;
  }

  public static String duffelRejected(final String reason)
  {
    return "Duffel rejected the order: " + reason;
  }

  public static String badDate()
  {
    return "Bad date";
  }

  public static String badNights()
  {
    return "Bad nights";
  }
}
