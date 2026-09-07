/*
 * Copyright (c) 2024 Domatar
 */

package com.domatar.crypto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.domatar.util.DomId;

/**
 * Custody invariants for {@link Path} and {@link Provenance}: the hop
 * array is never published, credentials ride once, append does not
 * mutate the source (fan-out safety).
 */
public class ProvenanceCustodyTest
{
  private static final String PRV = "testprv";

  @BeforeAll
  public static void ephemeralProviderKey()
  {
    ProviderKeyStore.resetForTesting();
    ProviderKeyStore.getOrCreate();
  }

  @Test
  public void pathArrayIsNotPublished()
  {
    for (final Field field : Path.class.getDeclaredFields())
    {
      if (field.getType().isArray()
          && field.getType().getComponentType() == Hop.class)
        assertTrue(Modifier.isPrivate(field.getModifiers()), field.getName());
    }

    for (final Method method : Path.class.getDeclaredMethods())
    {
      if (!Modifier.isPublic(method.getModifiers()))
        continue;

      assertTrue(method.getReturnType() != Hop[].class, method.getName());
      assertTrue(method.getReturnType() != Object[].class, method.getName());
    }
  }

  @Test
  public void provenanceFieldsArePrivate()
  {
    for (final Field field : Provenance.class.getDeclaredFields())
      assertTrue(Modifier.isPrivate(field.getModifiers()), field.getName());
  }

  @Test
  public void domIdPathReturnsFreshArrayPerCall() throws Exception
  {
    final Provenance prov = Provenance.of(
        Path.root(ui(), dstA(), bodyBytes("GetA"), "act0", PRV), null, null);
    final DomId[] a = prov.domIdPath();
    final DomId[] b = prov.domIdPath();

    assertNotSame(a, b);
    a[0] = dstB();
    assertEquals(ui().toString(), prov.domIdPath()[0].toString());
  }

  @Test
  public void appendCarriesCredentialsUnchanged() throws Exception
  {
    final AccountKeys genesis = AccountKeys.generate();
    final AccountKeys own     = AccountKeys.generate();
    final Binding binding = Binding.sign(genesis, own.rootPubKey, 1L);
    final Delegation deleg = Delegation.issue(genesis.actId, own, PRV,
                                              System.currentTimeMillis() + 60_000L);
    final Provenance orig = Provenance.of(
        Path.root(ui(), dstA(), bodyBytes("GetA"), genesis.actId, PRV),
        deleg, binding);
    final Provenance next = orig.append(dstA(), dstB(), bodyBytes("GetB"), PRV);

    assertSame(deleg, next.delegation());
    assertSame(binding, next.binding());
  }

  @Test
  public void appendDoesNotMutateTheOriginal() throws Exception
  {
    final Provenance orig = Provenance.of(
        Path.root(ui(), dstA(), bodyBytes("GetA"), "act0", PRV), null, null);
    assertEquals(1, orig.depth());

    orig.append(dstA(), dstB(), bodyBytes("GetB"), PRV);

    assertEquals(1, orig.depth());
  }

  private static DomId ui() throws Exception
  {
    return new DomId("prv", "ui", "act", "uiObj");
  }

  private static DomId dstA() throws Exception
  {
    return new DomId("hst", "app", "act", "objA");
  }

  private static DomId dstB() throws Exception
  {
    return new DomId("hst", "app", "act", "objB");
  }

  private static byte[] bodyBytes(final String op)
  {
    final com.domatar.util.JsonMap m = new com.domatar.util.JsonHashMap();
    m.put("Operation", op);
    return CanonicalJson.canonicalize(m);
  }
}
