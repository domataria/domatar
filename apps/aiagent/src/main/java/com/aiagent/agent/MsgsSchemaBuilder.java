/*
 * Copyright (c) 2024 Domatar
 */

package com.aiagent.agent;

import java.util.ArrayList;
import java.util.List;

import com.domatar.util.JsonList;
import com.domatar.util.JsonMap;

import com.aiagent.llm.LlmTool;

/**
 * Converts a class descriptor's Msgs field into a list of ToolEntry carriers,
 * one per Msg. Pure utility; no Domatar I/O, no side effects.
 * Used by the useApp handler (PART 16.3) to register app operations as
 * typed tools so the LLM calls them by name with full schema enforcement.
 *
 * Two descriptor formats are supported:
 *
 *   Structured: "Msgs" is a JsonList. Each element is a JsonMap with fields:
 *                 Name (required), Description, SideEffect, Srv
 *                 (Srv = "srvAppId.srvId" from the resolved descriptor),
 *                 Compensates (optional MsgName; metadata only),
 *                 Parms (JsonList of {Name, Type, Description}).
 *               Optional parms have a Type ending with "?".
 *
 *   Plain-text: "Msgs" is a String: "Op(Arg1,Arg2)->{}; Op2()->{}; ..."
 *               "SideEffect" is a String: "Op:None, Op2:Write, ...".
 *               All parms are required strings. "None" maps to "Read".
 *               Plain-text descriptors carry no per-Msg service identity
 *               (srvAppId/srvId are null).
 *
 * See Spec-AIAgent.txt PART 16.3 and PART 16.7.
 */
public final class MsgsSchemaBuilder
{
  /**
   * Typed carrier returned by {@link #build}.
   *
   * tool      The LlmTool built from the Msg definition. Its name is the
   *           BARE operation name; DynamicToolRegistry.register() is
   *           responsible for uniquifying it on collision.
   * srvAppId  The defining service's app namespace (e.g. "bookstore").
   *           Null for plain-text descriptors that have no Srv tag.
   * srvId     The service identifier (e.g. "forsale").
   *           Null for plain-text descriptors.
   * compensates  Optional undo MsgName from GetCls. Metadata only —
   *           not a consent signal (SideEffect stays the v1 gate).
   */
  public static class ToolEntry
  {
    public final LlmTool tool;
    public final String  srvAppId;
    public final String  srvId;
    public final String  compensates;

    public ToolEntry(final LlmTool tool, final String srvAppId, final String srvId)
    {
      this(tool, srvAppId, srvId, null);
    }

    public ToolEntry(final LlmTool tool, final String srvAppId, final String srvId,
                     final String compensates)
    {
      this.tool        = tool;
      this.srvAppId    = srvAppId;
      this.srvId       = srvId;
      this.compensates = compensates;
    }
  }

  /**
   * Build one {@link ToolEntry} per Msg in the descriptor.
   * Returns an empty list if the descriptor has no Msgs field or if the
   * Msgs field cannot be parsed.
   *
   * @param descriptor  the Attrs map from a GetCls response, already
   *                    unwrapped from the outer "Attrs" envelope key
   */
  public static List<ToolEntry> build(final JsonMap descriptor)
  {
    final List<ToolEntry> result = new ArrayList<>();
    if (descriptor == null)
      return result;

    final Object msgsRaw = descriptor.get("Msgs");

    // -----------------------------------------------------------------------
    // Structured format: Msgs is a JsonList
    // -----------------------------------------------------------------------
    if (msgsRaw instanceof JsonList)
    {
      for (final Object o : (JsonList) msgsRaw)
      {
        if (!(o instanceof JsonMap))
          continue;
        final JsonMap msg = (JsonMap) o;

        final String name = msg.getString("Name");
        if (name == null || name.isEmpty())
          continue;

        String description = msg.getString("Description");
        if (description == null || description.isEmpty())
          description = name;

        String sideEffect = msg.getString("SideEffect");
        if (sideEffect == null || sideEffect.isEmpty())
          sideEffect = AgentDefaults.SIDE_EFFECT_DEFAULT;

        // Service identity: "Srv" = "srvAppId.srvId" from the resolved descriptor.
        String srvAppId = null;
        String srvId    = null;
        final String srv = msg.getString("Srv");
        if (srv != null && !srv.isEmpty())
        {
          final int dot = srv.indexOf('.');
          if (dot > 0)
          {
            srvAppId = srv.substring(0, dot);
            srvId    = srv.substring(dot + 1);
          }
        }

        final List<String> parmNames = new ArrayList<>();
        final List<String> parmDescs = new ArrayList<>();
        final List<String> required  = new ArrayList<>();

        final Object parmsRaw = msg.get("Parms");
        if (parmsRaw instanceof JsonList)
        {
          for (final Object p : (JsonList) parmsRaw)
          {
            if (!(p instanceof JsonMap))
              continue;
            final JsonMap parm  = (JsonMap) p;
            final String  pName = parm.getString("Name");
            if (pName == null || pName.isEmpty())
              continue;
            parmNames.add(pName);
            final String pDesc = parm.getString("Description");
            parmDescs.add(pDesc != null && !pDesc.isEmpty() ? pDesc : pName);
            final String pType = parm.getString("Type");
            if (pType == null || !pType.endsWith("?"))
              required.add(pName);
          }
        }

        // WHY: Compensates is copied as ToolEntry metadata only. LlmTool /
        // SideEffectClassifier / confirmation still use SideEffect until
        // publisher-signed descriptors (Spec-Saga KD8). GetCls already
        // carries the string for Saga.
        String compensates = msg.getString("Compensates");
        if (compensates != null && compensates.isEmpty())
          compensates = null;

        result.add(new ToolEntry(
            new LlmTool(name, description,
                buildSchema(parmNames, parmDescs, required), sideEffect),
            srvAppId, srvId, compensates));
      }
      return result;
    }

    // -----------------------------------------------------------------------
    // Plain-text format: Msgs is a String
    // Each semicolon-separated entry: "Op(Arg1,Arg2) -> {ReturnShape}"
    // Plain-text descriptors have no per-Msg Srv tag (srvAppId/srvId null).
    // -----------------------------------------------------------------------
    if (!(msgsRaw instanceof String))
      return result;

    final String msgsStr = (String) msgsRaw;
    final String seStr   = descriptor.get("SideEffect") instanceof String
                     ? (String) descriptor.get("SideEffect") : "";

    for (String entry : msgsStr.split(";"))
    {
      entry = entry.trim();
      if (entry.isEmpty())
        continue;

      final int parenOpen = entry.indexOf('(');
      if (parenOpen < 0)
        continue;

      final String name = entry.substring(0, parenOpen).trim();
      if (name.isEmpty())
        continue;

      final int    parenClose = entry.indexOf(')', parenOpen);
      final String parmStr    = parenClose > parenOpen
                       ? entry.substring(parenOpen + 1, parenClose).trim()
                       : "";

      final List<String> parmNames  = parseParmNames(parmStr);
      final String       sideEffect = sideEffectFor(seStr, name);
      // Use the full entry text (e.g. "GetSpreadsheet(Name) -> {...}") as the
      // description — it shows both the signature and the return shape.
      result.add(new ToolEntry(
          new LlmTool(name, entry,
              buildSchema(parmNames, parmNames, parmNames), sideEffect),
          null, null));
    }

    return result;
  }

  // -------------------------------------------------------------------------
  // Package-visible helpers (used by tests and by SideEffectClassifier)
  // -------------------------------------------------------------------------

  /**
   * For the plain-text SideEffect string "Op1:None, Op2:Write, ...",
   * return the effect for the named operation.
   * "None" maps to "Read". Returns SIDE_EFFECT_DEFAULT if not found.
   */
  static String sideEffectFor(final String sideEffectStr, final String opName)
  {
    if (sideEffectStr == null || sideEffectStr.isEmpty())
      return AgentDefaults.SIDE_EFFECT_DEFAULT;

    for (String entry : sideEffectStr.split(","))
    {
      entry = entry.trim();
      final int colon = entry.indexOf(':');
      if (colon <= 0)
        continue;
      final String op     = entry.substring(0, colon).trim();
      final String effect = entry.substring(colon + 1).trim();
      if (opName.equals(op))
        return "None".equals(effect) ? "Read" : effect;
    }
    return AgentDefaults.SIDE_EFFECT_DEFAULT;
  }

  /**
   * Parse parm names from a plain-text comma-separated list like "Name,Cols,Rows".
   * Returns an empty list if the signature is null or empty.
   */
  static List<String> parseParmNames(final String signature)
  {
    final List<String> names = new ArrayList<>();
    if (signature == null || signature.isEmpty())
      return names;
    for (final String parm : signature.split(","))
    {
      final String p = parm.trim();
      if (!p.isEmpty())
        names.add(p);
    }
    return names;
  }

  // -------------------------------------------------------------------------
  // Private schema builder
  // -------------------------------------------------------------------------

  /**
   * Produce a JSON Schema string for the tool's parameters.
   * All properties are typed as "string". The required[] array contains
   * exactly the names in the required list.
   *
   * @param parmNames    ordered list of parameter names
   * @param descriptions parallel list of per-parameter descriptions
   *                     (falls back to the parm name if shorter than parmNames)
   * @param required     subset of parmNames that are required
   */
  private static String buildSchema(final List<String> parmNames,
                                     final List<String> descriptions,
                                     final List<String> required)
  {
    final StringBuilder sb = new StringBuilder();
    sb.append("{\"type\":\"object\",\"properties\":{");
    for (int i = 0; i < parmNames.size(); i++)
    {
      if (i > 0)
        sb.append(",");
      final String n = esc(parmNames.get(i));
      final String d = esc(descriptions.size() > i ? descriptions.get(i) : parmNames.get(i));
      sb.append("\"").append(n)
        .append("\":{\"type\":\"string\",\"description\":\"").append(d).append("\"}");
    }
    sb.append("},\"required\":[");
    for (int i = 0; i < required.size(); i++)
    {
      if (i > 0)
        sb.append(",");
      sb.append("\"").append(esc(required.get(i))).append("\"");
    }
    sb.append("]}");
    return sb.toString();
  }

  /** Minimal JSON string escaping — backslash and double-quote only. */
  private static String esc(final String s)
  {
    if (s == null)
      return "";
    return s.replace("\\", "\\\\").replace("\"", "\\\"");
  }

  private MsgsSchemaBuilder()
  {
  }
}
