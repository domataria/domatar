/*
 * Copyright (c) 2024 Domatar
 */

package com.domatar.util;

/**
 * In-tree JSON. Object values are {@link JsonHashMap} (insertion order).
 * Signatures do not use this writer; they use
 * {@link com.domatar.crypto.CanonicalJson}, which sorts keys.
 */
public class Json
{
  public static Object parse(final String jsonStr) throws DomatarException
  {
    final JsonStr json = new JsonStr(jsonStr);

    return json.parseJson();
  }

  public static JsonMap parseMap(final String jsonStr) throws DomatarException
  {
    final JsonStr json = new JsonStr(jsonStr);

    return (JsonMap)json.parseJson();
  }

  public static JsonList parseList(final String jsonStr) throws DomatarException
  {
    final JsonStr json = new JsonStr(jsonStr);

    return (JsonList)json.parseJson();
  }

  public static String toJson(final Object obj) throws DomatarException
  {
    final JsonBuf buf = new JsonBuf(obj);

    return buf.toString();
  }
}
