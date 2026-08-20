/*
 * Copyright (c) 2024 Domatar
 */

package com.aiagent.llm;

import java.util.Collections;
import java.util.List;

/**
 * The result returned by an LLM provider after one chat-completion call.
 * Spec-AIAgent.txt PART 9.2.
 *
 * text         The assistant's reply text. Empty string when finishReason
 *              is "tool_calls" (the LLM spoke through tools, not text).
 *
 * toolCalls    Structured tool-call requests emitted by the LLM. Non-empty
 *              only when finishReason is "tool_calls". The agent loop
 *              dispatches each entry through ToolDispatcher.
 *
 * tokensIn     Prompt token count reported by the provider (0 if unavailable).
 * tokensOut    Completion token count reported by the provider (0 if unavailable).
 *
 * finishReason "stop"           — normal text completion
 *              "length"         — context / token limit reached
 *              "content_filter" — blocked by provider policy
 *              "tool_calls"     — LLM wants to invoke one or more tools
 *              "error"          — in-band error materialised by the agent
 *
 * The four-arg constructor (text, tokensIn, tokensOut, finishReason) is
 * preserved so all existing call sites in ConvImpl continue to compile.
 */
public class LlmResult
{
  public final String            text;
  public final List<LlmToolCall> toolCalls;
  public final int               tokensIn;
  public final int               tokensOut;
  public final String            finishReason;

  public LlmResult(final String text, final int tokensIn, final int tokensOut,
                   final String finishReason)
  {
    this(text, Collections.emptyList(), tokensIn, tokensOut, finishReason);
  }

  public LlmResult(final String            text,
                   final List<LlmToolCall> toolCalls,
                   final int               tokensIn,
                   final int               tokensOut,
                   final String            finishReason)
  {
    this.text         = text;
    this.toolCalls    = (toolCalls != null)
                          ? toolCalls
                          : Collections.emptyList();
    this.tokensIn     = tokensIn;
    this.tokensOut    = tokensOut;
    this.finishReason = finishReason;
  }
}
