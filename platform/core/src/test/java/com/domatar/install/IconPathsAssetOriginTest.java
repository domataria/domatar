/*
 * Copyright (c) 2024 Domatar
 */

package com.domatar.install;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/**
 * Open GetLnks AssetOrigin is IconPaths.assetOrigin(scheme, domain)
 * (Spec-Icons.txt PART 7.3 / Update-Icons.txt Phase 3).
 */
public class IconPathsAssetOriginTest
{
  @Test
  public void assetOriginFormatsSchemeAndDomain()
  {
    assertEquals("http://quippin:8080",
        IconPaths.assetOrigin("http", "quippin:8080"));
    assertEquals("http://tomcat2:8080",
        IconPaths.assetOrigin("http", "tomcat2:8080"));
  }

  @Test
  public void assetOriginOmitsWhenDomainUnset()
  {
    assertNull(IconPaths.assetOrigin("http", null));
    assertNull(IconPaths.assetOrigin("http", ""));
  }

  @Test
  public void assetOriginDefaultsScheme()
  {
    assertEquals("https://example.com",
        IconPaths.assetOrigin(null, "example.com"));
    assertEquals("https://example.com",
        IconPaths.assetOrigin("", "example.com"));
  }

  @Test
  public void openAttrMatchesAssetOriginHelper()
  {
    // ObjImpl.openObj stores this exact string as Attrs.AssetOrigin.
    final String scheme = "http";
    final String domain = "localhost:8081";

    assertEquals(IconPaths.assetOrigin(scheme, domain),
        "http://localhost:8081");
  }
}
