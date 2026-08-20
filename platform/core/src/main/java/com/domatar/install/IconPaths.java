/*
 * Copyright (c) 2024 Domatar
 */

package com.domatar.install;

/**
 * Compatibility facade for launcher / class icon URLs. All path algebra
 * lives in {@link AssetPaths}.
 */
public final class IconPaths
{
  public static final String DEFAULT_RELATIVE = AssetPaths.DEFAULT_RELATIVE;

  private IconPaths() {}

  public static String launcher(final String appId)
  {
    return AssetPaths.launcher(appId);
  }

  public static String cls(final String appId, final String clsId)
  {
    return AssetPaths.cls(appId, clsId);
  }

  public static String defaultCls()
  {
    return AssetPaths.defaultCls();
  }

  public static String absolute(final String scheme, final String domain,
                                final String relativePath)
  {
    return AssetPaths.absolute(scheme, domain, relativePath);
  }

  public static String stripContext(final String relativeWithContext)
  {
    return AssetPaths.stripContext(relativeWithContext);
  }

  public static boolean usesFrontDoorPaths(final String publicDomain,
                                           final String wireDomain)
  {
    return AssetPaths.usesFrontDoorPaths(publicDomain, wireDomain);
  }

  public static String browserAbsolute(final String scheme,
                                       final String publicDomain,
                                       final String wireDomain,
                                       final String relativeWithContext)
  {
    return AssetPaths.browserAbsolute(scheme, publicDomain, wireDomain,
        relativeWithContext);
  }

  public static String launcherAbsolute(final String scheme, final String domain,
                                        final String appId)
  {
    return AssetPaths.launcherAbsolute(scheme, domain, appId);
  }

  public static String clsAbsolute(final String scheme, final String domain,
                                   final String appId, final String clsId)
  {
    return AssetPaths.clsAbsolute(scheme, domain, appId, clsId);
  }

  public static String forInstall(final boolean remote, final String scheme,
                                  final String publicDomain,
                                  final String wireDomain, final String appId)
  {
    return AssetPaths.forInstall(remote, scheme, publicDomain, wireDomain,
        appId);
  }

  public static String forInstall(final boolean remote, final String scheme,
                                  final String domain, final String appId)
  {
    return AssetPaths.forInstall(remote, scheme, domain, appId);
  }

  public static String assetOrigin(final String scheme, final String domain)
  {
    return AssetPaths.assetOrigin(scheme, domain);
  }

  public static boolean isIconPath(final String pathInfo)
  {
    return AssetPaths.isIconPath(pathInfo);
  }
}
