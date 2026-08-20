/*
 * Copyright (c) 2024 Domatar
 */

package com.domatar.install;

/**
 * App Store / Desktop launch URL helpers (Spec-AppStore KD8).
 */
public final class LaunchPaths
{
  private LaunchPaths() {}

  /** Relative precursor path: {@code /domatar/<appId>/<appId>.html}. */
  public static String relative(final String appId)
  {
    if (appId == null || appId.isEmpty())
      return AssetPaths.WIRE_CONTEXT + "/";

    return AssetPaths.url(null, AssetPaths.WIRE_CONTEXT, appId, appId + ".html");
  }

  /**
   * Default LaunchPath for a shell-role binding. Stock login UI opens
   * account.html (manage identity), not login.html (sign-in form) —
   * Spec-Desktop.txt PART 8 Account tile.
   */
  public static String forShell(final String roleId, final String appId)
  {
    if ("login".equals(roleId) && "login".equals(appId))
      return AssetPaths.url(null, AssetPaths.WIRE_CONTEXT, "login",
          "account.html");

    return relative(appId);
  }

  /**
   * Icon path convention used by Desktop seed / App Store tiles.
   * Canonical implementation is {@link AssetPaths#launcher}.
   */
  public static String icon(final String appId)
  {
    return AssetPaths.launcher(appId);
  }

  /**
   * Absolute cross-provider LaunchPath against a wire Domain
   * ({@code {scheme}://{domain}/domatar/<appId>/...}).
   */
  public static String absolute(final String scheme, final String domain,
                                final String appId)
  {
    final String sch = (scheme == null || scheme.isEmpty()) ? "https" : scheme;
    final String dom = domain == null ? "" : domain;

    return sch + "://" + dom + relative(appId);
  }

  /**
   * Absolute browser LaunchPath. When {@code publicDomain} is a distinct
   * front door, use empty context (same rule as
   * {@link AssetPaths#browserAbsolute}).
   */
  public static String browserAbsolute(final String scheme,
                                       final String publicDomain,
                                       final String wireDomain,
                                       final String appId)
  {
    return AssetPaths.browserAbsolute(scheme, publicDomain, wireDomain,
        relative(appId));
  }

  /**
   * Relative for same-provider installs; absolute browser URL when remote.
   */
  public static String forInstall(final boolean remote, final String scheme,
                                  final String publicDomain,
                                  final String wireDomain, final String appId)
  {
    if (!remote)
      return relative(appId);

    return browserAbsolute(scheme, publicDomain, wireDomain, appId);
  }

  /**
   * Backward-compatible: treats {@code domain} as both public and wire host.
   */
  public static String forInstall(final boolean remote, final String scheme,
                                  final String domain, final String appId)
  {
    return forInstall(remote, scheme, domain, domain, appId);
  }
}
