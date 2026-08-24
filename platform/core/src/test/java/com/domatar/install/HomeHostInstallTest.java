/*
 * Copyright (c) 2024 Domatar
 */

package com.domatar.install;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

public class HomeHostInstallTest
{
  @Test
  public void usrIdIsAppAtApp()
  {
    assertEquals("quippin@quippin", HomeHostInstall.usrId("quippin"));
    assertEquals("appstore@appstore", HomeHostInstall.usrId("appstore"));
    assertEquals("prv1@prv1", HomeHostInstall.usrId("prv1"));
  }

  @Test
  public void usrIdNullOrEmpty()
  {
    assertNull(HomeHostInstall.usrId(null));
    assertNull(HomeHostInstall.usrId(""));
  }

  @Test
  public void skipDomatarGlobalHomeUser()
  {
    assertTrue(HomeHostInstall.skipHomeUser("domatar"));
    assertTrue(HomeHostInstall.skipHomeUser(null));
    assertTrue(HomeHostInstall.skipHomeUser(""));
    assertFalse(HomeHostInstall.skipHomeUser("quippin"));
    assertFalse(HomeHostInstall.skipHomeUser("login"));
    assertFalse(HomeHostInstall.skipHomeUser("prv1"));
  }
}
