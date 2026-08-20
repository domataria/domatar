/*
 * Copyright (c) 2024 Domatar
 */

package com.desktop.webui;

import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;

import com.domatar.core.Context;
import com.domatar.core.DomatarConfig;
import com.domatar.install.UserSubstrateIds;
import com.domatar.servlet.DomatarServlet;
import com.domatar.util.Act;
import com.domatar.util.Json;
import com.domatar.util.JsonMsg;
import com.domatar.util.ObjAttrs;
import com.domatar.util.DomId;
import com.domatar.util.DomatarException;
import com.domatar.util.DomatarMsgClient;

/**
 * Browser-facing endpoint backing the home-screen page.
 * Spec-Desktop.txt PART 5; Spec-Desktop-Synchronized.txt PART 3;
 * Update-Mandatory-App-Rewrite.txt Phase 2 (GetUserApps dual-read).
 *
 * Desktop-tile actions address dst = the LOCAL Desktop apps container
 * (desktop-&lt;actId&gt;-&lt;prvId&gt;). User-registry actions address the local
 * domatar substrate userApps container.
 *
 * Actions:
 *   GetApps / GetAppsVersion / PullApps / MergeApps / ReconcileApps /
 *   InstallApp / UninstallApp — desktop tiles (legacy).
 *   GetTileOrder / SetTileOrder — Desktop UI launcher layout.
 *   GetUserApps / GetUserAppsVersion / ReconcileUserApps — domatar
 *   userApps registry (preferred launcher source).
 */
@WebServlet("/AppsWui/*")
public class AppsWui extends DomatarServlet
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

    final String actId = srcDomId.actId;
    final String prvId = DomatarConfig.getPrvId();
    final DomId  desktopApps = new DomId(
        DomId.localSubHstId("desktop", actId, prvId),
        "desktop", actId, "apps");

    if ("GetUserApps".equals(opr)
        || "GetUserAppsVersion".equals(opr)
        || "ReconcileUserApps".equals(opr))
    {
      final DomId userApps = UserSubstrateIds.userApps(actId, prvId);

      msg.addRequestHead(srcDomId, userApps, context);
      msg.addRequestBody(opr, null);
      msg.addClsId("domatar", "userApps");
    }
    else if ("GetApps".equals(opr)
        || "GetAppsVersion".equals(opr)
        || "PullApps".equals(opr)
        || "ReconcileApps".equals(opr)
        || "GetTileOrder".equals(opr))
    {
      msg.addRequestHead(srcDomId, desktopApps, context);
      msg.addRequestBody(opr, null);
      msg.addClsId("desktop", "apps");
    }
    else if ("SetTileOrder".equals(opr))
    {
      final ObjAttrs attrs = new ObjAttrs();

      attrs.addAttr("AppIds", getParam(req, "AppIds"));

      msg.addRequestHead(srcDomId, desktopApps, context);
      msg.addRequestBody(opr, attrs);
      msg.addClsId("desktop", "apps");
    }
    else if ("MergeApps".equals(opr))
    {
      final ObjAttrs attrs = new ObjAttrs();
      final String appsJson = getParam(req, "Apps");

      if (appsJson != null && !appsJson.isEmpty())
        attrs.addAttr("Apps", Json.parseList(appsJson));

      msg.addRequestHead(srcDomId, desktopApps, context);
      msg.addRequestBody(opr, attrs);
      msg.addClsId("desktop", "apps");
    }
    else if ("InstallApp".equals(opr))
    {
      final ObjAttrs attrs = new ObjAttrs();

      attrs.addAttr("AppId",       getParam(req, "AppId"));
      attrs.addAttr("DisplayName", getParam(req, "DisplayName"));
      attrs.addAttr("IconPath",    getParam(req, "IconPath"));
      attrs.addAttr("LaunchPath",  getParam(req, "LaunchPath"));
      attrs.addAttr("Position",    getParam(req, "Position"));

      msg.addRequestHead(srcDomId, desktopApps, context);
      msg.addRequestBody(opr, attrs);
      msg.addClsId("desktop", "apps");
    }
    else if ("UninstallApp".equals(opr))
    {
      final ObjAttrs attrs = new ObjAttrs();

      attrs.addAttr("AppId", getParam(req, "AppId"));

      msg.addRequestHead(srcDomId, desktopApps, context);
      msg.addRequestBody(opr, attrs);
      msg.addClsId("desktop", "apps");
    }
    else
      msg.addError(opr, "Unknown action");

    return msg;
  }
}
