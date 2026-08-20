/*
 * Copyright (c) 2024 Domatar
 */

package com.domatar.core;

import com.domatar.util.Act;
import com.domatar.util.JsonMsg;
import com.domatar.util.ObjAttrs;
import com.domatar.util.DomatarException;
import com.domatar.util.DomatarMsgClient;
import com.domatar.util.DomId;

public class LoginRemote
{
  // Federated identity (Spec-Login.txt). Verification and account lookup
  // for a given usrId are routed to the CENTRAL HOST of the appId in the
  // usrId's "@<appId>" suffix - not to this prv's own act manager. The
  // central host is the only authority for its own act rows and passwords;
  // every prv reaches the same authority for the same usrId, which is
  // what makes a session world-wide.
  //
  // The trust-boundary callers (DomatarServlet, Msg.doAction) are
  // unchanged - they call verifyLogin / getAct and treat the return
  // value as the verdict; the routing change is invisible to them.

  /**
   * Build the DomId addressing the central act manager for the appId in
   * the given usrId (must be of the form {@code <localname>@<appId>}).
   * Returns null when the input has no '@' suffix or is null.
   * Fingerprint actIds have no '@' and CANNOT be routed; callers must
   * supply a usrId. Limitation resolved in Phase 4 when cross-provider
   * trust switches to the message-carried credential chain.
   */
  private static DomId centralActManager(final String usrIdWithAppSuffix) throws DomatarException
  {
    final String appId = DomId.getAppId(usrIdWithAppSuffix);

    if (appId == null)
      return null;

    return new DomId(appId, "act", "act@act", "actManager");
  }

  public static Act getAct(final String actId,
                           final String usrId,
                           final DomatarMsgClient msgClient) throws DomatarException
  {
    try
    {
      if (usrId == null)
        return null; // fingerprint actId has no '@'; not routable without usrId

      final JsonMsg msg = new JsonMsg();

      final ObjAttrs outAttrs = new ObjAttrs();

      if (actId != null)
        outAttrs.addAttr("ActId", actId);

      outAttrs.addAttr("UsrId", usrId);

      final DomId domId = centralActManager(usrId);

      if (domId == null)
        return null;

      msg.addRequestBody("GetAct", outAttrs);
      msg.addClsId("act", "actManager");

      final JsonMsg retMsg = msgClient.send(domId, msg);

      if (retMsg.isSuccess())
      {
        final Act act = new Act(retMsg.getAttr("ActId"),
                                retMsg.getAttr("UsrId"),
                                retMsg.getAttr("UsrName"),
                                null);

        return act;
      }
      else
        return null;
    }
    catch (Exception e)
    {
      throw new DomatarException(e);
    }
  }

  public static Act verifyLogin(final String usrId,
                                final String actId,
                                final String token,
                                final String ip,
                                final DomatarMsgClient msgClient) throws DomatarException
  {
    try
    {
      // Multi-provider: if THIS node hosts the usrId (attached replica),
      // verify against the local act table first. Tokens issued here are
      // not on the app's original central host (Spec-Login-Multiple R1).
      if (usrId != null)
      {
        try
        {
          if (com.domatar.db.ActDb.getActByUsrId(usrId) != null)
          {
            final Act local = com.domatar.db.ActDb.verifyLogin(actId, usrId, ip, token);

            if (local != null)
              return local;
          }
        }
        catch (final Exception ignored)
        {
          // fall through to central-host verify
        }
      }

      final JsonMsg msg = new JsonMsg();

      final ObjAttrs outAttrs = new ObjAttrs();

      String routingId;

      if (actId != null)
      {
        outAttrs.addAttr("ActId", actId);
        routingId = actId;
      }
      else if (usrId != null)
      {
        outAttrs.addAttr("UsrId", usrId);
        routingId = usrId;
      }
      else
        return null;

      final DomId domId = centralActManager(routingId);

      if (domId == null)
        return null;

      outAttrs.addAttr("Token", token);
      outAttrs.addAttr("Ip", ip);

      msg.addRequestBody("VerifyLogin", outAttrs);
      msg.addClsId("act", "actManager");

      final JsonMsg retMsg = msgClient.send(domId, msg);

      // VerifyLogin always replies with a Success body (the actManager
      // treats "not logged in" as a normal answer, not an error). The
      // verdict lives in the body: a non-null ActId means the user is
      // verified, anything else means they are not. We intentionally do
      // NOT branch on isSuccess() for the verdict; isSuccess()==false
      // would mean the request itself failed (transport, malformed
      // body, etc.), which is also "not verified".
      if (!retMsg.isSuccess())
        return null;

      final String retActId = retMsg.getAttr("ActId");

      if (retActId == null)
        return null;

      final Act act = new Act(retActId,
                              retMsg.getAttr("UsrId"),
                              retMsg.getAttr("UsrName"),
                              token);

      return act;
    }
    catch (Exception e)
    {
      throw new DomatarException(e);
    }
  }
}
