/*
 * Copyright (c) 2024 Domatar
 */

package com.canton.objimpl;

import java.util.LinkedHashMap;
import java.util.Map;

import com.canton.ledger.CantonClients;
import com.canton.ledger.Contract;
import com.canton.ledger.SubmitResult;
import com.canton.ledger.TemplateDesc;
import com.canton.ledger.TemplateDesc.ChoiceDesc;
import com.canton.ledger.TemplateDesc.FieldDesc;
import com.domatar.db.ObjDb;
import com.domatar.util.Json;
import com.domatar.util.JsonArrayList;
import com.domatar.util.JsonList;
import com.domatar.util.JsonMap;
import com.domatar.util.JsonMsg;
import com.domatar.util.Obj;
import com.domatar.util.ObjAttrs;
import com.domatar.util.ObjImpl;
import com.domatar.util.DomatarException;
import com.domatar.util.DomatarMsgClient;

public class ContractImpl extends ObjImpl
{
  @Override
  public String handleMsg(final String msg, final Obj obj, final String contextPath,
                          final String contextRealPath, final DomatarMsgClient msgClient)
      throws DomatarException
  {
    final JsonMsg inMsg = new JsonMsg(msg);
    final String opr = inMsg.getOperation();
    final JsonMsg outMsg = new JsonMsg();

    if (!hasRights(inMsg, obj, msgClient))
      return notAuthorized(inMsg);

    final Obj target = CantonAuth.requireObj(inMsg, obj);
    if (target == null)
    {
      outMsg.addError(opr, "Obj not found");
      return outMsg.toString();
    }

    if ("GetLnks".equals(opr) || "GetObj".equals(opr))
    {
      try
      {
        if (HandleSync.refreshOrDrop(target) == null)
        {
          outMsg.addError(opr, "Contract no longer visible");
          return outMsg.toString();
        }
      }
      catch (final DomatarException e)
      {
        outMsg.addError(opr, e.getMessage());
        return outMsg.toString();
      }
      final Obj live = ObjDb.getObj(target.domId);
      return super.handleMsg(msg, live != null ? live : target,
          contextPath, contextRealPath, msgClient);
    }

    if ("GetIou".equals(opr) || "GetContract".equals(opr))
      readLive(opr, target, outMsg);
    else if ("Transfer".equals(opr) || "Settle".equals(opr) || "Archive".equals(opr))
      exercise(opr, opr, inMsg, target, outMsg);
    else if ("Exercise".equals(opr))
      exercise(opr, inMsg.getAttr("Choice"), inMsg, target, outMsg);
    else
      return super.handleMsg(msg, obj, contextPath, contextRealPath, msgClient);

    return outMsg.toString();
  }

  @Override
  public boolean hasRights(final JsonMsg inMsg, final Obj obj,
                           final DomatarMsgClient msgClient)
      throws DomatarException
  {
    if (!CantonAuth.isVerifiedOwner(inMsg, obj))
      return false;

    final String opr = inMsg.getOperation();

    if ("GetLnks".equals(opr) || "GetObj".equals(opr)
        || "GetIou".equals(opr) || "GetContract".equals(opr))
      return true;

    if ("Transfer".equals(opr) || "Settle".equals(opr)
        || "Archive".equals(opr) || "Exercise".equals(opr))
    {
      final Obj target = CantonAuth.requireObj(inMsg, obj);
      return target != null && isController(inMsg, target, opr);
    }

    return false;
  }

  private static boolean isController(final JsonMsg inMsg, final Obj obj,
                                      final String opr)
  {
    try
    {
      if ("Exercise".equals(opr) && !"contract".equals(obj.clsId))
        return false;

      final Contract live = HandleSync.findLive(obj.domId.actId, obj.domId.objId);
      if (live == null)
        return false;

      String choiceName = opr;
      if ("Exercise".equals(opr))
      {
        choiceName = inMsg.getAttr("Choice");
        if (choiceName == null || choiceName.isEmpty())
          return false;
      }

      final TemplateDesc t = CantonClients.get().getTemplate(live.templateId);
      final ChoiceDesc ch = namedChoice(t, choiceName);
      if (ch == null)
        return false;

      final String submitter = HandleSync.boundParty(obj.domId.actId);
      for (final String field : ch.controllerFields)
      {
        final String value = live.payload.get(field);
        if (value == null || !value.equals(submitter))
          return false;
      }
      return true;
    }
    catch (final DomatarException e)
    {
      return false;
    }
  }

  private static void readLive(final String opr, final Obj obj,
                               final JsonMsg outMsg) throws DomatarException
  {
    try
    {
      final Contract c = HandleSync.refreshOrDrop(obj);
      if (c == null)
      {
        outMsg.addError(opr, "Contract no longer visible");
        return;
      }
      outMsg.addResponseBody(opr, HandleSync.attrsFrom(c));
    }
    catch (final DomatarException e)
    {
      outMsg.addError(opr, e.getMessage());
    }
  }

  private static void exercise(final String opr, final String choice,
                               final JsonMsg inMsg, final Obj obj,
                               final JsonMsg outMsg) throws DomatarException
  {
    try
    {
      if (choice == null || choice.isEmpty())
      {
        outMsg.addError(opr, "Missing Choice");
        return;
      }

      final String actId = obj.domId.actId;
      final String party = HandleSync.boundParty(actId);
      final Contract live = HandleSync.findLive(actId, obj.domId.objId);
      if (live == null)
      {
        outMsg.addError(opr, "Contract no longer visible");
        return;
      }

      final TemplateDesc t = CantonClients.get().getTemplate(live.templateId);
      final ChoiceDesc ch = namedChoice(t, choice);
      if (ch == null)
      {
        outMsg.addError(opr, "Unknown choice: " + choice);
        return;
      }

      final Map<String, String> args = choiceArgs(inMsg, ch);
      final SubmitResult result = CantonClients.get()
          .submitExercise(party, obj.domId.objId, choice, args);
      HandleSync.sync(actId);

      final ObjAttrs out = new ObjAttrs();
      out.addAttr("Archived", jsonIds(result.archived));
      out.addAttr("Created", createdIds(result));
      if (result.created != null && !result.created.isEmpty())
        out.addAttr("ContractId", result.created.get(0).contractId);
      outMsg.addResponseBody(opr, out);
    }
    catch (final DomatarException e)
    {
      outMsg.addError(opr, e.getMessage());
    }
  }

  private static ChoiceDesc namedChoice(final TemplateDesc t, final String name)
  {
    if (t == null || name == null)
      return null;
    for (final ChoiceDesc ch : t.choices)
    {
      if (name.equals(ch.name))
        return ch;
    }
    return null;
  }

  private static Map<String, String> choiceArgs(final JsonMsg inMsg,
                                                final ChoiceDesc ch)
      throws DomatarException
  {
    final Map<String, String> args = new LinkedHashMap<>();
    final String argument = inMsg.getAttr("Argument");

    if (argument != null && !argument.isEmpty())
    {
      final String trimmed = argument.trim();
      if (trimmed.startsWith("{"))
      {
        final JsonMap map = Json.parseMap(trimmed);
        for (final String key : map.keySet())
        {
          final Object value = map.get(key);
          if (value != null)
            args.put(key, value.toString());
        }
      }
    }

    if (ch != null)
    {
      for (final FieldDesc field : ch.argumentFields)
      {
        final String value = inMsg.getAttr(field.name);
        if (value != null)
          args.put(field.name, value);
      }
    }
    return args;
  }

  private static String jsonIds(final java.util.List<String> ids)
      throws DomatarException
  {
    final JsonList list = new JsonArrayList();
    if (ids != null)
    {
      for (final String id : ids)
        list.add(id);
    }
    return Json.toJson(list);
  }

  private static String createdIds(final SubmitResult result)
      throws DomatarException
  {
    final JsonList list = new JsonArrayList();
    if (result != null && result.created != null)
    {
      for (final Contract c : result.created)
        list.add(c.contractId);
    }
    return Json.toJson(list);
  }
}
