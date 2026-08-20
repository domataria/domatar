/*
 * Copyright (c) 2024 Domatar
 */

package com.domatar.install;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/**
 * Spec-AppStore PART 9 / Update-AppStore Phase 1 — portable host ids.
 */
public class UserHostIdsTest
{
  private static final String ACT = "Tx6TdyhrbKVzbBMd5UMQcMFNLCJdkyHT";

  @Test
  public void loginReplicaUsesPrvId()
  {
    assertEquals("login-" + ACT + "-prv1",
        UserHostIds.resolveWithExtension("login", ACT, "prv1", null));
  }

  @Test
  public void desktopReplicaUsesPrvId()
  {
    assertEquals("desktop-" + ACT + "-prv2",
        UserHostIds.resolveWithExtension("desktop", ACT, "prv2", "ignored"));
  }

  @Test
  public void appstoreReplicaUsesPrvId()
  {
    assertEquals("appstore-" + ACT + "-prv1",
        UserHostIds.resolveWithExtension("appstore", ACT, "prv1", "ignored"));
  }

  @Test
  public void ordinaryAppPortableWhenExtNull()
  {
    assertEquals("bookstore-" + ACT,
        UserHostIds.resolveWithExtension("bookstore", ACT, "prv1", null));
  }

  @Test
  public void ordinaryAppAcceptsValidExtension()
  {
    assertEquals("bookstore-" + ACT + "-inst1",
        UserHostIds.resolveWithExtension("bookstore", ACT, "prv1", "inst1"));
  }

  @Test
  public void ordinaryAppRejectsExtensionContainingHostSep()
  {
    assertEquals("bookstore-" + ACT,
        UserHostIds.resolveWithExtension("bookstore", ACT, "prv1", "bad-ext"));
  }

  @Test
  public void ordinaryAppRejectsBlankExtension()
  {
    assertEquals("bookstore-" + ACT,
        UserHostIds.resolveWithExtension("bookstore", ACT, "prv1", "   "));
  }

  @Test
  public void nullAppIdReturnsNull()
  {
    assertNull(UserHostIds.resolveWithExtension(null, ACT, "prv1", null));
  }
}
