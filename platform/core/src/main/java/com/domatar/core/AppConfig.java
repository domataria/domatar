/*
 * Copyright (c) 2024 Domatar
 */

package com.domatar.core;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;

/**
 * Per-application configuration, read from app.config.txt.
 *
 * In the current monolithic-WAR build, each app's app.config.txt is
 * bundled as a classpath resource at /<appId>/app.config.txt
 * (src/main/resources/<appId>/app.config.txt).
 *
 * In the future per-WAR build (Pass 3), each WAR will carry its own
 * app.config.txt at the root of the classpath (i.e. /app.config.txt),
 * and this class will be called without an appId.
 *
 * Supported keys:
 *   AppId         - unique identifier for this application
 *   AppName       - human-readable name shown in the App Store
 *   AppDesc       - short description shown in the App Store
 *   Version       - application version string
 *   DependsOn     - comma-separated list of appIds that must be installed first
 *   ProviderHosts - comma-separated host ID patterns created by installProvider()
 *                   (use {PrvId} as a placeholder for the provider ID)
 *   ShellRoles    - comma-separated role ids this app may fill
 *                   (login | desktop | navigator | appstore); KD7 / Spec PART 9
 */
public class AppConfig
{
  private final String     appId;
  private final Properties props;

  private AppConfig(final String appId, final Properties props)
  {
    this.appId = appId;
    this.props = props;
  }

  // -------------------------------------------------------------------------
  // Factory
  // -------------------------------------------------------------------------

  /**
   * Loads app.config.txt for the given appId using the supplied ClassLoader.
   * The resource path is {@code /<appId>/app.config.txt}.
   * Returns null if the resource is not found.
   *
   * <p>Callers should pass the app's own ClassLoader (e.g.
   * {@code AppRegistry.get(appId).classLoader}) so that resources inside the
   * per-app JAR are visible.  Using {@code AppConfig.class.getClassLoader()}
   * would only see resources on the parent (domatar-core) classpath.
   */
  public static AppConfig load(final String appId, final ClassLoader cl)
  {
    if (appId == null || cl == null)
      return null;

    final String withSlash = "/" + appId + "/app.config.txt";
    final String bare     = appId + "/app.config.txt";

    InputStream is = cl.getResourceAsStream(withSlash);

    if (is == null)
      is = cl.getResourceAsStream(bare);

    if (is == null)
      return null;

    try (InputStream in = is)
    {
      final Properties props = new Properties();

      props.load(in);

      return new AppConfig(appId, props);
    }
    catch (IOException e)
    {
      return null;
    }
  }

  // -------------------------------------------------------------------------
  // Getters
  // -------------------------------------------------------------------------

  public String getAppId()
  {
    return props.getProperty("AppId", appId);
  }

  public String getAppName()
  {
    return props.getProperty("AppName", appId);
  }

  public String getAppDesc()
  {
    return props.getProperty("AppDesc", "");
  }

  public String getVersion()
  {
    return props.getProperty("Version", "1.0");
  }

  /**
   * Returns the ordered list of appIds that must be installed before this one.
   */
  public List<String> getDependsOn()
  {
    return splitList(props.getProperty("DependsOn", ""));
  }

  /**
   * Returns the list of provider-level host ID patterns that
   * installProvider() creates on this provider.  May contain {PrvId}
   * as a placeholder for the actual provider ID.
   */
  public List<String> getProviderHosts()
  {
    return splitList(props.getProperty("ProviderHosts", ""));
  }

  /**
   * Shell roles this app declares it can fill (KD7). Empty when the
   * {@code ShellRoles} key is absent — the app is not shell-capable.
   */
  public List<String> getShellRoles()
  {
    return splitList(props.getProperty("ShellRoles", ""));
  }

  // -------------------------------------------------------------------------
  // Internal
  // -------------------------------------------------------------------------

  private static List<String> splitList(final String value)
  {
    if (value == null || value.trim().isEmpty())
      return Collections.emptyList();

    final String[] parts = value.split(",");

    final List<String> list = new ArrayList<>();

    for (final String p : parts)
    {
      final String t = p.trim();

      if (!t.isEmpty())
        list.add(t);
    }

    return Collections.unmodifiableList(list);
  }
}
