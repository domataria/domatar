/*
 * Copyright (c) 2024 Domatar
 */

package com.domatar.install;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

public class IconPathsTest
{
  @Test
  public void launcherUsesAppId()
  {
    assertEquals("/domatar/bookstore/icons/app.svg",
        IconPaths.launcher("bookstore"));
  }

  @Test
  public void launcherEmptyUsesDefault()
  {
    assertEquals(IconPaths.DEFAULT_RELATIVE, IconPaths.launcher(null));
    assertEquals(IconPaths.DEFAULT_RELATIVE, IconPaths.launcher(""));
  }

  @Test
  public void clsUsesAppAndClsId()
  {
    assertEquals("/domatar/quippin/icons/cls/quip.svg",
        IconPaths.cls("quippin", "quip"));
  }

  @Test
  public void clsMissingUsesDefault()
  {
    assertEquals(IconPaths.DEFAULT_RELATIVE, IconPaths.cls("quippin", null));
    assertEquals(IconPaths.DEFAULT_RELATIVE, IconPaths.cls(null, "quip"));
    assertEquals(IconPaths.DEFAULT_RELATIVE, IconPaths.cls("", "quip"));
  }

  @Test
  public void defaultCls()
  {
    assertEquals(IconPaths.DEFAULT_RELATIVE, IconPaths.defaultCls());
  }

  @Test
  public void absoluteJoinsSchemeDomainPath()
  {
    assertEquals("http://localhost:8081/domatar/bookstore/icons/app.svg",
        IconPaths.absolute("http", "localhost:8081",
            "/domatar/bookstore/icons/app.svg"));
  }

  @Test
  public void launcherAbsoluteAndClsAbsolute()
  {
    assertEquals("http://localhost:8081/domatar/bookstore/icons/app.svg",
        IconPaths.launcherAbsolute("http", "localhost:8081", "bookstore"));
    assertEquals("http://localhost:8081/domatar/quippin/icons/cls/quip.svg",
        IconPaths.clsAbsolute("http", "localhost:8081", "quippin", "quip"));
  }

  @Test
  public void forInstallChoosesRelativeOrAbsolute()
  {
    assertEquals("/domatar/bookstore/icons/app.svg",
        IconPaths.forInstall(false, "http", "localhost:8081", "bookstore"));
    assertEquals("http://localhost:8081/domatar/bookstore/icons/app.svg",
        IconPaths.forInstall(true, "http", "localhost:8081", "bookstore"));
  }

  @Test
  public void forInstallFrontDoorStripsContext()
  {
    assertEquals(
        "http://domatar.avatarvia.com/bookstore/icons/app.svg",
        IconPaths.forInstall(true, "http",
            "domatar.avatarvia.com", "tomcat2:8080", "bookstore"));
  }

  @Test
  public void browserAbsoluteWireHostKeepsContext()
  {
    assertEquals(
        "http://tomcat2:8080/domatar/bookstore/icons/app.svg",
        IconPaths.browserAbsolute("http", "tomcat2:8080", "tomcat2:8080",
            "/domatar/bookstore/icons/app.svg"));
  }

  @Test
  public void stripContext()
  {
    assertEquals("/bookstore/icons/app.svg",
        IconPaths.stripContext("/domatar/bookstore/icons/app.svg"));
    assertEquals("/", IconPaths.stripContext("/domatar"));
    assertEquals("/other", IconPaths.stripContext("/other"));
  }

  @Test
  public void usesFrontDoorPaths()
  {
    assertTrue(IconPaths.usesFrontDoorPaths(
        "domatar.avatarvia.com", "tomcat2:8080"));
    assertFalse(IconPaths.usesFrontDoorPaths(
        "tomcat2:8080", "tomcat2:8080"));
    assertFalse(IconPaths.usesFrontDoorPaths(null, "tomcat2:8080"));
  }

  @Test
  public void assetOrigin()
  {
    assertEquals("http://localhost:8081",
        IconPaths.assetOrigin("http", "localhost:8081"));
    assertNull(IconPaths.assetOrigin("http", null));
    assertNull(IconPaths.assetOrigin("http", ""));
  }

  @Test
  public void isIconPath()
  {
    assertTrue(IconPaths.isIconPath("/icons/app.svg"));
    assertTrue(IconPaths.isIconPath("/icons"));
    assertTrue(IconPaths.isIconPath("/icons/cls/quip.svg"));
    assertFalse(IconPaths.isIconPath("/desktop.html"));
    assertFalse(IconPaths.isIconPath(null));
  }
}
