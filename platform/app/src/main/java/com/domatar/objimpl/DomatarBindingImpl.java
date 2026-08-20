/*
 * Copyright (c) 2024 Domatar
 */

package com.domatar.objimpl;

import com.domatar.core.Auth;
import com.domatar.core.Context;
import com.domatar.crypto.Binding;
import com.domatar.db.ActDb;
import com.domatar.db.ObjDb;
import com.domatar.util.JsonMsg;
import com.domatar.util.Obj;
import com.domatar.util.ObjAttrs;
import com.domatar.util.ObjImpl;
import com.domatar.util.DomId;
import com.domatar.util.DomatarException;
import com.domatar.util.DomatarMsgClient;

/**
 * Handler for {@code (domatar, binding)} on the user substrate
 * (Update-Mandatory-App-Rewrite.txt Phase 4).
 *
 * <p>Ops: GetBinding, SetBinding, PutBinding (alias of SetBinding).
 */
public class DomatarBindingImpl extends ObjImpl
{
  @Override
  public String handleMsg(final String msg, final Obj obj, final String contextPath,
                          final String contextRealPath, final DomatarMsgClient msgClient)
      throws DomatarException
  {
    final JsonMsg inMsg  = new JsonMsg(msg);
    final String  opr    = inMsg.getOperation();
    final JsonMsg outMsg = new JsonMsg();

    if (!hasRights(inMsg, obj, msgClient))
      return notAuthorized(inMsg);

    if ("GetBinding".equals(opr))
      getBinding(opr, inMsg, outMsg);
    else if ("SetBinding".equals(opr) || "PutBinding".equals(opr))
      setBinding(opr, inMsg, outMsg);
    else
      return super.handleMsg(msg, obj, contextPath, contextRealPath, msgClient);

    return outMsg.toString();
  }

  @Override
  public boolean hasRights(final JsonMsg inMsg, final Obj obj,
                           final DomatarMsgClient msgClient) throws DomatarException
  {
    if (!Auth.isVerified(inMsg))
      return false;

    final Context ctx = inMsg.getContext();
    final DomId   dst = inMsg.getDstId();

    return ctx != null && ctx.actId != null && dst != null
        && ctx.actId.equals(dst.actId);
  }

  private void getBinding(final String opr, final JsonMsg inMsg, final JsonMsg outMsg)
      throws DomatarException
  {
    final DomId dst = inMsg.getDstId();
    final Obj bindingObj = ObjDb.getObj(dst);
    final ObjAttrs outAttrs = new ObjAttrs();

    if (bindingObj != null && bindingObj.attrs != null)
      outAttrs.addAll(bindingObj.attrs.toMap());

    outMsg.addResponseBody(opr, outAttrs);
  }

  private void setBinding(final String opr, final JsonMsg inMsg, final JsonMsg outMsg)
      throws DomatarException
  {
    final DomId dst = inMsg.getDstId();
    final String actId = inMsg.getAttr("ActId") != null
        ? inMsg.getAttr("ActId") : dst.actId;
    final String genesisPubKey = inMsg.getAttr("GenesisPubKey");
    final String ownId = inMsg.getAttr("OwnId");
    final String ownPubKey = inMsg.getAttr("OwnPubKey");
    final String versionStr = inMsg.getAttr("Version");
    final String notBeforeStr = inMsg.getAttr("NotBefore");
    final String genesisSig = inMsg.getAttr("GenesisSig");

    if (actId == null || genesisPubKey == null || ownPubKey == null
        || versionStr == null || notBeforeStr == null || genesisSig == null)
    {
      outMsg.addError(opr, "Missing binding fields");
      return;
    }

    if (!actId.equals(dst.actId))
    {
      outMsg.addError(opr, "Binding ActId does not match sub-host");
      return;
    }

    final long version;
    final long notBefore;

    try
    {
      version = Long.parseLong(versionStr);
      notBefore = Long.parseLong(notBeforeStr);
    }
    catch (final NumberFormatException e)
    {
      outMsg.addError(opr, "Invalid Version / NotBefore");
      return;
    }

    final Binding binding = Binding.fromStored(actId, genesisPubKey, ownPubKey,
        version, notBefore, genesisSig);

    if (ownId != null && !ownId.equals(binding.ownId))
    {
      outMsg.addError(opr, "OwnId does not match OwnPubKey");
      return;
    }

    if (!binding.verify())
    {
      outMsg.addError(opr, "Binding.verify failed");
      return;
    }

    final Obj existing = ObjDb.getObj(dst);
    long localVersion = -1L;

    if (existing != null && existing.attrs != null
        && existing.attrs.getAttr("Version") != null)
    {
      try
      {
        localVersion = Long.parseLong(existing.attrs.getAttr("Version"));
      }
      catch (final NumberFormatException ignored)
      {
        localVersion = -1L;
      }
    }

    if (binding.version <= localVersion)
    {
      outMsg.addResponseBody(opr, null);
      return;
    }

    final ObjAttrs attrs = existing != null && existing.attrs != null
        ? new ObjAttrs(existing.attrs) : new ObjAttrs();

    attrs.addAttr("ActId", binding.actId);
    attrs.addAttr("GenesisPubKey", binding.genesisPubKeyB64);
    attrs.addAttr("OwnId", binding.ownId);
    attrs.addAttr("OwnPubKey", binding.ownPubKeyB64);
    attrs.addAttr("Version", Long.toString(binding.version));
    attrs.addAttr("NotBefore", Long.toString(binding.notBefore));
    attrs.addAttr("GenesisSig", binding.genesisSig);

    copyDelegIfPresent(inMsg, attrs);

    if (existing != null)
      ObjDb.modifyObj(existing.modify(null, null, null, null, null, attrs));
    else
      ObjDb.addObj(new Obj(dst, "domatar", "binding",
          "Binding", "actId->ownId binding", attrs));

    try
    {
      ActDb.setBinding(binding.actId, binding.genesisPubKeyB64, binding.ownPubKeyB64,
          binding.version, binding.notBefore, binding.genesisSig);
    }
    catch (final Exception e)
    {
      System.out.println("WARN: ActDb.setBinding failed for actId="
          + binding.actId + ": " + e);
    }

    outMsg.addResponseBody(opr, null);
  }

  private static void copyDelegIfPresent(final JsonMsg inMsg, final ObjAttrs attrs)
      throws DomatarException
  {
    final String delegation = inMsg.getAttr("Delegation");
    final String delegNotAfter = inMsg.getAttr("DelegNotAfter");
    final String delegSig = inMsg.getAttr("DelegSig");

    if (delegation != null)
      attrs.addAttr("Delegation", delegation);

    if (delegNotAfter != null)
      attrs.addAttr("DelegNotAfter", delegNotAfter);

    if (delegSig != null)
      attrs.addAttr("DelegSig", delegSig);
  }
}
