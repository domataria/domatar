/*
 * Copyright (c) 2024 Domatar
 */

package com.aiagent.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

public class PolicyTest
{
  @Test
  public void emptyPolicyUsesAssistantDefaults()
  {
    assertDefaults(Policy.parse(null));
    assertDefaults(Policy.parse(""));
    assertDefaults(Policy.parse("{}"));
  }

  @Test
  public void storedBudgetWins()
  {
    final Policy policy = Policy.parse(
        "{\"Budgets\":{\"MaxIterations\":4,\"MaxMillis\":9000}}");

    assertEquals(4, policy.maxIterations);
    assertEquals(9000L, policy.maxMillis);
    assertEquals(AgentDefaults.TOKENS_IN_PER_TURN, policy.maxTokensIn);
    assertEquals(AgentDefaults.TOKENS_OUT_PER_TURN, policy.maxTokensOut);
  }

  private static void assertDefaults(final Policy policy)
  {
    assertEquals(12, policy.maxIterations);
    assertEquals(180000L, policy.maxMillis);
    assertEquals(50000, policy.maxTokensIn);
    assertEquals(8000, policy.maxTokensOut);
  }
}
