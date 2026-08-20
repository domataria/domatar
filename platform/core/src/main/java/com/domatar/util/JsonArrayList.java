package com.domatar.util;

import java.util.ArrayList;

public class JsonArrayList extends ArrayList<Object> implements JsonList
{
  private static final long serialVersionUID = -4436758763738028390L;

  public JsonArrayList()
  {
    super();
  }

  public JsonArrayList(int size)
  {
    super(size);
  }

  public JsonArrayList(String json) throws DomatarException
  {
    super();

    JsonStr jsonStr = new JsonStr(json);

    jsonStr.parseList(this);
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
