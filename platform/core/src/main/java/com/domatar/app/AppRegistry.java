/*
 * Copyright (c) 2024 Domatar
 */

package com.domatar.app;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Static singleton holding all loaded apps.
 *
 * Populated by AppLoader during ServletContext initialisation.
 * Iteration order matches JAR discovery order (filename-sorted).
 */
public final class AppRegistry
{
  private static final Map<String, App> byAppId = new LinkedHashMap<>();

  private AppRegistry() {}

  public static synchronized void register(App app)
  {
    byAppId.put(app.appId, app);
  }

  public static synchronized App get(String appId)
  {
    return byAppId.get(appId);
  }

  /** Returns all registered apps in insertion order. */
  public static synchronized Collection<App> all()
  {
    return Collections.unmodifiableCollection(byAppId.values());
  }

  /** Removes all registrations.  For use in tests only. */
  public static synchronized void clear()
  {
    byAppId.clear();
  }
}
