/*
 * Copyright (c) 2024 Domatar
 */

package com.aiagent.agent;

import com.aiagent.llm.LlmTool;

/**
 * Conservative defaults applied when a class descriptor
 * (Spec-Class.txt PART 3.1) does not carry the agent-friendly fields.
 * See Spec-AIAgent.txt PART 16.3.
 *
 * All phases of the agent loop reference these constants instead of
 * embedding literals, so the policy is consistent across the codebase.
 */
public final class AgentDefaults
{
  /** The agent loop uses the adaptive toolset (Spec-AIAgent.txt PART 16). */
  public static final boolean DYNAMIC_TOOLSET = true;

  // -----------------------------------------------------------------------
  // Base-tool definitions (PART 16.7) — built once, reused every turn.
  // -----------------------------------------------------------------------

  /** listApps tool definition (Spec-AIAgent.txt PART 16.7). */
  public static LlmTool toolListApps()
  {
    return new LlmTool(
        TOOL_LIST_APPS,
        "List the apps available to the current user.",
        "{\"type\":\"object\",\"properties\":{},\"required\":[]}",
        "Read");
  }

  /** useApp tool definition (Spec-AIAgent.txt PART 16.7). */
  public static LlmTool toolUseApp()
  {
    return new LlmTool(
        TOOL_USE_APP,
        "Load an app's operations as callable tools. "
        + "You MUST call listApps first and use an EXACT name from that result. "
        + "Returns the list of loaded tool names.",
        "{\"type\":\"object\",\"properties\":"
        + "{\"name\":{\"type\":\"string\","
        + "\"description\":"
        + "\"Exact app name from listApps. Never guess — call listApps first.\"}}"
        + ",\"required\":[\"name\"]}",
        "Read");
  }

  /** The two base tools of the adaptive toolset (PART 16.2). */
  public static final String TOOL_LIST_APPS = "listApps";
  public static final String TOOL_USE_APP   = "useApp";

  /** The five fixed verbs (PART 16.1). */
  public static final String TOOL_GET_OBJ  = "GetObj";
  public static final String TOOL_GET_LNKS = "GetLnks";
  public static final String TOOL_GET_CLS  = "GetCls";
  public static final String TOOL_GET_HSTS = "GetHsts";
  public static final String TOOL_SEND_MSG = "SendMsg";

  /**
   * SideEffect assumed when a Msg descriptor omits the SideEffect field.
   * "Write" is conservative: the agent will ask for user confirmation
   * before dispatching in Agent mode.
   */
  public static final String SIDE_EFFECT_DEFAULT = "Write";

  /** SideEffect marker for SendMsg, classified per call (PART 16.6). */
  public static final String SIDE_EFFECT_VARIES = "Varies";

  /**
   * Prefix returned by SideEffectClassifier when classification itself throws.
   * The suffix is the exception class + message for display in the UI.
   */
  public static final String SIDE_EFFECT_CLASSIFY_ERROR = "classify-error:";

  /**
   * Prefix returned by SideEffectClassifier when the class descriptor was
   * fetched successfully but the requested operation is not listed in Msgs.
   * The suffix is the operation name, for display in the UI.
   */
  public static final String SIDE_EFFECT_OP_NOT_FOUND = "op-not-found:";

  /** Maximum agent loop iterations per user turn. */
  public static final int  ITERATIONS_PER_TURN  = 8;

  /** Maximum wall-clock time per user turn (milliseconds). */
  public static final long DEADLINE_MS_PER_TURN = 60_000L;

  /** Maximum prompt tokens charged per user turn. */
  public static final int  TOKENS_IN_PER_TURN   = 50_000;

  /** Maximum completion tokens charged per user turn. */
  public static final int  TOKENS_OUT_PER_TURN  = 8_000;

  private AgentDefaults()
  {
  }
}
