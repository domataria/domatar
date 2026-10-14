/*
 * Copyright (c) 2024 Domatar
 */

package com.domatar.util;

/**
 * Current time as 8 characters of {@link Base64Encoder}, the same clock
 * encoding as {@link IdGen}. Not a secret and not unique within one
 * millisecond.
 */
public class DomTimeStamp
{
  public static String get()
  {
    final long time = System.currentTimeMillis();

    return Base64Encoder.encode(time, 8);
  }
}
