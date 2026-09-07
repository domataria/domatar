/*
 * Copyright (c) 2024 Domatar
 */

package com.domatar.crypto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.domatar.util.DomId;
import com.domatar.util.DomatarException;
import com.domatar.util.JsonHashMap;
import com.domatar.util.JsonMap;

/**
 * {@link SecWire} Ver-first envelope beside the message.
 */
public class SecWireTest
{
  private static final String PRV = "testprv";

  @BeforeAll
  public static void ephemeralProviderKey()
  {
    ProviderKeyStore.resetForTesting();
    ProviderKeyStore.getOrCreate();
  }

  @Test
  public void encodeDecodeRoundTrip() throws Exception
  {
    final AccountKeys genesis = AccountKeys.generate();
    final AccountKeys own     = AccountKeys.generate();
    final Binding binding = Binding.sign(genesis, own.rootPubKey,
                                         System.currentTimeMillis());
    final Delegation deleg = Delegation.issue(genesis.actId, own, PRV,
        System.currentTimeMillis() + 3_600_000L);
    final Path path = Path.root(ui(), dstA(), bodyBytes("GetA"),
                                genesis.actId, PRV)
        .append(dstA(), dstB(), bodyBytes("GetB"), PRV);
    final Provenance orig = Provenance.of(path, deleg, binding);

    final Provenance decoded = SecWire.decode(SecWire.encode(orig));

    assertEquals(2, decoded.depth());
    assertEquals(path.contextId(), decoded.contextId());
    assertEquals(genesis.actId, decoded.hop0ActId());
    assertEquals(deleg.actId, decoded.delegation().actId);
    assertEquals(deleg.prvId, decoded.delegation().prvId);
    assertEquals(binding.actId, decoded.binding().actId);
    assertEquals(binding.ownId, decoded.binding().ownId);
    assertTrue(decoded.hasCredentials());
  }

  @Test
  public void verIsFirstField() throws Exception
  {
    final Path path = Path.root(ui(), dstA(), bodyBytes("GetA"), null, PRV);
    final String json = SecWire.encode(Provenance.of(path, null, null));
    final String trimmed = json.trim();

    assertTrue(trimmed.startsWith("{"), json);
    assertTrue(trimmed.substring(1).trim().startsWith("\"Ver\""), json);
  }

  @Test
  public void missingVerRejected() throws Exception
  {
    final Path path = Path.root(ui(), dstA(), bodyBytes("GetA"), null, PRV);
    final String json = SecWire.encode(Provenance.of(path, null, null))
        .replaceFirst("\"Ver\"\\s*:\\s*1\\s*,", "");

    final DomatarException ex = assertThrows(DomatarException.class,
        () -> SecWire.decode(json));
    assertEquals("Sec: no Ver", ex.getMessage());
  }

  @Test
  public void unsupportedVerRejected() throws Exception
  {
    final Path path = Path.root(ui(), dstA(), bodyBytes("GetA"), null, PRV);
    final String json = SecWire.encode(Provenance.of(path, null, null))
        .replaceFirst("\"Ver\"\\s*:\\s*1", "\"Ver\" : 2");

    final DomatarException ex = assertThrows(DomatarException.class,
        () -> SecWire.decode(json));
    assertEquals("Sec: unsupported Ver 2", ex.getMessage());
    assertTrue(ex.getMessage().contains("2"));
  }

  @Test
  public void missingPathRejected()
  {
    final DomatarException ex = assertThrows(DomatarException.class,
        () -> SecWire.decode("{ \"Ver\" : 1 }"));
    assertEquals("Sec: no Path", ex.getMessage());
  }

  @Test
  public void credentialsOmittedWhenAbsent() throws Exception
  {
    final Path path = Path.root(ui(), dstA(), bodyBytes("GetA"), null, PRV);
    final String json = SecWire.encode(Provenance.of(path, null, null));

    assertFalse(json.contains("Delegation"), json);
    assertFalse(json.contains("Binding"), json);

    final Provenance decoded = SecWire.decode(json);
    assertFalse(decoded.hasCredentials());
    assertTrue(decoded.hop0ActId() == null);
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
