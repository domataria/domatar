/*
 * Copyright (c) 2024 Domatar
 */

package com.domatar.app;

import com.domatar.util.DomatarException;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Parses META-INF/domatar/app.manifest from an app JAR.
 *
 * Manifest format (UTF-8; # is comment; blank lines ignored):
 * <pre>
 *   AppId:          quippin
 *   Version:        1.0
 *   InstallClass:   com.quippin.install.QuippinInstall
 *   AssetDirectory: quippin/assets
 *   ProviderHosts:  (optional, comma-separated, {PrvId} placeholder)
 *
 *   Handler: quippin, quip,  com.quippin.objimpl.QuipImpl
 *   Wui:     QuipWui,        com.quippin.webui.QuipWui
 * </pre>
 *
 * Required keys: AppId, InstallClass, AssetDirectory.
 * Optional keys: Version (default "1.0"), ProviderHosts (default empty).
 */
public final class AppManifest
{
  private final String       appId;
  private final String       version;
  private final String       installClassName;
  private final String       assetDirectory;
  private final List<String> providerHosts;
  private final List<HandlerEntry> handlers;
  private final List<WuiEntry>     wuis;

  private AppManifest(String appId,
                      String version,
                      String installClassName,
                      String assetDirectory,
                      List<String>       providerHosts,
                      List<HandlerEntry> handlers,
                      List<WuiEntry>     wuis)
  {
    this.appId            = appId;
    this.version          = version;
    this.installClassName = installClassName;
    this.assetDirectory   = assetDirectory;
    this.providerHosts    = Collections.unmodifiableList(providerHosts);
    this.handlers         = Collections.unmodifiableList(handlers);
    this.wuis             = Collections.unmodifiableList(wuis);
  }

  // -------------------------------------------------------------------------
  // Factory
  // -------------------------------------------------------------------------

  /**
   * Reads and parses META-INF/domatar/app.manifest from the given ClassLoader.
   *
   * @throws DomatarException if the resource is missing or malformed.
   */
  public static AppManifest read(ClassLoader cl) throws DomatarException
  {
    InputStream is = cl.getResourceAsStream("META-INF/domatar/app.manifest");

    if (is == null)
      throw new DomatarException(
          "app.manifest not found: META-INF/domatar/app.manifest");

    String       appId            = null;
    String       version          = "1.0";
    String       installClassName = null;
    String       assetDirectory   = null;
    List<String> providerHosts    = new ArrayList<>();
    List<HandlerEntry> handlers   = new ArrayList<>();
    List<WuiEntry>     wuis       = new ArrayList<>();

    try (BufferedReader br = new BufferedReader(
             new InputStreamReader(is, StandardCharsets.UTF_8)))
    {
      String line;

      while ((line = br.readLine()) != null)
      {
        line = line.trim();

        if (line.isEmpty() || line.startsWith("#"))
          continue;

        int colon = line.indexOf(':');

        if (colon < 0)
          throw new DomatarException("Malformed manifest line: " + line);

        String key   = line.substring(0, colon).trim();
        String value = line.substring(colon + 1).trim();

        switch (key)
        {
          case "AppId":
            appId = value;
            break;

          case "Version":
            version = value;
            break;

          case "InstallClass":
            installClassName = value;
            break;

          case "AssetDirectory":
            assetDirectory = value;
            break;

          case "ProviderHosts":
            if (!value.isEmpty())
              for (String h : value.split(","))
                providerHosts.add(h.trim());
            break;

          case "Handler":
          {
            String[] parts = value.split(",");

            if (parts.length != 3)
              throw new DomatarException(
                  "Handler line requires 3 comma-separated fields: " + line);

            handlers.add(new HandlerEntry(
                parts[0].trim(), parts[1].trim(), parts[2].trim()));
            break;
          }

          case "Wui":
          {
            String[] parts = value.split(",");

            if (parts.length != 2)
              throw new DomatarException(
                  "Wui line requires 2 comma-separated fields: " + line);

            wuis.add(new WuiEntry(parts[0].trim(), parts[1].trim()));
            break;
          }

          default:
            throw new DomatarException("Unknown manifest key: " + key);
        }
      }
    }
    catch (DomatarException e)
    {
      throw e;
    }
    catch (Exception e)
    {
      throw new DomatarException("Failed to read app.manifest: " + e.getMessage());
    }

    if (appId == null)
      throw new DomatarException("app.manifest missing required key: AppId");

    if (installClassName == null)
      throw new DomatarException("app.manifest missing required key: InstallClass");

    if (assetDirectory == null)
      throw new DomatarException("app.manifest missing required key: AssetDirectory");

    return new AppManifest(appId, version, installClassName, assetDirectory,
                           providerHosts, handlers, wuis);
  }

  // -------------------------------------------------------------------------
  // Accessors
  // -------------------------------------------------------------------------

  public String getAppId()            { return appId; }
  public String getVersion()          { return version; }
  public String getInstallClassName() { return installClassName; }
  public String getAssetDirectory()   { return assetDirectory; }

  public List<String>       getProviderHosts() { return providerHosts; }
  public List<HandlerEntry> getHandlers()      { return handlers; }
  public List<WuiEntry>     getWuis()          { return wuis; }

  // -------------------------------------------------------------------------
  // Nested types
  // -------------------------------------------------------------------------

  public static final class HandlerEntry
  {
    public final String clsAppId;
    public final String clsId;
    public final String className;

    public HandlerEntry(String clsAppId, String clsId, String className)
    {
      this.clsAppId   = clsAppId;
      this.clsId      = clsId;
      this.className  = className;
    }
  }

  public static final class WuiEntry
  {
    public final String wuiName;
    public final String className;

    public WuiEntry(String wuiName, String className)
    {
      this.wuiName   = wuiName;
      this.className = className;
    }
  }
}
