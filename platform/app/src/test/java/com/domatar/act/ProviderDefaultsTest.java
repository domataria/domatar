/*
 * Copyright (c) 2024 Domatar
 */

package com.domatar.act;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Pure-logic tests for {@link ProviderDefaults#normalize} (no DB).
 */
class ProviderDefaultsTest
{
  @Test
  void normalize_appendsAfterMandatoryPrefix()
  {
    final List<String> out = ProviderDefaults.normalize(Arrays.asList("quippin"));
    assertEquals(
        Arrays.asList("navigator", "domatar", "login", "desktop", "quippin"),
        out);
  }

  @Test
  void normalize_floatsMandatoryToFrontAndDedupes()
  {
    final List<String> out =
        ProviderDefaults.normalize(Arrays.asList("login", "quippin", "desktop"));
    assertEquals(
        Arrays.asList("navigator", "domatar", "login", "desktop", "quippin"),
        out);
  }

  @Test
  void normalize_nullEqualsMandatoryOnly()
  {
    final List<String> out = ProviderDefaults.normalize(null);
    assertEquals(ProviderDefaults.MANDATORY_APPS, out);
  }

  @Test
  void normalize_dedupesDuplicateExtras()
  {
    final List<String> out =
        ProviderDefaults.normalize(Arrays.asList("money", "money"));
    assertEquals(1, out.stream().filter("money"::equals).count());
    assertTrue(out.contains("money"));
    assertEquals(
        Arrays.asList("navigator", "domatar", "login", "desktop", "money"),
        out);
  }

  @Test
  void isLocked_respectsSystemProperty()
  {
    final String key = "domatar.default.apps";
    final String prev = System.getProperty(key);
    try
    {
      System.clearProperty(key);
      // May still be locked if the env var is set in the test JVM.
      final boolean envLocked = System.getenv("DOMATAR_DEFAULT_APPS") != null
          && !System.getenv("DOMATAR_DEFAULT_APPS").trim().isEmpty();
      if (!envLocked)
        assertEquals(false, ProviderDefaults.isLocked());

      System.setProperty(key, "navigator,login,desktop,quippin");
      assertEquals(true, ProviderDefaults.isLocked());
    }
    finally
    {
      if (prev == null)
        System.clearProperty(key);
      else
        System.setProperty(key, prev);
    }
  }
}
