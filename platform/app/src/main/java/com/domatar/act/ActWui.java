/*
 * Copyright (c) 2024 Domatar
 */

package com.domatar.act;

import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;

import com.domatar.core.Context;
import com.domatar.core.DomatarConfig;
import com.domatar.db.ActDb;
import com.domatar.servlet.Cookies;
import com.domatar.servlet.DomatarServlet;
import com.domatar.util.Act;
import com.domatar.util.JsonMsg;
import com.domatar.util.ObjAttrs;
import com.domatar.util.DomatarException;
import com.domatar.util.DomatarMsgClient;
import com.domatar.util.DomId;

/**
 * Servlet implementation class ActWui.
 *
 * Federated identity entry-point (Spec-Login.txt). All four ops dispatch
 * to the CENTRAL HOST of the appId in the user's identity, not to this
 * prv's local act manager:
 *
 *   AddAct       -> (targetAppId, "act", "act@act", "actManager")
 *   Login        -> (parseAppId(usrId), ...)
 *   Logout       -> (parseAppId(usrId), ...)
 *   VerifyLogin  -> (parseAppId(usrId), ...)
 *
 * AddAct requires an explicit content AppId (no default). Shell ids
 * (login, desktop, navigator, appstore) and the platform app (domatar)
 * are rejected — see {@link SignupAppId}. The per-user sub-host HstId
 * is "<appId>-<actId>" (DomId.subHstId / HOST_SEP '-') — e.g.
 * "quippin-<fingerprint>" or "bookstore-<fingerprint>".
 */
@WebServlet("/ActWui")
public class ActWui extends DomatarServlet
{
  private static final long serialVersionUID = -9017146980616463634L;

  @Override
  protected boolean isAnonymousAccess()
  {
    return true;
  }

  @Override
  protected JsonMsg getMsg(final HttpServletRequest req,
                           final DomId srcDomId,
                           final Context context,
                           final Act srcAct,
                           final DomatarMsgClient msgClient) throws DomatarException
  {
    final String opr = getParam(req, "Action");

    final JsonMsg msg = new JsonMsg();
    final ObjAttrs attrs = new ObjAttrs();

    String dstAppId = null;

    if ("AddAct".equals(opr))
    {
      // ActId: absent/empty for sign-up (server mints it); a fingerprint for link mode.
      final String actId   = getParam(req, "ActId");
      final String usrIdParam = getParam(req, "UsrId");
      final String usrName    = getParam(req, "UsrName");
      final String pwd        = getParam(req, "Pwd");
      final String prvId      = srcDomId.hstId;
      String appId = getParam(req, "AppId");
      if (appId != null)
        appId = appId.trim();

      final String appIdErr = SignupAppId.rejectSignup(appId);
      if (appIdErr != null)
      {
        msg.addError(opr, appIdErr);
        return msg;
      }

      final String domain = DomatarConfig.getDomain();
      final String ip     = context.usrIp;

      if (usrIdParam == null)
      {
        msg.addError(opr, "Unknown UsrId");
        return msg;
      }

      String usrId = usrIdParam;
      final int at = usrId.lastIndexOf('@');
      if (at > 0)
      {
        final String suffix = usrId.substring(at + 1);
        if (!appId.equals(suffix))
        {
          msg.addError(opr, "UsrId suffix does not match AppId");
          return msg;
        }
        usrId = usrId.substring(0, at);
      }

      if (usrName == null)
      {
        msg.addError(opr, "Unknown UsrName");
        return msg;
      }

      if (pwd == null)
      {
        msg.addError(opr, "Unknown Pwd");
        return msg;
      }

      // ActId is optional (absent = sign-up). Pass it only for link mode.
      if (actId != null && !actId.isEmpty())
        attrs.addAttr("ActId", actId);
      attrs.addAttr("UsrId",   usrId);
      attrs.addAttr("UsrName", usrName);
      attrs.addAttr("Pwd",     pwd);
      attrs.addAttr("AppId",   appId);
      attrs.addAttr("Domain",  domain);
      attrs.addAttr("PrvId",   prvId);
      attrs.addAttr("Ip",      ip);

      dstAppId = appId;
    }
    else if ("Login".equals(opr))
    {
      final String usrId = getParam(req, "UsrId");
      final String pwd   = getParam(req, "Pwd");
      final String ip    = context.usrIp;

      if (usrId == null)
      {
        msg.addError(opr, "Unknown UsrId");
        return msg;
      }

      if (pwd == null)
      {
        msg.addError(opr, "Unknown Pwd");
        return msg;
      }

      dstAppId = DomId.getAppId(usrId);

      if (dstAppId == null)
      {
        msg.addError(opr, "UsrId must be of the form <localname>@<appId>");
        return msg;
      }

      attrs.addAttr("UsrId", usrId);
      attrs.addAttr("Pwd",   pwd);
      attrs.addAttr("Ip",    ip);
    }
    else if ("Logout".equals(opr))
    {
      final Cookie[] cookies = req.getCookies();
      final String usrId = Cookies.getCookie(cookies, "usrId");
      final String ip    = context.usrIp;

      if (usrId == null)
      {
        msg.addError(opr, "Unknown UsrId");
        return msg;
      }

      dstAppId = DomId.getAppId(usrId);

      if (dstAppId == null)
      {
        // Cookie-stored usrId is not in @appId form (e.g. legacy bare
        // handle). Fall back to the local prv's act manager so logout
        // at least clears the local row.
        dstAppId = srcDomId.hstId;
      }

      attrs.addAttr("UsrId", usrId);
      attrs.addAttr("Ip",    ip);
    }
    else if ("VerifyLogin".equals(opr))
    {
      final Cookie[] cookies = req.getCookies();
      final String token = Cookies.getCookie(cookies, "token");
      final String usrId = Cookies.getCookie(cookies, "usrId");

      dstAppId = DomId.getAppId(usrId);

      if (dstAppId == null)
        dstAppId = srcDomId.hstId;

      attrs.addAttr("Token", token);
      attrs.addAttr("UsrId", usrId);
    }
    else if ("Rebind".equals(opr))
    {
      // Spec-OwnIds.txt PART 10 / Update-OwnIds.txt Phase 5.
      // Requires a verified session as the target actId. Sim resolves the
      // genesis key from GenesisVault; production would verify a
      // genesis-signed device request (PART 7.4).
      final String actId = getParam(req, "ActId");
      final String usrId = (context != null) ? context.usrId : null;

      if (actId == null || actId.isEmpty())
      {
        msg.addError(opr, "Unknown ActId");
        return msg;
      }

      if (usrId == null || usrId.isEmpty())
      {
        msg.addError(opr, "Unknown UsrId (login required)");
        return msg;
      }

      dstAppId = DomId.getAppId(usrId);
      if (dstAppId == null)
      {
        msg.addError(opr, "UsrId must be of the form <localname>@<appId>");
        return msg;
      }

      attrs.addAttr("ActId", actId);
      attrs.addAttr("UsrId", usrId);
    }
    else
    {
      msg.addError(opr, "Unknown action");
      return msg;
    }

    // Provider accounts (usrId is of the form <x>@<x>, e.g. "prv1@prv1") have
    // their actManager on the domatar-<prvActId> sub-host, not on the central app host.
    // Multi-provider (Spec-Login-Multiple PART 9.2): if THIS node already
    // hosts an act row for the usrId, verify locally so sign-in on an
    // attached provider does not bounce to the original app central host.
    final DomId domId;
    final String usrId = attrs.getAttr("UsrId");
    if (isProviderUsrId(usrId))
    {
      final String prvActId = DomatarConfig.getPrvActId() != null
                                ? DomatarConfig.getPrvActId()
                                : dstAppId + "@" + dstAppId;
      final String ssHstId  = DomId.subHstId("domatar", prvActId);
      domId = new DomId(ssHstId, "domatar", prvActId, "actManager");
    }
    else if ("Login".equals(opr) && usrId != null && ActDb.getActByUsrId(usrId) != null)
    {
      final String localPrv = DomatarConfig.getPrvId() != null
          ? DomatarConfig.getPrvId() : DomatarConfig.getHstId();
      attrs.addAttr("AppId", dstAppId);
      domId = new DomId(localPrv, "act", "act@act", "actManager");
    }
    else
    {
      domId = new DomId(dstAppId, "act", "act@act", "actManager");
    }

    msg.addRequestHead(srcDomId, domId, context);
    msg.addClsId("act", "actManager");
    msg.addRequestBody(opr, attrs);

    return msg;
  }

  /**
   * Returns true when usrId is of the form &lt;x&gt;@&lt;x&gt;, i.e. the local part equals
   * the app part.  These are provider accounts whose actManager lives on the
   * domatar-<prvActId> sub-host rather than the federated app central host.
   */
  private static boolean isProviderUsrId(final String usrId)
  {
    if (usrId == null)
      return false;

    final int at = usrId.lastIndexOf('@');

    if (at <= 0)
      return false;

    final String local = usrId.substring(0, at);
    final String app   = usrId.substring(at + 1);

    return local.equals(app);
  }
}
