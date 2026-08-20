/*
 * Copyright (c) 2024 Domatar
 */

package com.aiagent.webui;

import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;

import java.util.List;

import com.aiagent.llm.LlmClient;
import com.domatar.core.Context;
import com.domatar.servlet.DomatarServlet;
import com.domatar.util.Act;
import com.domatar.util.JsonArrayList;
import com.domatar.util.JsonHashMap;
import com.domatar.util.JsonList;
import com.domatar.util.JsonMap;
import com.domatar.util.JsonMsg;
import com.domatar.util.ObjAttrs;
import com.domatar.util.DomId;
import com.domatar.util.DomatarException;
import com.domatar.util.DomatarMsgClient;

/**
 * Browser-facing endpoint backing the AI Agent page.
 * Spec-AIAgent.txt PART 7.
 *
 * All actions target the current user's own sub-host (aiagent~<actId>),
 * which lives on the same prv as this servlet — so dispatch is always
 * sendLocal. Credential checking is handled upstream by DomatarServlet's
 * outer dispatch; AgentWui never touches credentials directly.
 *
 * Actions:
 *   ListModels          — tool-capable Groq model ids (answered locally)
 *   ListConversations   — list all conversations (dst=conversations container)
 *   GetConversation     — fetch one conversation with its messages (dst=conv row)
 *   SendMessage         — send a message; ConvId empty → new conversation
 *   RenameConversation  — rename a conversation
 *   DeleteConversation  — delete a conversation and all its messages
 *   SetMode             — update the conversation's Mode attr
 *   SetPolicy           — update the conversation's Policy attr
 *   SetForeignActIds    — update the conversation's ForeignActIds attr
 */
@WebServlet("/AgentWui/*")
public class AgentWui extends DomatarServlet
{
  private static final long serialVersionUID = 1L;

  @Override
  protected JsonMsg getMsg(final HttpServletRequest req,
                           final DomId              srcDomId,
                           final Context            context,
                           final Act                srcAct,
                           final DomatarMsgClient   msgClient) throws DomatarException
  {
    final JsonMsg msg    = new JsonMsg();
    final String  action = getParam(req, "Action");

    if (action == null)
    {
      msg.addError(action, "Missing Action");
      return msg;
    }

    final String actId   = context.actId;
    final String aiHstId = DomId.subHstId("aiagent", actId);

    if ("ListModels".equals(action))
    {
      try
      {
        final List<String> ids    = LlmClient.listToolModels();
        final JsonList     models = new JsonArrayList(ids.size());

        for (final String id : ids)
        {
          final JsonMap entry = new JsonHashMap(1);
          entry.put("Id", id);
          models.add(entry);
        }

        final ObjAttrs attrs = new ObjAttrs();
        attrs.addAttr("Models", models);
        msg.addResponseBody(action, attrs);
      }
      catch (DomatarException e)
      {
        msg.addError(action, e.getMessage());
      }

      return msg;
    }
    else if ("ListConversations".equals(action))
    {
      final DomId dst = new DomId(aiHstId, "aiagent", actId, "conversations");

      msg.addRequestHead(srcDomId, dst, context);
      msg.addRequestBody("ListConversations", null);
      msg.addClsId("aiagent", "conversations");
    }
    else if ("GetConversation".equals(action))
    {
      final String convId = getParam(req, "ConvId");

      if (convId == null || convId.isEmpty())
      {
        msg.addError(action, "ConvId is required");
        return msg;
      }

      final DomId dst = new DomId(aiHstId, "aiagent", actId, convId);

      msg.addRequestHead(srcDomId, dst, context);
      msg.addRequestBody("GetConversation", null);
      msg.addClsId("aiagent", "conv");
    }
    else if ("SendMessage".equals(action))
    {
      final String text   = getParam(req, "Text");
      final String convId = getParam(req, "ConvId");

      final ObjAttrs attrs = new ObjAttrs();

      if (text != null)
        attrs.addAttr("Text", text);

      final String mode = getParam(req, "Mode");
      if (mode != null && !mode.isEmpty())
        attrs.addAttr("Mode", mode);

      final String model = getParam(req, "Model");
      if (model != null && !model.isEmpty())
        attrs.addAttr("Model", model);

      if (convId == null || convId.isEmpty())
      {
        // New conversation: dispatch to the conversations container, which
        // will create the conv row and forward to doSendMessage.
        final DomId dst = new DomId(aiHstId, "aiagent", actId, "conversations");
        msg.addRequestHead(srcDomId, dst, context);
        msg.addRequestBody("SendMessage", attrs);
        msg.addClsId("aiagent", "conversations");
      }
      else
      {
        // Existing conversation: dispatch to the conv row.
        attrs.addAttr("ConvId", convId);
        final DomId dst = new DomId(aiHstId, "aiagent", actId, convId);
        msg.addRequestHead(srcDomId, dst, context);
        msg.addRequestBody("SendMessage", attrs);
        msg.addClsId("aiagent", "conv");
      }
    }
    else if ("RenameConversation".equals(action))
    {
      final String convId = getParam(req, "ConvId");
      final String title  = getParam(req, "Title");

      if (convId == null || convId.isEmpty())
      {
        msg.addError(action, "ConvId is required");
        return msg;
      }

      final ObjAttrs attrs = new ObjAttrs();

      if (title != null)
        attrs.addAttr("Title", title);

      final DomId dst = new DomId(aiHstId, "aiagent", actId, convId);
      msg.addRequestHead(srcDomId, dst, context);
      msg.addRequestBody("RenameConversation", attrs);
      msg.addClsId("aiagent", "conv");
    }
    else if ("DeleteConversation".equals(action))
    {
      final String convId = getParam(req, "ConvId");

      if (convId == null || convId.isEmpty())
      {
        msg.addError(action, "ConvId is required");
        return msg;
      }

      final DomId dst = new DomId(aiHstId, "aiagent", actId, convId);
      msg.addRequestHead(srcDomId, dst, context);
      msg.addRequestBody("DeleteConversation", null);
      msg.addClsId("aiagent", "conv");
    }
    else if ("SetMode".equals(action))
    {
      final String convId = getParam(req, "ConvId");
      final String mode   = getParam(req, "Mode");

      if (convId == null || convId.isEmpty()
          || mode == null
          || !("Chat".equals(mode)
               || "ReadOnly".equals(mode)
               || "Agent".equals(mode)))
      {
        msg.addError(action,
            "ConvId and valid Mode (Chat|ReadOnly|Agent) are required");
        return msg;
      }

      final ObjAttrs attrs = new ObjAttrs();
      attrs.addAttr("Mode", mode);

      final DomId dst = new DomId(aiHstId, "aiagent", actId, convId);
      msg.addRequestHead(srcDomId, dst, context);
      msg.addRequestBody("SetMode", attrs);
      msg.addClsId("aiagent", "conv");
    }
    else if ("SetPolicy".equals(action))
    {
      final String convId = getParam(req, "ConvId");
      final String policy = getParam(req, "Policy");

      if (convId == null || convId.isEmpty() || policy == null)
      {
        msg.addError(action, "ConvId and Policy are required");
        return msg;
      }

      final ObjAttrs attrs = new ObjAttrs();
      attrs.addAttr("Policy", policy);

      final DomId dst = new DomId(aiHstId, "aiagent", actId, convId);
      msg.addRequestHead(srcDomId, dst, context);
      msg.addRequestBody("SetPolicy", attrs);
      msg.addClsId("aiagent", "conv");
    }
    else if ("ApproveToolCall".equals(action))
    {
      final String convId    = getParam(req, "ConvId");
      final String pendingId = getParam(req, "PendingId");
      final String always    = getParam(req, "Always"); // "True" | "False"

      if (convId == null || convId.isEmpty()
          || pendingId == null || pendingId.isEmpty())
      {
        msg.addError(action, "ConvId and PendingId are required");
        return msg;
      }

      final ObjAttrs attrs = new ObjAttrs();
      attrs.addAttr("PendingId", pendingId);
      if (always != null)
        attrs.addAttr("Always", always);

      final DomId dst = new DomId(aiHstId, "aiagent", actId, convId);
      msg.addRequestHead(srcDomId, dst, context);
      msg.addRequestBody("ApproveToolCall", attrs);
      msg.addClsId("aiagent", "conv");
    }
    else if ("RejectToolCall".equals(action))
    {
      final String convId    = getParam(req, "ConvId");
      final String pendingId = getParam(req, "PendingId");

      if (convId == null || convId.isEmpty()
          || pendingId == null || pendingId.isEmpty())
      {
        msg.addError(action, "ConvId and PendingId are required");
        return msg;
      }

      final ObjAttrs attrs = new ObjAttrs();
      attrs.addAttr("PendingId", pendingId);

      final DomId dst = new DomId(aiHstId, "aiagent", actId, convId);
      msg.addRequestHead(srcDomId, dst, context);
      msg.addRequestBody("RejectToolCall", attrs);
      msg.addClsId("aiagent", "conv");
    }
    else if ("SetForeignActIds".equals(action))
    {
      final String convId        = getParam(req, "ConvId");
      final String foreignActIds = getParam(req, "ForeignActIds");

      if (convId == null || convId.isEmpty() || foreignActIds == null)
      {
        msg.addError(action, "ConvId and ForeignActIds are required");
        return msg;
      }

      final ObjAttrs attrs = new ObjAttrs();
      attrs.addAttr("ForeignActIds", foreignActIds);

      final DomId dst = new DomId(aiHstId, "aiagent", actId, convId);
      msg.addRequestHead(srcDomId, dst, context);
      msg.addRequestBody("SetForeignActIds", attrs);
      msg.addClsId("aiagent", "conv");
    }
    else
    {
      msg.addError(action, "Unknown action: " + action);
    }

    return msg;
  }
}
