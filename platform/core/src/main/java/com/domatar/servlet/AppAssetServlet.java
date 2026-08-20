/*
 * Copyright (c) 2024 Domatar
 */

package com.domatar.servlet;

import com.domatar.crypto.KeyOps;
import com.domatar.install.AssetPaths;
import com.domatar.util.Base64Encoder;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.net.URLConnection;

/**
 * Generic static-asset servlet for a single app JAR.
 *
 * <p>Registered programmatically by {@link com.domatar.app.AppLoader} for
 * each loaded app at {@code /<appId>/*}.  Reads resources from the app's
 * own {@link ClassLoader} under the app's declared
 * {@code AssetDirectory}.
 *
 * <p>Security: requests whose path contains {@code ..}, {@code \}, or that
 * start with {@code /WEB-INF} are rejected with 400.
 *
 * <p>Image paths ({@code /icons/...} and common image extensions) get
 * {@code Cache-Control} and ETag (Spec-Icons.txt PART 4.4).
 */
public class AppAssetServlet extends HttpServlet
{
  private static final long serialVersionUID = 1L;

  private final String      appId;
  private final String      assetDirectory;
  private final ClassLoader cl;

  public AppAssetServlet(String appId, String assetDirectory, ClassLoader cl)
  {
    this.appId          = appId;
    this.assetDirectory = assetDirectory;
    this.cl             = cl;
  }

  @Override
  protected void doGet(HttpServletRequest req, HttpServletResponse res)
      throws IOException
  {
    String pathInfo = req.getPathInfo();

    if (pathInfo == null || pathInfo.endsWith("/"))
      pathInfo = "/index.html";

    // Security checks.
    if (pathInfo.contains("..") || pathInfo.contains("\\")
        || pathInfo.toUpperCase().startsWith("/WEB-INF"))
    {
      res.sendError(HttpServletResponse.SC_BAD_REQUEST, "Invalid path");
      return;
    }

    String resourcePath = assetDirectory + pathInfo;

    URL u = cl.getResource(resourcePath);

    if (u == null)
    {
      res.sendError(HttpServletResponse.SC_NOT_FOUND);
      return;
    }

    String contentType = URLConnection.guessContentTypeFromName(resourcePath);

    if (contentType == null)
    {
      String lower = resourcePath.toLowerCase();

      if (lower.endsWith(".svg"))
        contentType = "image/svg+xml";
      else if (lower.endsWith(".js"))
        contentType = "application/javascript";
      else if (lower.endsWith(".css"))
        contentType = "text/css";
      else if (lower.endsWith(".html"))
        contentType = "text/html; charset=UTF-8";
      else
        contentType = "application/octet-stream";
    }

    if (AssetPaths.isCachedAsset(pathInfo))
    {
      final byte[] bytes;

      try (InputStream in = u.openStream())
      {
        bytes = readAll(in);
      }

      final String etag = etagFor(bytes);

      res.setHeader("Cache-Control", "public, max-age=86400");
      res.setHeader("ETag", etag);

      final String inm = req.getHeader("If-None-Match");

      if (inm != null && inm.equals(etag))
      {
        res.setStatus(HttpServletResponse.SC_NOT_MODIFIED);
        return;
      }

      res.setContentType(contentType);
      res.setContentLength(bytes.length);
      res.getOutputStream().write(bytes);
      return;
    }

    res.setContentType(contentType);

    try (InputStream in  = u.openStream();
         OutputStream out = res.getOutputStream())
    {
      byte[] buf = new byte[8192];
      int    n;

      while ((n = in.read(buf)) != -1)
        out.write(buf, 0, n);
    }
  }

  private static byte[] readAll(final InputStream in) throws IOException
  {
    final ByteArrayOutputStream out = new ByteArrayOutputStream();
    final byte[] buf = new byte[8192];
    int n;

    while ((n = in.read(buf)) != -1)
      out.write(buf, 0, n);

    return out.toByteArray();
  }

  private static String etagFor(final byte[] bytes)
  {
    return "\"" + Base64Encoder.encode(KeyOps.sha256(bytes)) + "\"";
  }
}
