package com.domatar.util;

public class ObjAttrs
{
  private final JsonMap jsonMap;

  public ObjAttrs(String document) throws DomatarException
  {
    if (document == null || document.trim().isEmpty())
      jsonMap = new JsonHashMap();
    else
      jsonMap = Json.parseMap(document);
  }

  public ObjAttrs() throws DomatarException
  {
    jsonMap = new JsonHashMap();
  }

  public ObjAttrs(final JsonMap jobj) throws DomatarException
  {
    jsonMap = jobj;
  }

  public ObjAttrs(final ObjAttrs objAttrs) throws DomatarException
  {
    jsonMap = objAttrs.toMap();
  }

  public void addAttr(final String key, final Object value) throws DomatarException
  {
    jsonMap.put(key, value);
  }

  public void addAll(JsonMap map) throws DomatarException
  {
    jsonMap.putAll(map);
  }

  public void addAttrs(String key, ObjAttrs attrs) throws DomatarException
  {
    jsonMap.put(key, attrs.toMap());
  }

  public String getAttr(String key) throws DomatarException
  {
    return jsonMap.getString(key);
  }

  public JsonList getAttrList(String key) throws DomatarException
  {
    return jsonMap.getList(key);
  }

  public ObjAttrs getObjAttrs(String key) throws DomatarException
  {
    return new ObjAttrs(jsonMap.getMap(key));
  }

  public JsonMap toMap()
  {
    return jsonMap;
  }

  @Override
  public String toString()
  {
    try
    {
      return Json.toJson(jsonMap);
    }
    catch (Exception e)
    {
      e.printStackTrace();

      return "Error: Malformed JSON";
    }
  }

  public String toSqlString() throws DomatarException
  {
    String jsonStr = toString();

    StringBuffer buf = new StringBuffer(jsonStr.length());

    /*
     * Special characters in SQL
     *
        \0     An ASCII NUL (0x00) character.
        \'     A single quote (“'”) character.
        \"     A double quote (“"”) character.
        \b     A backspace character.
        \n     A newline (linefeed) character.
        \r     A carriage return character.
        \t     A tab character.
        \Z     ASCII 26 (Control-Z). See note following the table.
        \\     A backslash (“\”) character.
     */

    for (int i = 0; i < jsonStr.length(); i++)
    {
      char c = jsonStr.charAt(i);

      switch (c)
      {
        case '\u0000':
          buf.append("\\0");
        break;

        case '\'':
          buf.append("\\\'");
        break;

        case '\"':
          buf.append("\\\"");
        break;

        case '\n':
          buf.append("\\n");
        break;

        case '\r':
          buf.append("\\r");
        break;

        case '\t':
          buf.append("\\t");
        break;

        case '\\':
          buf.append("\\\\");
        break;

        default:
          buf.append(c);
      }
    }

    return buf.toString();
  }
}
