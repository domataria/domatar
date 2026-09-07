/*
 * Copyright (c) 2024 Domatar
 */

package com.domatar.crypto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.security.PublicKey;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.domatar.core.Trust;
import com.domatar.util.DomId;
import com.domatar.util.DomatarException;
import com.domatar.util.JsonArrayList;
import com.domatar.util.JsonHashMap;
import com.domatar.util.JsonList;
import com.domatar.util.JsonMap;

/**
 * Q1 on top of Q2: {@link Provenance#verify} after hop 0 carries ActId.
 *
 * Seam: {@link ProviderKeyStore#resetForTesting()} with a stub
 * {@link ProviderKeyResolver} for {@code testprv} (and {@code prvB} in
 * the A2 mismatch case). Production ProviderKeyStore is unchanged.
 */
public class Q1HopZeroTest
{
  private static final String PRV  = "testprv";
  private static final String PRVA = "prvA";
  private static final String PRVB = "prvB";

  private static final ProviderKeyResolver KEYS = new ProviderKeyResolver()
  {
    @Override
    public PublicKey resolve(final String prvId)
    {
      if (PRV.equals(prvId) || PRVB.equals(prvId))
        return ProviderKeyStore.getOrCreate().getPublic();
      return null;
    }
  };

  @BeforeAll
  public static void ephemeralProviderKey()
  {
    ProviderKeyStore.resetForTesting();
    ProviderKeyStore.getOrCreate();
  }

  @Test
  public void validChainYieldsAccountTrust() throws Exception
  {
    final AccountKeys genesis = AccountKeys.generate();
    final AccountKeys own     = AccountKeys.generate();
    final Binding binding = Binding.sign(genesis, own.rootPubKey,
                                         System.currentTimeMillis());
    final Delegation deleg = Delegation.issue(genesis.actId, own, PRV,
        System.currentTimeMillis() + 3_600_000L);
    final Path path = Path.root(ui(), dstA(), bodyBytes("GetA"),
                                genesis.actId, PRV);

    final Verdict v = Provenance.of(path, deleg, binding)
        .verify(bodyMap("GetA"), KEYS, 1, null);

    assertEquals(Trust.ACCOUNT, v.trust);
    assertEquals(genesis.actId, v.actId);
    assertEquals(path.contextId(), v.contextId);
  }

  @Test
  public void absentActIdYieldsPathTrust() throws Exception
  {
    final Path path = Path.root(ui(), dstA(), bodyBytes("GetA"), null, PRV);

    final Verdict v = Provenance.of(path, null, null)
        .verify(bodyMap("GetA"), KEYS, 1, null);

    assertEquals(Trust.PATH, v.trust);
    assertTrue(v.actId == null);
  }

  @Test
  public void delegationForAnotherProviderRejected() throws Exception
  {
    final AccountKeys genesis = AccountKeys.generate();
    final AccountKeys own     = AccountKeys.generate();
    final Binding binding = Binding.sign(genesis, own.rootPubKey,
                                         System.currentTimeMillis());
    final Delegation deleg = Delegation.issue(genesis.actId, own, PRVA,
        System.currentTimeMillis() + 3_600_000L);
    final Path path = Path.root(ui(), dstA(), bodyBytes("GetA"),
                                genesis.actId, PRVB);

    final Verdict v = Provenance.of(path, deleg, binding)
        .verify(bodyMap("GetA"), KEYS, 1, null);

    assertEquals(Trust.NONE, v.trust);
    assertTrue(v.reason.contains(PRVA), "reason must name the delegation provider");
    assertTrue(v.reason.contains(PRVB), "reason must name the hop-0 signer");
  }

  @Test
  public void bindingActIdMismatchRejected() throws Exception
  {
    final AccountKeys genesis = AccountKeys.generate();
    final AccountKeys other   = AccountKeys.generate();
    final AccountKeys own     = AccountKeys.generate();
    final Binding binding = Binding.sign(other, own.rootPubKey,
                                         System.currentTimeMillis());
    final Delegation deleg = Delegation.issue(other.actId, own, PRV,
        System.currentTimeMillis() + 3_600_000L);
    final Path path = Path.root(ui(), dstA(), bodyBytes("GetA"),
                                genesis.actId, PRV);

    final Verdict v = Provenance.of(path, deleg, binding)
        .verify(bodyMap("GetA"), KEYS, 1, null);

    assertEquals(Trust.NONE, v.trust);
    assertEquals("actId mismatch", v.reason);
  }

  @Test
  public void expiredDelegationRejected() throws Exception
  {
    final AccountKeys genesis = AccountKeys.generate();
    final AccountKeys own     = AccountKeys.generate();
    final Binding binding = Binding.sign(genesis, own.rootPubKey,
                                         System.currentTimeMillis());
    final long notAfter = System.currentTimeMillis() - (6L * 60L * 1000L);
    final Delegation deleg = Delegation.issue(genesis.actId, own, PRV, notAfter);
    final Path path = Path.root(ui(), dstA(), bodyBytes("GetA"),
                                genesis.actId, PRV);

    final Verdict v = Provenance.of(path, deleg, binding)
        .verify(bodyMap("GetA"), KEYS, 1, null);

    assertEquals(Trust.NONE, v.trust);
    assertEquals("delegation expired", v.reason);
  }

  @Test
  public void supersededBindingVersionRejected() throws Exception
  {
    final AccountKeys genesis = AccountKeys.generate();
    final AccountKeys own     = AccountKeys.generate();
    final Binding binding = Binding.sign(genesis, own.rootPubKey, 1L);
    final Delegation deleg = Delegation.issue(genesis.actId, own, PRV,
        System.currentTimeMillis() + 3_600_000L);
    final Path path = Path.root(ui(), dstA(), bodyBytes("GetA"),
                                genesis.actId, PRV);

    final Verdict v = Provenance.of(path, deleg, binding)
        .verify(bodyMap("GetA"), KEYS, 1, Long.valueOf(2L));

    assertEquals(Trust.NONE, v.trust);
    assertEquals("superseded binding", v.reason);
  }

  @Test
  public void tamperedActIdOnHopZeroRejected() throws Exception
  {
    final AccountKeys genesis = AccountKeys.generate();
    final AccountKeys own     = AccountKeys.generate();
    final Binding binding = Binding.sign(genesis, own.rootPubKey,
                                         System.currentTimeMillis());
    final Delegation deleg = Delegation.issue(genesis.actId, own, PRV,
        System.currentTimeMillis() + 3_600_000L);
    final Path path = Path.root(ui(), dstA(), bodyBytes("GetA"),
                                genesis.actId, PRV);
    final Hop h0 = hopAt(path, 0);
    final Hop flipped = new Hop(h0.contextId, "flipped-act-id", h0.signerPrv,
                                h0.srcDomId, h0.dstDomId, h0.bodyHash,
                                h0.prevHopHash, h0.timestamp, h0.nonce,
                                h0.hopSig);

    final Verdict v = Provenance.of(pathOf(flipped), deleg, binding)
        .verify(bodyMap("GetA"), KEYS, 1, null);

    assertEquals(Trust.NONE, v.trust);
    assertEquals("bad hop sig 0", v.reason);
  }

  @Test
  public void brokenChainNeverReachesQ1() throws Exception
  {
    final AccountKeys genesis = AccountKeys.generate();
    final AccountKeys own     = AccountKeys.generate();
    final Binding binding = Binding.sign(genesis, own.rootPubKey,
                                         System.currentTimeMillis());
    final Delegation deleg = Delegation.issue(genesis.actId, own, PRV,
        System.currentTimeMillis() + 3_600_000L);
    final Path path = Path.root(ui(), dstA(), bodyBytes("GetA"),
                                genesis.actId, PRV);

    final Verdict v = Provenance.of(path, deleg, binding)
        .verify(bodyMap("TAMPER"), KEYS, 1, null);

    assertEquals(Trust.NONE, v.trust);
    assertEquals("body hash", v.reason);
  }

  private static Hop hopAt(final Path path, final int i) throws DomatarException
  {
    final JsonList wire = path.toWire();
    return Hop.fromMap((JsonMap) wire.get(i));
  }

  private static Path pathOf(final Hop... hops)
  {
    final JsonList list = new JsonArrayList(hops.length);
    for (final Hop hop : hops)
      list.add(hop.toMap());
    return Path.fromWire(list);
  }

  private static DomId ui() throws DomatarException
  {
    return new DomId("prv", "ui", "act", "uiObj");
  }

  private static DomId dstA() throws DomatarException
  {
    return new DomId("hst", "app", "act", "objA");
  }

  private static JsonMap bodyMap(final String op)
  {
    final JsonMap m = new JsonHashMap();
    m.put("Operation", op);
    return m;
  }

  private static byte[] bodyBytes(final String op)
  {
    return CanonicalJson.canonicalize(bodyMap(op));
  }
}
