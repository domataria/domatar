/*
 * Copyright (c) 2024 Domatar
 */

package com.domatar.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Replica-host parse tests (Spec-ActId-Versioning.txt PART 7 / KD7).
 */
class DomIdReplicaParseTest
{
  private static final String DAVIDB_ACT =
      "Tx6TdyhrbKVzbBMd5UMQcMFNLCJdkyHT";

  @Test
  void replicaParse_roundTripsV1Host()
  {
    final String hst = DomId.subHstId("desktop", DAVIDB_ACT, "prv1");

    assertEquals(DAVIDB_ACT, DomId.replicaActId(hst));
    assertEquals("prv1", DomId.replicaPrvId(hst));
  }

  @Test
  void splitReplicaHst_isNotFixedWidth()
  {
    // 40-char synthetic middle segment (not a v1 fingerprint shape).
    final String longAct = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ0123";
    assertEquals(40, longAct.length());
    assertFalse(DomId.isFingerprintActId(longAct),
        "40-char id must not pass the v1 shape gate");

    final String hst = "desktop-" + longAct + "-prv2";
    final String[] parts = DomId.splitReplicaHst(hst);

    assertNotNull(parts, "structural split must not assume actId width 32");
    assertEquals("desktop", parts[0]);
    assertEquals(longAct, parts[1]);
    assertEquals("prv2", parts[2]);

    // Public parsers still gate on the v1 shape.
    assertNull(DomId.replicaActId(hst));
    assertNull(DomId.replicaPrvId(hst));
  }
}
