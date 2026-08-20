/*
 * Copyright (c) 2024 Domatar
 */

package com.domatar.install;

import com.domatar.app.App;
import com.domatar.app.AppRegistry;
import com.domatar.core.AppConfig;

/**
 * Whether an AppId may be bound to a shell RoleId (KD7 /
 * Update-Mandatory-App-Rewrite.txt Phase 8).
 *
 * <p>When {@link AppConfig} is loadable, the app must list the role in
 * {@code ShellRoles}. When config is missing, only stock self-bindings
 * ({@code login}/{@code desktop}/{@code navigator}/{@code appstore}) are
 * allowed.
 */
public final class ShellRolePolicy
{
  private ShellRolePolicy() {}

  public static boolean allows(final String appId, final String roleId)
  {
    if (appId == null || appId.isEmpty() || roleId == null || roleId.isEmpty())
      return false;

    AppConfig cfg = null;
    final App app = AppRegistry.get(appId);

    if (app != null && app.classLoader != null)
      cfg = AppConfig.load(appId, app.classLoader);

    if (cfg == null)
      cfg = AppConfig.load(appId, ShellRolePolicy.class.getClassLoader());

    if (cfg == null)
      return isStockSelfBinding(appId, roleId);

    return cfg.getShellRoles().contains(roleId);
  }

  /** For unit tests with an explicit ClassLoader (no AppRegistry). */
  public static boolean allows(final String appId, final String roleId,
                               final ClassLoader cl)
  {
    if (appId == null || appId.isEmpty() || roleId == null || roleId.isEmpty())
      return false;

    final AppConfig cfg = (cl != null) ? AppConfig.load(appId, cl) : null;

    if (cfg == null)
      return isStockSelfBinding(appId, roleId);

    return cfg.getShellRoles().contains(roleId);
  }

  private static boolean isStockSelfBinding(final String appId,
                                            final String roleId)
  {
    for (final String r : UserSubstrateIds.ROLE_IDS)
    {
      if (r.equals(roleId) && r.equals(appId))
        return true;
    }
    return false;
  }
}
