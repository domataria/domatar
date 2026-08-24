/*
 * Copyright (c) 2024 Domatar
 */

package com.domatar.util;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

public class DomIdAlphabetTest
{
  @Test
  public void objIdMayEmbedUsrId()
  {
    assertDoesNotThrow(() ->
        new DomId("login", "login", "actIdactIdactIdactIdactIdactIdac",
            "peer-dave@quippin"));
  }

  @Test
  public void hstIdSuffixMayEmbedActIdAndHomeHost()
  {
    assertDoesNotThrow(() ->
        new DomId("desktop-qK3nZ8pMvB2rT9wLxF4hJ7dScA1yE6gU-prv1",
            "desktop", "qK3nZ8pMvB2rT9wLxF4hJ7dScA1yE6gU", "app-desktop"));
  }

  @Test
  public void dotForbiddenInObjId()
  {
    final DomatarException ex = assertThrows(DomatarException.class, () ->
        new DomId("quippin", "quippin", "actIdactIdactIdactIdactIdactIdac",
            "file.name"));
    assertTrue(ex.getMessage().contains("not valid"));
  }

  @Test
  public void printableAsciiExceptDotAllowedInObjId()
  {
    assertDoesNotThrow(() ->
        new DomId("quippin", "quippin", "actIdactIdactIdactIdactIdactIdac",
            "acct:foo~bar_baz"));
  }
}
