/*
 * Copyright (c) 2024 Domatar
 */

package com.domatar.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.domatar.install.ShellRolePolicy;

/**
 * U8.1 — AppConfig.ShellRoles parsing (Update-Mandatory-App-Rewrite Phase 8).
 */
class AppConfigShellRolesTest
{
  @Test
  void getShellRoles_parsesCommaList()
  {
    final AppConfig cfg = AppConfig.load("fixtureapp",
        AppConfigShellRolesTest.class.getClassLoader());

    assertTrue(cfg != null);
    final List<String> roles = cfg.getShellRoles();

    assertEquals(2, roles.size());
    assertTrue(roles.contains("navigator"));
    assertTrue(roles.contains("desktop"));
  }

  @Test
  void getShellRoles_emptyWhenAbsent()
  {
    final AppConfig cfg = AppConfig.load("fixtureapp_noroles",
        AppConfigShellRolesTest.class.getClassLoader());

    assertTrue(cfg != null);
    assertTrue(cfg.getShellRoles().isEmpty());
  }

  @Test
  void shellRolePolicy_allowsDeclaredRole()
  {
    final ClassLoader cl = AppConfigShellRolesTest.class.getClassLoader();

    assertTrue(ShellRolePolicy.allows("fixtureapp", "navigator", cl));
    assertTrue(ShellRolePolicy.allows("fixtureapp", "desktop", cl));
    assertFalse(ShellRolePolicy.allows("fixtureapp", "login", cl));
  }

  @Test
  void shellRolePolicy_nullConfig_allowsStockSelfOnly()
  {
    final ClassLoader cl = AppConfigShellRolesTest.class.getClassLoader();

    assertTrue(ShellRolePolicy.allows("login", "login", cl));
    assertTrue(ShellRolePolicy.allows("appstore", "appstore", cl));
    assertFalse(ShellRolePolicy.allows("login", "desktop", cl));
    assertFalse(ShellRolePolicy.allows("missingapp", "navigator", cl));
  }
}
