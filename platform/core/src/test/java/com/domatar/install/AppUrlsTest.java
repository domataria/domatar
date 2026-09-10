/*
 * Copyright (c) 2024 Domatar
 */

package com.domatar.install;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

public class AppUrlsTest
{
  @Test
  public void explicitAppUrlWins()
  {
    assertEquals("http://localhost:9080/domatar",
        AppUrls.resolve("http://localhost:9080/domatar", "http",
            "http://ignored:9", "domatar.quippin.com", "quippin:8080"));
  }

  @Test
  public void inheritBrowserOriginAppendsWarContext()
  {
    assertEquals("http://localhost:9080/domatar",
        AppUrls.inheritProviderBase("http", "http://localhost:9080",
            "domatar.quippin.com", "quippin:8080"));
  }

  @Test
  public void inheritBrowserOriginKeepsExplicitPath()
  {
    assertEquals("http://localhost:9080/domatar",
        AppUrls.inheritProviderBase("http", "http://localhost:9080/domatar",
            "domatar.quippin.com", "quippin:8080"));
  }

  @Test
  public void inheritPublicDomainFrontDoor()
  {
    assertEquals("http://domatar.quippin.com",
        AppUrls.inheritProviderBase("http", null,
            "domatar.quippin.com", "quippin:8080"));
  }

  @Test
  public void inheritWireDomainSkipsDockerHost()
  {
    assertNull(AppUrls.inheritProviderBase("http", null, null, "quippin:8080"));
  }

  @Test
  public void explicitDockerAppUrlDoesNotWin()
  {
    assertNull(AppUrls.resolve("http://quippin:8080/domatar", "http",
        null, null, "quippin:8080"));
  }

  @Test
  public void browserReachableLocalhostAndDottedHosts()
  {
    assertTrue(AppUrls.isBrowserReachable("http://localhost:9080/domatar"));
    assertTrue(AppUrls.isBrowserReachable("127.0.0.1"));
    assertTrue(AppUrls.isBrowserReachable("domatar.quippin.com"));
    assertFalse(AppUrls.isBrowserReachable("quippin:8080"));
    assertFalse(AppUrls.isBrowserReachable("http://tomcat2:8080/domatar"));
  }

  @Test
  public void inheritBlankIsNull()
  {
    assertNull(AppUrls.inheritProviderBase("http", null, null, null));
  }

  @Test
  public void launchAndIconFromBase()
  {
    assertEquals(
        "http://localhost:9080/domatar/quippin/quippin.html",
        AppUrls.launchPath("http://localhost:9080/domatar", "quippin"));
    assertEquals(
        "http://localhost:9080/domatar/quippin/icons/app.svg",
        AppUrls.iconPath("http://localhost:9080/domatar", "quippin"));
  }

  @Test
  public void relativeWhenBaseEmpty()
  {
    assertEquals("/domatar/quippin/quippin.html",
        AppUrls.launchPath(null, "quippin"));
    assertEquals("/domatar/quippin/news.html",
        AppUrls.launchPath(null, "quippin", "news.html"));
    assertEquals("/domatar/quippin/icons/app.svg",
        AppUrls.iconPath("", "quippin"));
  }

  @Test
  public void replaceLaunchAssetKeepsDirectory()
  {
    assertEquals("/domatar/quippin/news.html",
        AppUrls.replaceLaunchAsset("/domatar/quippin/quippin.html",
            "news.html"));
    assertEquals(
        "http://localhost:9080/domatar/quippin/news.html",
        AppUrls.replaceLaunchAsset(
            "http://localhost:9080/domatar/quippin/quippin.html",
            "news.html"));
    assertEquals("/domatar/quippin/news.html?x=1",
        AppUrls.replaceLaunchAsset("/domatar/quippin/quippin.html?x=1",
            "news.html"));
    assertNull(AppUrls.replaceLaunchAsset(null, "news.html"));
    assertEquals("/domatar/quippin/quippin.html",
        AppUrls.replaceLaunchAsset("/domatar/quippin/quippin.html", ""));
  }

  @Test
  public void hostOnlyGetsScheme()
  {
    assertEquals("http://localhost:9080",
        AppUrls.withScheme("http", "localhost:9080"));
  }
}
