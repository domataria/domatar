/*
 * Copyright (c) 2024 Domatar
 */

package com.aiagent.llm;

/**
 * One entry in the tool catalogue passed to the LLM.
 * Spec-AIAgent.txt PART 9.1 / PART 16.4.
 *
 * name             The name the LLM uses when emitting a tool_call and
 *                  the name ToolDispatcher looks up when routing the call.
 *
 * description      Prose the LLM reads to decide whether to use this tool.
 *
 * parametersSchema JSON Schema (as a string) for the tool's arguments.
 *
 * sideEffect       "Read", "Write", or "Destructive".
 *                  Stored here so the agent loop can gate writes without
 *                  re-fetching the descriptor (Spec-AIAgent.txt PART 16.3).
 */
public class LlmTool
{
  public final String name;
  public final String description;
  public final String parametersSchema;
  public final String sideEffect;

  public LlmTool(final String name,
                 final String description,
                 final String parametersSchema,
                 final String sideEffect)
  {
    this.name             = name;
    this.description      = description;
    this.parametersSchema = parametersSchema;
    this.sideEffect       = sideEffect;
  }
}
