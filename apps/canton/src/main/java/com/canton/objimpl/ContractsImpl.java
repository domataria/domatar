/*
 * Copyright (c) 2024 Domatar
 */

package com.canton.objimpl;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.canton.ledger.CantonClients;
import com.canton.ledger.IouTemplates;
import com.canton.ledger.SubmitResult;
import com.domatar.db.LnkDb;
import com.domatar.db.ObjDb;
import com.domatar.util.JsonArrayList;
import com.domatar.util.JsonList;
import com.domatar.util.JsonMsg;
import com.domatar.util.Lnk;
import com.domatar.util.Obj;
import com.domatar.util.ObjAttrs;
import com.domatar.util.ObjImpl;
import com.domatar.util.DomatarException;
import com.domatar.util.DomatarMsgClient;

public class ContractsImpl extends ObjImpl
{
  @Override
  public String handleMsg(final String msg, final Obj obj, final String contextPath,
                          final String contextRealPath, final DomatarMsgClient msgClient)
      throws DomatarException
  {
    final JsonMsg inMsg = new JsonMsg(msg);
    final String opr = inMsg.getOperation();

    if ("Compensate".equals(opr))
      return super.handleMsg(msg, obj, contextPath, contextRealPath, msgClient);

    final JsonMsg outMsg = new JsonMsg();

    if (!hasRights(inMsg, obj, msgClient))
      return notAuthorized(inMsg);

    final Obj target = CantonAuth.requireObj(inMsg, obj);
    if (target == null)
    {
      outMsg.addError(opr, "Obj not found");
      return outMsg.toString();
    }

    if ("GetLnks".equals(opr))
    {
      try
      {
        HandleSync.sync(target.domId.actId);
      }
      catch (final DomatarException e)
      {
        outMsg.addError(opr, e.getMessage());
        return outMsg.toString();
      }
      return super.handleMsg(msg, target, contextPath, contextRealPath, msgClient);
    }

    if ("Sync".equals(opr))
      sync(opr, target, outMsg);
    else if ("Create".equals(opr))
      create(opr, inMsg, target, outMsg);
    else if ("GetContracts".equals(opr))
      getContracts(opr, target, outMsg);
    else
      return super.handleMsg(msg, obj, contextPath, contextRealPath, msgClient);

    return outMsg.toString();
  }

  @Override
  public boolean hasRights(final JsonMsg inMsg, final Obj obj,
                           final DomatarMsgClient msgClient)
      throws DomatarException
  {
    return CantonAuth.isVerifiedOwner(inMsg, obj, msgClient);
  }

  private static void sync(final String opr, final Obj obj, final JsonMsg outMsg)
      throws DomatarException
  {
    try
    {
      final int count = HandleSync.sync(obj.domId.actId);
      final ObjAttrs out = new ObjAttrs();
      out.addAttr("Count", Integer.toString(count));
      outMsg.addResponseBody(opr, out);
    }
    catch (final DomatarException e)
    {
      outMsg.addError(opr, e.getMessage());
    }
  }

  private static void create(final String opr, final JsonMsg inMsg,
                             final Obj obj, final JsonMsg outMsg)
      throws DomatarException
  {
    try
    {
      final String actId = obj.domId.actId;
      final String party = HandleSync.boundParty(actId);
      String templateId = inMsg.getAttr("TemplateId");
      if (templateId == null || templateId.isEmpty())
        templateId = IouTemplates.TEMPLATE_ID;

      final Map<String, String> payload = new LinkedHashMap<>();
      putAttr(payload, "Issuer", inMsg);
      putAttr(payload, "Owner", inMsg);
      putAttr(payload, "Amount", inMsg);
      putAttr(payload, "Currency", inMsg);

      final SubmitResult result = CantonClients.get()
          .submitCreate(party, templateId, payload);
      HandleSync.sync(actId);

      String contractId = "";
      if (result != null && result.created != null && !result.created.isEmpty())
        contractId = result.created.get(0).contractId;

      final ObjAttrs out = new ObjAttrs();
      out.addAttr("ContractId", contractId);
      outMsg.addResponseBody(opr, out);
    }
    catch (final DomatarException e)
    {
      outMsg.addError(opr, e.getMessage());
    }
  }

  private static void getContracts(final String opr, final Obj obj,
                                   final JsonMsg outMsg) throws DomatarException
  {
    try
    {
      HandleSync.sync(obj.domId.actId);
    }
    catch (final DomatarException e)
    {
      outMsg.addError(opr, e.getMessage());
      return;
    }

    final List<Lnk> lnks = LnkDb.getLnks(obj.domId, "canton", null,
                                          null, null, 10000, false);
    final JsonList list = new JsonArrayList(lnks.size());

    for (final Lnk lnk : lnks)
    {
      final Obj handle = ObjDb.getObj(lnk.lnkDomId);
      if (handle == null || handle.attrs == null)
        continue;
      final ObjAttrs row = new ObjAttrs(handle.attrs);
      row.addAttr("ContractDomId", lnk.lnkDomId.toString());
      list.add(row.toMap());
    }

    final ObjAttrs out = new ObjAttrs();
    out.addAttr("Contracts", list);
    outMsg.addResponseBody(opr, out);
  }

  private static void putAttr(final Map<String, String> payload,
                              final String name, final JsonMsg inMsg)
      throws DomatarException
  {
    final String value = inMsg.getAttr(name);
    if (value != null)
      payload.put(name, value);
  }
}
