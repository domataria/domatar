/*
 * Copyright (c) 2024 Domatar
 */

package com.domatar.act;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SignupAppIdTest
{
  @Test
  void shellsAndPlatformAreForbiddenIdentityApps()
  {
    assertTrue(SignupAppId.isShellAppId("login"));
    assertTrue(SignupAppId.isShellAppId("desktop"));
    assertTrue(SignupAppId.isShellAppId("navigator"));
    assertTrue(SignupAppId.isShellAppId("appstore"));
    assertFalse(SignupAppId.isShellAppId("quippin"));
    assertFalse(SignupAppId.isShellAppId("bookstore"));
    assertTrue(SignupAppId.isForbiddenIdentityAppId("domatar"));
    assertFalse(SignupAppId.isForbiddenIdentityAppId("quippin"));
  }

  @Test
  void rejectSignup_requiresAppId()
  {
    assertEquals("AppId is required", SignupAppId.rejectSignup(null));
    assertEquals("AppId is required", SignupAppId.rejectSignup(""));
    assertEquals("AppId is required", SignupAppId.rejectSignup("  "));
  }

  @Test
  void rejectSignup_rejectsShellsWithoutNeedingRegistry()
  {
    final String err = SignupAppId.rejectSignup("login");
    assertNotNull(err);
    assertTrue(err.contains("login"));
    assertTrue(err.contains("not a valid identity app"));
    assertNotNull(SignupAppId.rejectSignup("appstore"));
    assertNotNull(SignupAppId.rejectSignup("domatar"));
  }
}
