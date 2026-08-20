/*
 * Copyright (c) 2024 Domatar
 */

package com.aiagent.llm;

/**
 * A single tool-call request emitted by the LLM in the tool_calls
 * array of an assistant turn.
 * Spec-AIAgent.txt PART 9.1.
 *
 * id            Opaque identifier minted by the LLM provider. Must be
 *               echoed back as the tool_call_id of the eventual
 *               Role="tool" reply message so the LLM can correlate
 *               results with requests.
 *
 * name          The dotted tool name: "<ClsAppId>.<ClsId>.<MsgName>".
 *               ToolDispatcher splits on dots to reconstruct the
 *               destination class and operation.
 *
 * argumentsJson The LLM's argument JSON string, exactly as returned by
 *               the provider. The agent loop parses it; if the JSON is
 *               malformed the call is surfaced as a tool error without
 *               crashing the loop.
 */
public class LlmToolCall
{
  public final String id;
  public final String name;
  public final String argumentsJson;

  public LlmToolCall(final String id, final String name, final String argumentsJson)
  {
    this.id            = id;
    this.name          = name;
    this.argumentsJson = argumentsJson;
  }
}
