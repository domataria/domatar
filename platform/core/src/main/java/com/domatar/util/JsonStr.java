package com.domatar.util;

import java.math.BigDecimal;

class JsonStr
{
  final String str;
  int ind = 0;

  JsonStr (String jsonStr)
  {
    str = jsonStr;
  }

  Object parseJson() throws DomatarException
  {
    skipWhitespace();

    switch (str.charAt(ind))
    {
      case '\"':
         return parseString();

      case '0':
      case '1':
      case '2':
      case '3':
      case '4':
      case '5':
      case '6':
      case '7':
      case '8':
      case '9':
      case '-':
        return parseNumber();

      case 't':
      case 'f':
        return parseBoolean();

      case 'n':
         return parseNull();

      case '{':
         return parseMap();

      case '[':
         return parseList();

      default:
        throw new DomatarException("Json parsing error. Unknown value at: " + substr());
    }
  }

  private String substr()
  {
    String substr = str.substring(ind);

    return substr;
  }

  private void skipWhitespace()
  {
    for (; whitespace(str.charAt(ind)); ind++)
      ;
  }

  private static boolean whitespace(char c)
  {
    if (c == ' ')
      return true;

    if (c == '\n')
      return true;

    if (c == '\r')
      return true;

    if (c == '\t')
      return true;

    return false;
  }

  private String parseString () throws DomatarException
  {
    StringBuffer buf = new StringBuffer();

    for (ind++; ind < str.length(); ind++)
    {
      switch (str.charAt(ind))
      {
        case '\\':
          if (str.charAt(ind + 1) == 'u')
          {
            String hex = str.substring(ind + 2, ind + 6);

            buf.append((char)Integer.parseInt(hex, 16));

            ind += 6;
          }
          else
          {
            switch (str.charAt(ind + 1))
            {
              case '\"':
                buf.append('\"');
              break;

              case '\\':
                buf.append('\\');
              break;

              case '/':
                buf.append('/');
              break;

              case 'b':
                buf.append('\b');
              break;

              case 'f':
                buf.append('\f');
              break;

              case 'n':
                buf.append('\n');
              break;

              case 'r':
                buf.append('\r');
              break;

              case 't':
                buf.append('\t');
              break;

              default:
                buf.append('?');
              break;
            }

            ind++;
          }
        break;

        default:
          buf.append(str.charAt(ind));
        break;

        case '"':
          ind++;
          return buf.toString();
      }
    }

    throw new DomatarException("Json parsing error. Unexpected end of string at: " + substr());
  }

  private Number parseNumber () throws DomatarException
  {
    String numStr;

    try
    {
      int i = ind;

      for (; isNumChar(str.charAt(i)); i++);

      numStr = str.substring(ind, i);

      BigDecimal num = new BigDecimal(numStr);

      ind = i;

      return num;
    }
    catch (Exception e)
    {
      throw new DomatarException("Json parsing error.\n" + e + "\nat: " + substr());
    }
  }

  private static boolean isNumChar(char c)
  {
    switch (c)
    {
      case '0':
      case '1':
      case '2':
      case '3':
      case '4':
      case '5':
      case '6':
      case '7':
      case '8':
      case '9':
      case '-':
      case '+':
      case 'e':
      case 'E':
      case '.':
        return true;

      default:
        return false;
    }
  }

  private Boolean parseBoolean() throws DomatarException
  {
    if (str.regionMatches(ind, "true", 0, 4))
    {
      ind += 4;
      return Boolean.valueOf(true); // new Boolean(true);
    }
    else if (str.regionMatches(ind, "false", 0, 5))
    {
      ind += 5;
      return Boolean.valueOf(false); // new Boolean(false);
    }

    throw new DomatarException("Json parsing error. Unknown value at: " + substr());
  }

  private Object parseNull() throws DomatarException
  {
    if (str.regionMatches(ind, "null", 0, 4))
    {
      ind += 4;
      return null;
    }

    throw new DomatarException("Json parsing error. Unknown value at: " + substr());
  }

  private JsonMap parseMap() throws DomatarException
  {
    JsonMap map = new JsonHashMap();

    return parseMap(map);
  }

  public JsonMap parseMap(JsonMap map) throws DomatarException
  {
    for (ind++; ind < str.length();)
    {
      parseMapEntry(map);

      if (str.charAt(ind) == ',')
        ind++;
      else if (str.charAt(ind) == '}')
        break;
      else
        throw new DomatarException("Json parsing error. Missing ',' or '}' at: " + substr());
    }

    ind++;

    return map;
  }

  private void parseMapEntry(JsonMap map) throws DomatarException
  {
    skipWhitespace();

    if (str.charAt(ind) == '}')
      return;

    String key = parseString();

    skipWhitespace();

    if (str.charAt(ind) == ':')
      ind++;
    else
      throw new DomatarException("Json parsing error. Missing ':' at: " + substr());

    skipWhitespace();

    if (str.charAt(ind) == '}')
      return;

    Object value = parseJson();

    map.put(key, value);

    skipWhitespace();
  }

  private JsonList parseList() throws DomatarException
  {
    JsonList list = new JsonArrayList();

    return parseList(list);
  }

  public JsonList parseList(JsonList list) throws DomatarException
  {
    for (ind++; ind < str.length();)
    {
      skipWhitespace();

      if (str.charAt(ind) == ']')
        break;

      Object value = parseJson();

      list.add(value);

      skipWhitespace();

      if (str.charAt(ind) == ',')
        ind++;
      else if (str.charAt(ind) == ']')
        break;
      else
        throw new DomatarException("Json parsing error. Missing ',' at: " + substr());
    }

    ind++;

    return list;
  }
}
