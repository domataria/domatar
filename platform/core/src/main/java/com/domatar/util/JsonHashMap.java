/*
 * Copyright (c) 2024 Domatar
 */

package com.domatar.util;

import java.util.LinkedHashMap;

/**
 * JSON object. Insertion order is kept ({@link java.util.LinkedHashMap})
 * so a round trip through {@link Json} does not reshuffle keys.
 * Signature bytes are a different writer:
 * {@link com.domatar.crypto.CanonicalJson} sorts keys, so this order is
 * not a signature input.
 */
public class JsonHashMap extends LinkedHashMap<String, Object> implements JsonMap
{
  private static final long serialVersionUID = -1299723788678993353L;

  public JsonHashMap()
  {
    super();
  }

  public JsonHashMap(int size)
  {
    super(size);
  }

  public JsonHashMap(String json) throws DomatarException
  {
    super();

    JsonStr jsonStr = new JsonStr(json);

    jsonStr.parseMap(this);
  }

  @Override
  public String toString()
  {
    try
    {
      return Json.toJson(this);
    }
    catch (DomatarException e)
    {
      e.printStackTrace();
      return "Error: Malformed JSON";
    }
  }
}
