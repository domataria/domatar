/*
 * Copyright (c) 2024 Domatar
 */

package com.domatar.srv;

import com.domatar.db.ObjDb;
import com.domatar.util.JsonMsg;
import com.domatar.util.Obj;
import com.domatar.util.ObjAttrs;
import com.domatar.util.ObjImpl;
import com.domatar.util.DomId;
import com.domatar.util.DomatarException;
import com.domatar.util.DomatarMsgClient;

/**
 * Handler for the (domatar, srv) class.
 * Spec-Service.txt PART 5 / PART 6.
 *
 * Operations:
 *   GetSrv(SrvAppId, SrvId) — return the service descriptor's Attrs document,
 *                             located on this host by the ObjId convention
 *                             <SrvId>Srv, with obj AppId = SrvAppId.
 *   GetObj / GetLnks        — inherited from ObjImpl.
 *
 * Authorization: Public. Service descriptors contain structural metadata only.
 */
public class SrvImpl extends ObjImpl
{
  @Override
  public boolean hasRights(final JsonMsg inMsg, final Obj obj,
                           final DomatarMsgClient msgClient)
      throws DomatarException
  {
    return true;
  }

  @Override
  public String handleMsg(final String msg, final Obj obj,
                          final String contextPath,
                          final String contextRealPath,
                          final DomatarMsgClient msgClient)
      throws DomatarException
  {
    final JsonMsg inMsg  = new JsonMsg(msg);
    final String  opr    = inMsg.getOperation();
    final JsonMsg outMsg = new JsonMsg();

    if ("GetSrv".equals(opr))
    {
      final String reqAppId = inMsg.getAttr("SrvAppId");
      final String reqSrvId = inMsg.getAttr("SrvId");

      if (reqAppId == null || reqAppId.isEmpty()
          || reqSrvId == null || reqSrvId.isEmpty())
      {
        outMsg.addError(opr, "SrvAppId and SrvId are required");
        return outMsg.toString();
      }

      final DomId dst      = inMsg.getDstId();
      final DomId srvDomId = new DomId(dst.hstId, reqAppId, dst.actId, reqSrvId + "Srv");

      final Obj srvObj = ObjDb.getObj(srvDomId);

      if (srvObj == null)
      {
        outMsg.addError(opr, "Service not found: (" + reqAppId + ", " + reqSrvId
                        + ") on " + dst.hstId);
        return outMsg.toString();
      }

      final ObjAttrs out = new ObjAttrs();
      out.addAttr("SrvAppId", reqAppId);
      out.addAttr("SrvId",    reqSrvId);
      out.addAttr("ObjName",  srvObj.objName);
      out.addAttr("ObjDesc",  srvObj.objDesc);
      out.addAttrs("Attrs",   srvObj.attrs);
      outMsg.addResponseBody(opr, out);
      return outMsg.toString();
    }

    return super.handleMsg(msg, obj, contextPath, contextRealPath, msgClient);
  }
}
