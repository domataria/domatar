/*
 * Copyright (c) 2024 Domatar
 */

package com.domatar.objimpl;

import com.domatar.core.Auth;
import com.domatar.core.Context;
import com.domatar.db.HstDb;
import com.domatar.db.ObjDb;
import com.domatar.util.JsonMsg;
import com.domatar.util.Obj;
import com.domatar.util.ObjAttrs;
import com.domatar.util.ObjImpl;
import com.domatar.util.DomatarException;
import com.domatar.util.DomatarMsgClient;

/**
 * Handler for class (domatar, host).
 *
 * Lives at:
 *   domatar-&lt;prvActId&gt; / domatar / &lt;prvActId&gt; / host-&lt;hstId&gt;
 *
 * Spec-DomatarApp.txt PART 7.2.
 *
 * Operations:
 *   GetObj    - inherited from ObjImpl
 *   Open      - returns empty children list (leaf in v1; PART 12 TODO)
 *   UpdateHst - updates Domain and/or PrvId in HstDb and on this obj row
 */
public class HostImpl extends ObjImpl
{
  @Override
  public String handleMsg(final String msg, final Obj obj,
                          final String contextPath,
                          final String contextRealPath,
                          final DomatarMsgClient msgClient)
      throws DomatarException
  {
    final JsonMsg inMsg  = new JsonMsg(msg);
    final String  opr    = inMsg.getOperation();

    if ("Compensate".equals(opr))
      return super.handleMsg(msg, obj, contextPath, contextRealPath, msgClient);

    final JsonMsg outMsg = new JsonMsg();

    if (!hasRights(inMsg, obj, msgClient))
      return notAuthorized(inMsg);

    if ("UpdateHst".equals(opr))
      updateHst(opr, inMsg, outMsg, obj);
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

  private boolean isOwner(final JsonMsg inMsg, final Obj obj) throws DomatarException
  {
    final Context ctx = inMsg.getContext();
    return ctx != null && ctx.actId != null
        && obj != null && ctx.actId.equals(obj.domId.actId);
  }

  /**
   * UpdateHst: patch Domain and/or PrvId in the local hst table and on
   * this obj row's attrs.  Owner only.
   *
   * The hstId is derived from the obj row's ObjId (strip "host-" prefix).
   */
  private void updateHst(final String opr, final JsonMsg inMsg,
                         final JsonMsg outMsg, final Obj obj)
      throws DomatarException
  {
    if (!isOwner(inMsg, obj))
    {
      outMsg.addError(opr, "Not authorized");
      return;
    }

    final String newDomain = inMsg.getAttr("Domain");
    final String newPrvId  = inMsg.getAttr("PrvId");

    if (newDomain == null && newPrvId == null)
    {
      outMsg.addError(opr, "Provide at least one of Domain or PrvId");
      return;
    }

    // Derive hstId from ObjId: strip the "host-" prefix
    final String objId = obj.domId.objId;
    final String hstId = objId.startsWith("host-") ? objId.substring(5) : objId;

    HstDb.modifyHst(hstId, newDomain, newPrvId);

    final ObjAttrs oldAttrs = obj.attrs != null ? obj.attrs : new ObjAttrs();

    final String resolvedDomain = newDomain != null ? newDomain : oldAttrs.getAttr("Domain");
    final String resolvedPrvId  = newPrvId  != null ? newPrvId  : oldAttrs.getAttr("PrvId");

    final ObjAttrs newAttrs = new ObjAttrs();
    newAttrs.addAttr("HstId",   hstId);
    newAttrs.addAttr("Domain",  resolvedDomain);
    newAttrs.addAttr("PrvId",   resolvedPrvId);
    newAttrs.addAttr("Version", oldAttrs.getAttr("Version"));

    final String newDesc = resolvedDomain + "  prv:" + resolvedPrvId;

    final Obj updated = new Obj(obj.domId,
                                obj.clsAppId, obj.clsId,
                                obj.objName, newDesc,
                                newAttrs);

    ObjDb.modifyObj(updated);

    outMsg.addResponseBody(opr, null);
  }
}
