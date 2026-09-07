/*
 * Copyright (c) 2024 Domatar
 */

package com.domatar.crypto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.domatar.util.JsonMap;

/**
 * Wire shape of {@link Hop} after the Seq-out / ContextId-ActId-in reshape.
 *
 * Seam: {@link ProviderKeyStore#sign} with an ephemeral in-process key
 * ({@link ProviderKeyStore#resetForTesting}; no ProviderKeyPath). Production
 * path is unchanged.
 */
public class HopWireShapeTest
{
  @BeforeAll
  public static void ephemeralProviderKey()
  {
    ProviderKeyStore.resetForTesting();
    ProviderKeyStore.getOrCreate();
  }

  @Test
  public void toMapFromMapRoundTrip()
  {
    final Hop hop = signed("ctx1", "act1");
    final Hop round = Hop.fromMap(hop.toMap());

    assertEquals(hop.contextId, round.contextId);
    assertEquals(hop.actId, round.actId);
    assertEquals(hop.signerPrv, round.signerPrv);
    assertEquals(hop.srcDomId, round.srcDomId);
    assertEquals(hop.dstDomId, round.dstDomId);
    assertEquals(hop.bodyHash, round.bodyHash);
    assertEquals(hop.prevHopHash, round.prevHopHash);
    assertEquals(hop.timestamp, round.timestamp);
    assertEquals(hop.nonce, round.nonce);
    assertEquals(hop.hopSig, round.hopSig);
  }

  @Test
  public void noSeqFieldOnTheWire()
  {
    assertFalse(signed("ctx", "act").toMap().containsKey("Seq"));
    assertFalse(Hop.unsigned("ctx", null, "prv", "s", "d", body(), "").toMap()
        .containsKey("Seq"));
  }

  @Test
  public void absentActIdIsOmittedNotNull()
  {
    final Hop hop = signed("ctx", null);
    final JsonMap map = hop.toMap();

    assertFalse(map.containsKey("ActId"));
    assertNull(Hop.fromMap(map).actId);
  }

  @Test
  public void hopSigCoversContextIdAndActId()
  {
    final Hop hop = signed("ctx-orig", "act-orig");
    final byte[] canonical = CanonicalJson.canonicalizeExcluding(hop.toMap(), "HopSig");
    final byte[] sig = com.domatar.util.Base64Encoder.decode(hop.hopSig);

    assertTrue(KeyOps.verify(ProviderKeyStore.getOrCreate().getPublic(), canonical, sig));

    final JsonMap mutatedCtx = hop.toMap();
    mutatedCtx.put("ContextId", "ctx-tampered");
    final Hop tamperedCtx = Hop.fromMap(mutatedCtx);
    final byte[] canonicalCtx = CanonicalJson.canonicalizeExcluding(tamperedCtx.toMap(), "HopSig");
    assertFalse(KeyOps.verify(ProviderKeyStore.getOrCreate().getPublic(), canonicalCtx, sig));

    final JsonMap mutatedAct = hop.toMap();
    mutatedAct.put("ActId", "act-tampered");
    final Hop tamperedAct = Hop.fromMap(mutatedAct);
    final byte[] canonicalAct = CanonicalJson.canonicalizeExcluding(tamperedAct.toMap(), "HopSig");
    assertFalse(KeyOps.verify(ProviderKeyStore.getOrCreate().getPublic(), canonicalAct, sig));
  }

  @Test
  public void unsignedHopHasNoHopSigKey()
  {
    final Hop hop = Hop.unsigned("ctx", "act", "prv", "s", "d", body(), "");

    assertNull(hop.hopSig);
    assertFalse(hop.toMap().containsKey("HopSig"));
  }

  private static Hop signed(final String contextId, final String actId)
  {
    return Hop.sign(contextId, actId, "testprv",
                    "hst.app.act.src", "hst.app.act.dst", body(), "");
  }

  private static byte[] body()
  {
    return new byte[] { 1, 2, 3, 4 };
  }
}
