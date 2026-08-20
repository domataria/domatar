/*
 * Copyright (c) 2024 Domatar
 */

package com.domatar.install;

/**
 * Browser URLs for any file served by an app's {@code AppAssetServlet}
 * (HTML, images, icons). Icons are paths under {@code icons/}, not a
 * separate URL language.
 *
 * <p>{@code url(origin, contextPath, appId, assetPath)} is the join.
 * {@code contextPath} is how browsers reach the WAR ({@code /domatar},
 * {@code ""}, or another prefix) — an install property, not a proxy brand.
 */
public final class AssetPaths
{
  public static final String WIRE_CONTEXT = "/domatar";
  public static final String DEFAULT_APP_ID = "domatar";
  public static final String LAUNCHER_ASSET = "icons/app.svg";
  public static final String DEFAULT_GLYPH_ASSET = "icons/cls/default/obj.svg";

  public static final String DEFAULT_RELATIVE =
      WIRE_CONTEXT + "/" + DEFAULT_APP_ID + "/" + DEFAULT_GLYPH_ASSET;

  private AssetPaths() {}

  /**
   * Browser URL for a file under {@code <appId>/assets/<assetPath>}.
   *
   * @param origin       {@code {scheme}://{host[:port]}} or null/empty
   *                     for a same-origin relative URL
   * @param contextPath  WAR mount as browsers see it ({@code /domatar}
   *                     or {@code ""}); null means {@link #WIRE_CONTEXT}
   * @param appId        app servlet mapping
   * @param assetPath    path inside AssetDirectory
   *                     ({@code icons/app.svg}, {@code img/a/b.png})
   */
  public static String url(final String origin, final String contextPath,
                           final String appId, final String assetPath)
  {
    if (appId == null || appId.isEmpty()
        || assetPath == null || assetPath.isEmpty())
      return url(origin, contextPath, DEFAULT_APP_ID, DEFAULT_GLYPH_ASSET);

    final String path = join(contextPath, appId, assetPath);

    if (origin == null || origin.isEmpty())
      return path;

    return trimTrailingSlash(origin) + path;
  }

  public static String launcher(final String origin, final String contextPath,
                                final String appId)
  {
    return url(origin, contextPath, appId, LAUNCHER_ASSET);
  }

  /** Wire-relative launcher ({@code /domatar/<appId>/icons/app.svg}). */
  public static String launcher(final String appId)
  {
    return launcher(null, WIRE_CONTEXT, appId);
  }

  public static String cls(final String origin, final String contextPath,
                           final String appId, final String clsId)
  {
    if (clsId == null || clsId.isEmpty())
      return url(origin, contextPath, DEFAULT_APP_ID, DEFAULT_GLYPH_ASSET);

    return url(origin, contextPath, appId, "icons/cls/" + clsId + ".svg");
  }

  public static String cls(final String appId, final String clsId)
  {
    return cls(null, WIRE_CONTEXT, appId, clsId);
  }

  public static String defaultGlyph(final String origin, final String contextPath)
  {
    return url(origin, contextPath, DEFAULT_APP_ID, DEFAULT_GLYPH_ASSET);
  }

  public static String defaultCls()
  {
    return DEFAULT_RELATIVE;
  }

  /**
   * Context browsers should use for this pair of hosts. Distinct public
   * host → empty context (front door remounts at {@code /}). Same host
   * or public unset → {@link #WIRE_CONTEXT}. Override with
   * {@code AssetContextPath} / {@code DOMATAR_ASSET_CONTEXT_PATH}.
   */
  public static String contextPath(final String publicDomain,
                                   final String wireDomain)
  {
    return usesFrontDoorPaths(publicDomain, wireDomain) ? "" : WIRE_CONTEXT;
  }

  public static String normalizeContext(final String contextPath)
  {
    if (contextPath == null)
      return WIRE_CONTEXT;

    String ctx = contextPath.trim();

    if (ctx.isEmpty() || "-".equals(ctx) || "none".equals(ctx)
        || "/".equals(ctx))
      return "";

    if (!ctx.startsWith("/"))
      ctx = "/" + ctx;

    while (ctx.length() > 1 && ctx.endsWith("/"))
      ctx = ctx.substring(0, ctx.length() - 1);

    return ctx;
  }

  public static String absolute(final String scheme, final String domain,
                                final String relativePath)
  {
    final String path = (relativePath == null || relativePath.isEmpty())
        ? DEFAULT_RELATIVE : relativePath;
    final String sch = (scheme == null || scheme.isEmpty()) ? "https" : scheme;
    final String dom = domain == null ? "" : domain;

    return sch + "://" + dom + path;
  }

  public static String stripContext(final String relativeWithContext)
  {
    if (relativeWithContext == null || relativeWithContext.isEmpty())
      return relativeWithContext;

    if (relativeWithContext.equals(WIRE_CONTEXT))
      return "/";

    if (relativeWithContext.startsWith(WIRE_CONTEXT + "/"))
      return relativeWithContext.substring(WIRE_CONTEXT.length());

    return relativeWithContext;
  }

  public static boolean usesFrontDoorPaths(final String publicDomain,
                                           final String wireDomain)
  {
    if (publicDomain == null || publicDomain.isEmpty())
      return false;

    if (wireDomain == null || wireDomain.isEmpty())
      return true;

    return !publicDomain.equals(wireDomain);
  }

  public static String browserAbsolute(final String scheme,
                                       final String publicDomain,
                                       final String wireDomain,
                                       final String relativeWithContext)
  {
    final String path = (relativeWithContext == null
        || relativeWithContext.isEmpty())
        ? DEFAULT_RELATIVE : relativeWithContext;

    if (usesFrontDoorPaths(publicDomain, wireDomain))
      return absolute(scheme, publicDomain, stripContext(path));

    final String host = (wireDomain != null && !wireDomain.isEmpty())
        ? wireDomain : publicDomain;

    return absolute(scheme, host, path);
  }

  public static String launcherAbsolute(final String scheme, final String domain,
                                        final String appId)
  {
    return absolute(scheme, domain, launcher(appId));
  }

  public static String clsAbsolute(final String scheme, final String domain,
                                   final String appId, final String clsId)
  {
    return absolute(scheme, domain, cls(appId, clsId));
  }

  public static String forInstall(final boolean remote, final String scheme,
                                  final String publicDomain,
                                  final String wireDomain, final String appId)
  {
    if (!remote)
      return launcher(appId);

    return browserAbsolute(scheme, publicDomain, wireDomain, launcher(appId));
  }

  public static String forInstall(final boolean remote, final String scheme,
                                  final String domain, final String appId)
  {
    return forInstall(remote, scheme, domain, domain, appId);
  }

  public static String assetOrigin(final String scheme, final String domain)
  {
    if (domain == null || domain.isEmpty())
      return null;

    final String sch = (scheme == null || scheme.isEmpty()) ? "https" : scheme;

    return sch + "://" + domain;
  }

  public static boolean isIconPath(final String pathInfo)
  {
    if (pathInfo == null)
      return false;

    return pathInfo.equals("/icons") || pathInfo.startsWith("/icons/");
  }

  /** Cache-Control/ETag candidates: {@code /icons/} and image files. */
  public static boolean isCachedAsset(final String pathInfo)
  {
    if (isIconPath(pathInfo))
      return true;

    if (pathInfo == null)
      return false;

    final String lower = pathInfo.toLowerCase();

    return lower.endsWith(".svg") || lower.endsWith(".png")
        || lower.endsWith(".jpg") || lower.endsWith(".jpeg")
        || lower.endsWith(".gif") || lower.endsWith(".webp")
        || lower.endsWith(".ico");
  }

  static String join(final String contextPath, final String appId,
                     final String assetPath)
  {
    final String ctx = (contextPath == null)
        ? WIRE_CONTEXT : normalizeContext(contextPath);
    final String asset = trimLeadingSlash(assetPath.replace('\\', '/'));

    return ctx + "/" + appId + "/" + asset;
  }

  private static String trimLeadingSlash(final String path)
  {
    String p = path;

    while (p.startsWith("/"))
      p = p.substring(1);

    return p;
  }

  private static String trimTrailingSlash(final String origin)
  {
    if (origin.endsWith("/"))
      return origin.substring(0, origin.length() - 1);

    return origin;
  }
}
