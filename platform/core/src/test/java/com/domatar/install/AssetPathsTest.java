/*
 * Copyright (c) 2024 Domatar
 */

package com.domatar.install;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

public class AssetPathsTest
{
  @Test
  public void urlJoinsContextAppAndAsset()
  {
    assertEquals("/domatar/bookstore/icons/app.svg",
        AssetPaths.url(null, "/domatar", "bookstore", "icons/app.svg"));
  }

  @Test
  public void urlEmptyContextIsFrontDoor()
  {
    assertEquals("/bookstore/icons/app.svg",
        AssetPaths.url(null, "", "bookstore", "icons/app.svg"));
  }

  @Test
  public void urlNestedImagePath()
  {
    assertEquals("/domatar/bookstore/img/products/hero.png",
        AssetPaths.url(null, "/domatar", "bookstore",
            "img/products/hero.png"));
  }

  @Test
  public void urlAbsoluteRemote()
  {
    assertEquals("http://domatar.avatarvia.com/bookstore/img/a.png",
        AssetPaths.url("http://domatar.avatarvia.com", "",
            "bookstore", "img/a.png"));
  }

  @Test
  public void urlMissingAppUsesDefaultGlyph()
  {
    assertEquals(AssetPaths.DEFAULT_RELATIVE,
        AssetPaths.url(null, "/domatar", null, "icons/app.svg"));
  }

  @Test
  public void launcherAndClsAreAssetConventions()
  {
    assertEquals("/domatar/quippin/icons/app.svg",
        AssetPaths.launcher("quippin"));
    assertEquals("/domatar/quippin/icons/cls/quip.svg",
        AssetPaths.cls("quippin", "quip"));
    assertEquals("/quippin/icons/app.svg",
        AssetPaths.launcher(null, "", "quippin"));
  }

  @Test
  public void contextPathFromHostPair()
  {
    assertEquals("", AssetPaths.contextPath(
        "domatar.avatarvia.com", "tomcat2:8080"));
    assertEquals("/domatar", AssetPaths.contextPath(
        "tomcat2:8080", "tomcat2:8080"));
    assertEquals("/domatar", AssetPaths.contextPath(null, "tomcat2:8080"));
  }

  @Test
  public void normalizeContext()
  {
    assertEquals("", AssetPaths.normalizeContext(""));
    assertEquals("", AssetPaths.normalizeContext("-"));
    assertEquals("/domatar", AssetPaths.normalizeContext("domatar"));
    assertEquals("/domatar", AssetPaths.normalizeContext("/domatar/"));
    assertEquals("/domatar", AssetPaths.normalizeContext(null));
  }

  @Test
  public void isCachedAssetIncludesImages()
  {
    assertTrue(AssetPaths.isCachedAsset("/icons/app.svg"));
    assertTrue(AssetPaths.isCachedAsset("/img/products/hero.png"));
    assertFalse(AssetPaths.isCachedAsset("/bookstore.html"));
  }
}
