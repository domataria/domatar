/*
 * Copyright (c) 2024 Domatar
 */

package com.aiagent.objimpl;

import java.util.ArrayList;
import java.util.List;

import com.domatar.core.Auth;
import com.domatar.core.Context;
import com.domatar.db.LnkDb;
import com.domatar.db.ObjDb;
import com.domatar.util.IdGen;
import com.domatar.util.Json;
import com.domatar.util.JsonArrayList;
import com.domatar.util.JsonHashMap;
import com.domatar.util.JsonList;
import com.domatar.util.JsonMap;
import com.domatar.util.JsonMsg;
import com.domatar.util.Lnk;
import com.domatar.util.Obj;
import com.domatar.util.ObjAttrs;
import com.domatar.util.ObjImpl;
import com.domatar.util.DomId;
import com.domatar.util.DomatarException;
import com.domatar.util.DomatarMsgClient;

import com.aiagent.agent.AgentLoop;
import com.aiagent.agent.Policy;
import com.aiagent.llm.LlmClient;
import com.aiagent.llm.LlmMessage;
import com.aiagent.llm.LlmResult;

/**
 * Handler for a single conversation row.
 * Spec-AIAgent.txt PART 8.2.
 *
 * Registered in ImplMap under (aiagent, conv).
 *
 * Authorization: verified-only + owner-match. The chat history is
 * sensitive; the calling actId must equal the obj's actId.
 *
 * Operations:
 *   GetConversation    — return the conv's message list
 *   SendMessage        — add a user turn, call LLM / run agent loop
 *   RenameConversation — change the conv's Title attr and ObjName
 *   DeleteConversation — cascade-delete all msgs, then the conv row itself
 *   SetMode            — update the conv's Mode attr
 *   SetPolicy          — update the conv's Policy attr
 *   SetForeignActIds   — update the conv's ForeignActIds attr
 *   ApproveToolCall    — approve a pending tool call (resumes agent loop)
 *   RejectToolCall     — reject a pending tool call (resumes agent loop)
 *
 * The static doSendMessage method is also called by ConversationsImpl when
 * creating a new conversation.
 */
public class ConvImpl extends ObjImpl
{
  @Override
  public String handleMsg(final String msg, final Obj obj, final String contextPath,
                          final String contextRealPath, final DomatarMsgClient msgClient)
      throws DomatarException
  {
    final JsonMsg inMsg  = new JsonMsg(msg);
    final String  opr    = inMsg.getOperation();

    if ("Compensate".equals(opr))
      return super.handleMsg(msg, obj, contextPath, contextRealPath, msgClient);

    final JsonMsg outMsg = new JsonMsg();

    if (!Auth.isVerified(inMsg))
      return notAuthorized(inMsg);

    final Context ctx = inMsg.getContext();

    if (ctx == null || ctx.actId == null ||
        !ctx.actId.equals(inMsg.getDstId().actId))
      return notAuthorized(inMsg);

    final DomId convDomId = inMsg.getDstId();
    final Obj   conv      = ObjDb.getObj(convDomId);

    if (conv == null)
    {
      outMsg.addError(opr, "Conversation not found");
      return outMsg.toString();
    }

    if ("GetConversation".equals(opr))
      getConversation(opr, inMsg, outMsg, conv);
    else if ("SendMessage".equals(opr))
      sendMessage(opr, inMsg, outMsg, conv, msgClient);
    else if ("RenameConversation".equals(opr))
      renameConversation(opr, inMsg, outMsg, conv);
    else if ("DeleteConversation".equals(opr))
      deleteConversation(opr, inMsg, outMsg, conv);
    else if ("SetMode".equals(opr))
      setMode(opr, inMsg, outMsg, conv);
    else if ("SetPolicy".equals(opr))
      setPolicy(opr, inMsg, outMsg, conv);
    else if ("SetForeignActIds".equals(opr))
      setForeignActIds(opr, inMsg, outMsg, conv);
    else if ("ApproveToolCall".equals(opr))
      approveToolCall(opr, inMsg, outMsg, conv, msgClient);
    else if ("RejectToolCall".equals(opr))
      rejectToolCall(opr, inMsg, outMsg, conv, msgClient);
    else
      return super.handleMsg(msg, conv, contextPath, contextRealPath, msgClient);

    return outMsg.toString();
  }

  @Override
  public boolean hasRights(final JsonMsg inMsg, final Obj obj, final DomatarMsgClient msgClient)
      throws DomatarException
  {
    if (!Auth.isVerified(inMsg))
      return false;

    final Context ctx        = inMsg.getContext();
    final String  ownerActId = (obj != null) ? obj.domId.actId
                                             : inMsg.getDstId().actId;
    return ctx != null && ctx.actId != null && ctx.actId.equals(ownerActId);
  }

  // ── operations ───────────────────────────────────────────────────────────

  private void getConversation(final String opr, final JsonMsg inMsg,
                                final JsonMsg outMsg, final Obj obj)
      throws DomatarException
  {
    final DomId dst = obj.domId;

    final List<Obj> allMsgs = ObjDb.getObjPrefix(
        dst.hstId, "aiagent", dst.actId, "msg", null, 1000);

    final List<Obj> msgs = filterByConvId(allMsgs, dst.objId);

    msgs.sort((a, b) ->
        Long.compare(
            parseLong(safeGetAttr(a.attrs, "Time")),
            parseLong(safeGetAttr(b.attrs, "Time"))));

    final JsonList msgList = new JsonArrayList(msgs.size());

    for (final Obj m : msgs)
    {
      final ObjAttrs a    = m.attrs;
      final String   role = safeGetAttr(a, "Role");

      final JsonMap entry = new JsonHashMap(8);
      entry.put("MsgId",        m.domId.objId);
      entry.put("Role",         role);
      entry.put("Text",         safeGetAttr(a, "Text"));
      entry.put("Time",         safeGetAttr(a, "Time"));
      entry.put("Model",        safeGetAttr(a, "Model"));
      entry.put("TokensIn",     safeGetAttr(a, "TokensIn"));
      entry.put("TokensOut",    safeGetAttr(a, "TokensOut"));
      entry.put("FinishReason", safeGetAttr(a, "FinishReason"));

      if ("tool".equals(role))
      {
        entry.put("ToolName",      safeGetAttr(a, "ToolName"));
        entry.put("ToolTargetSov", safeGetAttr(a, "ToolTargetSov"));
        entry.put("ToolArgs",      safeGetAttr(a, "ToolArgs"));
        entry.put("ToolResult",    safeGetAttr(a, "ToolResult"));
        entry.put("ToolStatus",    safeGetAttr(a, "ToolStatus"));
        entry.put("PendingId",     safeGetAttr(a, "PendingId"));
      }

      msgList.add(entry);
    }

    final ObjAttrs outAttrs = new ObjAttrs();
    outAttrs.addAttr("ConvId",         dst.objId);
    outAttrs.addAttr("Title",          safeGetAttr(obj.attrs, "Title"));
    outAttrs.addAttr("Model",          safeGetAttr(obj.attrs, "Model"));
    outAttrs.addAttr("Mode",
        defaultIfNull(safeGetAttr(obj.attrs, "Mode"),          "Chat"));
    outAttrs.addAttr("Policy",
        defaultIfNull(safeGetAttr(obj.attrs, "Policy"),        "{}"));
    outAttrs.addAttr("ForeignActIds",
        defaultIfNull(safeGetAttr(obj.attrs, "ForeignActIds"), "[]"));
    outAttrs.addAttr("Messages", msgList);
    outMsg.addResponseBody(opr, outAttrs);
  }

  private void sendMessage(final String opr, final JsonMsg inMsg, final JsonMsg outMsg,
                           final Obj obj, final DomatarMsgClient msgClient)
      throws DomatarException
  {
    final String text = inMsg.getAttr("Text");

    if (text == null || text.isEmpty())
    {
      outMsg.addError(opr, "Text is required");
      return;
    }

    final String model = requireModel(inMsg, outMsg, opr);

    if (model == null)
      return;

    obj.attrs.addAttr("Model", model);
    ObjDb.modifyObj(new Obj(obj.domId, "aiagent", "conv",
                             obj.objName, obj.objDesc, obj.attrs));

    doSendMessage(obj.domId, text, obj.attrs,
                  inMsg.getContext(), msgClient, outMsg);
  }

  private void renameConversation(final String opr, final JsonMsg inMsg,
                                   final JsonMsg outMsg, final Obj obj)
      throws DomatarException
  {
    final String title = inMsg.getAttr("Title");

    if (title == null || title.isEmpty())
    {
      outMsg.addError(opr, "Title is required");
      return;
    }

    obj.attrs.addAttr("Title", title);
    final String objName = title.length() > 40 ? title.substring(0, 40) : title;
    ObjDb.modifyObj(new Obj(obj.domId, "aiagent", "conv", objName, "", obj.attrs));

    final ObjAttrs outAttrs = new ObjAttrs();
    outAttrs.addAttr("Renamed", "True");
    outMsg.addResponseBody(opr, outAttrs);
  }

  private void deleteConversation(final String opr, final JsonMsg inMsg,
                                   final JsonMsg outMsg, final Obj obj)
      throws DomatarException
  {
    final DomId dst    = obj.domId;
    final DomId convsId = new DomId(dst.hstId, "aiagent", dst.actId, "conversations");

    final List<Obj> allMsgs = ObjDb.getObjPrefix(
        dst.hstId, "aiagent", dst.actId, "msg", null, 10000);

    for (final Obj m : filterByConvId(allMsgs, dst.objId))
    {
      ObjDb.deleteObj(m.domId);
      LnkDb.deleteLnks(dst, m.domId, "aiagent", "msg", null, null);
    }

    ObjDb.deleteObj(dst);
    LnkDb.deleteLnks(convsId, dst, "aiagent", "conv", null, null);

    final ObjAttrs outAttrs = new ObjAttrs();
    outAttrs.addAttr("Deleted", "True");
    outAttrs.addAttr("ConvId",  dst.objId);
    outMsg.addResponseBody(opr, outAttrs);
  }

  private void setMode(final String opr, final JsonMsg inMsg,
                       final JsonMsg outMsg, final Obj obj)
      throws DomatarException
  {
    final String mode = inMsg.getAttr("Mode");

    if (!("Chat".equals(mode)
          || "ReadOnly".equals(mode)
          || "Agent".equals(mode)))
    {
      outMsg.addError(opr, "Invalid Mode; must be Chat, ReadOnly, or Agent");
      return;
    }

    obj.attrs.addAttr("Mode", mode);
    ObjDb.modifyObj(new Obj(obj.domId, "aiagent", "conv",
                             obj.objName, obj.objDesc, obj.attrs));

    final ObjAttrs out = new ObjAttrs();
    out.addAttr("Mode", mode);
    outMsg.addResponseBody(opr, out);
  }

  private void setPolicy(final String opr, final JsonMsg inMsg,
                          final JsonMsg outMsg, final Obj obj)
      throws DomatarException
  {
    final String policyJson = inMsg.getAttr("Policy");

    // Validate by parsing; Policy.parse always succeeds (returns DEFAULT on
    // bad JSON) so we just confirm it round-trips without throwing.
    if (policyJson == null)
    {
      outMsg.addError(opr, "Policy is required");
      return;
    }

    // Let Policy.parse validate; a non-DEFAULT result is acceptable.
    Policy.parse(policyJson);

    obj.attrs.addAttr("Policy", policyJson);
    ObjDb.modifyObj(new Obj(obj.domId, "aiagent", "conv",
                             obj.objName, obj.objDesc, obj.attrs));

    final ObjAttrs out = new ObjAttrs();
    out.addAttr("Saved", "True");
    outMsg.addResponseBody(opr, out);
  }

  private void approveToolCall(final String opr, final JsonMsg inMsg, final JsonMsg outMsg,
                               final Obj obj, final DomatarMsgClient msgClient)
      throws DomatarException
  {
    final String  pendingId = inMsg.getAttr("PendingId");
    final boolean always    = "True".equals(inMsg.getAttr("Always"));
    resume(obj.domId, obj.attrs, inMsg.getContext(), msgClient,
           pendingId, "approve", always, outMsg);
  }

  private void rejectToolCall(final String opr, final JsonMsg inMsg, final JsonMsg outMsg,
                              final Obj obj, final DomatarMsgClient msgClient)
      throws DomatarException
  {
    final String pendingId = inMsg.getAttr("PendingId");
    resume(obj.domId, obj.attrs, inMsg.getContext(), msgClient,
           pendingId, "reject", false, outMsg);
  }

  private void resume(final DomId            convDomId,
                      final ObjAttrs         convAttrs,
                      final Context          ctx,
                      final DomatarMsgClient msgClient,
                      final String           pendingId,
                      final String           decision,
                      final boolean          always,
                      final JsonMsg          outMsg)
      throws DomatarException
  {
    if (pendingId == null || pendingId.isEmpty())
    {
      outMsg.addError("Resume", "PendingId is required");
      return;
    }

    final DomId callerDomId = new DomId(convDomId.hstId, "aiagent",
                                         ctx.actId, "agent-loop");

    final ObjAttrs resumeData = AgentLoop.resume(convDomId, convAttrs, callerDomId,
                                                  ctx, pendingId, decision, always,
                                                  msgClient);

    final ObjAttrs out = new ObjAttrs();
    out.addAttrs("Resume", resumeData);
    outMsg.addResponseBody("Resume", out);
  }

  private void setForeignActIds(final String opr, final JsonMsg inMsg,
                                 final JsonMsg outMsg, final Obj obj)
      throws DomatarException
  {
    final String foreignJson = inMsg.getAttr("ForeignActIds");

    if (foreignJson == null)
    {
      outMsg.addError(opr, "ForeignActIds is required");
      return;
    }

    // Validate that the value is a JSON array and each element is "name@appId".
    try
    {
      final JsonList list = Json.parseList(foreignJson);
      for (final Object o : list)
      {
        final String actId = o != null ? o.toString().trim() : "";
        if (actId.isEmpty())
          continue;
        if (!actId.contains("@"))
        {
          outMsg.addError(opr, "Invalid actId (must contain @): " + actId);
          return;
        }
      }
    }
    catch (Exception e)
    {
      outMsg.addError(opr, "ForeignActIds must be a valid JSON array: " + e.getMessage());
      return;
    }

    obj.attrs.addAttr("ForeignActIds", foreignJson);
    ObjDb.modifyObj(new Obj(obj.domId, "aiagent", "conv",
                             obj.objName, obj.objDesc, obj.attrs));

    final ObjAttrs out = new ObjAttrs();
    out.addAttr("Saved", "True");
    outMsg.addResponseBody(opr, out);
  }

  // ── shared LLM round-trip ────────────────────────────────────────────────

  /**
   * Core "send message" logic, shared between:
   *   - ConversationsImpl.sendMessage  (new conversation path)
   *   - ConvImpl.sendMessage           (existing conversation path)
   *
   * Branches on Mode:
   *   "Chat"                → doSendMessageChatPath (original plain-LLM behaviour)
   *   "ReadOnly" | "Agent"  → AgentLoop.run
   */
  static void doSendMessage(final DomId            convDomId,
                            final String           text,
                            final ObjAttrs         convAttrs,
                            final Context          callerContext,
                            final DomatarMsgClient msgClient,
                            final JsonMsg          outMsg)
      throws DomatarException
  {
    String mode = safeGetAttr(convAttrs, "Mode");
    if (mode == null || mode.isEmpty())
      mode = "Chat";

    if ("Chat".equals(mode))
    {
      doSendMessageChatPath(convDomId, text, convAttrs, outMsg);
      return;
    }

    // ReadOnly or Agent — run the full agent loop.
    final DomId callerDomId = new DomId(convDomId.hstId, "aiagent",
                                         callerContext.actId, "agent-loop");

    final AgentLoop.Outcome outcome = AgentLoop.run(
        convDomId, convAttrs, text,
        callerDomId, callerContext, msgClient);

    outMsg.addResponseBody("SendMessage", outcome.toResponseAttrs());
  }

  /**
   * Original plain-chat path (Mode="Chat").  Unchanged from the v1 behaviour.
   */
  private static void doSendMessageChatPath(final DomId    convDomId,
                                             final String   text,
                                             final ObjAttrs convAttrs,
                                             final JsonMsg  outMsg)
      throws DomatarException
  {
    final long   now    = System.currentTimeMillis();
    final String nowStr = Long.toString(now);

    // 1. Write the user message
    final String userMsgId    = IdGen.createIdFromCurTime("msg");
    final DomId  userMsgDomId = new DomId(convDomId.hstId, "aiagent",
                                          convDomId.actId,  userMsgId);

    final String userObjName = text.length() > 40  ? text.substring(0, 40)  : text;
    final String userObjDesc = "user: " + (text.length() > 100 ? text.substring(0, 100) : text);

    final ObjAttrs userAttrs = new ObjAttrs();
    userAttrs.addAttr("ConvId", convDomId.objId);
    userAttrs.addAttr("Role",   "user");
    userAttrs.addAttr("Text",   text);
    userAttrs.addAttr("Time",   nowStr);

    ObjDb.addObj(new Obj(userMsgDomId, "aiagent", "msg",
                         userObjName, userObjDesc, userAttrs));

    LnkDb.addLnk(new Lnk(convDomId, userMsgDomId,
                          "aiagent", "msg",
                          userObjName, userObjDesc,
                          "aiagent", "msg",
                          null, now));

    // 2. Load full conversation history for the LLM context.
    final List<Obj> allMsgs = ObjDb.getObjPrefix(
        convDomId.hstId, "aiagent", convDomId.actId, "msg", null, 1000);

    final List<Obj> history = filterByConvId(allMsgs, convDomId.objId);

    history.sort((a, b) ->
        Long.compare(
            parseLong(safeGetAttr(a.attrs, "Time")),
            parseLong(safeGetAttr(b.attrs, "Time"))));

    // 3. Call the LLM.
    String systemPrompt = safeGetAttr(convAttrs, "SystemPrompt");
    if (systemPrompt == null || systemPrompt.isEmpty())
      systemPrompt = "You are a helpful assistant.";

    String model = safeGetAttr(convAttrs, "Model");
    if (model == null || model.isEmpty())
      model = LlmClient.defaultModel;

    final List<LlmMessage> llmMessages = new ArrayList<>(history.size() + 1);
    llmMessages.add(new LlmMessage("system", systemPrompt));

    for (final Obj m : history)
    {
      final String role = safeGetAttr(m.attrs, "Role");
      final String txt  = safeGetAttr(m.attrs, "Text");

      if (role != null && txt != null
          && ("user".equals(role) || "assistant".equals(role)))
        llmMessages.add(new LlmMessage(role, txt));
    }

    LlmResult llmResult;
    try
    {
      llmResult = LlmClient.complete(model, llmMessages);
    }
    catch (DomatarException e)
    {
      final String errText = "(error: " + e.getMessage() + ")";
      llmResult = new LlmResult(errText, 0, 0, "error");
    }

    // 4. Write the assistant message.
    final long   nowA    = Math.max(System.currentTimeMillis(), now + 1);
    final String nowAStr = Long.toString(nowA);

    final String asgMsgId    = IdGen.createIdFromCurTime("msg");
    final DomId  asgMsgDomId = new DomId(convDomId.hstId, "aiagent",
                                         convDomId.actId,  asgMsgId);

    final String asgText    = llmResult.text != null ? llmResult.text : "";
    final String asgObjName = asgText.length() > 40  ? asgText.substring(0, 40)  : asgText;
    final String asgObjDesc = "assistant: " + (asgText.length() > 100
                                               ? asgText.substring(0, 100) : asgText);

    final ObjAttrs asgAttrs = new ObjAttrs();
    asgAttrs.addAttr("ConvId",       convDomId.objId);
    asgAttrs.addAttr("Role",         "assistant");
    asgAttrs.addAttr("Text",         asgText);
    asgAttrs.addAttr("Time",         nowAStr);
    asgAttrs.addAttr("Model",        model);
    asgAttrs.addAttr("TokensIn",     Integer.toString(llmResult.tokensIn));
    asgAttrs.addAttr("TokensOut",    Integer.toString(llmResult.tokensOut));
    asgAttrs.addAttr("FinishReason", llmResult.finishReason);

    ObjDb.addObj(new Obj(asgMsgDomId, "aiagent", "msg",
                         asgObjName, asgObjDesc, asgAttrs));

    LnkDb.addLnk(new Lnk(convDomId, asgMsgDomId,
                          "aiagent", "msg",
                          asgObjName, asgObjDesc,
                          "aiagent", "msg",
                          null, nowA));

    // 5. Update the conv's running counters.
    final long prevCount    = parseLong(safeGetAttr(convAttrs, "MessageCount"));
    final long prevTokensIn = parseLong(safeGetAttr(convAttrs, "TokensIn"));
    final long prevTokOut   = parseLong(safeGetAttr(convAttrs, "TokensOut"));

    convAttrs.addAttr("MessageCount", Long.toString(prevCount    + 2));
    convAttrs.addAttr("UpdatedAt",    nowAStr);
    convAttrs.addAttr("TokensIn",     Long.toString(prevTokensIn + llmResult.tokensIn));
    convAttrs.addAttr("TokensOut",    Long.toString(prevTokOut   + llmResult.tokensOut));

    final String title   = safeGetAttr(convAttrs, "Title");
    final String objName = (title != null && !title.isEmpty()) ? title : "";

    ObjDb.modifyObj(new Obj(convDomId, "aiagent", "conv", objName, "", convAttrs));

    // 6. Build the reply.
    final JsonMap userMsgMap = new JsonHashMap(4);
    userMsgMap.put("Role", "user");
    userMsgMap.put("Text", text);
    userMsgMap.put("Time", nowStr);

    final JsonMap asgMsgMap = new JsonHashMap(8);
    asgMsgMap.put("Role",         "assistant");
    asgMsgMap.put("Text",         asgText);
    asgMsgMap.put("Time",         nowAStr);
    asgMsgMap.put("Model",        model);
    asgMsgMap.put("TokensIn",     Integer.toString(llmResult.tokensIn));
    asgMsgMap.put("TokensOut",    Integer.toString(llmResult.tokensOut));
    asgMsgMap.put("FinishReason", llmResult.finishReason);

    final ObjAttrs outAttrs = new ObjAttrs();
    outAttrs.addAttr("ConvId",            convDomId.objId);
    outAttrs.addAttr("UserMessage",       userMsgMap);
    outAttrs.addAttr("AssistantMessage",  asgMsgMap);
    outMsg.addResponseBody("SendMessage", outAttrs);
  }

  // ── private helpers ──────────────────────────────────────────────────────

  private static List<Obj> filterByConvId(final List<Obj> msgs, final String convId)
  {
    final List<Obj> result = new ArrayList<>();
    for (final Obj m : msgs)
    {
      final String cid = safeGetAttr(m.attrs, "ConvId");
      if (convId.equals(cid))
        result.add(m);
    }
    return result;
  }

  static String requireModel(final JsonMsg inMsg, final JsonMsg outMsg,
                              final String opr)
      throws DomatarException
  {
    final String model = inMsg.getAttr("Model");

    if (model == null || model.trim().isEmpty())
    {
      outMsg.addError(opr, "Choose a model");
      return null;
    }

    return model.trim();
  }

  static String safeGetAttr(final ObjAttrs attrs, final String key)
  {
    try
    {
      return attrs.getAttr(key);
    }
    catch (Exception e)
    {
      return null;
    }
  }

  /**
   * Returns v if non-null and non-empty, otherwise returns the given default.
   * Used to provide backwards-compatible defaults for attrs added in Phase 3
   * that are absent from conv rows created before this release.
   */
  static String defaultIfNull(final String v, final String d)
  {
    return (v == null || v.isEmpty()) ? d : v;
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
}
