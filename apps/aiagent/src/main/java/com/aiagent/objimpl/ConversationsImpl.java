/*
 * Copyright (c) 2024 Domatar
 */

package com.aiagent.objimpl;

import java.util.ArrayList;
import java.util.List;

import com.domatar.core.Auth;
import com.domatar.db.LnkDb;
import com.domatar.db.ObjDb;
import com.domatar.util.IdGen;
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

/**
 * Handler for the per-user conversations container.
 * Spec-AIAgent.txt PART 8.1.
 *
 * Registered in ImplMap under (aiagent, conversations).
 * Authorization: verified-only. The container is always addressed
 * using the caller's own actId (AgentWui builds the dst from srcActId),
 * so the owner-match constraint is enforced at the address level.
 *
 * Operations:
 *   ListConversations  — return all conv rows sorted by UpdatedAt desc
 *   SendMessage        — create a new conversation, send the first message
 */
public class ConversationsImpl extends ObjImpl
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

    if (!hasRights(inMsg, obj, msgClient))
      return notAuthorized(inMsg);

    if ("ListConversations".equals(opr))
      listConversations(opr, inMsg, outMsg);
    else if ("SendMessage".equals(opr))
      sendMessage(opr, inMsg, outMsg, obj, msgClient);
    else
      return super.handleMsg(msg, obj, contextPath, contextRealPath, msgClient);

    return outMsg.toString();
  }

  @Override
  public boolean hasRights(final JsonMsg inMsg, final Obj obj, final DomatarMsgClient msgClient)
      throws DomatarException
  {
    return Auth.isVerified(inMsg);
  }

  // ── operations ───────────────────────────────────────────────────────────

  /**
   * Returns every conv row on this sub-host sorted by UpdatedAt descending
   * (most-recently-updated first, so the freshest chat is at the top).
   */
  private void listConversations(final String opr, final JsonMsg inMsg, final JsonMsg outMsg)
      throws DomatarException
  {
    final DomId dst = inMsg.getDstId();

    List<Obj> rows = ObjDb.getObjPrefix(
        dst.hstId, "aiagent", dst.actId, "conv", null, 1000);

    // Sort by UpdatedAt descending. getObjPrefix returns ObjId desc (newest
    // conv first by creation time), but UpdatedAt changes with each message,
    // so we sort explicitly.
    rows = new ArrayList<>(rows);
    rows.sort((a, b) ->
    {
      final long la = parseLongSafe(safeGetAttr(a.attrs, "UpdatedAt"));
      final long lb = parseLongSafe(safeGetAttr(b.attrs, "UpdatedAt"));
      return Long.compare(lb, la); // descending
    });

    final JsonList convList = new JsonArrayList(rows.size());

    for (final Obj row : rows)
    {
      final ObjAttrs a = row.attrs;

      final JsonMap entry = new JsonHashMap(7);
      entry.put("ConvId",       row.domId.objId);
      entry.put("Title",        safeGetAttr(a, "Title"));
      entry.put("CreatedAt",    safeGetAttr(a, "CreatedAt"));
      entry.put("UpdatedAt",    safeGetAttr(a, "UpdatedAt"));
      entry.put("MessageCount", safeGetAttr(a, "MessageCount"));
      entry.put("Model",        safeGetAttr(a, "Model"));
      entry.put("Mode",         safeGetAttr(a, "Mode"));
      convList.add(entry);
    }

    final ObjAttrs outAttrs = new ObjAttrs();
    outAttrs.addAttr("Conversations", convList);
    outMsg.addResponseBody(opr, outAttrs);
  }

  /**
   * New-conversation path: creates the conv row, adds it to the navigator
   * lnk graph, then delegates to ConvImpl.doSendMessage for the LLM round-trip.
   */
  private void sendMessage(final String opr, final JsonMsg inMsg, final JsonMsg outMsg,
                           final Obj obj, final DomatarMsgClient msgClient)
      throws DomatarException
  {
    // Use getDstId() rather than obj.domId: when a clsId hint is present in
    // the message envelope, sendLocal skips the ObjDb lookup so obj is null.
    final DomId dst = inMsg.getDstId(); // the conversations container

    final String text = inMsg.getAttr("Text");

    if (text == null || text.isEmpty())
    {
      outMsg.addError(opr, "Text is required");
      return;
    }

    final String model = ConvImpl.requireModel(inMsg, outMsg, opr);

    if (model == null)
      return;

    final long   now    = System.currentTimeMillis();
    final String nowStr = Long.toString(now);

    final String convId    = IdGen.createIdFromCurTime("conv");
    final DomId  convDomId = new DomId(dst.hstId, "aiagent", dst.actId, convId);

    final String title = text.length() > 40 ? text.substring(0, 40) : text;

    final ObjAttrs convAttrs = new ObjAttrs();
    convAttrs.addAttr("Title",        title);
    convAttrs.addAttr("Model",        model);
    convAttrs.addAttr("SystemPrompt", "You are a helpful assistant.");
    convAttrs.addAttr("CreatedAt",    nowStr);
    convAttrs.addAttr("UpdatedAt",    nowStr);
    convAttrs.addAttr("MessageCount", "0");
    convAttrs.addAttr("TokensIn",      "0");
    convAttrs.addAttr("TokensOut",     "0");
    String mode = inMsg.getAttr("Mode");
    if (mode == null || mode.isEmpty()
        || (!("Chat".equals(mode) || "ReadOnly".equals(mode)
             || "Agent".equals(mode))))
      mode = "Chat";
    convAttrs.addAttr("Mode",          mode);
    convAttrs.addAttr("Policy",        "{}");
    convAttrs.addAttr("ForeignActIds", "[]");

    ObjDb.addObj(new Obj(convDomId, "aiagent", "conv", title, "", convAttrs));

    // conversations -> conv lnk; seqNum = creation time for chronological
    // ordering in the Navigator tree
    LnkDb.addLnk(new Lnk(dst, convDomId,
                          "aiagent", "conv",
                          title, "",
                          "aiagent", "conv",
                          null, now));

    // Delegate LLM round-trip to the shared method. The response already
    // contains both messages and the ConvId, so the browser can switch into
    // the new conversation immediately.
    ConvImpl.doSendMessage(convDomId, text, convAttrs,
                           inMsg.getContext(), msgClient, outMsg);
  }

  // ── helpers ──────────────────────────────────────────────────────────────

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

  static long parseLongSafe(final String s)
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
