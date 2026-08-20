/*
 * Copyright (c) 2024 Domatar
 */

package com.aiagent.llm;

import java.util.List;

/**
 * A single message in an LLM conversation history.
 * Spec-AIAgent.txt PART 9.1.
 *
 * Role is one of:
 *   "system"    - per-conversation instructions
 *   "user"      - the human's turn
 *   "assistant" - the model's reply; may carry toolCalls when
 *                 finishReason is "tool_calls"
 *   "tool"      - result of a tool call; must carry toolCallId
 *
 * The two-arg constructor (role, content) is preserved so all
 * existing call sites in ConvImpl and elsewhere continue to compile.
 */
public class LlmMessage
{
  public final String            role;
  public final String            content;
  public final String            toolCallId;
  public final List<LlmToolCall> toolCalls;

  public LlmMessage(final String role, final String content)
  {
    this(role, content, null, null);
  }

  public LlmMessage(final String            role,
                    final String            content,
                    final String            toolCallId,
                    final List<LlmToolCall> toolCalls)
  {
    this.role       = role;
    this.content    = content;
    this.toolCallId = toolCallId;
    this.toolCalls  = toolCalls;
  }
}
