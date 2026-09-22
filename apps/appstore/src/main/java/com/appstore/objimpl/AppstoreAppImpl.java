/*
 * Copyright (c) 2024 Domatar
 */

package com.appstore.objimpl;

import com.domatar.core.Auth;
import com.domatar.util.JsonMsg;
import com.domatar.util.Obj;
import com.domatar.util.ObjAttrs;
import com.domatar.util.ObjImpl;
import com.domatar.util.DomId;
import com.domatar.util.DomatarException;
import com.domatar.util.DomatarMsgClient;

/**
 * LLM-native facade for the App Store app.
 *
 * Handles class (appstore, app) — the app-appstore entry-point object.
 * Delegates to the existing (appstore, home) handler so the LLM can ask
 * "what apps do I have installed?" and "what apps are available?" with a
 * single GetApps call at the well-known entry point.
 *
 * Operations: GetApps().
 *
 * Spec: Spec-LLM-Oriented-Msgs.txt — APP STORE section.
 */
public class AppstoreAppImpl extends ObjImpl
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

    if (!Auth.isVerified(inMsg))
      return notAuthorized(inMsg);

    final DomId appDomId = inMsg.getDstId();

    if ("GetApps".equals(opr))
      getApps(opr, outMsg, appDomId, obj, msgClient);
    else
      return super.handleMsg(msg, obj, contextPath, contextRealPath, msgClient);

    return outMsg.toString();
  }

  // ---------------------------------------------------------------------------

  /**
   * Delegates to the (appstore, home) handler on the home sub-object.
   * The home handler (AppstoreImpl) already returns InstalledApps and
   * AvailableApps; we relay its response directly.
   */
  private void getApps(final String opr, final JsonMsg outMsg, final DomId appDomId,
                       final Obj obj, final DomatarMsgClient msgClient)
      throws DomatarException
  {
    final DomId homeId = new DomId(appDomId.hstId, "appstore", appDomId.actId, "home");

    final JsonMsg req = new JsonMsg();
    req.addRequestBody("GetApps", null);
    req.addClsId("appstore", "home");

    try
    {
      final JsonMsg resp = msgClient.send(homeId, req);
      if (resp == null)
      {
        outMsg.addError(opr, "App Store unavailable");
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
    catch (Exception e)
    {
      outMsg.addError(opr, "App Store error: " + e.getMessage());
    }
  }
}
