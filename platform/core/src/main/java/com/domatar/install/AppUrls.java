/*
 * Copyright (c) 2024 Domatar
 */

package com.domatar.install;

/**
 * Browser base URL for an installed app (Spec-Icons PART 7.2).
 *
 * <p>Resolution: explicit {@code AppUrl} if set; else the offering
 * provider's {@code BrowserOrigin}; else PublicDomain / Domain.
 * IconPath and LaunchPath are derived from that base plus convention
 * paths ({@code /<appId>/icons/app.svg}, {@code /<appId>/<appId>.html}).
 */
public final class AppUrls
{
  private AppUrls() {}

  public static boolean isBlank(final String s)
  {
    return s == null || s.trim().isEmpty();
  }

  public static String trimSlash(final String s)
  {
    if (s == null || s.isEmpty())
      return s;

    if (s.length() > 1 && s.endsWith("/"))
      return s.substring(0, s.length() - 1);

    return s;
  }

  /**
   * {@code scheme://host[:port]} or full URL. Host-only values get
   * {@code scheme} prepended.
   */
  public static String withScheme(final String scheme, final String originOrHost)
  {
    if (isBlank(originOrHost))
      return null;

    final String v = originOrHost.trim();

    if (v.contains("://"))
      return trimSlash(v);

    final String sch = isBlank(scheme) ? "https" : scheme;

    return sch + "://" + v;
  }

  /**
   * Offering provider's browser base when the app has no AppUrl.
   * {@code BrowserOrigin} is the host the user's browser can open
   * (e.g. {@code http://localhost:9080}); a path on that origin is
   * kept, otherwise {@code /domatar} is appended. Without
   * BrowserOrigin, PublicDomain front door or wire Domain + context.
   */
  public static String inheritProviderBase(final String scheme,
                                           final String browserOrigin,
                                           final String publicDomain,
                                           final String wireDomain)
  {
    if (!isBlank(browserOrigin))
    {
      final String origin = withScheme(scheme, browserOrigin);
      final int auth = origin.indexOf("://");
      final int slash = origin.indexOf('/', auth + 3);

      if (slash > 0)
      {
        final String path = origin.substring(slash);

        if (!path.isEmpty() && !"/".equals(path))
          return trimSlash(origin);
      }

      return trimSlash(origin) + AssetPaths.WIRE_CONTEXT;
    }

    if (AssetPaths.usesFrontDoorPaths(publicDomain, wireDomain)
        && isBrowserReachable(publicDomain))
      return (isBlank(scheme) ? "https" : scheme) + "://" + publicDomain;

    final String host = !isBlank(wireDomain) ? wireDomain : publicDomain;

    if (isBlank(host) || !isBrowserReachable(host))
      return null;

    return (isBlank(scheme) ? "https" : scheme) + "://" + host
        + AssetPaths.WIRE_CONTEXT;
  }

  /**
   * True when a browser on the user's machine can resolve {@code hostOrUrl}.
   * Docker-only names ({@code quippin:8080}) are false; {@code localhost}
   * and dotted hostnames are true.
   */
  public static boolean isBrowserReachable(final String hostOrUrl)
  {
    if (isBlank(hostOrUrl))
      return false;

    String host = hostOrUrl.trim();

    final int scheme = host.indexOf("://");

    if (scheme >= 0)
      host = host.substring(scheme + 3);

    final int slash = host.indexOf('/');

    if (slash >= 0)
      host = host.substring(0, slash);

    final int colon = host.lastIndexOf(':');

    if (colon > 0 && host.indexOf(':') == colon)
      host = host.substring(0, colon);

    if (host.isEmpty())
      return false;

    if ("localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host))
      return true;

    return host.indexOf('.') >= 0;
  }

  /**
   * AppUrl if set; otherwise {@link #inheritProviderBase}.
   */
  public static String resolve(final String appUrl, final String scheme,
                               final String browserOrigin,
                               final String publicDomain,
                               final String wireDomain)
  {
    if (!isBlank(appUrl))
    {
      final String explicit = trimSlash(withScheme(scheme, appUrl.trim()));

      if (isBrowserReachable(explicit))
        return explicit;
    }

    return inheritProviderBase(scheme, browserOrigin, publicDomain, wireDomain);
  }

  public static String join(final String appUrl, final String appId,
                            final String assetPath)
  {
    if (isBlank(appId) || isBlank(assetPath))
      return AssetPaths.DEFAULT_RELATIVE;

    String asset = assetPath.replace('\\', '/');

    while (asset.startsWith("/"))
      asset = asset.substring(1);

    if (isBlank(appUrl))
      return AssetPaths.WIRE_CONTEXT + "/" + appId + "/" + asset;

    return trimSlash(appUrl) + "/" + appId + "/" + asset;
  }

  public static String launchAsset(final String appId, final String launchPage)
  {
    if (!isBlank(launchPage))
    {
      String page = launchPage.trim().replace('\\', '/');
      final int slash = page.lastIndexOf('/');

      if (slash >= 0)
        page = page.substring(slash + 1);

      if (!page.isEmpty() && !page.contains(".."))
        return page;
    }

    if (isBlank(appId))
      return "";

    return appId + ".html";
  }

  public static String launchPath(final String appUrl, final String appId)
  {
    return launchPath(appUrl, appId, null);
  }

  public static String launchPath(final String appUrl, final String appId,
                                  final String launchPage)
  {
    if (isBlank(appId))
      return AssetPaths.WIRE_CONTEXT + "/";

    return join(appUrl, appId, launchAsset(appId, launchPage));
  }

  public static String iconPath(final String appUrl, final String appId)
  {
    return join(appUrl, appId, AssetPaths.LAUNCHER_ASSET);
  }
}
