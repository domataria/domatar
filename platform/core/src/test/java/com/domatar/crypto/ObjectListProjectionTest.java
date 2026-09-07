/*
 * Copyright (c) 2024 Domatar
 */

package com.domatar.crypto;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.domatar.util.DomId;
import com.domatar.util.DomatarException;
import com.domatar.util.JsonHashMap;
import com.domatar.util.JsonMap;

/**
 * Spec PART 8.5 object-list projection: UI then every destination.
 * Path.domIdPath() is the only remaining lineage after Context.domIdPath
 * was deleted.
 */
public class ObjectListProjectionTest
{
  private static final String PRV = "testprv";

  @BeforeAll
  public static void ephemeralProviderKey()
  {
    ProviderKeyStore.resetForTesting();
    ProviderKeyStore.getOrCreate();
  }

  @Test
  public void projectionIsUiThenEveryDestination() throws Exception
  {
    final Path path = threeHop();
    final DomId[] ids = path.domIdPath();

    assertEquals(4, ids.length);
    assertEquals(ui().toString(), ids[0].toString());
    assertEquals(dstA().toString(), ids[1].toString());
    assertEquals(dstB().toString(), ids[2].toString());
    assertEquals(dstC().toString(), ids[3].toString());
  }

  @Test
  public void uiIdIsNotAPathElement() throws Exception
  {
    final Path path = threeHop();

    assertEquals(3, path.depth());
    assertEquals(4, path.domIdPath().length);
  }

  @Test
  public void freshArrayPerCall() throws Exception
  {
    final Path path = threeHop();
    final DomId[] a = path.domIdPath();
    final DomId[] b = path.domIdPath();

    assertNotSame(a, b);
    assertArrayEquals(
        new String[] { a[0].toString(), a[1].toString(), a[2].toString(), a[3].toString() },
        new String[] { b[0].toString(), b[1].toString(), b[2].toString(), b[3].toString() });
    a[0] = dstC();
    assertEquals(ui().toString(), path.domIdPath()[0].toString());
  }

  @Test
  public void emptyPathProjectsToEmptyArray() throws Exception
  {
    final DomId[] ids = Path.empty().domIdPath();

    assertEquals(0, ids.length);
  }

  private static Path threeHop() throws DomatarException
  {
    return Path.root(ui(), dstA(), bodyBytes("GetA"), "act0", PRV)
        .append(dstA(), dstB(), bodyBytes("GetB"), PRV)
        .append(dstB(), dstC(), bodyBytes("GetC"), PRV);
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
