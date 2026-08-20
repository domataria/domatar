/*
 * Copyright (c) 2024 Domatar
 */

package com.domatar.install;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import com.domatar.util.DomId;

/**
 * Update-Mandatory-App-Rewrite.txt Phase 1 / TASK 1.1.
 */
public class UserSubstrateIdsTest
{
  private static final String ACT = "Tx6TdyhrbKVzbBMd5UMQcMFNLCJdkyHT";

  @Test
  public void hstIdUsesLocalSubHstId()
  {
    // Qualified form matches DomId.subHstId; after addHst, localSubHstId agrees.
    assertEquals(DomId.subHstId("domatar", ACT, "prv1"),
        UserSubstrateIds.hstId(ACT, "prv1"));
    assertEquals("domatar-" + ACT + "-prv1", UserSubstrateIds.hstId(ACT, "prv1"));
  }

  @Test
  public void shellRowObjIdFormat() throws Exception
  {
    assertEquals("shell-prv1-desktop",
        UserSubstrateIds.shellRow(ACT, "prv1", "prv1", "desktop").objId);
    assertEquals("shell-prv2-navigator",
        UserSubstrateIds.shellRow(ACT, "prv1", "prv2", "navigator").objId);
  }

  @Test
  public void roleIdsStockFour()
  {
    assertArrayEquals(
        new String[] { "login", "desktop", "navigator", "appstore" },
        UserSubstrateIds.ROLE_IDS);
  }

  @Test
  public void userAppRowObjIdFormat() throws Exception
  {
    assertEquals("app-bookstore",
        UserSubstrateIds.userAppRow(ACT, "prv1", "bookstore").objId);
  }
}
