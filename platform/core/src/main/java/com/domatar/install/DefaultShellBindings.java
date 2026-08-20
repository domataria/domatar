/*
 * Copyright (c) 2024 Domatar
 */

package com.domatar.install;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import com.domatar.core.DomatarConfig;
import com.domatar.db.ObjDb;
import com.domatar.util.Obj;
import com.domatar.util.DomId;
import com.domatar.util.DomatarException;

/**
 * Stock + provider-config default shell role → AppId bindings
 * (Spec-Mandatory-App-Rewrite.txt PART 10.2; Update Phase 6).
 *
 * <p>Persisted as compact {@code login=app,desktop=app,navigator=app,appstore=app} on the
 * provider-config object ({@link #ATTR_DEFAULT_SHELLS}). Lives in core so
 * {@link UserSubstrateInstall} can resolve bindings without depending on
 * domatar-app.
 */
public final class DefaultShellBindings
{
  public static final String ATTR_DEFAULT_SHELLS = "DefaultShells";
  public static final String PROVIDER_CONFIG_OBJ_ID = "provider-config";

  private DefaultShellBindings() {}

  /** Stock bindings: each role maps to the same-named appId. */
  public static Map<String, String> stock()
  {
    final Map<String, String> m = new LinkedHashMap<>();
    for (final String roleId : UserSubstrateIds.ROLE_IDS)
      m.put(roleId, roleId);
    return Collections.unmodifiableMap(m);
  }

  /**
   * Effective map for new accounts on this provider: provider-config
   * DefaultShells when present, else stock. Missing roles filled from stock.
   */
  public static Map<String, String> resolve(final String prvActId)
      throws DomatarException
  {
    final String raw = readRaw(prvActId);
    if (raw == null || raw.trim().isEmpty())
      return new LinkedHashMap<>(stock());
    return normalize(parse(raw));
  }

  /** Compact form → map (does not fill missing roles). */
  public static Map<String, String> parse(final String compact)
  {
    final Map<String, String> out = new LinkedHashMap<>();
    if (compact == null || compact.trim().isEmpty())
      return out;
    for (final String part : compact.split(","))
    {
      final String t = part.trim();
      if (t.isEmpty())
        continue;
      final int eq = t.indexOf('=');
      if (eq <= 0 || eq >= t.length() - 1)
        continue;
      final String role = t.substring(0, eq).trim();
      final String app  = t.substring(eq + 1).trim();
      if (!role.isEmpty() && !app.isEmpty())
        out.put(role, app);
    }
    return out;
  }

  /** Map → compact {@code role=app,...} in ROLE_IDS order then extras. */
  public static String format(final Map<String, String> m)
  {
    final Map<String, String> norm = normalize(m);
    final StringBuilder sb = new StringBuilder();
    for (final Map.Entry<String, String> e : norm.entrySet())
    {
      if (sb.length() > 0)
        sb.append(',');
      sb.append(e.getKey()).append('=').append(e.getValue());
    }
    return sb.toString();
  }

  /**
   * Ensure all {@link UserSubstrateIds#ROLE_IDS} are present (stock fill);
   * keep first-seen order for extras after the stock roles.
   */
  public static Map<String, String> normalize(final Map<String, String> in)
  {
    final Map<String, String> out = new LinkedHashMap<>(stock());
    if (in != null)
    {
      for (final Map.Entry<String, String> e : in.entrySet())
      {
        if (e.getKey() == null || e.getValue() == null)
          continue;
        final String role = e.getKey().trim();
        final String app  = e.getValue().trim();
        if (role.isEmpty() || app.isEmpty())
          continue;
        out.put(role, app);
      }
    }
    return out;
  }

  static String readRaw(final String prvActId) throws DomatarException
  {
    final Obj o = ObjDb.getObj(providerConfigDomId(prvActId));
    if (o == null || o.attrs == null)
      return null;
    return o.attrs.getAttr(ATTR_DEFAULT_SHELLS);
  }

  public static DomId providerConfigDomId(final String prvActId)
      throws DomatarException
  {
    final String id = (prvActId != null && !prvActId.isEmpty())
                        ? prvActId : DomatarConfig.getPrvActId();
    final String ss = DomId.subHstId("domatar", id);
    return new DomId(ss, "domatar", id, PROVIDER_CONFIG_OBJ_ID);
  }
}
