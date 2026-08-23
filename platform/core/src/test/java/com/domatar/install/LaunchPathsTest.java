/*
 * Copyright (c) 2024 Domatar
 */

package com.domatar.install;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

public class LaunchPathsTest
{
  @Test
  public void relativeUsesAppIdHtml()
  {
    assertEquals("/domatar/bookstore/bookstore.html",
        LaunchPaths.relative("bookstore"));
  }

  @Test
  public void forShellLoginOpensAccountNotSignIn()
  {
    assertEquals("/domatar/login/account.html",
        LaunchPaths.forShell("login", "login"));
    assertEquals("/domatar/desktop/desktop.html",
        LaunchPaths.forShell("desktop", "desktop"));
    assertEquals("/domatar/appstore/appstore.html",
        LaunchPaths.forShell("appstore", "appstore"));
    assertEquals("/domatar/custom/custom.html",
        LaunchPaths.forShell("login", "custom"));
  }

  @Test
  public void absoluteIncludesSchemeAndDomain()
  {
    assertEquals("http://tomcat2:8080/domatar/bookstore/bookstore.html",
        LaunchPaths.absolute("http", "tomcat2:8080", "bookstore"));
  }

  @Test
  public void forInstallChoosesRelativeOrAbsolute()
  {
    assertEquals("/domatar/money/money.html",
        LaunchPaths.forInstall(false, "http", "quippin:8080", "money"));
    assertEquals("/domatar/money/money.html",
        LaunchPaths.forInstall(true, "http", "tomcat2:8080", "money"));
  }

  @Test
  public void forInstallFrontDoorStripsContext()
  {
    assertEquals("http://domatar.avatarvia.com/bookstore/bookstore.html",
        LaunchPaths.forInstall(true, "http",
            "domatar.avatarvia.com", "tomcat2:8080", "bookstore"));
  }

  @Test
  public void forInstallRemoteUsesBrowserOrigin()
  {
    assertEquals("http://localhost:9080/domatar/quippin/news.html",
        LaunchPaths.forInstall(true, "http", null, "http://localhost:9080",
            "domatar.quippin.com", "quippin:8080", "quippin"));
  }

  @Test
  public void forInstallExplicitAppUrl()
  {
    assertEquals("https://quippin.example.com/quippin/news.html",
        LaunchPaths.forInstall(true, "http", "https://quippin.example.com",
            "http://localhost:9080", "domatar.quippin.com", "quippin:8080",
            "quippin"));
  }

  @Test
  public void quippinLaunchPageIsNewsHtml()
  {
    assertEquals("news.html", LaunchPaths.launchPage("quippin"));
    assertEquals("/domatar/quippin/news.html", LaunchPaths.relative("quippin"));
  }

  @Test
  public void iconPath()
  {
    assertEquals("/domatar/bookstore/icons/app.svg",
        LaunchPaths.icon("bookstore"));
  }

  @Test
  public void iconEmptyUsesDefault()
  {
    assertEquals(AssetPaths.DEFAULT_RELATIVE, LaunchPaths.icon(null));
    assertEquals(AssetPaths.DEFAULT_RELATIVE, LaunchPaths.icon(""));
  }
}
