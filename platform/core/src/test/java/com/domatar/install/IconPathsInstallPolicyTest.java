/*
 * Copyright (c) 2024 Domatar
 */

package com.domatar.install;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Documents the IconPath minting policy used by MarketplaceInstall
 * (Spec-Icons.txt PART 7.2 / Update-Icons.txt Phase 2).
 */
public class IconPathsInstallPolicyTest
{
  @Test
  public void localInstallUsesRelativeLauncher()
  {
    assertEquals("/domatar/bookstore/icons/app.svg",
        IconPaths.forInstall(false, "http", "localhost:8081", "bookstore"));
  }

  @Test
  public void remoteInstallUsesAbsoluteLauncher()
  {
    assertEquals("http://localhost:8081/domatar/bookstore/icons/app.svg",
        IconPaths.forInstall(true, "http", "localhost:8081", "bookstore"));
  }

  @Test
  public void remoteInstallUsesPublicDomainFrontDoor()
  {
    assertEquals("http://domatar.avatarvia.com/bookstore/icons/app.svg",
        IconPaths.forInstall(true, "http",
            "domatar.avatarvia.com", "tomcat2:8080", "bookstore"));
  }
}
