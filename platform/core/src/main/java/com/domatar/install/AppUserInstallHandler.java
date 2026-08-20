/*
 * Copyright (c) 2024 Domatar
 */

package com.domatar.install;

import com.domatar.app.App;
import com.domatar.app.AppRegistry;
import com.domatar.core.Auth;
import com.domatar.core.Context;
import com.domatar.util.JsonMsg;
import com.domatar.util.Obj;
import com.domatar.util.ObjAttrs;
import com.domatar.util.ObjImpl;
import com.domatar.util.DomatarException;
import com.domatar.util.DomatarMsgClient;

/**
 * Handler for (appId, {@code install}) — the <b>InstallUser</b> message
 * dispatched to an application's install handler (Spec-Installation.txt
 * PART 11.6).
 *
 * <p>AppLoader registers one instance per loaded app:
 * <pre>
 *   ImplMap.register(appId, "install", new AppUserInstallHandler(appId));
 * </pre>
 * The handler looks up the app's {@link AppInstall} instance from
 * {@link AppRegistry} and delegates to {@link AppInstall#installUser}.
 */
public class AppUserInstallHandler extends ObjImpl
{
  private final String appId;

  public AppUserInstallHandler(String appId)
  {
    this.appId = appId;
  }

  // -------------------------------------------------------------------------

  @Override
  public boolean hasRights(JsonMsg inMsg, Obj obj, DomatarMsgClient msgClient)
      throws DomatarException
  {
    String op = inMsg.getOperation();

    if ("InstallUser".equals(op))
      return Auth.isVerified(inMsg);

    return super.hasRights(inMsg, obj, msgClient);
  }

  @Override
  public String handleMsg(String msg,
                          Obj obj,
                          String contextPath,
                          String contextRealPath,
                          DomatarMsgClient msgClient) throws DomatarException
  {
    JsonMsg inMsg  = new JsonMsg(msg);
    String  opr    = inMsg.getOperation();
    JsonMsg outMsg = new JsonMsg();

    if ("InstallUser".equals(opr))
      return handleInstallUser(opr, inMsg, outMsg, msgClient);

    return super.handleMsg(msg, obj, contextPath, contextRealPath, msgClient);
  }

  // -------------------------------------------------------------------------

  private String handleInstallUser(String opr,
                                   JsonMsg inMsg,
                                   JsonMsg outMsg,
                                   DomatarMsgClient msgClient) throws DomatarException
  {
    String actId   = inMsg.getAttr("ActId");
    String usrId   = inMsg.getAttr("UsrId");
    String usrName = inMsg.getAttr("UsrName");
    String prvId   = inMsg.getAttr("PrvId");
    String domain  = inMsg.getAttr("Domain");

    if (actId == null || usrId == null || usrName == null
        || prvId == null || domain == null)
    {
      outMsg.addError(opr,
          "InstallUser requires ActId, UsrId, UsrName, PrvId, and Domain");
      return outMsg.toString();
    }

    Context ctx = inMsg.getContext();

    if (ctx == null || ctx.actId == null || !ctx.actId.equals(actId))
    {
      outMsg.addError(opr, "Not authorized for this account");
      return outMsg.toString();
    }

    if (ctx.usrId != null && !ctx.usrId.equals(usrId))
    {
      outMsg.addError(opr, "UsrId does not match session");
      return outMsg.toString();
    }

    try
    {
      runInstall(actId, usrId, usrName, prvId, domain, msgClient);
    }
    catch (DomatarException e)
    {
      String detail = e.getMessage() != null ? e.getMessage() : e.toString();
      outMsg.addError(opr, "InstallUser failed: " + detail);
      return outMsg.toString();
    }

    outMsg.addResponseBody(opr, new ObjAttrs());
    return outMsg.toString();
  }

  /**
   * Delegates to the app's {@link AppInstall#installUser} via AppRegistry.
   */
  protected void runInstall(String actId,
                            String usrId,
                            String usrName,
                            String prvId,
                            String domain,
                            DomatarMsgClient msgClient) throws DomatarException
  {
    App app = AppRegistry.get(appId);

    if (app == null)
      throw new DomatarException("No AppInstall registered for " + appId);

    app.installInstance.installUser(actId, usrId, usrName, prvId, domain, msgClient);
  }

}
