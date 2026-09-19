/*
 * Copyright (c) 2024 Domatar
 */

package com.canton.objimpl;

import com.canton.ledger.IouTemplates;
import com.domatar.util.DomId;
import com.domatar.util.JsonMsg;
import com.domatar.util.Obj;
import com.domatar.util.ObjAttrs;
import com.domatar.util.ObjImpl;
import com.domatar.util.DomatarException;
import com.domatar.util.DomatarMsgClient;

/**
 * LLM-native facade for the Canton app. Forwards to this user's party,
 * Active container, or contract handle — never another actId's host.
 */
public class CantonAppImpl extends ObjImpl
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

    final String actId = CantonAuth.destActId(inMsg, obj);
    if (actId == null)
    {
      outMsg.addError(opr, "Obj not found");
      return outMsg.toString();
    }

    if ("BindParty".equals(opr) || "GetParty".equals(opr))
      forward(opr, opr, HandleSync.partyDomId(actId), "party",
          copyAttrs(inMsg, "PartyId"), msgClient, outMsg);
    else if ("ListContracts".equals(opr))
      forward(opr, "GetContracts", HandleSync.activeDomId(actId), "contracts",
          null, msgClient, outMsg);
    else if ("Sync".equals(opr))
      forward(opr, "Sync", HandleSync.activeDomId(actId), "contracts",
          null, msgClient, outMsg);
    else if ("CreateIou".equals(opr))
    {
      final ObjAttrs attrs = copyAttrs(inMsg, "Issuer", "Owner", "Amount", "Currency");
      attrs.addAttr("TemplateId", IouTemplates.TEMPLATE_ID);
      forward(opr, "Create", HandleSync.activeDomId(actId), "contracts",
          attrs, msgClient, outMsg);
    }
    else if ("GetIou".equals(opr) || "Transfer".equals(opr) || "Settle".equals(opr))
    {
      try
      {
        final DomId handle = handleOf(inMsg, actId);
        if (handle == null)
        {
          outMsg.addError(opr, "Missing ContractId");
          return outMsg.toString();
        }
        final ObjAttrs attrs = "Transfer".equals(opr)
            ? copyAttrs(inMsg, "NewOwner") : null;
        forward(opr, opr, handle, IouTemplates.CLS_ID, attrs, msgClient, outMsg);
      }
      catch (final DomatarException e)
      {
        outMsg.addError(opr, e.getMessage());
      }
    }
    else
      return super.handleMsg(msg, obj, contextPath, contextRealPath, msgClient);

    return outMsg.toString();
  }

  @Override
  public boolean hasRights(final JsonMsg inMsg, final Obj obj,
                           final DomatarMsgClient msgClient)
      throws DomatarException
  {
    return CantonAuth.isVerifiedOwner(inMsg, obj);
  }

  private static DomId handleOf(final JsonMsg inMsg, final String actId)
      throws DomatarException
  {
    final String handleStr = firstNonEmpty(inMsg, "HandleDomId", "ContractDomId");
    if (handleStr != null)
      return localHandle(new DomId(handleStr), actId);

    final String contractId = inMsg.getAttr("ContractId");
    if (contractId == null || contractId.isEmpty())
      return null;

    if (contractId.indexOf('.') >= 0)
    {
      DomId parsed = null;
      try
      {
        parsed = new DomId(contractId);
      }
      catch (final DomatarException ignored)
      {
      }
      if (parsed != null)
        return localHandle(parsed, actId);
    }
    return localHandle(HandleSync.subHost(actId, contractId), actId);
  }

  private static DomId localHandle(final DomId dst, final String actId)
      throws DomatarException
  {
    if (dst == null || dst.actId == null || !actId.equals(dst.actId)
        || !"canton".equals(dst.appId)
        || !DomId.subHstId("canton", actId).equals(dst.hstId))
      throw new DomatarException("Do not send to another actId");
    return dst;
  }

  private static void forward(final String facadeOpr, final String innerOpr,
                              final DomId dst, final String clsId,
                              final ObjAttrs attrs,
                              final DomatarMsgClient msgClient,
                              final JsonMsg outMsg)
      throws DomatarException
  {
    try
    {
      final JsonMsg req = new JsonMsg();
      req.addRequestBody(innerOpr, attrs);
      if (clsId != null)
        req.addClsId("canton", clsId);
      copyReply(outMsg, facadeOpr, msgClient.send(dst, req));
    }
    catch (final DomatarException e)
    {
      outMsg.addError(facadeOpr, e.getMessage());
    }
  }

  private static void copyReply(final JsonMsg outMsg, final String opr,
                                final JsonMsg resp)
      throws DomatarException
  {
    if (resp == null)
    {
      outMsg.addError(opr, "No response");
      return;
    }
    if (resp.isFailure() || (resp.getError() != null && !resp.getError().isEmpty()
        && !"Success".equals(resp.getError())))
    {
      String msg = resp.getErrorMsg();
      if (msg == null || msg.isEmpty())
        msg = resp.getError();
      outMsg.addError(opr, msg);
      return;
    }
    final ObjAttrs attrs = resp.getAttrs();
    outMsg.addResponseBody(opr, attrs != null ? attrs : new ObjAttrs());
  }

  private static ObjAttrs copyAttrs(final JsonMsg inMsg, final String... names)
      throws DomatarException
  {
    final ObjAttrs attrs = new ObjAttrs();
    if (inMsg == null || names == null)
      return attrs;
    for (final String name : names)
    {
      final String value = inMsg.getAttr(name);
      if (value != null)
        attrs.addAttr(name, value);
    }
    return attrs;
  }

  private static String firstNonEmpty(final JsonMsg inMsg, final String... names)
      throws DomatarException
  {
    if (inMsg == null || names == null)
      return null;
    for (final String name : names)
    {
      final String value = inMsg.getAttr(name);
      if (value != null && !value.isEmpty())
        return value;
    }
    return null;
  }
}
