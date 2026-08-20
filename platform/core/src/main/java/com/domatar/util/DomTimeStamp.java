package com.domatar.util;

public class DomTimeStamp
{
  public static String get()
  {
    final long time = System.currentTimeMillis();

    return Base64Encoder.encode(time, 8);
  }
}
