/*
 * Copyright (c) 2024 Domatar
 */

package com.aiagent.agent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.domatar.core.Context;
import com.domatar.db.LnkDb;
import com.domatar.db.ObjDb;
import com.domatar.util.IdGen;
import com.domatar.util.Json;
import com.domatar.util.JsonArrayList;
import com.domatar.util.JsonHashMap;
import com.domatar.util.JsonList;
import com.domatar.util.JsonMap;
import com.domatar.util.Lnk;
import com.domatar.util.Obj;
import com.domatar.util.ObjAttrs;
import com.domatar.util.DomId;
import com.domatar.util.DomatarException;
import com.domatar.util.DomatarMsgClient;

import com.aiagent.llm.LlmClient;
import com.aiagent.llm.LlmMessage;
import com.aiagent.llm.LlmResult;
import com.aiagent.llm.LlmTool;
import com.aiagent.llm.LlmToolCall;

/**
 * Drives a single agent turn for Mode="ReadOnly" or Mode="Agent".
 * Spec-AIAgent.txt PART 15.4.
 *
 * Loop invariant:
 *   Each iteration either produces a text reply (loop exits) or produces
 *   one batch of tool calls (dispatched, results appended to history,
 *   loop continues). Budget limits (iterations, wall-clock, tokens) act
 *   as safety valves.
 *
 * Mode="Agent" adds a consent gate: before dispatching any non-Read tool
 * call that is not pre-approved, the loop persists a Role="tool" row with
 * ToolStatus="pending" and returns Outcome with
 * finishReason="awaiting_user_confirmation". The UI then calls
 * AgentWui ApproveToolCall / RejectToolCall which drives resume().
 */
public class AgentLoop
{
  /** Recent turns only — keeps Groq on_demand requests under ~12k TPM. */
  private static final int MAX_HISTORY_MSGS     = 12;
  private static final int MAX_TOOL_RESULT_CHARS = 4000;

  // ── public entry point ───────────────────────────────────────────────────

  /**
   * Run one agent turn (from a fresh user message).
   */
  public static Outcome run(final DomId            convDomId,
                             final ObjAttrs         convAttrs,
                             final String           userText,
                             final DomId            callerDomId,
                             final Context          callerContext,
                             final DomatarMsgClient msgClient)
      throws DomatarException
  {
    final long   now    = System.currentTimeMillis();
    final String nowStr = Long.toString(now);

    final Policy policy = Policy.parse(safeGet(convAttrs, "Policy"));
    final String mode   = defaultIfNull(safeGet(convAttrs, "Mode"),  "Chat");
    final String model  = defaultIfNull(safeGet(convAttrs, "Model"), LlmClient.defaultModel);
    String systemPrompt = defaultIfNull(safeGet(convAttrs, "SystemPrompt"),
                                        "You are a helpful assistant.");

    final long deadline = now + policy.maxMillis;

    // ── 1. Persist user turn ──────────────────────────────────────────────
    final ObjAttrs userAttrs = new ObjAttrs();
    userAttrs.addAttr("ConvId", convDomId.objId);
    userAttrs.addAttr("Role",   "user");
    userAttrs.addAttr("Text",   userText);
    userAttrs.addAttr("Time",   nowStr);

    addMsgRow(convDomId, userAttrs, now);

    // ── 2. Load full history from DB ──────────────────────────────────────
    final List<Obj> convMsgs = loadSortedConvMsgs(convDomId);
    List<LlmMessage> history = buildHistory(convMsgs, systemPrompt);

    // ── 3. Build tool catalogue ───────────────────────────────────────────
    final DynamicToolRegistry registry  = new DynamicToolRegistry();
    final List<LlmTool>       baseTools = new ArrayList<>();
    baseTools.add(AgentDefaults.toolListApps());
    baseTools.add(AgentDefaults.toolUseApp());
    systemPrompt = Primer.build(callerContext) + "\n\n" + systemPrompt;
    history = buildHistory(convMsgs, systemPrompt);

    // Re-register any apps loaded by useApp in prior turns so the LLM can
    // continue calling their tools without needing to call useApp again.
    reloadPreviousApps(convMsgs, callerDomId, callerContext, msgClient, registry);

    final SideEffectClassifier classifier = new SideEffectClassifier(
        callerDomId, callerContext, msgClient);

    // ── 4. Loop ───────────────────────────────────────────────────────────
    final List<ToolMsgInfo> toolMessages     = new ArrayList<>();
    final List<ToolMsgInfo> pendingToolCalls = new ArrayList<>();
    String  finalText      = "";
    String  finalFinish    = "error";
    String  finalAsgTime   = nowStr;
    int     totalTokensIn  = 0;
    int     totalTokensOut = 0;
    int     lastTokensIn   = 0;
    int     lastTokensOut  = 0;
    int     iterationsUsed = 0;
    long    lastTime       = now;
    boolean paused         = false;

    outer:
    for (int iter = 0; iter < policy.maxIterations; iter++)
    {
      if (System.currentTimeMillis() > deadline)
      {
        finalFinish = "deadline";
        break;
      }
      if (totalTokensIn  > policy.maxTokensIn
          || totalTokensOut > policy.maxTokensOut)
      {
        finalFinish = "tokens";
        break;
      }

      final List<LlmTool> tools = assembleTools(baseTools, registry);
      LlmResult result;
      try
      {
        result = LlmClient.complete(model, history,
                                    tools.isEmpty() ? null : tools,
                                    "auto");
      }
      catch (DomatarException e)
      {
        finalText   = "(error: " + e.getMessage() + ")";
        finalFinish = "error";
        break;
      }

      totalTokensIn  += result.tokensIn;
      totalTokensOut += result.tokensOut;
      lastTokensIn    = result.tokensIn;
      lastTokensOut   = result.tokensOut;
      iterationsUsed++;

      if (result.toolCalls.isEmpty())
      {
        finalText   = result.text != null ? result.text : "";
        finalFinish = result.finishReason;

        lastTime     = Math.max(System.currentTimeMillis(), lastTime + 1);
        finalAsgTime = Long.toString(lastTime);

        final ObjAttrs asgAttrs = new ObjAttrs();
        asgAttrs.addAttr("ConvId",       convDomId.objId);
        asgAttrs.addAttr("Role",         "assistant");
        asgAttrs.addAttr("Text",         finalText);
        asgAttrs.addAttr("Time",         finalAsgTime);
        asgAttrs.addAttr("Model",        model);
        asgAttrs.addAttr("TokensIn",     Integer.toString(result.tokensIn));
        asgAttrs.addAttr("TokensOut",    Integer.toString(result.tokensOut));
        asgAttrs.addAttr("FinishReason", result.finishReason);

        addMsgRow(convDomId, asgAttrs, lastTime);
        break;
      }

      // Assistant turn with tool calls — persist then execute each call.
      lastTime     = Math.max(System.currentTimeMillis(), lastTime + 1);
      finalAsgTime = Long.toString(lastTime);

      final String toolCallsJson = serializeToolCalls(result.toolCalls);
      final String asgText       = result.text != null ? result.text : "";

      final ObjAttrs asgAttrs = new ObjAttrs();
      asgAttrs.addAttr("ConvId",        convDomId.objId);
      asgAttrs.addAttr("Role",          "assistant");
      asgAttrs.addAttr("Text",          asgText);
      asgAttrs.addAttr("Time",          finalAsgTime);
      asgAttrs.addAttr("Model",         model);
      asgAttrs.addAttr("TokensIn",      Integer.toString(result.tokensIn));
      asgAttrs.addAttr("TokensOut",     Integer.toString(result.tokensOut));
      asgAttrs.addAttr("FinishReason",  result.finishReason);
      asgAttrs.addAttr("ToolCallsJson", toolCallsJson);

      addMsgRow(convDomId, asgAttrs, lastTime);

      history.add(new LlmMessage("assistant", asgText, null, result.toolCalls));

      for (int k = 0; k < result.toolCalls.size(); k++)
      {
        final LlmToolCall call   = result.toolCalls.get(k);
        final String      opName = ToolDispatcher.canonicalOpName(call);

        final String  sideEffect        = classifySideEffect(call, registry, classifier);

        boolean needsConfirmation = false;
        if ("Agent".equals(mode))
        {
          // "Destructive" tools ALWAYS prompt even if listed in
          // PreApprovedWrites (safety gate; cannot be bypassed by the user).
          // "Write" tools prompt only when not pre-approved.
          // "Read" tools never prompt.
          needsConfirmation = !"Read".equals(sideEffect)
              && ("Destructive".equals(sideEffect)
                  || !policy.writePreApproved(opName));
        }

        if (needsConfirmation)
        {
          lastTime = Math.max(System.currentTimeMillis(), lastTime + 1);
          final String pendingTimeStr = Long.toString(lastTime);
          final String pendingSovStr  = decodeTargetSov(call.argumentsJson);

          final ObjAttrs pendingAttrs = new ObjAttrs();
          pendingAttrs.addAttr("ConvId",        convDomId.objId);
          pendingAttrs.addAttr("Role",          "tool");
          pendingAttrs.addAttr("Text",          opName + " (pending)");
          pendingAttrs.addAttr("Time",          pendingTimeStr);
          pendingAttrs.addAttr("ToolName",      opName);
          pendingAttrs.addAttr("ToolCallName",  call.name);
          pendingAttrs.addAttr("ToolTargetSov", pendingSovStr);
          pendingAttrs.addAttr("ToolArgs",      call.argumentsJson);
          pendingAttrs.addAttr("ToolResult",    "");
          pendingAttrs.addAttr("ToolStatus",    "pending");
          pendingAttrs.addAttr("PendingId",     call.id);

          addMsgRow(convDomId, pendingAttrs, lastTime);

          final List<LlmToolCall> remaining = result.toolCalls.subList(k + 1,
                                                                        result.toolCalls.size());
          convAttrs.addAttr("PendingTurn",
              serializePendingTurn(remaining, iterationsUsed,
                                   totalTokensIn, totalTokensOut, lastTime));

          pendingToolCalls.add(new ToolMsgInfo(call.id, opName, pendingSovStr,
                                               call.argumentsJson, "", "pending",
                                               opName + " (pending)", pendingTimeStr));
          paused      = true;
          finalFinish = "awaiting_user_confirmation";
          break outer;
        }

        // ReadOnly: refuse non-Read operations (T4.3).
        if ("ReadOnly".equals(mode) && !"Read".equals(sideEffect))
        {
          lastTime = Math.max(System.currentTimeMillis(), lastTime + 1);
          final String refusedTimeStr = Long.toString(lastTime);
          final String refusedText    = buildRefusedText(sideEffect);

          final ObjAttrs refusedAttrs = new ObjAttrs();
          refusedAttrs.addAttr("ConvId",        convDomId.objId);
          refusedAttrs.addAttr("Role",          "tool");
          refusedAttrs.addAttr("Text",          opName + " (refused)");
          refusedAttrs.addAttr("Time",          refusedTimeStr);
          refusedAttrs.addAttr("ToolName",      opName);
          refusedAttrs.addAttr("ToolTargetSov", decodeTargetSov(call.argumentsJson));
          refusedAttrs.addAttr("ToolArgs",      call.argumentsJson != null ? call.argumentsJson : "{}");
          refusedAttrs.addAttr("ToolResult",    refusedText);
          refusedAttrs.addAttr("ToolStatus",    "error");
          refusedAttrs.addAttr("PendingId",     call.id);

          addMsgRow(convDomId, refusedAttrs, lastTime);
          toolMessages.add(new ToolMsgInfo(call.id, opName,
              decodeTargetSov(call.argumentsJson),
              call.argumentsJson != null ? call.argumentsJson : "{}",
              refusedText, "error", opName + " (refused)", refusedTimeStr));
          history.add(new LlmMessage("tool", refusedText, call.id, null));
          continue;
        }

        // Dispatch the tool call.
        final ToolDispatcher.Result r = ToolDispatcher.execute(
            callerDomId, callerContext, call, msgClient, registry);

        lastTime = Math.max(System.currentTimeMillis(), lastTime + 1);
        final String toolTimeStr  = Long.toString(lastTime);
        final String targetSovStr = r.targetSov != null ? r.targetSov.toString() : "";
        final String shortText    = opName + "(...) -> " + truncate(r.resultJson, 100);

        final ObjAttrs toolAttrs = new ObjAttrs();
        toolAttrs.addAttr("ConvId",        convDomId.objId);
        toolAttrs.addAttr("Role",          "tool");
        toolAttrs.addAttr("Text",          shortText);
        toolAttrs.addAttr("Time",          toolTimeStr);
        toolAttrs.addAttr("ToolName",      opName);
        toolAttrs.addAttr("ToolTargetSov", targetSovStr);
        toolAttrs.addAttr("ToolArgs",      r.argsJson);
        toolAttrs.addAttr("ToolResult",    r.resultJson);
        toolAttrs.addAttr("ToolStatus",    r.status);
        toolAttrs.addAttr("PendingId",     call.id);

        addMsgRow(convDomId, toolAttrs, lastTime);

        toolMessages.add(new ToolMsgInfo(call.id, opName, targetSovStr, r.argsJson,
                                          r.resultJson, r.status, shortText, toolTimeStr));

        history.add(new LlmMessage("tool",
            llmToolContent(r.resultJson),
            call.id, null));
      }

      finalFinish = "iterations";
    }

    // Synthesise a terminal assistant message when the loop ended abnormally
    // (but not when it paused for confirmation).
    if (!paused
        && ("error".equals(finalFinish) || "iterations".equals(finalFinish)
            || "deadline".equals(finalFinish) || "tokens".equals(finalFinish)))
    {
      if (finalText.isEmpty())
        finalText = "(agent loop terminated: " + finalFinish + ")";

      lastTime     = Math.max(System.currentTimeMillis(), lastTime + 1);
      finalAsgTime = Long.toString(lastTime);

      final ObjAttrs synAttrs = new ObjAttrs();
      synAttrs.addAttr("ConvId",       convDomId.objId);
      synAttrs.addAttr("Role",         "assistant");
      synAttrs.addAttr("Text",         finalText);
      synAttrs.addAttr("Time",         finalAsgTime);
      synAttrs.addAttr("Model",        model);
      synAttrs.addAttr("TokensIn",     Integer.toString(lastTokensIn));
      synAttrs.addAttr("TokensOut",    Integer.toString(lastTokensOut));
      synAttrs.addAttr("FinishReason", finalFinish);

      addMsgRow(convDomId, synAttrs, lastTime);
    }

    // ── 5. Update conv counters ───────────────────────────────────────────
    final int msgCount = 1 + toolMessages.size() + (paused ? 0 : 1);
    updateConvCounters(convDomId, convAttrs, msgCount,
                       finalAsgTime, totalTokensIn, totalTokensOut);

    return new Outcome(
        convDomId.objId,
        userText, nowStr,
        toolMessages,
        pendingToolCalls,
        finalText, finalAsgTime, model, finalFinish,
        lastTokensIn, lastTokensOut,
        totalTokensIn, totalTokensOut,
        iterationsUsed, finalFinish, mode);
  }

  // ── resume after user approves / rejects a pending tool call ─────────────

  /**
   * Resume a paused agent turn.  Called from ConvImpl after the user has
   * clicked Approve / Approve always / Reject on a pending tool-call card.
   *
   * Returns an ObjAttrs containing a "Resume" sub-document suitable for
   * nesting under the top-level response Attrs.
   */
  public static ObjAttrs resume(final DomId            convDomId,
                                 final ObjAttrs         convAttrs,
                                 final DomId            callerDomId,
                                 final Context          callerContext,
                                 final String           pendingId,
                                 final String           decision,
                                 final boolean          always,
                                 final DomatarMsgClient msgClient)
      throws DomatarException
  {
    Policy policy       = Policy.parse(safeGet(convAttrs, "Policy"));
    final String mode   = defaultIfNull(safeGet(convAttrs, "Mode"),  "Chat");
    final String model  = defaultIfNull(safeGet(convAttrs, "Model"), LlmClient.defaultModel);
    String systemPrompt = defaultIfNull(safeGet(convAttrs, "SystemPrompt"),
                                        "You are a helpful assistant.");

    // ── Parse PendingTurn ─────────────────────────────────────────────────
    final String pendingTurnJson = safeGet(convAttrs, "PendingTurn");
    List<LlmToolCall> remainingCalls;
    int  prevIterations;
    int  prevTokensIn;
    int  prevTokensOut;
    long lastTime;

    try
    {
      final JsonMap pt = Json.parseMap(pendingTurnJson != null ? pendingTurnJson : "{}");
      remainingCalls = deserializeToolCallList(pt.getList("RemainingCalls"));
      prevIterations = parseIntVal(pt.getString("IterationsUsed"));
      prevTokensIn   = parseIntVal(pt.getString("TotalTokensIn"));
      prevTokensOut  = parseIntVal(pt.getString("TotalTokensOut"));
      lastTime       = parseLongVal(pt.getString("LastTime"));
    }
    catch (Exception e)
    {
      remainingCalls = Collections.emptyList();
      prevIterations = 0;
      prevTokensIn   = 0;
      prevTokensOut  = 0;
      lastTime       = 0;
    }

    if (lastTime <= 0)
      lastTime = System.currentTimeMillis();

    // ── Find pending row ──────────────────────────────────────────────────
    final List<Obj> convMsgs = loadSortedConvMsgs(convDomId);

    Obj pendingRow = null;
    for (final Obj m : convMsgs)
    {
      if (pendingId.equals(safeGet(m.attrs, "PendingId"))
          && "pending".equals(safeGet(m.attrs, "ToolStatus")))
      {
        pendingRow = m;
        break;
      }
    }

    if (pendingRow == null)
      throw new DomatarException("Pending tool call not found: " + pendingId);

    final String toolName      = safeGet(pendingRow.attrs, "ToolName");
    final String toolTargetSov = safeGet(pendingRow.attrs, "ToolTargetSov");
    final String toolArgs      = defaultIfNull(safeGet(pendingRow.attrs, "ToolArgs"), "{}");
    // ToolCallName stores the original verb (e.g. "SendMsg") needed for
    // dispatch; ToolName is the canonical opName used for display/approval.
    final String toolCallName  = defaultIfNull(safeGet(pendingRow.attrs, "ToolCallName"), toolName);

    // ── Handle "always" ───────────────────────────────────────────────────
    if ("approve".equals(decision) && always)
    {
      final String newPolicyJson = addPreApprovedToPolicy(
          safeGet(convAttrs, "Policy"), toolName);
      convAttrs.addAttr("Policy", newPolicyJson);
      policy = Policy.parse(newPolicyJson);
    }

    // Reload useApp tools before dispatch. Approve of BindParty / ListContracts
    // (and any other dynamic tool) must hit the registry, not the legacy path.
    final DynamicToolRegistry registry = new DynamicToolRegistry();
    reloadPreviousApps(convMsgs, callerDomId, callerContext, msgClient, registry);

    // ── Resolve the pending row ───────────────────────────────────────────
    lastTime = Math.max(System.currentTimeMillis(), lastTime + 1);
    final String resolvedTimeStr = Long.toString(lastTime);

    final String resolvedStatus;
    final String resolvedResult;

    if ("approve".equals(decision))
    {
      final LlmToolCall        call = new LlmToolCall(pendingId, toolCallName, toolArgs);
      final ToolDispatcher.Result r = ToolDispatcher.execute(callerDomId, callerContext,
                                                              call, msgClient, registry);
      resolvedStatus = r.status;
      resolvedResult = r.resultJson != null ? r.resultJson : "";

      pendingRow.attrs.addAttr("ToolStatus", r.status);
      pendingRow.attrs.addAttr("ToolResult", resolvedResult);
      ObjDb.modifyObj(new Obj(pendingRow.domId, "aiagent", "msg",
                               pendingRow.objName, pendingRow.objDesc, pendingRow.attrs));
    }
    else
    {
      resolvedStatus = "rejected";
      resolvedResult = "";

      pendingRow.attrs.addAttr("ToolStatus", "rejected");
      pendingRow.attrs.addAttr("ToolResult", "");
      ObjDb.modifyObj(new Obj(pendingRow.domId, "aiagent", "msg",
                               pendingRow.objName, pendingRow.objDesc, pendingRow.attrs));
    }

    // Clear PendingTurn now (may be re-set below if we pause again).
    convAttrs.addAttr("PendingTurn", "");

    // ── Rebuild history from DB (resolved row now has correct ToolResult) ─
    final List<Obj> freshMsgs = loadSortedConvMsgs(convDomId);

    // ── Build tool catalogue ──────────────────────────────────────────────
    final List<LlmTool> baseTools = new ArrayList<>();
    baseTools.add(AgentDefaults.toolListApps());
    baseTools.add(AgentDefaults.toolUseApp());
    systemPrompt = Primer.build(callerContext) + "\n\n" + systemPrompt;

    final List<LlmMessage> history = buildHistory(freshMsgs, systemPrompt);

    // Re-register from the resolved history (same registry used for Approve).
    reloadPreviousApps(freshMsgs, callerDomId, callerContext, msgClient, registry);

    final SideEffectClassifier classifier = new SideEffectClassifier(
        callerDomId, callerContext, msgClient);

    final long deadline     = System.currentTimeMillis() + policy.maxMillis;
    int  totalTokensIn      = prevTokensIn;
    int  totalTokensOut     = prevTokensOut;
    int  iterationsUsed     = prevIterations;
    int  lastTokensIn       = 0;
    int  lastTokensOut      = 0;

    final List<ToolMsgInfo> newToolMsgs         = new ArrayList<>();
    final List<ToolMsgInfo> newPendingToolCalls  = new ArrayList<>();
    String  finalText      = "";
    String  finalFinish    = "error";
    String  finalAsgTime   = resolvedTimeStr;
    boolean paused         = false;

    // ── Process remaining calls from the paused batch ─────────────────────
    for (int i = 0; i < remainingCalls.size(); i++)
    {
      final LlmToolCall call   = remainingCalls.get(i);
      final String      opName = ToolDispatcher.canonicalOpName(call);

      final String  seRemain  = classifySideEffect(call, registry, classifier);
      final boolean needsConf = "Agent".equals(mode)
          && !"Read".equals(seRemain)
          && ("Destructive".equals(seRemain) || !policy.writePreApproved(opName));

      if (needsConf)
      {
        lastTime = Math.max(System.currentTimeMillis(), lastTime + 1);
        final String pendingTimeStr = Long.toString(lastTime);
        final String pendingSovStr  = decodeTargetSov(call.argumentsJson);

        final ObjAttrs pa = new ObjAttrs();
        pa.addAttr("ConvId",        convDomId.objId);
        pa.addAttr("Role",          "tool");
        pa.addAttr("Text",          opName + " (pending)");
        pa.addAttr("Time",          pendingTimeStr);
        pa.addAttr("ToolName",      opName);
        pa.addAttr("ToolCallName",  call.name);
        pa.addAttr("ToolTargetSov", pendingSovStr);
        pa.addAttr("ToolArgs",      call.argumentsJson);
        pa.addAttr("ToolResult",    "");
        pa.addAttr("ToolStatus",    "pending");
        pa.addAttr("PendingId",     call.id);

        addMsgRow(convDomId, pa, lastTime);

        final List<LlmToolCall> stillRemaining =
            remainingCalls.subList(i + 1, remainingCalls.size());
        convAttrs.addAttr("PendingTurn",
            serializePendingTurn(stillRemaining, iterationsUsed,
                                 totalTokensIn, totalTokensOut, lastTime));

        newPendingToolCalls.add(new ToolMsgInfo(call.id, opName, pendingSovStr,
                                                 call.argumentsJson, "", "pending",
                                                 opName + " (pending)", pendingTimeStr));
        paused      = true;
        finalFinish = "awaiting_user_confirmation";
        break;
      }

      // ReadOnly: refuse non-Read operations (T4.3).
      if ("ReadOnly".equals(mode) && !"Read".equals(seRemain))
      {
        lastTime = Math.max(System.currentTimeMillis(), lastTime + 1);
        final String refusedTimeStr = Long.toString(lastTime);
        final String refusedText    = buildRefusedText(seRemain);

        final ObjAttrs ra = new ObjAttrs();
        ra.addAttr("ConvId",        convDomId.objId);
        ra.addAttr("Role",          "tool");
        ra.addAttr("Text",          opName + " (refused)");
        ra.addAttr("Time",          refusedTimeStr);
        ra.addAttr("ToolName",      opName);
        ra.addAttr("ToolTargetSov", decodeTargetSov(call.argumentsJson));
        ra.addAttr("ToolArgs",      call.argumentsJson != null ? call.argumentsJson : "{}");
        ra.addAttr("ToolResult",    refusedText);
        ra.addAttr("ToolStatus",    "error");
        ra.addAttr("PendingId",     call.id);

        addMsgRow(convDomId, ra, lastTime);
        newToolMsgs.add(new ToolMsgInfo(call.id, opName,
            decodeTargetSov(call.argumentsJson),
            call.argumentsJson != null ? call.argumentsJson : "{}",
            refusedText, "error", opName + " (refused)", refusedTimeStr));
        history.add(new LlmMessage("tool", refusedText, call.id, null));
        continue;
      }

      final ToolDispatcher.Result r = ToolDispatcher.execute(
          callerDomId, callerContext, call, msgClient, registry);

      lastTime = Math.max(System.currentTimeMillis(), lastTime + 1);
      final String toolTimeStr  = Long.toString(lastTime);
      final String targetSovStr = r.targetSov != null ? r.targetSov.toString() : "";
      final String shortText    = opName + "(...) -> " + truncate(r.resultJson, 100);

      final ObjAttrs ta = new ObjAttrs();
      ta.addAttr("ConvId",        convDomId.objId);
      ta.addAttr("Role",          "tool");
      ta.addAttr("Text",          shortText);
      ta.addAttr("Time",          toolTimeStr);
      ta.addAttr("ToolName",      opName);
      ta.addAttr("ToolTargetSov", targetSovStr);
      ta.addAttr("ToolArgs",      r.argsJson);
      ta.addAttr("ToolResult",    r.resultJson);
      ta.addAttr("ToolStatus",    r.status);
      ta.addAttr("PendingId",     call.id);

      addMsgRow(convDomId, ta, lastTime);

      newToolMsgs.add(new ToolMsgInfo(call.id, opName, targetSovStr, r.argsJson,
                                       r.resultJson, r.status, shortText, toolTimeStr));
      history.add(new LlmMessage("tool",
          llmToolContent(r.resultJson), call.id, null));
    }

    // ── Continue main loop (if not paused again) ──────────────────────────
    if (!paused)
    {
      final int maxIter = Math.max(1, policy.maxIterations - iterationsUsed);

      outer2:
      for (int iter = 0; iter < maxIter; iter++)
      {
        if (System.currentTimeMillis() > deadline)
        {
          finalFinish = "deadline";
          break;
        }
        if (totalTokensIn  > policy.maxTokensIn
            || totalTokensOut > policy.maxTokensOut)
        {
          finalFinish = "tokens";
          break;
        }

        final List<LlmTool> tools = assembleTools(baseTools, registry);
        LlmResult result;
        try
        {
          result = LlmClient.complete(model, history,
                                      tools.isEmpty() ? null : tools, "auto");
        }
        catch (DomatarException e)
        {
          finalText   = "(error: " + e.getMessage() + ")";
          finalFinish = "error";
          break;
        }

        totalTokensIn  += result.tokensIn;
        totalTokensOut += result.tokensOut;
        lastTokensIn    = result.tokensIn;
        lastTokensOut   = result.tokensOut;
        iterationsUsed++;

        if (result.toolCalls.isEmpty())
        {
          finalText   = result.text != null ? result.text : "";
          finalFinish = result.finishReason;

          lastTime     = Math.max(System.currentTimeMillis(), lastTime + 1);
          finalAsgTime = Long.toString(lastTime);

          final ObjAttrs aa = new ObjAttrs();
          aa.addAttr("ConvId",       convDomId.objId);
          aa.addAttr("Role",         "assistant");
          aa.addAttr("Text",         finalText);
          aa.addAttr("Time",         finalAsgTime);
          aa.addAttr("Model",        model);
          aa.addAttr("TokensIn",     Integer.toString(result.tokensIn));
          aa.addAttr("TokensOut",    Integer.toString(result.tokensOut));
          aa.addAttr("FinishReason", result.finishReason);

          addMsgRow(convDomId, aa, lastTime);
          break;
        }

        lastTime     = Math.max(System.currentTimeMillis(), lastTime + 1);
        finalAsgTime = Long.toString(lastTime);

        final String toolCallsJson = serializeToolCalls(result.toolCalls);
        final String asgText       = result.text != null ? result.text : "";

        final ObjAttrs aa = new ObjAttrs();
        aa.addAttr("ConvId",        convDomId.objId);
        aa.addAttr("Role",          "assistant");
        aa.addAttr("Text",          asgText);
        aa.addAttr("Time",          finalAsgTime);
        aa.addAttr("Model",         model);
        aa.addAttr("TokensIn",      Integer.toString(result.tokensIn));
        aa.addAttr("TokensOut",     Integer.toString(result.tokensOut));
        aa.addAttr("FinishReason",  result.finishReason);
        aa.addAttr("ToolCallsJson", toolCallsJson);

        addMsgRow(convDomId, aa, lastTime);
        history.add(new LlmMessage("assistant", asgText, null, result.toolCalls));

        for (int k = 0; k < result.toolCalls.size(); k++)
        {
          final LlmToolCall call   = result.toolCalls.get(k);
          final String      opName = ToolDispatcher.canonicalOpName(call);

          final String  seCont    = classifySideEffect(call, registry, classifier);
          final boolean needsConf = "Agent".equals(mode)
              && !"Read".equals(seCont)
              && ("Destructive".equals(seCont) || !policy.writePreApproved(opName));

          if (needsConf)
          {
            lastTime = Math.max(System.currentTimeMillis(), lastTime + 1);
            final String pendingTimeStr = Long.toString(lastTime);
            final String pendingSovStr  = decodeTargetSov(call.argumentsJson);

            final ObjAttrs pa = new ObjAttrs();
            pa.addAttr("ConvId",        convDomId.objId);
            pa.addAttr("Role",          "tool");
            pa.addAttr("Text",          opName + " (pending)");
            pa.addAttr("Time",          pendingTimeStr);
            pa.addAttr("ToolName",      opName);
            pa.addAttr("ToolCallName",  call.name);
            pa.addAttr("ToolTargetSov", pendingSovStr);
            pa.addAttr("ToolArgs",      call.argumentsJson);
            pa.addAttr("ToolResult",    "");
            pa.addAttr("ToolStatus",    "pending");
            pa.addAttr("PendingId",     call.id);

            addMsgRow(convDomId, pa, lastTime);

            final List<LlmToolCall> stillRemaining =
                result.toolCalls.subList(k + 1, result.toolCalls.size());
            convAttrs.addAttr("PendingTurn",
                serializePendingTurn(stillRemaining, iterationsUsed,
                                     totalTokensIn, totalTokensOut, lastTime));

            newPendingToolCalls.add(new ToolMsgInfo(call.id, opName, pendingSovStr,
                                                     call.argumentsJson, "", "pending",
                                                     opName + " (pending)", pendingTimeStr));
            paused      = true;
            finalFinish = "awaiting_user_confirmation";
            break outer2;
          }

          // ReadOnly: refuse non-Read operations (T4.3).
          if ("ReadOnly".equals(mode) && !"Read".equals(seCont))
          {
            lastTime = Math.max(System.currentTimeMillis(), lastTime + 1);
            final String refusedTimeStr = Long.toString(lastTime);
            final String refusedText    = buildRefusedText(seCont);

            final ObjAttrs ra = new ObjAttrs();
            ra.addAttr("ConvId",        convDomId.objId);
            ra.addAttr("Role",          "tool");
            ra.addAttr("Text",          opName + " (refused)");
            ra.addAttr("Time",          refusedTimeStr);
            ra.addAttr("ToolName",      opName);
            ra.addAttr("ToolTargetSov", decodeTargetSov(call.argumentsJson));
            ra.addAttr("ToolArgs",      call.argumentsJson != null ? call.argumentsJson : "{}");
            ra.addAttr("ToolResult",    refusedText);
            ra.addAttr("ToolStatus",    "error");
            ra.addAttr("PendingId",     call.id);

            addMsgRow(convDomId, ra, lastTime);
            newToolMsgs.add(new ToolMsgInfo(call.id, opName,
                decodeTargetSov(call.argumentsJson),
                call.argumentsJson != null ? call.argumentsJson : "{}",
                refusedText, "error", opName + " (refused)", refusedTimeStr));
            history.add(new LlmMessage("tool", refusedText, call.id, null));
            continue;
          }

          final ToolDispatcher.Result r = ToolDispatcher.execute(
              callerDomId, callerContext, call, msgClient, registry);

          lastTime = Math.max(System.currentTimeMillis(), lastTime + 1);
          final String toolTimeStr  = Long.toString(lastTime);
          final String targetSovStr = r.targetSov != null ? r.targetSov.toString() : "";
          final String shortText    = opName + "(...) -> " + truncate(r.resultJson, 100);

          final ObjAttrs ta = new ObjAttrs();
          ta.addAttr("ConvId",        convDomId.objId);
          ta.addAttr("Role",          "tool");
          ta.addAttr("Text",          shortText);
          ta.addAttr("Time",          toolTimeStr);
          ta.addAttr("ToolName",      opName);
          ta.addAttr("ToolTargetSov", targetSovStr);
          ta.addAttr("ToolArgs",      r.argsJson);
          ta.addAttr("ToolResult",    r.resultJson);
          ta.addAttr("ToolStatus",    r.status);
          ta.addAttr("PendingId",     call.id);

          addMsgRow(convDomId, ta, lastTime);
          newToolMsgs.add(new ToolMsgInfo(call.id, call.name, targetSovStr, r.argsJson,
                                           r.resultJson, r.status, shortText, toolTimeStr));
          history.add(new LlmMessage("tool",
              llmToolContent(r.resultJson), call.id, null));
        }

        if (!paused)
          finalFinish = "iterations";
      }

      if (!paused
          && ("error".equals(finalFinish) || "iterations".equals(finalFinish)
              || "deadline".equals(finalFinish) || "tokens".equals(finalFinish)))
      {
        if (finalText.isEmpty())
          finalText = "(agent loop terminated: " + finalFinish + ")";

        lastTime     = Math.max(System.currentTimeMillis(), lastTime + 1);
        finalAsgTime = Long.toString(lastTime);

        final ObjAttrs sa = new ObjAttrs();
        sa.addAttr("ConvId",       convDomId.objId);
        sa.addAttr("Role",         "assistant");
        sa.addAttr("Text",         finalText);
        sa.addAttr("Time",         finalAsgTime);
        sa.addAttr("Model",        model);
        sa.addAttr("TokensIn",     Integer.toString(lastTokensIn));
        sa.addAttr("TokensOut",    Integer.toString(lastTokensOut));
        sa.addAttr("FinishReason", finalFinish);

        addMsgRow(convDomId, sa, lastTime);
      }
    }

    // ── Update conv counters ──────────────────────────────────────────────
    final int msgCount = newToolMsgs.size() + (paused ? 0 : 1);
    updateConvCounters(convDomId, convAttrs, msgCount,
                       finalAsgTime, totalTokensIn - prevTokensIn,
                       totalTokensOut - prevTokensOut);

    // ── Build resume response ─────────────────────────────────────────────
    final ObjAttrs resumeAttrs = new ObjAttrs();
    resumeAttrs.addAttr("ResolvedPendingId",     pendingId);
    resumeAttrs.addAttr("ResolvedStatus",        resolvedStatus);
    resumeAttrs.addAttr("ResolvedToolName",      toolName      != null ? toolName      : "");
    resumeAttrs.addAttr("ResolvedToolTargetSov", toolTargetSov != null ? toolTargetSov : "");
    resumeAttrs.addAttr("ResolvedToolResult",    resolvedResult);

    final JsonList toolList = toolMsgInfoToJson(newToolMsgs);
    resumeAttrs.addAttr("ToolMessages", toolList);

    if (!paused && !finalText.isEmpty())
    {
      final JsonMap asgMap = new JsonHashMap(7);
      asgMap.put("Role",         "assistant");
      asgMap.put("Text",         finalText);
      asgMap.put("Time",         finalAsgTime);
      asgMap.put("Model",        model);
      asgMap.put("TokensIn",     Integer.toString(lastTokensIn));
      asgMap.put("TokensOut",    Integer.toString(lastTokensOut));
      asgMap.put("FinishReason", finalFinish);
      resumeAttrs.addAttr("AssistantMessage", asgMap);
    }

    if (!newPendingToolCalls.isEmpty())
      resumeAttrs.addAttr("PendingToolCalls",
          toolMsgInfoToJson(newPendingToolCalls));

    resumeAttrs.addAttr("FinishReason", finalFinish);
    return resumeAttrs;
  }

  // ── public result types ──────────────────────────────────────────────────

  /** Carries everything needed to build the WUI SendMessage response. */
  public static class Outcome
  {
    public final String            convId;
    public final String            userText;
    public final String            userTime;
    public final List<ToolMsgInfo> toolMessages;
    public final List<ToolMsgInfo> pendingToolCalls;
    public final String            assistantText;
    public final String            assistantTime;
    public final String            assistantModel;
    public final String            assistantFinishReason;
    public final int               lastTokensIn;
    public final int               lastTokensOut;
    public final int               totalTokensIn;
    public final int               totalTokensOut;
    public final int               iterationsUsed;
    public final String            loopFinishReason;
    public final String            mode;

    Outcome(final String convId,
            final String userText, final String userTime,
            final List<ToolMsgInfo> toolMessages,
            final List<ToolMsgInfo> pendingToolCalls,
            final String assistantText, final String assistantTime,
            final String assistantModel, final String assistantFinishReason,
            final int lastTokensIn, final int lastTokensOut,
            final int totalTokensIn, final int totalTokensOut,
            final int iterationsUsed, final String loopFinishReason, final String mode)
    {
      this.convId                = convId;
      this.userText              = userText;
      this.userTime              = userTime;
      this.toolMessages          = toolMessages;
      this.pendingToolCalls      = pendingToolCalls;
      this.assistantText         = assistantText;
      this.assistantTime         = assistantTime;
      this.assistantModel        = assistantModel;
      this.assistantFinishReason = assistantFinishReason;
      this.lastTokensIn          = lastTokensIn;
      this.lastTokensOut         = lastTokensOut;
      this.totalTokensIn         = totalTokensIn;
      this.totalTokensOut        = totalTokensOut;
      this.iterationsUsed        = iterationsUsed;
      this.loopFinishReason      = loopFinishReason;
      this.mode                  = mode;
    }

    /**
     * Build the ObjAttrs that goes into outMsg.addResponseBody("SendMessage", …).
     * When paused (pendingToolCalls non-empty), AssistantMessage is omitted and
     * PendingToolCalls is included instead.
     */
    public ObjAttrs toResponseAttrs()
        throws DomatarException
    {
      final JsonMap userMap = new JsonHashMap(3);
      userMap.put("Role", "user");
      userMap.put("Text", userText);
      userMap.put("Time", userTime);

      final ObjAttrs out = new ObjAttrs();
      out.addAttr("ConvId",       convId);
      out.addAttr("UserMessage",  userMap);
      out.addAttr("ToolMessages", toolMsgInfoToJson(toolMessages));
      out.addAttr("Mode",         mode);
      out.addAttr("FinishReason", loopFinishReason);

      if (!pendingToolCalls.isEmpty())
      {
        // Loop paused — no assistant message yet; return pending cards.
        out.addAttr("PendingToolCalls", toolMsgInfoToJson(pendingToolCalls));
      }
      else
      {
        final JsonMap asgMap = new JsonHashMap(7);
        asgMap.put("Role",         "assistant");
        asgMap.put("Text",         assistantText);
        asgMap.put("Time",         assistantTime);
        asgMap.put("Model",        assistantModel);
        asgMap.put("TokensIn",     Integer.toString(lastTokensIn));
        asgMap.put("TokensOut",    Integer.toString(lastTokensOut));
        asgMap.put("FinishReason", assistantFinishReason);
        out.addAttr("AssistantMessage", asgMap);
      }

      out.addAttr("TokensInTurn",   Integer.toString(totalTokensIn));
      out.addAttr("TokensOutTurn",  Integer.toString(totalTokensOut));
      out.addAttr("IterationsUsed", Integer.toString(iterationsUsed));
      return out;
    }
  }

  /** Per-tool-call summary held in the Outcome. Also used for pending calls. */
  public static class ToolMsgInfo
  {
    public final String pendingId;
    public final String toolName;
    public final String toolTargetSov;
    public final String toolArgs;
    public final String toolResult;
    public final String toolStatus;
    public final String text;
    public final String time;

    ToolMsgInfo(final String pendingId, final String toolName, final String toolTargetSov,
                final String toolArgs, final String toolResult,
                final String toolStatus, final String text, final String time)
    {
      this.pendingId     = pendingId;
      this.toolName      = toolName;
      this.toolTargetSov = toolTargetSov;
      this.toolArgs      = toolArgs;
      this.toolResult    = toolResult;
      this.toolStatus    = toolStatus;
      this.text          = text;
      this.time          = time;
    }
  }

  // ── private helpers ──────────────────────────────────────────────────────

  /**
   * Build the LlmMessage list for the LLM from persisted msg rows.
   * Skips Role="tool" rows with ToolStatus="pending" (no result yet).
   * Uses "User rejected this tool call." as the content for "rejected" rows.
   */
  private static List<LlmMessage> buildHistory(List<Obj> msgs, final String systemPrompt)
      throws DomatarException
  {
    if (msgs.size() > MAX_HISTORY_MSGS)
      msgs = msgs.subList(msgs.size() - MAX_HISTORY_MSGS, msgs.size());

    final List<LlmMessage> history = new ArrayList<>(msgs.size() + 1);
    history.add(new LlmMessage("system", systemPrompt));

    for (final Obj m : msgs)
    {
      final String role = safeGet(m.attrs, "Role");
      final String text = safeGet(m.attrs, "Text");

      if ("user".equals(role))
      {
        history.add(new LlmMessage("user", text != null ? text : ""));
      }
      else if ("assistant".equals(role))
      {
        final String toolCallsJson = safeGet(m.attrs, "ToolCallsJson");
        if (toolCallsJson != null && !toolCallsJson.isEmpty())
        {
          final List<LlmToolCall> toolCalls = deserializeToolCalls(toolCallsJson);
          history.add(new LlmMessage("assistant",
              text != null ? text : "", null, toolCalls));
        }
        else
        {
          String asg = text != null ? text : "";
          if (asg.length() > MAX_TOOL_RESULT_CHARS
              || asg.startsWith("(error:"))
            asg = truncateForLlm(asg);
          history.add(new LlmMessage("assistant", asg));
        }
      }
      else if ("tool".equals(role))
      {
        final String toolStatus = safeGet(m.attrs, "ToolStatus");
        if ("pending".equals(toolStatus))
          continue; // no result yet

        final String pendingId  = safeGet(m.attrs, "PendingId");
        final String toolResult = safeGet(m.attrs, "ToolResult");

        final String content;
        if ("rejected".equals(toolStatus))
          content = "User rejected this tool call.";
        else
          content = llmToolContent(toolResult);

        history.add(new LlmMessage("tool", content, pendingId, null));
      }
    }

    return history;
  }

  /** Slim JSON for the completion prompt; the msg row still stores the full result. */
  private static String llmToolContent(final String raw)
  {
    return truncateForLlm(ToolResultCompact.forLlm(raw));
  }

  private static String truncateForLlm(final String s)
  {
    if (s == null || s.length() <= MAX_TOOL_RESULT_CHARS)
      return s != null ? s : "";
    return s.substring(0, MAX_TOOL_RESULT_CHARS) + "\u2026(truncated)";
  }

  /** Load all msg rows for this conversation, sorted by Time ascending. */
  private static List<Obj> loadSortedConvMsgs(final DomId convDomId)
      throws DomatarException
  {
    final List<Obj> allMsgs = ObjDb.getObjPrefix(
        convDomId.hstId, "aiagent", convDomId.actId, "msg", null, 1000);

    final List<Obj> convMsgs = filterByConvId(allMsgs, convDomId.objId);
    convMsgs.sort((a, b) ->
        Long.compare(parseLong(safeGet(a.attrs, "Time")),
                     parseLong(safeGet(b.attrs, "Time"))));
    return convMsgs;
  }

  /** Persist one msg row and add a conv→msg lnk. */
  static DomId addMsgRow(final DomId    convDomId,
                          final ObjAttrs msgAttrs,
                          final long     seqNum)
      throws DomatarException
  {
    final String msgId    = IdGen.createIdFromCurTime("msg");
    final DomId  msgDomId = new DomId(convDomId.hstId, "aiagent",
                                      convDomId.actId, msgId);

    final String text    = safeGet(msgAttrs, "Text");
    final String objName = text != null && text.length() > 40
                           ? text.substring(0, 40) : (text != null ? text : "");
    final String objDesc = safeGet(msgAttrs, "Role") + ": " + objName;

    ObjDb.addObj(new Obj(msgDomId, "aiagent", "msg", objName, objDesc, msgAttrs));

    LnkDb.addLnk(new Lnk(convDomId, msgDomId,
                          "aiagent", "msg",
                          objName, objDesc,
                          "aiagent", "msg",
                          null, seqNum));
    return msgDomId;
  }

  /** Update conv counters and persist the conv row. */
  private static void updateConvCounters(final DomId    convDomId,
                                          final ObjAttrs convAttrs,
                                          final int      newMsgCount,
                                          final String   updatedAtStr,
                                          final int      addTokensIn,
                                          final int      addTokensOut)
      throws DomatarException
  {
    final long prevCount  = parseLong(safeGet(convAttrs, "MessageCount"));
    final long prevTokIn  = parseLong(safeGet(convAttrs, "TokensIn"));
    final long prevTokOut = parseLong(safeGet(convAttrs, "TokensOut"));

    convAttrs.addAttr("MessageCount", Long.toString(prevCount  + newMsgCount));
    convAttrs.addAttr("UpdatedAt",    updatedAtStr);
    convAttrs.addAttr("TokensIn",     Long.toString(prevTokIn  + addTokensIn));
    convAttrs.addAttr("TokensOut",    Long.toString(prevTokOut + addTokensOut));

    final String title   = safeGet(convAttrs, "Title");
    final String objName = (title != null && !title.isEmpty()) ? title : "";

    ObjDb.modifyObj(new Obj(convDomId, "aiagent", "conv", objName, "", convAttrs));
  }

  /** Serialise List<LlmToolCall> to JSON array string for storage. */
  private static String serializeToolCalls(final List<LlmToolCall> toolCalls)
      throws DomatarException
  {
    final JsonList arr = new JsonArrayList(toolCalls.size());

    for (final LlmToolCall tc : toolCalls)
    {
      final JsonMap m = new JsonHashMap(3);
      m.put("id",            tc.id);
      m.put("name",          tc.name);
      m.put("argumentsJson", tc.argumentsJson);
      arr.add(m);
    }

    return Json.toJson(arr);
  }

  /** Deserialise JSON array string back to List<LlmToolCall>. */
  private static List<LlmToolCall> deserializeToolCalls(final String json)
  {
    try
    {
      final JsonList arr = Json.parseList(json);
      return toolCallsFromList(arr);
    }
    catch (Exception e)
    {
      return Collections.emptyList();
    }
  }

  private static List<LlmToolCall> deserializeToolCallList(final JsonList list)
  {
    if (list == null)
      return Collections.emptyList();
    return toolCallsFromList(list);
  }

  private static List<LlmToolCall> toolCallsFromList(final JsonList arr)
  {
    final List<LlmToolCall> result = new ArrayList<>(arr.size());
    for (final Object o : arr)
    {
      if (!(o instanceof JsonMap))
        continue;
      final JsonMap m = (JsonMap) o;
      result.add(new LlmToolCall(
          m.getString("id"),
          m.getString("name"),
          m.getString("argumentsJson")));
    }
    return result;
  }

  /** Serialise PendingTurn state to JSON string for storage in conv attrs. */
  private static String serializePendingTurn(final List<LlmToolCall> remaining,
                                              final int iterationsUsed,
                                              final int totalTokensIn,
                                              final int totalTokensOut,
                                              final long lastTime)
      throws DomatarException
  {
    final JsonList remainArr = new JsonArrayList(remaining.size());
    for (final LlmToolCall tc : remaining)
    {
      final JsonMap m = new JsonHashMap(3);
      m.put("id",            tc.id);
      m.put("name",          tc.name);
      m.put("argumentsJson", tc.argumentsJson);
      remainArr.add(m);
    }

    final JsonMap pt = new JsonHashMap(5);
    pt.put("RemainingCalls",  remainArr);
    pt.put("IterationsUsed",  String.valueOf(iterationsUsed));
    pt.put("TotalTokensIn",   String.valueOf(totalTokensIn));
    pt.put("TotalTokensOut",  String.valueOf(totalTokensOut));
    pt.put("LastTime",        String.valueOf(lastTime));
    return Json.toJson(pt);
  }

  /**
   * Add a tool name to the Policy's PreApprovedWrites list and return the
   * updated Policy JSON string.  Preserves all other policy fields.
   */
  static String addPreApprovedToPolicy(final String policyJson, final String toolName)
      throws DomatarException
  {
    final Policy cur = Policy.parse(policyJson == null ? "{}" : policyJson);

    // Pre-approved list with the new tool name added (deduplicated).
    final List<String> newPre = new ArrayList<>(cur.preApprovedWrites);
    if (!newPre.contains(toolName))
      newPre.add(toolName);

    final JsonMap map = new JsonHashMap(5);

    if (cur.allowApps != null)
    {
      final JsonList l = new JsonArrayList(cur.allowApps.size());
      for (final String s : cur.allowApps)
        l.add(s);
      map.put("AllowApps", l);
    }

    if (cur.denyApps != null)
    {
      final JsonList l = new JsonArrayList(cur.denyApps.size());
      for (final String s : cur.denyApps)
        l.add(s);
      map.put("DenyApps", l);
    }

    final JsonList preList = new JsonArrayList(newPre.size());
    for (final String s : newPre)
      preList.add(s);
    map.put("PreApprovedWrites", preList);

    // Preserve budgets.
    final JsonMap budgets = new JsonHashMap(4);
    budgets.put("MaxIterations", String.valueOf(cur.maxIterations));
    budgets.put("MaxMillis",     String.valueOf(cur.maxMillis));
    budgets.put("MaxTokensIn",   String.valueOf(cur.maxTokensIn));
    budgets.put("MaxTokensOut",  String.valueOf(cur.maxTokensOut));
    map.put("Budgets", budgets);

    return Json.toJson(map);
  }

  /**
   * Assemble the working tool list for one LLM call:
   * base tools (listApps, useApp) plus the registry's current dynamic tools.
   */
  private static List<LlmTool> assembleTools(final List<LlmTool>       baseTools,
                                              final DynamicToolRegistry registry)
  {
    final List<LlmTool> tools = new ArrayList<>(baseTools);
    tools.addAll(registry.getTools());
    return tools;
  }

  /**
   * Classify the side effect of a tool call.
   *
   * Priority:
   *   1. listApps / useApp are always "Read".
   *   2. O(1) registry lookup for dynamic tools.
   *   3. Fallback: SideEffectClassifier (fetches class descriptor).
   */
  private static String classifySideEffect(final LlmToolCall          call,
                                            final DynamicToolRegistry  registry,
                                            final SideEffectClassifier classifier)
  {
    if (AgentDefaults.TOOL_LIST_APPS.equals(call.name)
        || AgentDefaults.TOOL_USE_APP.equals(call.name))
      return "Read";

    if (registry != null)
    {
      final DynamicToolRegistry.Entry e = registry.lookup(call.name);
      if (e != null)
        return e.sideEffect;
    }

    return classifier.classify(call);
  }

  /**
   * Build the text injected into the conversation when ReadOnly mode blocks a call.
   * If classification itself threw (sentinel prefix), surface the exception detail
   * instead of the misleading "read-only" message.
   */
  private static String buildRefusedText(final String sideEffect)
  {
    if (sideEffect != null && sideEffect.startsWith(AgentDefaults.SIDE_EFFECT_CLASSIFY_ERROR))
    {
      final String detail = sideEffect.substring(AgentDefaults.SIDE_EFFECT_CLASSIFY_ERROR.length());
      return "(refused: could not classify operation side-effect — " + detail
          + ". This is a server-side bug; the operation was not blocked for policy reasons.)";
    }
    if (sideEffect != null && sideEffect.startsWith(AgentDefaults.SIDE_EFFECT_OP_NOT_FOUND))
    {
      final String rest   = sideEffect.substring(AgentDefaults.SIDE_EFFECT_OP_NOT_FOUND.length());
      final int    nl     = rest.indexOf('\n');
      final String opName = nl >= 0 ? rest.substring(0, nl) : rest;
      final String valid  = nl >= 0 ? rest.substring(nl + 1).trim() : "";
      final StringBuilder sb = new StringBuilder();
      sb.append("(refused: operation '").append(opName)
        .append("' does not exist on this object. ");
      if (!valid.isEmpty())
        sb.append("The valid operations are: ").append(valid).append(". ");
      sb.append("Use ONLY these operation names. Do not invent names.)");
      return sb.toString();
    }
    return "(refused: this conversation is read-only; "
        + "only Read operations are permitted. Choose a Read operation "
        + "or ask the user to switch to Agent mode.)";
  }

  /** Try to extract the _target DomId string from a tool-call argumentsJson. */
  private static String decodeTargetSov(final String argsJson)
  {
    if (argsJson == null || argsJson.isEmpty())
      return "";
    try
    {
      final JsonMap args   = Json.parseMap(argsJson);
      final JsonMap target = args.getMap("_target");
      if (target == null)
        return "";
      final String hstId = target.getString("HstId");
      final String appId = target.getString("AppId");
      final String actId = target.getString("ActId");
      final String objId = target.getString("ObjId");
      return new DomId(hstId, appId, actId, objId).toString();
    }
    catch (Exception e)
    {
      return "";
    }
  }

  /** Serialise a List<ToolMsgInfo> to a JsonList for use in response attrs. */
  private static JsonList toolMsgInfoToJson(final List<ToolMsgInfo> list)
  {
    final JsonList arr = new JsonArrayList(list.size());
    for (final ToolMsgInfo tm : list)
    {
      final JsonMap m = new JsonHashMap(9);
      m.put("Role",          "tool");
      m.put("Text",          tm.text);
      m.put("Time",          tm.time);
      m.put("PendingId",     tm.pendingId);
      m.put("ToolName",      tm.toolName);
      m.put("ToolStatus",    tm.toolStatus);
      m.put("ToolTargetSov", tm.toolTargetSov);
      m.put("ToolArgs",      tm.toolArgs);
      m.put("ToolResult",    tm.toolResult);
      arr.add(m);
    }
    return arr;
  }

  /**
   * Finds the most-recent successful useApp call in the conversation history
   * and re-executes it to re-populate the registry.
   *
   * The registry holds exactly one app's tools at a time (by design), so only
   * the last useApp call matters — replaying it is sufficient to restore the
   * tool set the LLM expects for the next turn.
   */
  private static void reloadPreviousApps(final List<Obj>            convMsgs,
                                          final DomId                callerDomId,
                                          final Context              callerContext,
                                          final DomatarMsgClient     msgClient,
                                          final DynamicToolRegistry  registry)
  {
    // Walk backwards to find the most-recent successful useApp call.
    String lastAppName = null;
    for (int i = convMsgs.size() - 1; i >= 0; i--)
    {
      final Obj    m          = convMsgs.get(i);
      final String toolName   = safeGet(m.attrs, "ToolName");
      final String toolStatus = safeGet(m.attrs, "ToolStatus");
      final String toolArgs   = safeGet(m.attrs, "ToolArgs");

      if (!AgentDefaults.TOOL_USE_APP.equals(toolName))
        continue;
      if (!"ok".equals(toolStatus))
        continue;

      try
      {
        final JsonMap args = Json.parseMap(toolArgs != null ? toolArgs : "{}");
        final String  name = args.getString("name");
        if (name != null && !name.isEmpty())
        {
          lastAppName = name;
          break;
        }
      }
      catch (Exception ignored)
      {
      }
    }

    if (lastAppName == null)
      return;

    try
    {
      final String      argsJson = "{\"name\":\"" + lastAppName + "\"}";
      final LlmToolCall call     = new LlmToolCall("reload-" + lastAppName,
                                                   AgentDefaults.TOOL_USE_APP,
                                                   argsJson);
      ToolDispatcher.execute(callerDomId, callerContext, call, msgClient, registry);
    }
    catch (Exception ignored)
    {
    }
  }

  /** Filter a list of msg objs to those belonging to the given convId. */
  private static List<Obj> filterByConvId(final List<Obj> msgs, final String convId)
  {
    final List<Obj> result = new ArrayList<>();
    for (final Obj m : msgs)
    {
      if (convId.equals(safeGet(m.attrs, "ConvId")))
        result.add(m);
    }
    return result;
  }

  static String safeGet(final ObjAttrs attrs, final String key)
  {
    if (attrs == null)
      return null;
    try
    {
      return attrs.getAttr(key);
    }
    catch (Exception e)
    {
      return null;
    }
  }

  static long parseLong(final String s)
  {
    if (s == null)
      return 0L;
    try
    {
      return Long.parseLong(s);
    }
    catch (NumberFormatException e)
    {
      return 0L;
    }
  }

  private static int parseIntVal(final String s)
  {
    if (s == null)
      return 0;
    try
    {
      return Integer.parseInt(s);
    }
    catch (NumberFormatException e)
    {
      return 0;
    }
  }

  private static long parseLongVal(final String s)
  {
    if (s == null)
      return 0L;
    try
    {
      return Long.parseLong(s);
    }
    catch (NumberFormatException e)
    {
      return 0L;
    }
  }

  private static String defaultIfNull(final String v, final String d)
  {
    return (v == null || v.isEmpty()) ? d : v;
  }

  private static String truncate(final String s, final int max)
  {
    if (s == null)
      return "";
    return s.length() <= max ? s : s.substring(0, max) + "\u2026";
  }
}
