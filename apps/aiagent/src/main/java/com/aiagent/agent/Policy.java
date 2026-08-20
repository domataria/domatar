/*
 * Copyright (c) 2024 Domatar
 */

package com.aiagent.agent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.domatar.util.Json;
import com.domatar.util.JsonList;
import com.domatar.util.JsonMap;

/**
 * Parsed representation of the Policy JSON document stored on a conv row.
 * Spec-AIAgent.txt PART 17.1.
 *
 * Controls:
 *   - which apps the agent is allowed to call tools on (AllowApps / DenyApps)
 *   - which write tool names the user has pre-approved (PreApprovedWrites)
 *   - per-turn resource budgets (Budgets: MaxIterations, MaxMillis,
 *     MaxTokensIn, MaxTokensOut)
 *
 * AllowApps null  -> all apps allowed (DenyApps still wins)
 * AllowApps []    -> no apps allowed (effectively chat-only)
 * DenyApps  null  -> deny nothing extra
 * PreApprovedWrites -> tool names (dotted) previously approved with Always=true
 *
 * Parse() is resilient: any malformed JSON or missing field falls back to DEFAULT.
 */
public class Policy
{
  public static final Policy DEFAULT =
      new Policy(null, null, null,
                 AgentDefaults.ITERATIONS_PER_TURN,
                 AgentDefaults.DEADLINE_MS_PER_TURN,
                 AgentDefaults.TOKENS_IN_PER_TURN,
                 AgentDefaults.TOKENS_OUT_PER_TURN);

  public final List<String> allowApps;
  public final List<String> denyApps;
  public final List<String> preApprovedWrites;
  public final int          maxIterations;
  public final long         maxMillis;
  public final int          maxTokensIn;
  public final int          maxTokensOut;

  private Policy(final List<String> allowApps,
                 final List<String> denyApps,
                 final List<String> preApprovedWrites,
                 final int          maxIterations,
                 final long         maxMillis,
                 final int          maxTokensIn,
                 final int          maxTokensOut)
  {
    this.allowApps         = (allowApps != null)
                               ? Collections.unmodifiableList(allowApps)
                               : null;
    this.denyApps          = (denyApps != null)
                               ? Collections.unmodifiableList(denyApps)
                               : null;
    this.preApprovedWrites = (preApprovedWrites != null)
                               ? Collections.unmodifiableList(preApprovedWrites)
                               : Collections.emptyList();
    this.maxIterations     = maxIterations;
    this.maxMillis         = maxMillis;
    this.maxTokensIn       = maxTokensIn;
    this.maxTokensOut      = maxTokensOut;
  }

  /**
   * Parse the conv's Policy JSON string. Returns DEFAULT on any parse error
   * or when json is null / empty / "{}".
   */
  public static Policy parse(final String json)
  {
    if (json == null || json.isEmpty() || "{}".equals(json.trim()))
      return DEFAULT;

    final JsonMap m;
    try
    {
      m = Json.parseMap(json);
    }
    catch (Exception e)
    {
      return DEFAULT;
    }

    return new Policy(
        parseStrings(m.getList("AllowApps")),
        parseStrings(m.getList("DenyApps")),
        parseStrings(m.getList("PreApprovedWrites")),
        parseInt(m.getMap("Budgets"), "MaxIterations",
                 AgentDefaults.ITERATIONS_PER_TURN),
        parseLong(m.getMap("Budgets"), "MaxMillis",
                  AgentDefaults.DEADLINE_MS_PER_TURN),
        parseInt(m.getMap("Budgets"), "MaxTokensIn",
                 AgentDefaults.TOKENS_IN_PER_TURN),
        parseInt(m.getMap("Budgets"), "MaxTokensOut",
                 AgentDefaults.TOKENS_OUT_PER_TURN));
  }

  /**
   * Returns true iff the given dotted tool name has been pre-approved for
   * writes by the user (Always=true consent in a prior turn).
   */
  public boolean writePreApproved(final String toolName)
  {
    return preApprovedWrites.contains(toolName);
  }

  // ── private helpers ──────────────────────────────────────────────────────

  private static List<String> parseStrings(final JsonList list)
  {
    if (list == null)
      return null;
    final List<String> out = new ArrayList<>(list.size());
    for (final Object o : list)
    {
      if (o != null)
        out.add(o.toString());
    }
    return out;
  }

  private static int parseInt(final JsonMap m, final String key, final int dflt)
  {
    if (m == null)
      return dflt;
    final Number n = m.getNumber(key);
    return (n != null) ? n.intValue() : dflt;
  }

  private static long parseLong(final JsonMap m, final String key, final long dflt)
  {
    if (m == null)
      return dflt;
    final Number n = m.getNumber(key);
    return (n != null) ? n.longValue() : dflt;
  }
}
