/*
 * Copyright (c) 2024 Domatar
 */

package com.aiagent.agent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.domatar.util.DomId;

import com.aiagent.llm.LlmTool;

/**
 * Per-turn, in-memory map from dynamic tool name to dispatch details.
 *
 * Constructed once at the start of each AgentLoop turn and threaded through
 * the tool handling chain. Not persisted; discarded when the turn ends.
 *
 * The registry holds at most ONE app's tools at a time. Each call to
 * {@link #register} replaces (not accumulates) the previous set — this is
 * the key invariant that prevents tool-name collisions and token bloat.
 *
 * See Spec-AIAgent.txt PART 16.3.
 */
public class DynamicToolRegistry
{
  /** Entry stored per registered dynamic tool. */
  public static class Entry
  {
    /** The app entry-point DomId that receives the tool call. */
    public final DomId   targetDomId;
    /** The REAL bare operation name forwarded to the handler (may repeat across services). */
    public final String  operation;
    /** Service app namespace from which this operation comes; may be null. */
    public final String  srvAppId;
    /** Service identifier from which this operation comes; may be null. */
    public final String  srvId;
    /** Side-effect classification: "Read", "Write", or "Destructive". */
    public final String  sideEffect;
    /** The full LlmTool definition sent to the LLM; its name is the UNIQUE LLM-facing name. */
    public final LlmTool tool;

    public Entry(final DomId targetDomId, final String operation,
                 final String srvAppId, final String srvId,
                 final String sideEffect, final LlmTool tool)
    {
      this.targetDomId = targetDomId;
      this.operation   = operation;
      this.srvAppId    = srvAppId;
      this.srvId       = srvId;
      this.sideEffect  = sideEffect;
      this.tool        = tool;
    }
  }

  /** Insertion-ordered map from UNIQUE LLM-facing tool name to dispatch entry. */
  private final Map<String, Entry> entries = new LinkedHashMap<>();

  /**
   * Register a set of tools for a new app.
   * Clears any previously-registered dynamic tools first (replacement,
   * not accumulation).
   *
   * When two tools share the same bare operation name (overloading), a unique
   * LLM-facing name is computed: first collision gets "__&lt;srvId&gt;" appended,
   * further collisions get "__2", "__3", etc. The unique name is sanitised to
   * match {@code ^[A-Za-z0-9_-]{1,64}$}.
   *
   * @param toolEntries  typed carriers produced by {@link MsgsSchemaBuilder#build}
   * @param targetDomId  the app entry-point DomId that will receive calls
   */
  public void register(final List<MsgsSchemaBuilder.ToolEntry> toolEntries,
                       final DomId targetDomId)
  {
    entries.clear();
    for (final MsgsSchemaBuilder.ToolEntry te : toolEntries)
    {
      final String  bareOp  = te.tool.name;
      final String  llmName = uniqueName(bareOp, te.srvId);
      final LlmTool unique  = new LlmTool(llmName, te.tool.description,
                                           te.tool.parametersSchema, te.tool.sideEffect);
      entries.put(llmName, new Entry(targetDomId, bareOp,
                                     te.srvAppId, te.srvId,
                                     te.tool.sideEffect, unique));
    }
  }

  /**
   * Compute a unique LLM-facing name for a new tool.
   * Uses the bare op name if not yet taken; otherwise appends "__&lt;srvId&gt;",
   * then "__2", "__3", … until unique.
   */
  private String uniqueName(final String bareOp, final String srvId)
  {
    if (!entries.containsKey(bareOp))
      return bareOp;

    final String qualifier = srvId != null && !srvId.isEmpty() ? srvId : "srv";
    String candidate = sanitize(bareOp + "__" + qualifier);
    if (!entries.containsKey(candidate))
      return candidate;

    for (int n = 2; ; n++)
    {
      candidate = sanitize(bareOp + "__" + qualifier + "__" + n);
      if (!entries.containsKey(candidate))
        return candidate;
    }
  }

  /** Replace characters outside {@code [A-Za-z0-9_-]} with '_'; truncate to 64. */
  private static String sanitize(final String s)
  {
    final String clean = s.replaceAll("[^A-Za-z0-9_\\-]", "_");
    return clean.length() <= 64 ? clean : clean.substring(0, 64);
  }

  /**
   * Look up dispatch details for a tool name.
   *
   * @return the {@link Entry} for the tool, or {@code null} if not found
   */
  public Entry lookup(final String toolName)
  {
    return entries.get(toolName);
  }

  /**
   * All currently registered dynamic tools, in registration order.
   * Returns an unmodifiable snapshot suitable for passing to the LLM.
   */
  public List<LlmTool> getTools()
  {
    final List<LlmTool> list = new ArrayList<>(entries.size());
    for (final Entry e : entries.values())
      list.add(e.tool);
    return Collections.unmodifiableList(list);
  }
}
