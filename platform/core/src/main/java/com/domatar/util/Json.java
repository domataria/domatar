package com.domatar.util;

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
