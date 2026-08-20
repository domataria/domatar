/*
 * Copyright (c) 2024 Domatar
 */

package com.domatar.install;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * U8.2 — SetShell must refuse undeclared roles
 * (Update-Mandatory-App-Rewrite.txt Phase 8).
 */
class SetShellRejectsUndeclaredRoleTest
{
  @Test
  void rejectsAppWithoutDeclaredRole()
  {
    final ClassLoader cl = SetShellRejectsUndeclaredRoleTest.class.getClassLoader();

    // fixtureapp declares navigator,desktop — not login
    assertFalse(ShellRolePolicy.allows("fixtureapp", "login", cl));
  }

  @Test
  void rejectsUnknownAppForNonStockBinding()
  {
    final ClassLoader cl = SetShellRejectsUndeclaredRoleTest.class.getClassLoader();

    assertFalse(ShellRolePolicy.allows("no-such-app", "desktop", cl));
  }

  @Test
  void allowsStockSelfBindingWithoutConfig()
  {
    final ClassLoader cl = SetShellRejectsUndeclaredRoleTest.class.getClassLoader();

    assertTrue(ShellRolePolicy.allows("desktop", "desktop", cl));
  }
}
