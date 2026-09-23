/*
 * Copyright (c) 2024 Domatar
 */

package com.desktop.objimpl;

import com.domatar.core.Auth;
import com.domatar.core.DomatarConfig;
import com.domatar.install.UserSubstrateIds;
import com.domatar.util.JsonMsg;
import com.domatar.util.Obj;
import com.domatar.util.ObjAttrs;
import com.domatar.util.ObjImpl;
import com.domatar.util.DomId;
import com.domatar.util.DomatarException;
import com.domatar.util.DomatarMsgClient;

/**
 * LLM-native facade for the Desktop app.
 *
 * Handles class (desktop, app) — the app-desktop entry-point object.
 * Delegates GetApps to (desktop, apps) and GetUserApps to the local
 * domatar substrate userApps registry.
 *
 * Spec: Spec-LLM-Oriented-Msgs.txt — DESKTOP section;
 * Update-Mandatory-App-Rewrite.txt Phase 2.
 */
public class DesktopAppImpl extends ObjImpl
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

    if (!Auth.isVerified(msgClient))
      return notAuthorized(inMsg);

    final DomId appDomId = inMsg.getDstId();

    if ("GetApps".equals(opr))
      forward(opr, outMsg, msgClient,
          new DomId(appDomId.hstId, "desktop", appDomId.actId, "apps"),
          "GetApps", "desktop", "apps");
    else if ("GetUserApps".equals(opr))
      forward(opr, outMsg, msgClient,
          UserSubstrateIds.userApps(appDomId.actId, DomatarConfig.getPrvId()),
          "GetUserApps", "domatar", "userApps");
    else
      return super.handleMsg(msg, obj, contextPath, contextRealPath, msgClient);

    return outMsg.toString();
  }

  private void forward(final String opr, final JsonMsg outMsg,
                       final DomatarMsgClient msgClient, final DomId dst,
                       final String bodyOpr, final String clsAppId, final String clsId)
      throws DomatarException
  {
    final JsonMsg req = new JsonMsg();

    req.addRequestBody(bodyOpr, null);
    req.addClsId(clsAppId, clsId);

    try
    {
      final JsonMsg resp = msgClient.send(dst, req);

      if (resp == null)
      {
        outMsg.addError(opr, "Desktop unavailable");
        return;
      }

      final String err = resp.getError();

      if (err != null && !"".equals(err))
      {
        outMsg.addError(opr, err);
        return;
      }

      final ObjAttrs respAttrs = resp.getAttrs();

      outMsg.addResponseBody(opr, respAttrs != null ? respAttrs : new ObjAttrs());
    }
    catch (final Exception e)
    {
      outMsg.addError(opr, "Desktop error: " + e.getMessage());
    }
  }
}
