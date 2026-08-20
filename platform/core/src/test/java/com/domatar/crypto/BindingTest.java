/*
 * Copyright (c) 2024 Domatar
 */

package com.domatar.crypto;

import com.domatar.util.Base64Encoder;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link Binding} (Spec-OwnIds.txt PART 6 / Update-OwnIds.txt Phase 1).
 */
class BindingTest
{
  @Test
  void sign_then_verify_true()
  {
    final AccountKeys genesis = AccountKeys.generate();
    final AccountKeys own     = AccountKeys.generate();
    final long        version = System.currentTimeMillis();

    final Binding b = Binding.sign(genesis, own.rootPubKey, version);

    assertTrue(b.verify(), "Freshly signed binding must verify");
    assertTrue(b.verify(1), "verify(1) must succeed for a v1 binding");
    assertFalse(b.verify(2), "verify(2) must fail (unsupported FpVersion)");
    assertEquals(genesis.actId, b.actId);
    assertEquals(AccountKeys.deriveOwnId(own.rootPubKey), b.ownId);
    assertEquals(version, b.version);
  }

  @Test
  void verify_failsOnTamperedOwnPubKey()
  {
    final AccountKeys genesis = AccountKeys.generate();
    final AccountKeys own     = AccountKeys.generate();
    final Binding     good    = Binding.sign(genesis, own.rootPubKey, 1L);

    final AccountKeys other = AccountKeys.generate();
    // Rebuild with swapped OwnPubKey but same GenesisSig (via map mutation).
    final com.domatar.util.JsonMap m = good.toMap();
    m.put("OwnPubKey", Base64Encoder.encode(other.rootPubKey));
    m.put("OwnId", AccountKeys.deriveOwnId(other.rootPubKey));

    final Binding forged = Binding.fromMap(m);
    assertFalse(forged.verify(), "Tampered OwnPubKey must fail GenesisSig check");
  }

  @Test
  void verify_failsOnTamperedVersion()
  {
    final AccountKeys genesis = AccountKeys.generate();
    final AccountKeys own     = AccountKeys.generate();
    final Binding     good    = Binding.sign(genesis, own.rootPubKey, 100L);

    final com.domatar.util.JsonMap m = good.toMap();
    m.put("Version", 999L);
    final Binding forged = Binding.fromMap(m);

    assertFalse(forged.verify(), "Tampered Version must fail GenesisSig check");
  }

  @Test
  void verify_failsWhenActIdDoesNotMatchGenesisKey()
  {
    final AccountKeys genesisA = AccountKeys.generate();
    final AccountKeys genesisB = AccountKeys.generate();
    final AccountKeys own      = AccountKeys.generate();

    final Binding good = Binding.sign(genesisA, own.rootPubKey, 1L);

    // Claim genesisB's actId while carrying genesisA's pub key + sig.
    final com.domatar.util.JsonMap m = good.toMap();
    m.put("ActId", genesisB.actId);
    final Binding forged = Binding.fromMap(m);

    assertFalse(forged.verify(),
        "Binding claiming actId of key B but signed under key A must fail");
  }

  @Test
  void toJson_fromJson_roundTrip() throws Exception
  {
    final AccountKeys genesis = AccountKeys.generate();
    final AccountKeys own     = AccountKeys.generate();
    final Binding     original = Binding.sign(genesis, own.rootPubKey,
                                              System.currentTimeMillis());

    final Binding restored = Binding.fromJson(original.toJson());

    assertEquals(original.actId, restored.actId);
    assertEquals(original.genesisPubKeyB64, restored.genesisPubKeyB64);
    assertEquals(original.ownId, restored.ownId);
    assertEquals(original.ownPubKeyB64, restored.ownPubKeyB64);
    assertEquals(original.version, restored.version);
    assertEquals(original.notBefore, restored.notBefore);
    assertEquals(original.genesisSig, restored.genesisSig);
    assertTrue(restored.verify(), "Round-tripped binding must still verify");
  }
}
