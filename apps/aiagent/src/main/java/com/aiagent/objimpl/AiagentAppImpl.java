/*
 * Copyright (c) 2024 Domatar
 */

package com.aiagent.objimpl;

import java.util.ArrayList;
import java.util.List;

import com.domatar.core.Auth;
import com.domatar.db.ObjDb;
import com.domatar.util.JsonArrayList;
import com.domatar.util.JsonHashMap;
import com.domatar.util.JsonList;
import com.domatar.util.JsonMap;
import com.domatar.util.JsonMsg;
import com.domatar.util.Obj;
import com.domatar.util.ObjAttrs;
import com.domatar.util.ObjImpl;
import com.domatar.util.DomId;
import com.domatar.util.DomatarException;
import com.domatar.util.DomatarMsgClient;

/**
 * LLM-native facade for the AI Agent app.
 *
 * Handles class (aiagent, app) — the app-aiagent entry-point object.
 * Exposes conversation history so the LLM can answer questions about
 * past conversations without navigating the object graph manually.
 *
 * Operations: GetConversations(), GetConversation(Title).
 *
 * Spec: Spec-LLM-Oriented-Msgs.txt — AI AGENT section.
 */
public class AiagentAppImpl extends ObjImpl
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

    final DomId appDomId = inMsg.getDstId();

    if ("GetConversations".equals(opr))
      getConversations(opr, outMsg, appDomId);
    else if ("GetConversation".equals(opr))
      getConversation(opr, inMsg, outMsg, appDomId);
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

  // ---------------------------------------------------------------------------

  private void getConversations(final String opr, final JsonMsg outMsg, final DomId appDomId)
      throws DomatarException
  {
    List<Obj> rows = ObjDb.getObjPrefix(
        appDomId.hstId, "aiagent", appDomId.actId, "conv", null, 1000);

    rows = new ArrayList<>(rows);
    rows.sort((a, b) -> Long.compare(parseLong(safeGet(b.attrs, "UpdatedAt")),
                                     parseLong(safeGet(a.attrs, "UpdatedAt"))));

    final JsonList list = new JsonArrayList(rows.size());
    for (final Obj row : rows)
    {
      final JsonMap entry = new JsonHashMap(6);
      entry.put("ConvId",       row.domId.objId);
      entry.put("Title",        safeGet(row.attrs, "Title"));
      entry.put("Mode",         safeGet(row.attrs, "Mode"));
      entry.put("MessageCount", safeGet(row.attrs, "MessageCount"));
      entry.put("UpdatedAt",    safeGet(row.attrs, "UpdatedAt"));
      entry.put("Model",        safeGet(row.attrs, "Model"));
      list.add(entry);
    }

    final ObjAttrs out = new ObjAttrs();
    out.addAttr("Conversations", list);
    outMsg.addResponseBody(opr, out);
  }

  private void getConversation(final String opr, final JsonMsg inMsg, final JsonMsg outMsg,
                                final DomId appDomId)
      throws DomatarException
  {
    final String title = inMsg.getAttrs().getAttr("Title");
    if (title == null || title.isEmpty())
    {
      outMsg.addError(opr, "Missing Title");
      return;
    }

    final List<Obj> rows = ObjDb.getObjPrefix(
        appDomId.hstId, "aiagent", appDomId.actId, "conv", null, 1000);

    Obj found = null;
    for (final Obj row : rows)
    {
      final String t = safeGet(row.attrs, "Title");
      if (title.equalsIgnoreCase(t))
      {
        found = row;
        break;
      }
    }

    if (found == null)
    {
      outMsg.addError(opr, "No conversation titled '" + title + "'");
      return;
    }

    final String convId = found.domId.objId;

    final List<Obj> allMsgs = ObjDb.getObjPrefix(
        appDomId.hstId, "aiagent", appDomId.actId, "msg", null, 10000);

    final List<Obj> convMsgs = new ArrayList<>();
    for (final Obj m : allMsgs)
    {
      if (convId.equals(safeGet(m.attrs, "ConvId")))
        convMsgs.add(m);
    }
    convMsgs.sort((a, b) -> Long.compare(parseLong(safeGet(a.attrs, "Time")),
                                         parseLong(safeGet(b.attrs, "Time"))));

    final JsonList msgList = new JsonArrayList(convMsgs.size());
    for (final Obj m : convMsgs)
    {
      final String  role  = safeGet(m.attrs, "Role");
      final JsonMap entry = new JsonHashMap(5);
      entry.put("Role",       role);
      entry.put("Text",       safeGet(m.attrs, "Text"));
      entry.put("Time",       safeGet(m.attrs, "Time"));
      if ("tool".equals(role))
      {
        entry.put("ToolName",   safeGet(m.attrs, "ToolName"));
        entry.put("ToolResult", safeGet(m.attrs, "ToolResult"));
      }
      msgList.add(entry);
    }

    final ObjAttrs out = new ObjAttrs();
    out.addAttr("ConvId",   convId);
    out.addAttr("Title",    safeGet(found.attrs, "Title"));
    out.addAttr("Mode",     safeGet(found.attrs, "Mode"));
    out.addAttr("Messages", msgList);
    outMsg.addResponseBody(opr, out);
  }

  // ---------------------------------------------------------------------------

  private static String safeGet(final ObjAttrs attrs, final String key)
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

  private static long parseLong(final String s)
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
