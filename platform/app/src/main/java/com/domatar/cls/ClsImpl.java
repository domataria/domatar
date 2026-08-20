/*
 * Copyright (c) 2024 Domatar
 */

package com.domatar.cls;

import com.domatar.core.ClsResolver;
import com.domatar.db.ObjDb;
import com.domatar.util.JsonMsg;
import com.domatar.util.Obj;
import com.domatar.util.ObjAttrs;
import com.domatar.util.ObjImpl;
import com.domatar.util.DomId;
import com.domatar.util.DomatarException;
import com.domatar.util.DomatarMsgClient;

/**
 * Handler for the (domatar, cls) class.
 * Spec-Class.txt PART 6 / PART 8 / PART 9.
 *
 * Operations:
 *   GetCls(ClsAppId, ClsId) - return the requested class descriptor's
 *                             Attrs document, located on this host by
 *                             the ObjId convention: <ClsId>Cls.
 *   GetObj                  - inherited from ObjImpl.
 *
 * Authorization: Public. Class descriptors contain structural metadata
 * only; no user data is exposed.
 */
public class ClsImpl extends ObjImpl
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

    if ("GetCls".equals(opr))
    {
      final String reqAppId = inMsg.getAttr("ClsAppId");
      final String reqClsId = inMsg.getAttr("ClsId");

      if (reqAppId == null || reqAppId.isEmpty()
          || reqClsId == null || reqClsId.isEmpty())
      {
        outMsg.addError(opr, "ClsAppId and ClsId are required");
        return outMsg.toString();
      }

      // ObjId convention: ClsInstall.addClsObj writes descriptors
      // with ObjId = clsId + "Cls" to distinguish them from same-named
      // data objects (Spec-Class.txt PART 8).
      final DomId dst      = inMsg.getDstId();
      final DomId clsDomId = new DomId(dst.hstId, reqAppId,
                                       dst.actId, reqClsId + "Cls");

      // Route through ClsResolver: legacy inline descriptors pass through
      // unchanged; service-implementing descriptors are merged from their
      // service objects (Spec-Service.txt PART 11 / Update-to-Services T1.7).
      final String resolvedJson = ClsResolver.resolve(clsDomId, reqAppId, reqClsId);

      if (resolvedJson == null)
      {
        outMsg.addError(opr,
            "Class not found: (" + reqAppId + ", " + reqClsId
            + ") on " + dst.hstId);
        return outMsg.toString();
      }

      final Obj clsObj = ObjDb.getObj(clsDomId);

      if (clsObj == null)
      {
        outMsg.addError(opr,
            "Class not found: (" + reqAppId + ", " + reqClsId
            + ") on " + dst.hstId);
        return outMsg.toString();
      }

      final ObjAttrs out = new ObjAttrs();
      out.addAttr("ClsAppId", reqAppId);
      out.addAttr("ClsId",    reqClsId);
      out.addAttr("ObjName",  clsObj.objName);
      out.addAttr("ObjDesc",  clsObj.objDesc);
      out.addAttrs("Attrs",   new ObjAttrs(resolvedJson));
      outMsg.addResponseBody(opr, out);
      return outMsg.toString();
    }

    return super.handleMsg(msg, obj, contextPath,
                           contextRealPath, msgClient);
  }
}
