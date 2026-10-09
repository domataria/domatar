/*
 * Copyright (c) 2024 Domatar
 */

package com.travel.remote;

import java.util.LinkedHashMap;
import java.util.Map;

import com.domatar.util.DomatarException;

/**
 * Maps a URL prefix to a canned body. The longest matching prefix wins.
 */
public final class FakeTravelHttp implements TravelHttp
{
  private final Map<String, String> bodies = new LinkedHashMap<String, String>();
  private final Map<String, String> posts = new LinkedHashMap<String, String>();

  public int postCount;
  public int getCount;
  public String lastPostUrl;
  public String lastPostBody;
  public String lastGetUrl;
  public Map<String, String> lastPostHeaders;
  public Map<String, String> lastGetHeaders;

  public void put(final String prefix, final String body)
  {
    bodies.put(prefix, body);
  }

  public void putPost(final String prefix, final String body)
  {
    posts.put(prefix, body);
  }

  @Override
  public String get(final String url, final Map<String, String> headers)
      throws DomatarException
  {
    getCount++;
    lastGetUrl = url;
    lastGetHeaders = headers;
    return get(url);
  }

  @Override
  public String get(final String url) throws DomatarException
  {
    String best = null;
    String body = null;

    for (final Map.Entry<String, String> entry : bodies.entrySet())
    {
      if (url.startsWith(entry.getKey())
          && (best == null || entry.getKey().length() > best.length()))
      {
        best = entry.getKey();
        body = entry.getValue();
      }
    }

    if (body == null)
      throw new DomatarException("no canned body");
    return body;
  }

  @Override
  public String post(final String url, final String body, final Map<String, String> headers)
      throws DomatarException
  {
    postCount++;
    lastPostUrl = url;
    lastPostBody = body;
    lastPostHeaders = headers;

    String best = null;
    String canned = null;

    for (final Map.Entry<String, String> entry : posts.entrySet())
    {
      if (url.startsWith(entry.getKey())
          && (best == null || entry.getKey().length() > best.length()))
      {
        best = entry.getKey();
        canned = entry.getValue();
      }
    }

    if (canned == null)
      throw new DomatarException("post not canned");
    return canned;
  }
}
