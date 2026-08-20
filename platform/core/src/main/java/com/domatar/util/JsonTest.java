package com.domatar.util;

import java.util.List;
import java.util.Map;

public class JsonTest
{
  public static void main(final String[] args) throws DomatarException
  {
    final String str =
        "{\r\n"
        + "  \"Sentiment\": \"1\",\r\n"
        + "  \"Text\": \"Res\\\\p\\\"on\'se 1 to Quip 3\\n\\n15th try\",\r\n"
        + " \"Map\" : {\r\n"
        + "  \"Sentiment2\": \"1\",\r\n"
        + "  \"Text2\": \"Response2\\n\\n15th try\"\r\n,"
        + "\"Map2\": {\r\n"
        + "  \"Sentiment\": \"1\",\r\n"
        + "  \"Text\": \"Res\\\\p\\\"on\'se 1 to Quip 3\\n\\n15th try\",\r\n"
        + " \"Map\" : {\r\n"
        + "  \"Sentiment2\": \"1\",\r\n"
        + "  \"Text2\": \"Response2\\n\\n15th try\"\r\n"
        + "},"
        + "\"List\" : [\r\n"
        + "  \"1\",\r\n"
        + "  \"Response 1 to Quip 3\\n\\n15th try\"\r\n"
        + "],"
        + " \"NULL\" : null,"
        + " \"TRUE\" : true,"
        + " \"FALSE\" : false,"
        + " \"Number0\" : 5.4,"
        + " \"Number1\" : -5,"
        + " \"Number2\" : 3.14e-2,"
        + " \"Number3\" : 3.14e200,"
        + " \"Number4\" : 3.14e+2,"
        + " \"Number5\" : 0"
        + "}"
        + "},"
        + "\"List\" : [\r\n"
        + "  \"1\",\r\n"
        + "  \"Response 1 to Quip 3\\n\\n15th try\"\r\n,"
        + " {\r\n"
        + "  \"Sentiment2\": \"1\",\r\n"
        + "  \"Text2\": \"Response2\\n\\n15th try\"\r\n"
        + "},"
        + "[\r\n"
        + "  \"1\",\r\n"
        + "  \"Response 1 to Quip 3\\n\\n15th try\"\r\n,"
        + "[\r\n"
        + "  \"1\",\r\n"
        + "  \"Response 1 to Quip 3\\n\\n15th try\"\r\n"
        + "]"
        + "]"
        + "],"
        + " \"NULL\" : null,"
        + " \"TRUE\" : true,"
        + " \"FALSE\" : false,"
        + " \"Number0\" : 5.4,"
        + " \"Number1\" : -5,"
        + " \"Number2\" : 3.14e-2,"
        + " \"Number3\" : 3.14e200,"
        + " \"Number4\" : 3.14e+2,"
        + " \"Number5\" : 0"
        + "}";

    final Map<String, Object> map = Json.parseMap(str);

    final String json = Json.toJson(map);

    System.out.println(json);

    final String listStr =
        "[\r\n"
        + "  \"1\",\r\n"
        + "  \"Response 1 to Quip 3\\n\\n15th try\"\r\n,"
        + "[\r\n"
        + "  \"2\",\r\n"
        + "  \"Response 2 to Quip 3\\n\\n16th try\"\r\n"
        + "],"
        + "  \"3\",\r\n"
        + "  \"Response 3 to Quip 3\\n\\n17th try\"\r\n,"
        + "  \"4\",\r\n"
        + "  \"Response 4 to Quip 3\\n\\n18th try\"\r\n"
        + "]";

    final List<Object> listMap = Json.parseList(listStr);

    final String listJson = Json.toJson(listMap);

    System.out.println(listJson);
  }
}
