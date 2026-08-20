package com.domatar.util;

import java.util.Random;

public class IdGen
{
  private static Random random = new Random();

  public static String createIdFromCurTime(String prefix)
  {
    String timeStr = IdGen.getCurTimeBase64();

    return IdGen.createId(prefix, timeStr);
  }

  public static String createId(String prefix, String suffix)
  {
    return prefix + "-" + suffix;
  }

  public static String createId(String prefix, String suffix, char replaceChar)
  {
    StringBuffer buf = new StringBuffer(suffix.length());

    for (int i = 0; i < suffix.length(); i++)
    {
      char c = suffix.charAt(i);

      if (DomId.isObjIdChar(c))
        buf.append(c);
      else
        buf.append(replaceChar);
    }

    return prefix + "-" + buf.toString();
  }

  public static String getCurTimeBase64()
  {
    long time = System.currentTimeMillis();

    return Base64Encoder.encode(time, 8);
  }

  public static String getRandBase64()
  {
    long rand = random.nextLong();

    String randStr = Base64Encoder.encode(rand, 8);

    if (randStr.length() > 8)
      return randStr.substring(0, 8);

    return randStr;
  }

  public static String getBase64(long time)
  {
    return Base64Encoder.encode(time, 8);
  }

  public static String addTimeBase64(String baseTime,
                                     long plusHours,
                                     long plusMinutes,
                                     long plusSeconds,
                                     long plusMillis)
  {
    long time = Base64Encoder.decodeToLong(baseTime);

    long newTime = time +
                   (plusHours * 60 * 60 * 1000) +
                   (plusMinutes * 60 * 1000) +
                   (plusSeconds * 1000) +
                   plusMillis;

    return Base64Encoder.encode(newTime, 8);
  }

  public static String getId(final String prefix)
  {
    return createId(prefix, getCurTimeBase64());
  }

  public static String getIdFromBase10(final String prefix, final String suffix10)
  {
    final long time = Long.parseLong(suffix10);

    return createId(prefix, Base64Encoder.encode(time, 8));
  }

  public static String getIdPrefix(String id)
  {
    int i = id.indexOf('-');

    return id.substring(0, i);
  }

  public static String getIdSuffix(String id)
  {
    int i = id.indexOf('-');

    return id.substring(i + 1);
  }

  public static long getTimeFromIdLong(String id)
  {
    String time = getIdSuffix(id);

    return Base64Encoder.decodeToLong(time);
  }

  public static String getTimeFromIdBase10(String id)
  {
    long time = getTimeFromIdLong(id);

    return Long.toString(time);
  }
}
