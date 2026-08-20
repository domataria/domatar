/*
 * Copyright (c) 2024 Domatar
 */

package com.domatar.act;

import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;

import com.domatar.core.Context;
import com.domatar.core.DomatarConfig;
import com.domatar.servlet.DomatarServlet;
import com.domatar.util.Act;
import com.domatar.util.JsonMsg;
import com.domatar.util.ObjAttrs;
import com.domatar.util.DomId;
import com.domatar.util.DomatarException;
import com.domatar.util.DomatarMsgClient;

/**
 * Browser-facing endpoint for provider site customization
 * (Spec-Provider-Customization.txt PART 4;
 * Spec-Mandatory-App-Rewrite.txt PART 10.2).
 *
 * Actions:
 *   GetDefaultAppsConfig / SetDefaultApps / ResetDefaultApps
 *   GetDefaultShells / SetDefaultShells / ResetDefaultShells
 *
 * Dispatches to AppCatalogImpl on this provider's app-catalog object.
 * Requires a verified session (default DomatarServlet behaviour).
 */
@WebServlet("/SiteConfigWui/*")
public class SiteConfigWui extends DomatarServlet
{
  private static final long serialVersionUID = 1L;

  @Override
  protected JsonMsg getMsg(final HttpServletRequest req,
                           final DomId srcDomId,
                           final Context context,
                           final Act srcAct,
                           final DomatarMsgClient msgClient) throws DomatarException
  {
    final JsonMsg msg = new JsonMsg();
    final String  opr = getParam(req, "Action");

    if (opr == null)
    {
      msg.addError(opr, "Missing action");
      return msg;
    }

    if (!"GetDefaultAppsConfig".equals(opr)
        && !"SetDefaultApps".equals(opr)
        && !"ResetDefaultApps".equals(opr)
        && !"GetDefaultShells".equals(opr)
        && !"SetDefaultShells".equals(opr)
        && !"ResetDefaultShells".equals(opr))
    {
      msg.addError(opr, "Unknown action: " + opr);
      return msg;
    }

    final String prvActId = DomatarConfig.getPrvActId();
    final String ss       = DomId.subHstId("domatar", prvActId);
    final DomId  dst      = new DomId(ss, "domatar", prvActId, "app-catalog");

    msg.addRequestHead(srcDomId, dst, context);
    msg.addClsId("domatar", "catalog");

    if ("SetDefaultApps".equals(opr))
    {
      final ObjAttrs attrs = new ObjAttrs();
      attrs.addAttr("AppIds", getParam(req, "AppIds"));
      msg.addRequestBody("SetDefaultApps", attrs);
    }
    else if ("SetDefaultShells".equals(opr))
    {
      final ObjAttrs attrs = new ObjAttrs();
      final String shells = getParam(req, "Shells");
      if (shells != null && !shells.isEmpty())
        attrs.addAttr("Shells", shells);
      else
      {
        attrs.addAttr("LoginAppId", getParam(req, "LoginAppId"));
        attrs.addAttr("DesktopAppId", getParam(req, "DesktopAppId"));
        attrs.addAttr("NavigatorAppId", getParam(req, "NavigatorAppId"));
      }
      msg.addRequestBody("SetDefaultShells", attrs);
    }
    else
    {
      msg.addRequestBody(opr, null);
    }

    return msg;
  }
}
