/*
 * Copyright (c) 2024 Domatar
 */

package com.domatar.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

public class ClsResolverPolicyTest
{
  @Test
  public void policyWinsOverService()
  {
    assertEquals("Read",
        ClsResolver.pickDeclared("Read", "Write", "Write"));
  }

  @Test
  public void serviceUsedWhenPolicyMissing()
  {
    assertEquals("Read",
        ClsResolver.pickDeclared(null, "Read", "Write"));
  }

  @Test
  public void defaultWhenNeitherDeclared()
  {
    assertEquals("Write",
        ClsResolver.pickDeclared(null, null, "Write"));
  }
}
