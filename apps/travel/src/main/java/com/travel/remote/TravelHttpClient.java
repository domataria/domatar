/*
 * Copyright (c) 2024 Domatar
 */

package com.travel.remote;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

import com.domatar.util.DomatarException;
import com.domatar.util.Json;
import com.domatar.util.JsonList;
import com.domatar.util.JsonMap;

/**
 * java.net.http implementation of TravelHttp. The request URL is
 * not included in errors because Geoapify puts the key in the query.
 */
public final class TravelHttpClient implements TravelHttp
{
  private static final int TIMEOUT_SECONDS = 30;

  private final HttpClient http;

  public TravelHttpClient()
  {
    this.http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(TIMEOUT_SECONDS))
        .build();
  }

  @Override
  public String get(final String url) throws DomatarException
  {
    return get(url, null);
  }

  @Override
  public String get(final String url, final Map<String, String> headers)
      throws DomatarException
  {
    final HttpRequest.Builder builder = HttpRequest.newBuilder()
        .uri(URI.create(url))
        .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
        .GET();

    if (headers != null)
    {
      for (final Map.Entry<String, String> entry : headers.entrySet())
        builder.header(entry.getKey(), entry.getValue());
    }

    return send(builder.build());
  }

  @Override
  public String post(final String url, final String body,
                     final Map<String, String> headers) throws DomatarException
  {
    final HttpRequest.Builder builder = HttpRequest.newBuilder()
        .uri(URI.create(url))
        .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
        .POST(HttpRequest.BodyPublishers.ofString(body == null ? "" : body));

    if (headers != null)
    {
      for (final Map.Entry<String, String> entry : headers.entrySet())
        builder.header(entry.getKey(), entry.getValue());
    }

    return send(builder.build());
  }

  private String send(final HttpRequest request) throws DomatarException
  {
    final HttpResponse<String> response;

    try
    {
      response = http.send(request, HttpResponse.BodyHandlers.ofString());
    }
    catch (final InterruptedException e)
    {
      Thread.currentThread().interrupt();
      throw new DomatarException("HTTP request interrupted", e);
    }
    catch (final Exception e)
    {
      throw new DomatarException("HTTP request failed", e);
    }

    if (response.statusCode() < 200 || response.statusCode() >= 300)
      throw new DomatarException("HTTP " + response.statusCode() + reason(response.body()));

    return response.body();
  }

  private static String reason(final String body)
  {
    if (body == null || body.isBlank())
      return "";
    if (body.contains("duffel_") || body.contains("apiKey=") || body.contains("Bearer "))
      return "";

    try
    {
      final JsonMap root = Json.parseMap(body);
      final JsonList errors = root.getList("errors");

      if (errors == null || errors.isEmpty() || !(errors.get(0) instanceof JsonMap))
        return "";

      final String message = ((JsonMap) errors.get(0)).getString("message");

      if (message == null || message.isBlank() || message.length() > 180)
        return "";
      return ": " + message.trim();
    }
    catch (final Exception e)
    {
      return "";
    }
  }
}
