/*
 * Copyright (c) 2024 Domatar
 */

package com.domatar.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

public class TrustContextTest
{
  @Test
  public void accountTrustIsVerified()
  {
    final Context ctx = contextWith(Trust.ACCOUNT);

    assertTrue(ctx.isVerified());
  }

  @Test
  public void pathTrustIsNotVerified()
  {
    final Context ctx = contextWith(Trust.PATH);

    assertFalse(ctx.isVerified());
  }

  @Test
  public void noneTrustIsNotVerified()
  {
    final Context ctx = contextWith(Trust.NONE);

    assertFalse(ctx.isVerified());
  }

  @Test
  public void convenienceConstructorDefaultsToNone()
  {
    final Context ctx = new Context(null, null, null, null, null, null);

    assertFalse(ctx.isVerified());
  }

  private static Context contextWith(final Trust trust)
  {
    return new Context(null, null, null, null, null, trust, null, null);
  }
}
