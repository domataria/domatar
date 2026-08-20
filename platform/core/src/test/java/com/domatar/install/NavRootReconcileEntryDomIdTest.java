/*
 * Copyright (c) 2024 Domatar
 */

package com.domatar.install;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import com.domatar.util.DomId;
import com.domatar.util.DomatarException;

/**
 * Update-Mandatory-App-Rewrite.txt Phase 3 / U3.1.
 */
public class NavRootReconcileEntryDomIdTest
{
  private static final String ACT = "Tx6TdyhrbKVzbBMd5UMQcMFNLCJdkyHT";

  @Test
  public void entryDomIdAssembly() throws Exception
  {
    final DomId id = NavRootReconcile.entryDomId(ACT, "bookstore",
        "bookstore-" + ACT);

    assertEquals("bookstore-" + ACT, id.hstId);
    assertEquals("bookstore", id.appId);
    assertEquals(ACT, id.actId);
    assertEquals("app-bookstore", id.objId);
  }

  @Test
  public void entryDomIdRejectsMissingAppHstId()
  {
    assertThrows(DomatarException.class,
        () -> NavRootReconcile.entryDomId(ACT, "bookstore", null));
  }
}
