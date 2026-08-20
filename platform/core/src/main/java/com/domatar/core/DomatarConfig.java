/*
 * Copyright (c) 2024 Domatar
 */

package com.domatar.core;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;
import java.util.logging.Logger;

import com.domatar.install.AssetPaths;

/**
 * Centralised access to per-server configuration.
 *
 * Resolution precedence for every value (highest to lowest):
 *   1. Environment variable  (e.g. DOMATAR_HSTID)
 *   2. JVM system property   (e.g. -Ddomatar.hstid=...)
 *   3. provider.config.txt   (loaded once from filesystem or classpath)
 *   4. Hardcoded default     (where a sensible default exists)
 *
 * provider.config.txt location (first match wins):
 *   - Path given by DOMATAR_CONFIG_PATH env var / domatar.config.path system property
 *   - /opt/domatar/provider.config.txt  (production default)
 *   - Classpath resource /provider.config.txt (development / test fallback)
 *
 * Supported keys in provider.config.txt:
 *   HstId                  - this server's hstId (required)
 *   PrvId                  - provider ID, defaults to HstId
 *   Domain                 - public domain or host:port of this server
 *   PublicDomain           - optional browser hostname (front door)
 *   AssetContextPath       - browser path prefix for app assets (/domatar or empty)
 *   DbUrl                  - JDBC connection URL
 *   DefaultApps            - (legacy) comma-separated appIds; prefer default-apps-config.txt
 *   AdminPassword          - initial password for the provider administrator account
 *   PrvActId               - fingerprint actId of the provider account (written at bootstrap
 *                            by DomatarProviderInstall via setPrvActIdOnce; Phase 2+)
 *   MasterKey              - Base64Encoder-encoded 32-byte AES-256 master key used to seal
 *                            account root private keys in the database (Phase 1+)
 *   DirectoryRootPubKey    - Base64Encoder-encoded 32-byte Ed25519 public key of the global
 *                            directory root; required for hst record verification (Phase 3+)
 *   DirectoryRootPrivKey   - Base64Encoder-encoded 32-byte Ed25519 private key seed of the
 *                            directory root; set only on the directory server node (Phase 3+)
 *   ProviderKeyPath        - filesystem path to the provider.opkey file that holds the
 *                            provider's operational Ed25519 private key seed (Phase 1+)
 *   GenesisVaultPath       - filesystem path of the sim/test genesis-key vault
 *                            (Spec-OwnIds.txt PART 7/15; default mySQL/dev-keys/genesis-vault.txt)
 *
 * Default applications (new accounts) are read by getDefaultApps() with this
 * precedence:
 *   1. DOMATAR_DEFAULT_APPS env / system property
 *   2. default-apps-config.txt (see below)
 *   3. DefaultApps in provider.config.txt
 *   4. Built-in DEFAULT_APPS
 *
 * default-apps-config.txt (recommended): lives next to provider.config.txt
 * with the same discovery rules as that file's filesystem path (first:
 * directory of DOMATAR_CONFIG_PATH if set, else /opt/domatar/), or a path
 * given by DOMATAR_DEFAULT_APPS_CONFIG, or classpath resource
 * /default-apps-config.txt. Format: UTF-8, # line comments, comma-separated
 * appIds; multiple lines are concatenated in order (duplicates removed,
 * first wins).
 *
 * Environment variable aliases (override config file):
 *   DOMATAR_HSTID, DOMATAR_PRVID, DOMATAR_DOMAIN, DOMATAR_PUBLIC_DOMAIN,
 *   DOMATAR_ASSET_CONTEXT_PATH, DOMATAR_DB_URL,
 *   DOMATAR_DEFAULT_APPS, DOMATAR_DEFAULT_APPS_CONFIG, DOMATAR_ADMIN_PASSWORD,
 *   DOMATAR_DIRECTORY, DOMATAR_PRV_ACTID, DOMATAR_MASTER_KEY,
 *   DOMATAR_DIRECTORY_ROOT_PUBKEY, DOMATAR_DIRECTORY_ROOT_PRIVKEY,
 *   DOMATAR_PROVIDER_KEY_PATH, DOMATAR_GENESIS_VAULT_PATH
 */
public class DomatarConfig
{
  public static final String DEFAULT_DIRECTORY    = "domatar:8080";
  public static final String DEFAULT_ADMIN_PWD    = "changeme";
  public static final List<String> DEFAULT_APPS   =
      Collections.unmodifiableList(Arrays.asList(
          "navigator", "domatar", "login", "desktop", "appstore",
          "quippin", "bookstore", "spreadsheet", "money", "aiagent"));

  /** Tomcat context path of the platform WAR. Used by HttpClient for remote dispatch. */
  public static final String PLATFORM_CONTEXT_PATH = "/domatar";

  private static final Logger LOG = Logger.getLogger(DomatarConfig.class.getName());

  private static volatile String     directory;
  private static volatile Properties configFile;
  private static volatile boolean    configLoaded = false;

  // -------------------------------------------------------------------------
  // Public getters
  // -------------------------------------------------------------------------

  public static String getHstId()
  {
    final String v = resolve("DOMATAR_HSTID", "HstId", null);

    if (v == null || v.isEmpty())
      throw new IllegalStateException(
          "HstId is not set. Set DOMATAR_HSTID env var or HstId in provider.config.txt.");

    return v;
  }

  public static String getPrvId()
  {
    final String v = resolve("DOMATAR_PRVID", "PrvId", null);

    return (v != null && !v.isEmpty()) ? v : getHstId();
  }

  public static String getDomain()
  {
    return resolve("DOMATAR_DOMAIN", "Domain", null);
  }

  /**
   * Browser-facing hostname (nginx front door), e.g. {@code domatar.avatarvia.com}.
   * Distinct from {@link #getDomain()} which is the wire address
   * ({@code tomcat2:8080}). Null when unset — callers fall back to
   * {@link #getDomain()} for wire-only deployments.
   */
  public static String getPublicDomain()
  {
    final String v = resolve("DOMATAR_PUBLIC_DOMAIN", "PublicDomain", null);

    if (v == null || v.trim().isEmpty())
      return null;

    return v.trim();
  }

  /**
   * Host for browser absolute URLs: {@link #getPublicDomain()} when set,
   * otherwise {@link #getDomain()}.
   */
  public static String getBrowserDomain()
  {
    final String pub = getPublicDomain();

    if (pub != null)
      return pub;

    return getDomain();
  }

  /**
   * Path prefix browsers use to reach app assets ({@code /domatar} on
   * the WAR, {@code ""} when a reverse proxy remounts at {@code /}).
   * Env {@code DOMATAR_ASSET_CONTEXT_PATH} / {@code AssetContextPath};
   * use {@code -} for an explicit empty prefix. When unset, distinct
   * {@link #getPublicDomain()} implies empty (front door).
   */
  public static String getAssetContextPath()
  {
    final String v = resolve("DOMATAR_ASSET_CONTEXT_PATH", "AssetContextPath",
        null);

    if (v != null)
      return AssetPaths.normalizeContext(v);

    return AssetPaths.contextPath(getPublicDomain(), getDomain());
  }

  public static String getDirectory()
  {
    if (directory == null)
      directory = resolve("DOMATAR_DIRECTORY", null, DEFAULT_DIRECTORY);

    return directory;
  }

  /** Returns the JDBC URL override, or null if not set. */
  public static String getDbUrlOverride()
  {
    return resolve("DOMATAR_DB_URL", "DbUrl", null);
  }

  /**
   * Returns the ordered list of appIds that every new user account should
   * receive at sign-up time. See class Javadoc for precedence.
   */
  public static List<String> getDefaultApps()
  {
    String v = envOrSystemProperty("DOMATAR_DEFAULT_APPS");

    if (v != null && !v.trim().isEmpty())
      return parseCommaAppIds(v);

    final List<String> fromFile = loadDefaultAppsFromDedicatedFile();

    if (fromFile != null && !fromFile.isEmpty())
      return fromFile;

    final Properties props = loadConfigFile();

    if (props != null)
    {
      v = props.getProperty("DefaultApps");

      if (v != null && !v.trim().isEmpty())
        return parseCommaAppIds(v);
    }

    return DEFAULT_APPS;
  }

  /**
   * Returns the initial administrator password for provider bootstrap.
   * MUST be changed on first login.
   */
  public static String getAdminPassword()
  {
    return resolve("DOMATAR_ADMIN_PASSWORD", "AdminPassword", DEFAULT_ADMIN_PWD);
  }

  /**
   * Returns the absolute filesystem path of the {@code WEB-INF/apps} directory
   * inside the platform WAR.  Set by {@code AppLoader.contextInitialized} as a
   * JVM system property so that downstream code can also read it.  Returns
   * {@code null} before AppLoader has run.
   */
  public static String getAppsDirectory()
  {
    return System.getProperty("domatar.apps.directory");
  }

  // -------------------------------------------------------------------------
  // Security-related getters (Phase 1+)
  // -------------------------------------------------------------------------

  /**
   * Returns the fingerprint actId of this provider's own account, or {@code null}
   * if it has not been set yet (pre-bootstrap).
   *
   * Written once by {@code DomatarProviderInstall} via {@link #setPrvActIdOnce(String)}.
   */
  public static String getPrvActId()
  {
    return resolve("DOMATAR_PRV_ACTID", "PrvActId", null);
  }

  /**
   * Returns the Base64Encoder-encoded 32-byte AES-256 master key used to seal
   * account root private keys in the database, or {@code null} if not configured.
   *
   * Absence is allowed in development environments; {@code MasterKey} falls back
   * to a plaintext pass-through with a WARNING.
   */
  public static String getMasterKey()
  {
    return resolve("DOMATAR_MASTER_KEY", "MasterKey", null);
  }

  /**
   * Returns the Base64Encoder-encoded 32-byte Ed25519 public key of the global
   * directory root, or {@code null} if not configured.
   *
   * Required on all nodes that verify hst directory records (Phase 3+).
   */
  public static String getDirectoryRootPubKey()
  {
    return resolve("DOMATAR_DIRECTORY_ROOT_PUBKEY", "DirectoryRootPubKey", null);
  }

  /**
   * Returns the Base64Encoder-encoded 32-byte Ed25519 private key seed of the
   * directory root, or {@code null} if not configured.
   *
   * Set ONLY on the node that IS the directory server (Phase 3+).
   */
  public static String getDirectoryRootPrivKey()
  {
    return resolve("DOMATAR_DIRECTORY_ROOT_PRIVKEY", "DirectoryRootPrivKey", null);
  }

  /**
   * Returns the filesystem path of the {@code provider.opkey} file containing
   * this provider's operational Ed25519 private key seed, or {@code null} if
   * not configured (key will be ephemeral).
   */
  public static String getProviderKeyPath()
  {
    return resolve("DOMATAR_PROVIDER_KEY_PATH", "ProviderKeyPath", null);
  }

  /**
   * Returns the filesystem path of the sim/test genesis-key vault file
   * (Spec-OwnIds.txt PART 7 / PART 15). Default
   * {@code mySQL/dev-keys/genesis-vault.txt}. Configurable via
   * {@code DOMATAR_GENESIS_VAULT_PATH} / {@code GenesisVaultPath}.
   */
  public static String getGenesisVaultPath()
  {
    return resolve("DOMATAR_GENESIS_VAULT_PATH", "GenesisVaultPath",
                   "mySQL/dev-keys/genesis-vault.txt");
  }

  // -------------------------------------------------------------------------
  // Phase 6 — Q3 wire confidentiality (TLS / mTLS)
  // -------------------------------------------------------------------------

  /**
   * Returns the inter-provider wire scheme: {@code "https"} (default, production)
   * or {@code "http"} (dev mode only).
   * Configurable via {@code DOMATAR_WIRE_SCHEME} / {@code WireScheme}.
   *
   * <p>Using {@code "http"} in production disables TLS and is INSECURE.  Only
   * set it for a local same-host simulation where TLS certificates are not
   * available.
   */
  public static String getWireScheme()
  {
    final String v = resolve("DOMATAR_WIRE_SCHEME", "WireScheme", "https");
    return (v == null || v.isEmpty()) ? "https" : v.toLowerCase();
  }

  /**
   * Returns the filesystem path to the JKS or PKCS12 trust-store used for
   * outbound HTTPS connections, or {@code null} if not configured (JVM default
   * CA trust store is used).
   * Configurable via {@code DOMATAR_TRUST_STORE_PATH} / {@code TrustStorePath}.
   */
  public static String getTrustStorePath()
  {
    return resolve("DOMATAR_TRUST_STORE_PATH", "TrustStorePath", null);
  }

  /**
   * Returns the trust-store password, or {@code null} if not configured.
   * Configurable via {@code DOMATAR_TRUST_STORE_PASSWORD} / {@code TrustStorePassword}.
   */
  public static String getTrustStorePassword()
  {
    return resolve("DOMATAR_TRUST_STORE_PASSWORD", "TrustStorePassword", null);
  }

  /**
   * Returns the filesystem path to the JKS or PKCS12 key-store containing
   * this provider's TLS client identity (for mTLS directory writes), or
   * {@code null} if not configured.
   * Configurable via {@code DOMATAR_KEY_STORE_PATH} / {@code KeyStorePath}.
   */
  public static String getKeyStorePath()
  {
    return resolve("DOMATAR_KEY_STORE_PATH", "KeyStorePath", null);
  }

  /**
   * Returns the key-store password, or {@code null} if not configured.
   * Configurable via {@code DOMATAR_KEY_STORE_PASSWORD} / {@code KeyStorePassword}.
   */
  public static String getKeyStorePassword()
  {
    return resolve("DOMATAR_KEY_STORE_PASSWORD", "KeyStorePassword", null);
  }

  /**
   * Returns {@code true} when mutual TLS is required for directory write
   * operations ({@code UpdateHst}).  Defaults to {@code true} when
   * {@code WireScheme=https}; set {@code MtlsRequired=false} to disable
   * during a TLS transition period.
   * Configurable via {@code DOMATAR_MTLS_REQUIRED} / {@code MtlsRequired}.
   */
  public static boolean isMtlsRequired()
  {
    final String wireScheme = getWireScheme();
    final String v = resolve("DOMATAR_MTLS_REQUIRED", "MtlsRequired", null);

    if (v == null || v.isEmpty())
      return "https".equals(wireScheme); // on by default when TLS is active

    return "true".equalsIgnoreCase(v);
  }

  /**
   * Returns the delegation TTL in milliseconds (default 1 hour).
   * Configurable via {@code DOMATAR_DELEG_TTL_MS} / {@code DelegTtlMs}.
   */
  public static long getDelegTtlMs()
  {
    final String v = resolve("DOMATAR_DELEG_TTL_MS", "DelegTtlMs", null);
    if (v == null || v.isEmpty())
      return 60L * 60L * 1000L; // 1 hour default
    try
    {
      return Long.parseLong(v);
    }
    catch (final NumberFormatException ignored)
    {
      return 60L * 60L * 1000L;
    }
  }

  /**
   * Returns the maximum allowed clock skew for incoming messages in milliseconds
   * (default 120 s).  Configurable via {@code DOMATAR_MSG_SKEW_MS} /
   * {@code MsgSkewMs}.
   */
  public static long getMsgSkewMs()
  {
    final String v = resolve("DOMATAR_MSG_SKEW_MS", "MsgSkewMs", null);
    if (v == null || v.isEmpty())
      return 120_000L; // 120 seconds default
    try
    {
      return Long.parseLong(v);
    }
    catch (final NumberFormatException ignored)
    {
      return 120_000L;
    }
  }

  /**
   * Default fingerprint version for newly minted accounts (default 1).
   * Configurable via {@code DOMATAR_FP_DEFAULT_VERSION} / {@code FpDefaultVersion}
   * (Spec-ActId-Versioning.txt PART 8.4).
   */
  public static int getFpDefaultVersion()
  {
    final String v = resolve("DOMATAR_FP_DEFAULT_VERSION", "FpDefaultVersion", null);

    if (v == null || v.isEmpty())
      return 1;

    try
    {
      return Integer.parseInt(v);
    }
    catch (final NumberFormatException ignored)
    {
      return 1;
    }
  }

  /**
   * Persists {@code prvActId} as the {@code PrvActId} key in
   * {@code provider.config.txt} if and only if it is not already present.
   *
   * This method is called exactly once by {@code DomatarProviderInstall} at
   * bootstrap time (Phase 2+).  Subsequent calls are no-ops; the existing value
   * is never overwritten.
   *
   * @param prvActId the fingerprint actId of the provider account
   * @throws IllegalStateException if the config file cannot be updated
   */
  public static void setPrvActIdOnce(final String prvActId)
  {
    if (prvActId == null || prvActId.isEmpty())
      throw new IllegalArgumentException("prvActId must not be null or empty");

    synchronized (DomatarConfig.class)
    {
      // If already set (env var, system property, or file), do nothing.
      if (getPrvActId() != null)
      {
        LOG.fine("PrvActId already set; setPrvActIdOnce is a no-op");
        return;
      }

      writePrvActIdToFile(prvActId, /* replaceExisting= */ false);
    }
  }

  /**
   * Aligns filesystem {@code PrvActId} with the provider account's act row
   * (login identity). Overwrites a stale file value left by a prior Setup that
   * minted a new fingerprint after a DB restore / container recreate.
   * Does not override an env / system-property pin.
   */
  public static void reconcilePrvActId(final String prvActId)
  {
    if (prvActId == null || prvActId.isEmpty())
      throw new IllegalArgumentException("prvActId must not be null or empty");

    synchronized (DomatarConfig.class)
    {
      final String envOrProp = envOrSystemProperty("DOMATAR_PRV_ACTID");
      if (envOrProp != null && !envOrProp.isEmpty())
      {
        if (!prvActId.equals(envOrProp))
          LOG.warning("reconcilePrvActId: DOMATAR_PRV_ACTID is pinned to "
              + envOrProp + "; not rewriting to " + prvActId);
        return;
      }

      final String current = getPrvActId();
      if (prvActId.equals(current))
        return;

      writePrvActIdToFile(prvActId, /* replaceExisting= */ true);
      LOG.info("PrvActId reconciled to " + prvActId
          + (current != null ? " (was " + current + ")" : ""));
    }
  }

  /** Writes or replaces the PrvActId= line in provider.config.txt on disk. */
  private static void writePrvActIdToFile(final String prvActId,
                                          final boolean replaceExisting)
  {
    final String configPath = providerConfigFilesystemPath();

    try
    {
      final java.io.File file = new java.io.File(configPath);
      file.getParentFile().mkdirs();

      if (replaceExisting && file.exists())
      {
        final String body = Files.readString(file.toPath(), StandardCharsets.UTF_8);
        final StringBuilder out = new StringBuilder();
        boolean replaced = false;

        for (final String line : body.split("\\R", -1))
        {
          if (line.startsWith("PrvActId="))
          {
            if (!replaced)
            {
              out.append("PrvActId=").append(prvActId).append(System.lineSeparator());
              replaced = true;
            }
            // drop duplicate PrvActId lines
          }
          else if (!line.isEmpty() || out.length() > 0)
          {
            out.append(line).append(System.lineSeparator());
          }
        }

        if (!replaced)
          out.append("PrvActId=").append(prvActId).append(System.lineSeparator());

        Files.writeString(file.toPath(), out.toString(), StandardCharsets.UTF_8);
      }
      else
      {
        try (final Writer w = new OutputStreamWriter(
                new FileOutputStream(file, /* append= */ true), StandardCharsets.UTF_8))
        {
          w.write(System.lineSeparator() + "PrvActId=" + prvActId + System.lineSeparator());
        }
      }

      configFile   = null;
      configLoaded = false;

      LOG.info("PrvActId written to " + configPath);
    }
    catch (final IOException e)
    {
      throw new IllegalStateException(
          "writePrvActIdToFile: could not write PrvActId to " + configPath, e);
    }
  }

  // -------------------------------------------------------------------------
  // Internal helpers
  // -------------------------------------------------------------------------

  private static String envOrSystemProperty(final String envName)
  {
    if (envName == null)
      return null;

    String v = System.getenv(envName);

    if (v != null && !v.isEmpty())
      return v;

    final String prop = envName.toLowerCase().replace('_', '.');

    v = System.getProperty(prop);

    return (v != null && !v.isEmpty()) ? v : null;
  }

  /**
   * Ordered appIds from a comma-separated string (trim, skip empty).
   */
  private static List<String> parseCommaAppIds(final String commaList)
  {
    final String[] names = commaList.split(",");

    final List<String> list = new ArrayList<>();

    for (final String p : names)
    {
      final String trimmed = p.trim();

      if (!trimmed.isEmpty())
        list.add(trimmed);
    }

    return Collections.unmodifiableList(list);
  }

  /**
   * Reads default-apps-config.txt from explicit path, filesystem next to
   * provider.config.txt, or classpath; returns parsed appId list or null.
   */
  private static List<String> loadDefaultAppsFromDedicatedFile()
  {
    final String explicit = envOrSystemProperty("DOMATAR_DEFAULT_APPS_CONFIG");

    if (explicit != null && !explicit.isEmpty())
    {
      final String raw = readUtf8File(explicit);

      if (raw != null)
      {
        final List<String> p = parseDefaultAppsFileBody(raw);

        return p.isEmpty() ? null : p;
      }
    }

    final String providerPath = providerConfigFilesystemPath();

    if (providerPath != null)
    {
      final Path parent = Paths.get(providerPath).getParent();

      if (parent != null)
      {
        final String sibling = parent.resolve("default-apps-config.txt").toString();
        final String raw     = readUtf8File(sibling);

        if (raw != null)
        {
          final List<String> p = parseDefaultAppsFileBody(raw);

          if (!p.isEmpty())
            return p;
        }
      }
    }

    try (final InputStream is = DomatarConfig.class.getResourceAsStream("/default-apps-config.txt"))
    {
      if (is != null)
      {
        final String raw = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        final List<String> p = parseDefaultAppsFileBody(raw);

        if (!p.isEmpty())
          return p;
      }
    }
    catch (final IOException ignored) {}

    return null;
  }

  /**
   * Comma-separated appIds per line; {@code #} starts a line comment.
   * Duplicates removed; first occurrence wins.
   */
  private static List<String> parseDefaultAppsFileBody(final String body)
  {
    final LinkedHashSet<String> ordered = new LinkedHashSet<>();

    for (String line : body.split("\\R"))
    {
      final int hash = line.indexOf('#');

      if (hash >= 0)
        line = line.substring(0, hash);

      line = line.trim();

      if (line.isEmpty())
        continue;

      for (final String part : line.split(","))
      {
        final String t = part.trim();

        if (!t.isEmpty())
          ordered.add(t);
      }
    }

    return Collections.unmodifiableList(new ArrayList<>(ordered));
  }

  private static String readUtf8File(final String path)
  {
    try
    {
      return Files.readString(Paths.get(path), StandardCharsets.UTF_8);
    }
    catch (final IOException ignored)
    {
      return null;
    }
  }

  /** Path used for provider.config.txt on disk before classpath fallback. */
  private static String providerConfigFilesystemPath()
  {
    final String p = envOrSystemProperty("DOMATAR_CONFIG_PATH");

    if (p != null && !p.isEmpty())
      return p;

    return "/opt/domatar/provider.config.txt";
  }

  /**
   * Resolves a config value using the precedence order:
   *   env var → system property → provider.config.txt key → dflt
   */
  private static String resolve(final String envName, final String fileKey, final String dflt)
  {
    // 1. Environment variable
    if (envName != null)
    {
      String v = System.getenv(envName);

      if (v != null && !v.isEmpty())
        return v;

      // 2. JVM system property (lowercased with dots, e.g. domatar.hstid)
      final String prop = envName.toLowerCase().replace('_', '.');

      v = System.getProperty(prop);

      if (v != null && !v.isEmpty())
        return v;
    }

    // 3. provider.config.txt
    if (fileKey != null)
    {
      final Properties props = loadConfigFile();

      if (props != null)
      {
        final String v = props.getProperty(fileKey);

        if (v != null && !v.trim().isEmpty())
          return v.trim();
      }
    }

    return dflt;
  }

  /** Loads (and caches) provider.config.txt, returning null on failure. */
  private static Properties loadConfigFile()
  {
    if (configLoaded)
      return configFile;

    synchronized (DomatarConfig.class)
    {
      if (configLoaded)
        return configFile;

      final Properties props = new Properties();
      boolean    loaded = false;

      String configPath = resolve("DOMATAR_CONFIG_PATH", null, null);

      if (configPath == null)
        configPath = "/opt/domatar/provider.config.txt";

      try (final FileInputStream fis = new FileInputStream(configPath))
      {
        props.load(fis);
        loaded = true;
      }
      catch (final IOException ignored) {}

      // Fallback: classpath (useful in development / Docker with bind-mount)
      if (!loaded)
      {
        try (final InputStream is = DomatarConfig.class.getResourceAsStream("/provider.config.txt"))
        {
          if (is != null)
          {
            props.load(is);
            loaded = true;
          }
        }
        catch (final IOException ignored) {}
      }

      configFile  = loaded ? props : null;
      configLoaded = true;
    }

    return configFile;
  }
}
