/*
 * Copyright (c) 2024 Domatar
 */

package com.aiagent.agent;

import java.util.HashMap;
import java.util.Map;

import com.domatar.core.Context;
import com.domatar.util.Json;
import com.domatar.util.JsonList;
import com.domatar.util.JsonMap;
import com.domatar.util.DomId;
import com.domatar.util.DomatarMsgClient;

import com.aiagent.llm.LlmToolCall;

/**
 * Classifies the SideEffect of a tool call for the minimal-toolset path.
 * Spec-AIAgent.txt PART 16.6.
 *
 * Fixed read verbs (GetObj, GetLnks, GetCls, GetHsts) are "Read" by
 * construction.  For SendMsg the SideEffect is determined at dispatch time
 * by reading the target class descriptor via GetCls (cached per turn so
 * repeated SendMsg calls to the same class cost only one descriptor fetch).
 * Legacy/featured dotted-name tools fall back to their own sideEffect field.
 *
 * Construct one instance per AgentLoop turn; do not share across turns.
 */
public class SideEffectClassifier
{
  private final DomId            callerDomId;
  private final Context          callerCtx;
  private final DomatarMsgClient msgClient;

  /** key = "ClsAppId.ClsId", value = the Attrs JsonMap from GetCls response */
  private final Map<String, JsonMap> descriptorCache = new HashMap<>();

  private static final java.util.logging.Logger LOG =
      java.util.logging.Logger.getLogger(SideEffectClassifier.class.getName());

  public SideEffectClassifier(final DomId            callerDomId,
                               final Context          callerCtx,
                               final DomatarMsgClient msgClient)
  {
    this.callerDomId = callerDomId;
    this.callerCtx   = callerCtx;
    this.msgClient   = msgClient;
  }

  /**
   * Classify the SideEffect of a tool call. Never throws; returns
   * AgentDefaults.SIDE_EFFECT_DEFAULT ("Write") on any error.
   */
  public String classify(final LlmToolCall call)
  {
    try
    {
      final String name = call.name;

      // Fixed read verbs are always "Read".
      if (AgentDefaults.TOOL_GET_OBJ.equals(name)
          || AgentDefaults.TOOL_GET_LNKS.equals(name)
          || AgentDefaults.TOOL_GET_CLS.equals(name)
          || AgentDefaults.TOOL_GET_HSTS.equals(name))
        return "Read";

      if (AgentDefaults.TOOL_SEND_MSG.equals(name))
        return classifySendMsg(call);

      // Dynamic tools (from useApp) are resolved in AgentLoop.classifySideEffect
      // via the DynamicToolRegistry.Entry.sideEffect field — they never reach
      // this classifier. Any remaining call here is a legacy/dotted tool name
      // for which we have no descriptor, so fall back to the safe default.
      return AgentDefaults.SIDE_EFFECT_DEFAULT;
    }
    catch (Exception e)
    {
      final String detail = e.getClass().getSimpleName() + ": " + e.getMessage();
      LOG.warning("SideEffectClassifier.classify failed: " + detail);
      // Return a sentinel that AgentLoop can distinguish from a normal Write.
      return AgentDefaults.SIDE_EFFECT_CLASSIFY_ERROR + detail;
    }
  }

  // -----------------------------------------------------------------------

  private String classifySendMsg(final LlmToolCall call)
  {
    final String argsJson = call.argumentsJson != null ? call.argumentsJson : "{}";

    final JsonMap argsMap;
    try
    {
      argsMap = Json.parseMap(argsJson);
    }
    catch (Exception e)
    {
      return AgentDefaults.SIDE_EFFECT_DEFAULT;
    }

    final String clsAppId  = argsMap.getString("ClsAppId");
    final String clsId     = argsMap.getString("ClsId");
    final String operation = argsMap.getString("Operation");

    if (clsAppId == null || clsId == null || operation == null)
      return AgentDefaults.SIDE_EFFECT_DEFAULT;

    // The _target gives us the host and account to reach the descriptor on.
    final Object targetObj = argsMap.get("_target");
    String hstId = null;
    String actId = null;

    if (targetObj instanceof JsonMap)
    {
      final JsonMap t = (JsonMap) targetObj;
      hstId = t.getString("HstId");
      actId = t.getString("ActId");
    }

    // Also support flat form where DomId fields are at the top level
    // (ToolDispatcher.extractTargetMap accepts both forms).
    if (hstId == null)
      hstId = argsMap.getString("HstId");
    if (actId == null)
      actId = argsMap.getString("ActId");

    if (hstId == null || actId == null)
      return AgentDefaults.SIDE_EFFECT_DEFAULT;

    final JsonMap descriptor = fetchDescriptor(hstId, actId, clsAppId, clsId);
    if (descriptor == null)
      return AgentDefaults.SIDE_EFFECT_DEFAULT;

    return sideEffectFromDescriptor(descriptor, operation);
  }

  /**
   * Fetch the class descriptor for (clsAppId, clsId) on the given host,
   * using the per-turn cache. Returns the Attrs map of the GetCls response,
   * or null on any failure.
   */
  private JsonMap fetchDescriptor(final String hstId,
                                   final String actId,
                                   final String clsAppId,
                                   final String clsId)
  {
    final String cacheKey = clsAppId + "." + clsId;

    final JsonMap cached = descriptorCache.get(cacheKey);
    if (cached != null)
      return cached;

    // Build a GetCls tool call targeting the class registry on the remote host.
    final String targetJson =
        "{\"_target\":{" +
          "\"HstId\":\"" + esc(hstId) + "\"," +
          "\"AppId\":\"domatar\"," +
          "\"ActId\":\"" + esc(actId) + "\"," +
          "\"ObjId\":\"cls\"" +
        "}," +
        "\"ClsAppId\":\"" + esc(clsAppId) + "\"," +
        "\"ClsId\":\"" + esc(clsId) + "\"}";

    final LlmToolCall getClsCall = new LlmToolCall(
        "_classifier_getcls_", AgentDefaults.TOOL_GET_CLS, targetJson);

    final ToolDispatcher.Result result =
        ToolDispatcher.execute(callerDomId, callerCtx, getClsCall, msgClient);

    if (!"ok".equals(result.status) || result.resultJson == null)
      return null;

    JsonMap attrs;
    try
    {
      attrs = Json.parseMap(result.resultJson);
    }
    catch (Exception e)
    {
      return null;
    }

    // GetCls wraps the class definition inside an "Attrs" sub-object.
    // Unwrap it so sideEffectFromDescriptor can access Msgs/SideEffect directly.
    final Object nested = attrs.get("Attrs");
    if (nested instanceof JsonMap)
      attrs = (JsonMap) nested;

    descriptorCache.put(cacheKey, attrs);
    return attrs;
  }

  /**
   * Walk the Msgs list in the descriptor and return the SideEffect of the
   * named operation. Falls back to SIDE_EFFECT_DEFAULT if not found.
   *
   * Supports two descriptor formats:
   *   Structured: "Msgs" is a JSON array of objects, each with "Name" and
   *               "SideEffect" fields (e.g. convCls format).
   *   Plain-text: "SideEffect" is a string like
   *               "GetSpreadsheet:None, SetCell:Write, ..." where "None"
   *               means read-only (maps to "Read").
   */
  private static String sideEffectFromDescriptor(final JsonMap descriptor,
                                                   final String operation)
  {
    // Structured format: Msgs is a JSON array with per-message SideEffect.
    // Use instanceof check to avoid ClassCastException when Msgs is a String.
    final Object msgsRaw = descriptor.get("Msgs");
    final JsonList msgs  = msgsRaw instanceof JsonList ? (JsonList) msgsRaw : null;
    if (msgs != null)
    {
      final StringBuilder validOps = new StringBuilder();
      for (final Object o : msgs)
      {
        if (!(o instanceof JsonMap))
          continue;

        final JsonMap msgDef  = (JsonMap) o;
        final String  msgName = msgDef.getString("Name");
        if (operation.equals(msgName))
        {
          final String se = msgDef.getString("SideEffect");
          return se != null ? se : AgentDefaults.SIDE_EFFECT_DEFAULT;
        }
        if (validOps.length() > 0)
          validOps.append("; ");
        validOps.append(msgName != null ? msgName : "?");
        final String sig = msgDef.getString("Signature");
        if (sig != null)
          validOps.append("(").append(sig).append(")");
      }
      // Descriptor found, but this operation is not listed in Msgs.
      return AgentDefaults.SIDE_EFFECT_OP_NOT_FOUND + operation
          + "\n" + validOps;
    }

    // Plain-text fallback: "SideEffect" is "Op1:Effect1, Op2:Effect2, ..."
    // The "Msgs" field is a human-readable string like "Op(Args) -> Result; ..."
    // Include it verbatim so the LLM can see the valid signatures.
    final Object seObj = descriptor.get("SideEffect");
    if (seObj instanceof String)
    {
      for (String entry : ((String) seObj).split(","))
      {
        entry = entry.trim();
        final int colon = entry.indexOf(':');
        if (colon > 0)
        {
          final String opName = entry.substring(0, colon).trim();
          final String effect  = entry.substring(colon + 1).trim();
          if (operation.equals(opName))
            return "None".equals(effect) ? "Read" : effect;
        }
      }
      // Descriptor found, but this operation is not listed in SideEffect.
      // Include the Msgs string (if present) so the LLM sees the valid ops.
      final String validOps = msgsRaw instanceof String ? (String) msgsRaw : "";
      return AgentDefaults.SIDE_EFFECT_OP_NOT_FOUND + operation
          + "\n" + validOps;
    }

    // Descriptor exists but has neither a Msgs array nor a SideEffect string —
    // descriptor format is unknown, fall back to the conservative default.
    return AgentDefaults.SIDE_EFFECT_DEFAULT;
  }

  /** Minimal JSON string escaping — only backslash and double-quote. */
  private static String esc(final String s)
  {
    if (s == null)
      return "";
    return s.replace("\\", "\\\\").replace("\"", "\\\"");
  }
}
