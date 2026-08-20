package com.domatar.util;

/**
 * Base64Encoder is a class that provides methods to encode and decode strings to and from Base64.
 * 
 * The encoding is designed to provide an alphabetic ordering that is the same as its numeric 
 * ordering, and be url-safe.
 * 
 */


public class Base64Encoder
{
  private static final char codeChar[] =
    {'0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'A', 'B', 'C', 'D', 'E', 'F',
     'G', 'H', 'I', 'J', 'K', 'L', 'M', 'N', 'O', 'P', 'Q', 'R', 'S', 'T', 'U', 'V',
     'W', 'X', 'Y', 'Z', '_', 'a', 'b', 'c', 'd', 'e', 'f', 'g', 'h', 'i', 'j', 'k', 
     'l', 'm', 'n', 'o', 'p', 'q', 'r', 's', 't', 'u', 'v', 'w', 'x', 'y', 'z', '~'};

  private static final int codeInt[] =
    { 0,  0,  0,  0,  0,  0,  0,  0,  0,  0,  0,  0,  0,  0,  0,  0,
      0,  0,  0,  0,  0,  0,  0,  0,  0,  0,  0,  0,  0,  0,  0,  0,
      0,  0,  0,  0,  0,  0,  0,  0,  0,  0,  0,  0,  0,  0,  0,  0,
      0,  1,  2,  3,  4,  5,  6,  7,  8,  9,  0,  0,  0,  0,  0,  0,
      0, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24,
     25, 26, 27, 28, 29, 30, 31, 32, 33, 34, 35,  0,  0,  0,  0, 36,
      0, 37, 38, 39, 40, 41, 42, 43, 44, 45, 46, 47, 48, 49, 50, 51,
     52, 53, 54, 55, 56, 57, 58, 59, 60, 61, 62,  0,  0,  0, 63,  0};

  // Encodes a byte array so that it can be used in a url
  public static String encode(byte [] bytes)
  {
    byte b1, b2, b3;
    int  i1, i2, i3, i4;
    int  length = bytes.length;
    int rem = length % 3 == 0 ? 0 : length % 3 + 1;
    StringBuffer buf = new StringBuffer((length / 3) * 4 + rem);

    // Convert every 3 bytes into 4 characters
    for (int i = 0; i < length; i += 3)
    {
      b1 = bytes[i];

      if (i + 1 < length)
        b2 = bytes[i + 1];
      else
        b2 = 0;

      if (i + 2 < length)
        b3 = bytes[i + 2];
      else
        b3 = 0;

      i1 = (b1 >>> 2) & 0x3f; // Top 6 bits of b1
      i2 = ((b1 << 4) & 0x30) | ((b2 >>> 4) & 0x0f); // Bottom 2 of b1, top 4 of b2
      i3 = ((b2 << 2) & 0x3c) | ((b3 >>> 6) & 0x03); // Bottom 4 of b2, top 2 of b3
      i4 = b3 & 0x3f; // Bottom 6 of b3

      buf.append(codeChar[i1]);
      buf.append(codeChar[i2]);

      if (i + 1 < length) // i3 depends on b2 and b3
        buf.append(codeChar[i3]);

      if (i + 2 < length) // i4 only depends on b3
        buf.append(codeChar[i4]);
    }

    return buf.toString();
  }

  // Convert every six bits into a character
  // Most significant character at index 0, least significant at length - 1
  public static String encode(long val)
  {
    return encode(val, 0);
  }
  
  public static String encode(long val, int minLength)
  {
    StringBuffer buf = new StringBuffer(11);

    for (int i = 0; i < 11; i++)
    {
      buf.insert(0, codeChar[(int)(val & 0x0000003F)]);
      val >>>= 6;

      if (val == 0)
        break;
    }
    
    if (buf.length() < minLength)
    {
      int insertNum = minLength - buf.length();
      
      for (int i = 0; i < insertNum; i++)
        buf.insert(0, '0');
    }

    return buf.toString();
  }

  // Decodes a url64 encoded string into its byte array
  public static byte[] decode(String str)
  {
    int  i1, i2, i3, i4;
    int  length = str.length();

    int rem = length % 4 == 0 ? 0 : length % 4 - 1;
    int bytesLength = (length / 4) * 3 + rem;
    byte bytes[] = new byte[bytesLength];

    // Convert every 4 characters into 3 bytes
    for (int i = 0, b = 0; i < length; i += 4, b += 3)
    {
      i1 = codeInt[(int)str.charAt(i)];
      i2 = codeInt[(int)str.charAt(i + 1)];

      if (i + 2 < length)
        i3 = codeInt[(int)str.charAt(i + 2)];
      else
        i3 = 0;

      if (i + 3 < length)
        i4 = codeInt[(int)str.charAt(i + 3)];
      else
        i4 = 0;

      bytes[b] = (byte)(((i1 << 2) & 0xfc) | ((i2 >>> 4) & 0x03)); // i1, top 2 bits of i2

      if (i + 2 < length)
        bytes[b + 1] = (byte)(((i2 << 4) & 0xf0) | ((i3 >>> 2) & 0x0f)); // bottom 4 of i2,
                                                                         // top 4 of i3
      if (i + 3 < length)
        bytes[b + 2] = (byte)(((i3 << 6) & 0xc0) | (i4 & 0x3f)); // bottom 2 of i3, i4
    }

    return bytes;
  }

  public static long decodeToLong(String str)
  {
    int length = str.length();

    if (length > 11)
      return -1;

    long val = 0;

    // Convert every char into 6 bits
    // Least significant char at length - 1, most significant at 0
    for (int i = length - 1, shift = 0; i >= 0; i--, shift += 6)
      val += ((long)codeInt[str.charAt(i)]) << shift;

    return val;
  }
}
