/*
 * Copyright (c) 2024 Domatar
 */

package com.domatar.act;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import com.domatar.core.DomatarConfig;
import com.domatar.db.ObjDb;
import com.domatar.install.DefaultShellBindings;
import com.domatar.util.IdGen;
import com.domatar.util.Obj;
import com.domatar.util.ObjAttrs;
import com.domatar.util.DomId;
import com.domatar.util.DomatarException;

/**
 * Resolves and persists this provider's default-apps and default-shell
 * policies (Spec-Provider-Customization.txt PART 6;
 * Spec-Mandatory-App-Rewrite.txt PART 10.2).
 *
 * <p>Default apps precedence (first hit wins):
 *   1. {@code DOMATAR_DEFAULT_APPS} env / system property (Locked)
 *   2. {@code provider-config} object on the provider's domatar sub-host
 *   3. {@link DomatarConfig#getDefaultApps()} (file / config / built-in)
 *
 * <p>Default shells: provider-config {@link #ATTR_DEFAULT_SHELLS}, else stock
 * login/desktop/navigator/appstore self-bindings.
 *
 * <p>Lives in domatar-app so domatar-core stays free of app-layer UI policy
 * writes; core reads shells via {@link DefaultShellBindings}.
 */
public final class ProviderDefaults
{
  // Canonical mandatory prefix (install order). domatar is second (its
  // install-order requirement) and is enforced even though it is not a UI toggle.
  public static final List<String> MANDATORY_APPS =
      Collections.unmodifiableList(
          Arrays.asList("navigator", "domatar", "login", "desktop"));

  /** Alias of {@link DefaultShellBindings#ATTR_DEFAULT_SHELLS}. */
  public static final String ATTR_DEFAULT_SHELLS =
      DefaultShellBindings.ATTR_DEFAULT_SHELLS;

  private static final String DEFAULT_APPS_ATTR       = "DefaultApps";
  private static final String VERSION_ATTR            = "Version";

  private ProviderDefaults() {}

  /**
   * DomId of this provider's provider-config object. Uses the argument actId
   * when given, else {@link DomatarConfig#getPrvActId()}.
   */
  public static DomId providerConfigDomId(final String prvActId)
      throws DomatarException
  {
    return DefaultShellBindings.providerConfigDomId(prvActId);
  }

  /** True when DOMATAR_DEFAULT_APPS env/sysprop pins the list. */
  public static boolean isLocked()
  {
    final String v = System.getenv("DOMATAR_DEFAULT_APPS");
    if (v != null && !v.trim().isEmpty())
      return true;
    final String p = System.getProperty("domatar.default.apps");
    return p != null && !p.trim().isEmpty();
  }

  /**
   * Raw ordered list stored on the provider-config object, or null if the
   * object is absent / has no DefaultApps attr. Does NOT normalize.
   */
  public static List<String> readProviderConfigApps(final String prvActId)
      throws DomatarException
  {
    final Obj o = ObjDb.getObj(providerConfigDomId(prvActId));
    if (o == null || o.attrs == null)
      return null;
    final String csv = o.attrs.getAttr(DEFAULT_APPS_ATTR);
    if (csv == null || csv.trim().isEmpty())
      return null;
    return parseCsv(csv);
  }

  /**
   * The effective default app list (precedence + mandatory normalization).
   * {@code prvActId} may be null (uses {@link DomatarConfig#getPrvActId()}).
   */
  public static List<String> resolveDefaultApps(final String prvActId)
      throws DomatarException
  {
    // 1. env/sysprop override (Locked).
    final String env = envDefaultApps();
    if (env != null)
      return normalize(parseCsv(env));
    // 2. provider-config object.
    final List<String> fromObj = readProviderConfigApps(prvActId);
    if (fromObj != null && !fromObj.isEmpty())
      return normalize(fromObj);
    // 3. file / provider.config / built-in (env already handled above).
    return normalize(DomatarConfig.getDefaultApps());
  }

  /**
   * Effective shell role → AppId map for this provider (stock fill).
   * {@code prvActId} may be null.
   */
  public static Map<String, String> getDefaultShellAppIds()
      throws DomatarException
  {
    return getDefaultShellAppIds(null);
  }

  public static Map<String, String> getDefaultShellAppIds(final String prvActId)
      throws DomatarException
  {
    return DefaultShellBindings.resolve(prvActId);
  }

  /**
   * Persist shell role bindings (normalized) alongside existing DefaultApps;
   * create provider-config if missing.
   */
  public static Map<String, String> setDefaultShellAppIds(
      final Map<String, String> m) throws DomatarException
  {
    return setDefaultShellAppIds(null, m);
  }

  public static Map<String, String> setDefaultShellAppIds(
      final String prvActId, final Map<String, String> m)
      throws DomatarException
  {
    final Map<String, String> norm = DefaultShellBindings.normalize(m);
    upsertProviderConfig(prvActId, null, DefaultShellBindings.format(norm));
    return norm;
  }

  /**
   * Clear DefaultShells so resolution falls back to stock. Keeps DefaultApps.
   * Deletes provider-config when neither attr remains.
   */
  public static void clearDefaultShells(final String prvActId)
      throws DomatarException
  {
    clearProviderConfigAttr(prvActId, ATTR_DEFAULT_SHELLS);
  }

  /**
   * Persist the caller's chosen list (normalized) + a fresh Version stamp;
   * create the object if missing, else modify it (preserves DefaultShells).
   * Returns the normalized list actually stored.
   */
  public static List<String> writeProviderConfigApps(final String prvActId,
                                                     final List<String> chosen)
      throws DomatarException
  {
    final List<String> norm  = normalize(chosen);
    final String       csv   = String.join(",", norm);
    upsertProviderConfig(prvActId, csv, null);
    return norm;
  }

  /**
   * Clear DefaultApps so resolution falls back to file/built-in. Keeps
   * DefaultShells. Deletes provider-config when neither attr remains.
   */
  public static void clearDefaultApps(final String prvActId)
      throws DomatarException
  {
    clearProviderConfigAttr(prvActId, DEFAULT_APPS_ATTR);
  }

  /**
   * Delete the provider-config object so resolution falls back to the
   * file/built-in default (and stock shells). No-op if absent.
   */
  public static void deleteProviderConfig(final String prvActId)
      throws DomatarException
  {
    if (ObjDb.getObj(providerConfigDomId(prvActId)) != null)
      ObjDb.deleteObj(providerConfigDomId(prvActId));
  }

  /**
   * Mandatory prefix ++ list, dedupe keeping first occurrence.
   * Never throws for a missing mandatory app; injects it.
   */
  public static List<String> normalize(final List<String> in)
  {
    final LinkedHashSet<String> out = new LinkedHashSet<>(MANDATORY_APPS);
    if (in != null)
    {
      for (final String a : in)
      {
        if (a == null)
          continue;
        final String t = a.trim();
        if (!t.isEmpty())
          out.add(t);
      }
    }
    return new ArrayList<>(out);
  }

  // -------------------------------------------------------------------------

  /**
   * Write DefaultApps and/or DefaultShells; null means leave that attr as-is
   * (or omit when creating). Always refreshes Version.
   */
  private static void upsertProviderConfig(final String prvActId,
                                           final String defaultAppsCsv,
                                           final String defaultShellsCompact)
      throws DomatarException
  {
    final DomId id  = providerConfigDomId(prvActId);
    final Obj   cur = ObjDb.getObj(id);
    final ObjAttrs attrs = (cur != null && cur.attrs != null)
        ? new ObjAttrs(cur.attrs) : new ObjAttrs();

    if (defaultAppsCsv != null)
      attrs.addAttr(DEFAULT_APPS_ATTR, defaultAppsCsv);
    if (defaultShellsCompact != null)
      attrs.addAttr(ATTR_DEFAULT_SHELLS, defaultShellsCompact);
    attrs.addAttr(VERSION_ATTR, IdGen.getCurTimeBase64());

    if (cur == null)
      ObjDb.addObj(new Obj(id, "domatar", "providerConfig",
          "Provider configuration", "Site customization for this provider",
          attrs));
    else
      ObjDb.modifyObj(new Obj(id, "domatar", "providerConfig",
          "Provider configuration", "Site customization for this provider",
          attrs));
  }

  private static void clearProviderConfigAttr(final String prvActId,
                                              final String attrKey)
      throws DomatarException
  {
    final DomId id  = providerConfigDomId(prvActId);
    final Obj   cur = ObjDb.getObj(id);
    if (cur == null)
      return;

    final ObjAttrs attrs = (cur.attrs != null)
        ? new ObjAttrs(cur.attrs) : new ObjAttrs();
    attrs.toMap().remove(attrKey);

    final String apps   = attrs.getAttr(DEFAULT_APPS_ATTR);
    final String shells = attrs.getAttr(ATTR_DEFAULT_SHELLS);
    final boolean appsEmpty =
        apps == null || apps.trim().isEmpty();
    final boolean shellsEmpty =
        shells == null || shells.trim().isEmpty();

    if (appsEmpty && shellsEmpty)
    {
      ObjDb.deleteObj(id);
      return;
    }

    attrs.addAttr(VERSION_ATTR, IdGen.getCurTimeBase64());
    ObjDb.modifyObj(new Obj(id, "domatar", "providerConfig",
        "Provider configuration", "Site customization for this provider",
        attrs));
  }

  private static String envDefaultApps()
  {
    final String v = System.getenv("DOMATAR_DEFAULT_APPS");
    if (v != null && !v.trim().isEmpty())
      return v;
    final String p = System.getProperty("domatar.default.apps");
    if (p != null && !p.trim().isEmpty())
      return p;
    return null;
  }

  private static List<String> parseCsv(final String commaList)
  {
    final String[] names = commaList.split(",");
    final List<String> list = new ArrayList<>();
    for (final String part : names)
    {
      final String trimmed = part.trim();
      if (!trimmed.isEmpty())
        list.add(trimmed);
    }
    return list;
  }
}
