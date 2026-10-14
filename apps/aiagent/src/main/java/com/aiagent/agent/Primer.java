/*
 * Copyright (c) 2024 Domatar
 */

package com.aiagent.agent;

import com.domatar.core.Context;

/**
 * Generates the Domatar system-prompt primer for the adaptive toolset.
 * Spec-AIAgent.txt PART 16.4.
 *
 * The primer is prepended to the user-supplied system prompt on every
 * non-Chat turn. It is compact by design: every non-Chat turn pays its
 * token cost.
 */
public final class Primer
{
  /**
   * Build the primer for the given caller.
   * Emits the adaptive-toolset primer (Spec-AIAgent.txt PART 16.4).
   */
  public static String build(final Context callerCtx)
  {
    final String actId = callerCtx.actId;

    return "You are a Domatar assistant with access to the user's data.\n"
        + "WORKFLOW (follow in order):\n"
        + "1. Call 'listApps' to see the available apps.\n"
        + "2. Call 'useApp' with the EXACT name from the listApps result — "
        + "do NOT guess or paraphrase app names.\n"
        + "3. Call the loaded operation tools to answer the question.\n"
        + "4. To switch apps, call 'useApp' again with a different exact name from listApps.\n"
        + "Your identity: actId = " + actId + ".\n"
        + "If a tool description says to copy a value from an earlier result, copy it unchanged.\n"
        + "Do not invent names, prices, dates, or booking references.\n"
        + "If a required argument is not in the conversation, ask the user. "
        + "Do not guess a city or a date.\n"
        + "Call a write when the user asked for the change, then wait. "
        + "After it runs, describe what the result contains. "
        + "Do not add a payment, a reservation, or a confirmation the result does not contain.";
  }

  private Primer()
  {
  }
}
