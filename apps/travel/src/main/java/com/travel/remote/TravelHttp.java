/*
 * Copyright (c) 2024 Domatar
 */

package com.travel.remote;

import java.util.Map;

import com.domatar.util.DomatarException;

/**
 * Outbound HTTP. Duffel and Geoapify clients take this so tests
 * can pass a fake.
 */
public interface TravelHttp
{
  String get(String url) throws DomatarException;

  String get(String url, Map<String, String> headers) throws DomatarException;

  String post(String url, String body, Map<String, String> headers)
      throws DomatarException;
}
