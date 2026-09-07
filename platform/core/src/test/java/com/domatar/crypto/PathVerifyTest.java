/*
 * Copyright (c) 2024 Domatar
 */

package com.domatar.crypto;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.security.PublicKey;
import java.security.SecureRandom;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.domatar.core.Trust;
import com.domatar.util.Base64Encoder;
import com.domatar.util.DomId;
import com.domatar.util.DomatarException;
import com.domatar.util.JsonArrayList;
import com.domatar.util.JsonHashMap;
import com.domatar.util.JsonList;
import com.domatar.util.JsonMap;

/**
 * {@link Path#verify} checks (a)-(i) and the append depth cap.
 *
 * Seam: {@link ProviderKeyStore#sign} with an ephemeral in-process key
 * ({@link ProviderKeyStore#resetForTesting}; no ProviderKeyPath). The
 * stub {@link ProviderKeyResolver} returns that public key for
 * {@code testprv}. Production ProviderKeyStore is unchanged.
 */
public class PathVerifyTest
{
  private static final String PRV = "testprv";

  private static final ProviderKeyResolver KEYS = new ProviderKeyResolver()
  {
    @Override
    public PublicKey resolve(final String prvId)
    {
      return PRV.equals(prvId) ? ProviderKeyStore.getOrCreate().getPublic() : null;
    }
  };

  @BeforeAll
  public static void ephemeralProviderKey()
  {
    ProviderKeyStore.resetForTesting();
    ProviderKeyStore.getOrCreate();
  }

  @Test
  public void rootThenAppendVerifies() throws Exception
  {
    final DomId ui  = new DomId("prv", "ui", "act", "uiObj");
    final DomId a   = new DomId("hst", "app", "act", "objA");
    final DomId b   = new DomId("hst", "app", "act", "objB");
    final byte[] body0 = bodyBytes("GetA");
    final byte[] body1 = bodyBytes("GetB");

    final Path rooted = Path.root(ui, a, body0, "act0", PRV);
    final Path path   = rooted.append(a, b, body1, PRV);

    assertEquals(rooted.contextId(), path.last().contextId);
    assertEquals(rooted.contextId(), path.contextId());

    final Verdict v = path.verify(bodyMap("GetB"), KEYS);
    assertEquals(Trust.PATH, v.trust);
    assertEquals(path.contextId(), v.contextId);
  }

  @Test
  public void tamperedBodyFailsBodyHash() throws Exception
  {
    final Path path = Path.root(ui(), dstA(), bodyBytes("GetA"), "act0", PRV);
    final Verdict v = path.verify(bodyMap("TAMPER"), KEYS);

    assertEquals(Trust.NONE, v.trust);
    assertEquals("body hash", v.reason);
  }

  @Test
  public void brokenPrevHopHashFails() throws Exception
  {
    final Path two = twoHop();
    final Hop h0 = hopAt(two, 0);
    final Hop h1 = hopAt(two, 1);
    final Hop broken = new Hop(h1.contextId, null, h1.signerPrv, h1.srcDomId,
                               h1.dstDomId, h1.bodyHash, "not-the-prev",
                               h1.timestamp, h1.nonce, h1.hopSig);
    final Verdict v = pathOf(h0, broken).verify(bodyMap("GetB"), KEYS);

    assertEquals(Trust.NONE, v.trust);
    assertEquals("chain broken at 1", v.reason);
  }

  @Test
  public void nonContiguousDomIdsFail() throws Exception
  {
    final Path rooted = Path.root(ui(), dstA(), bodyBytes("GetA"), "act0", PRV);
    final Hop h0 = hopAt(rooted, 0);
    final Hop h1 = signedHop(h0.contextId, null, PRV,
                             dstB().toString(), dstB().toString(),
                             bodyBytes("GetB"), h0.canonicalHash(),
                             h0.timestamp + 1);
    final Verdict v = pathOf(h0, h1).verify(bodyMap("GetB"), KEYS);

    assertEquals(Trust.NONE, v.trust);
    assertEquals("not contiguous at 1", v.reason);
  }

  @Test
  public void actIdOnLaterHopRejected() throws Exception
  {
    final Path rooted = Path.root(ui(), dstA(), bodyBytes("GetA"), "act0", PRV);
    final Hop h0 = hopAt(rooted, 0);
    final Hop h1 = dummyHop(h0.contextId, "sneaky-act", h0.dstDomId, dstB().toString(),
                            h0.canonicalHash(), h0.timestamp + 1);
    final Verdict v = pathOf(h0, h1).verify(bodyMap("GetB"), KEYS);

    assertEquals(Trust.NONE, v.trust);
    assertEquals("actId at hop 1", v.reason);
  }

  @Test
  public void mixedContextIdRejected() throws Exception
  {
    final Path rooted = Path.root(ui(), dstA(), bodyBytes("GetA"), "act0", PRV);
    final Hop h0 = hopAt(rooted, 0);
    final Hop h1 = dummyHop("other-ctx", null, h0.dstDomId, dstB().toString(),
                            h0.canonicalHash(), h0.timestamp + 1);
    final Verdict v = pathOf(h0, h1).verify(bodyMap("GetB"), KEYS);

    assertEquals(Trust.NONE, v.trust);
    assertEquals("context id mismatch at 1", v.reason);
  }

  @Test
  public void decreasingTimestampRejected() throws Exception
  {
    final Path rooted = Path.root(ui(), dstA(), bodyBytes("GetA"), "act0", PRV);
    final Hop h0 = hopAt(rooted, 0);
    final Hop h1 = signedHop(h0.contextId, null, PRV,
                             h0.dstDomId, dstB().toString(),
                             bodyBytes("GetB"), h0.canonicalHash(),
                             h0.timestamp - 1);
    final Verdict v = pathOf(h0, h1).verify(bodyMap("GetB"), KEYS);

    assertEquals(Trust.NONE, v.trust);
    assertEquals("timestamp order at 1", v.reason);
  }

  @Test
  public void chainOlderThanMaxAgeRejected() throws Exception
  {
    System.setProperty("domatar.max.chain.age.ms", "50");
    try
    {
      final long now = System.currentTimeMillis();
      final Hop h0 = signedHop("ctx", "act0", PRV, ui().toString(), dstA().toString(),
                               bodyBytes("GetA"), "", now - 100);
      final Hop h1 = signedHop("ctx", null, PRV, dstA().toString(), dstB().toString(),
                               bodyBytes("GetB"), h0.canonicalHash(), now);
      final Verdict v = pathOf(h0, h1).verify(bodyMap("GetB"), KEYS);

      assertEquals(Trust.NONE, v.trust);
      assertEquals("chain too old", v.reason);
    }
    finally
    {
      System.clearProperty("domatar.max.chain.age.ms");
    }
  }

  @Test
  public void depthOverCapRejectedOnAppend() throws Exception
  {
    System.setProperty("domatar.max.hop.depth", "2");
    try
    {
      Path path = Path.root(ui(), dstA(), bodyBytes("GetA"), "act0", PRV);
      path = path.append(dstA(), dstB(), bodyBytes("GetB"), PRV);

      final Path frozen = path;
      final DomatarException ex = assertThrows(DomatarException.class,
          () -> frozen.append(dstB(), dstC(), bodyBytes("GetC"), PRV));
      assertEquals("hop depth cap exceeded", ex.getMessage());
    }
    finally
    {
      System.clearProperty("domatar.max.hop.depth");
    }
  }

  @Test
  public void unsignedRootYieldsTrustNone() throws Exception
  {
    final Path path = Path.unsignedRoot(ui(), dstA(), bodyBytes("GetA"), "act0", PRV);
    final Verdict v = path.verify(bodyMap("GetA"), KEYS);

    assertEquals(Trust.NONE, v.trust);
    assertEquals("unsigned hop 0", v.reason);
  }

  @Test
  public void replayedFinalHopRejected() throws Exception
  {
    final Path path = Path.root(ui(), dstA(), bodyBytes("GetA"), "act0", PRV);
    final Verdict first = path.verify(bodyMap("GetA"), KEYS);
    assertEquals(Trust.PATH, first.trust);

    final Verdict second = path.verify(bodyMap("GetA"), KEYS);
    assertEquals(Trust.NONE, second.trust);
    assertEquals("replay", second.reason);
  }

  @Test
  public void verifyNeverReturnsAccount() throws Exception
  {
    final Path path = Path.root(ui(), dstA(), bodyBytes("GetA"), "anActId", PRV);
    final Verdict v = path.verify(bodyMap("GetA"), KEYS);

    assertEquals(Trust.PATH, v.trust);
    assertTrue(v.actId == null);
  }

  @Test
  public void domIdPathIsUiThenDestinations() throws Exception
  {
    final Path path = Path.root(ui(), dstA(), bodyBytes("GetA"), "act0", PRV)
        .append(dstA(), dstB(), bodyBytes("GetB"), PRV);
    final DomId[] ids = path.domIdPath();

    assertEquals(3, ids.length);
    assertEquals(ui().toString(), ids[0].toString());
    assertEquals(dstA().toString(), ids[1].toString());
    assertEquals(dstB().toString(), ids[2].toString());
  }

  @Test
  public void domIdPathReturnsFreshArray() throws Exception
  {
    final Path path = Path.root(ui(), dstA(), bodyBytes("GetA"), "act0", PRV);
    final DomId[] a = path.domIdPath();
    final DomId[] b = path.domIdPath();

    assertNotSame(a, b);
    assertArrayEquals(new String[] { a[0].toString(), a[1].toString() },
                      new String[] { b[0].toString(), b[1].toString() });
    a[0] = dstB();
    assertEquals(ui().toString(), path.domIdPath()[0].toString());
  }

  private static Path twoHop() throws Exception
  {
    return Path.root(ui(), dstA(), bodyBytes("GetA"), "act0", PRV)
        .append(dstA(), dstB(), bodyBytes("GetB"), PRV);
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

  private static Hop dummyHop(final String contextId, final String actId,
                              final String src, final String dst,
                              final String prev, final long ts)
  {
    return new Hop(contextId, actId, PRV, src, dst, "hash", prev, ts, "nonce", "AA");
  }

  private static Hop signedHop(final String contextId, final String actId,
                               final String signerPrv, final String src,
                               final String dst, final byte[] canonicalBody,
                               final String prev, final long ts)
  {
    final String bodyHash = Base64Encoder.encode(KeyOps.sha256(canonicalBody));
    final byte[] nonceBytes = new byte[16];
    new SecureRandom().nextBytes(nonceBytes);
    final String nonce = Base64Encoder.encode(nonceBytes);
    final Hop unsigned = new Hop(contextId, actId, signerPrv, src, dst,
                                 bodyHash, prev, ts, nonce, null);
    final byte[] sig = ProviderKeyStore.sign(
        CanonicalJson.canonicalizeExcluding(unsigned.toMap(), "HopSig"));
    return new Hop(contextId, actId, signerPrv, src, dst, bodyHash, prev, ts,
                   nonce, Base64Encoder.encode(sig));
  }

  private static DomId ui() throws DomatarException
  {
    return new DomId("prv", "ui", "act", "uiObj");
  }

  private static DomId dstA() throws DomatarException
  {
    return new DomId("hst", "app", "act", "objA");
  }

  private static DomId dstB() throws DomatarException
  {
    return new DomId("hst", "app", "act", "objB");
  }

  private static DomId dstC() throws DomatarException
  {
    return new DomId("hst", "app", "act", "objC");
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
