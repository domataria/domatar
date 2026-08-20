package com.domatar.util;

import java.util.List;
import java.util.Map;

class JsonBuf
{
  StringBuffer buf = new StringBuffer();
  int level = 0;

  JsonBuf(final Object obj) throws DomatarException
  {
    appendObj(obj);
  }

  public String toString()
  {
    return buf.toString();
  }

  private void appendObj(final Object obj) throws DomatarException
  {
    if (obj == null)
      buf.append("null");
    else if (obj instanceof Boolean)
    {
      if ((Boolean) obj)
        buf.append("true");
      else
        buf.append("false");
    }
    else if (obj instanceof String)
      appendString((String) obj);
    else if (obj instanceof Number)
      appendNumber((Number) obj);
    else if (obj instanceof Map)
      appendMap((JsonMap) obj);
    else if (obj instanceof List)
      appendList((JsonList) obj);
    else
      throw new DomatarException("Json unknown type: " + obj.toString());
  }

  private void appendIndent()
  {
    if (buf.length() > 0)
      buf.append("\n");

    for (int i = 0; i < level; i++)
      buf.append("  ");
  }

  private void appendString(final String str)
  {
    buf.append('\"');

    for (int i = 0; i < str.length(); i++)
    {
      final char c = str.charAt(i);

      switch (c)
      {
        case '\\':
          buf.append("\\\\");
        break;

        case '\"':
          buf.append("\\\"");
        break;

        case '\b':
          buf.append("\\b");
        break;

        case '\f':
          buf.append("\\f");
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

        default:
          if ((int) c <= 0x001F)
          {
            buf.append("\\u");

            final String hex = String.format("%04x", (int) c);

            buf.append(hex);
          }
          else
            buf.append(c);
        break;
      }
    }

    buf.append('\"');
  }

  private void appendNumber(final Number num)
  {
    buf.append(num.toString());
  }

  private void appendMap(final JsonMap map) throws DomatarException
  {
    appendIndent();

    buf.append("{");

    level++;

    boolean firstTime = true;

    for (Map.Entry<String, Object> entry : map.entrySet())
    {
      if (firstTime)
        firstTime = false;
      else
        buf.append(',');

      appendIndent();
      appendString(entry.getKey());
      buf.append(" : ");
      appendObj(entry.getValue());
    }

    level--;

    appendIndent();

    buf.append("}");
  }

  private void appendList(final JsonList list) throws DomatarException
  {
    appendIndent();

    buf.append("[");

    level++;

    boolean firstTime = true;

    for (Object obj : list)
    {
      if (firstTime)
        firstTime = false;
      else
        buf.append(',');

      if (!(obj instanceof Map) && !(obj instanceof List))
        appendIndent();

      appendObj(obj);
    }

    level--;

    appendIndent();

    buf.append("]");
  }
}
