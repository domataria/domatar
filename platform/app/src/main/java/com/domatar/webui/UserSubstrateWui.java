/*
 * Copyright (c) 2024 Domatar
 */

package com.domatar.webui;

import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;

import com.domatar.core.Context;
import com.domatar.core.DomatarConfig;
import com.domatar.install.UserSubstrateIds;
import com.domatar.servlet.DomatarServlet;
import com.domatar.util.Act;
import com.domatar.util.JsonMsg;
import com.domatar.util.ObjAttrs;
import com.domatar.util.DomId;
import com.domatar.util.DomatarException;
import com.domatar.util.DomatarMsgClient;

/**
 * Browser-facing endpoint for the per-user domatar substrate
 * (Update-Mandatory-App-Rewrite.txt Phase 4+).
 *
 * <p>URL: {@code /domatar/domatar/Wui/UserSubstrateWui}
 */
@WebServlet("/UserSubstrateWui/*")
public class UserSubstrateWui extends DomatarServlet
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

    if ("ListMembership".equals(opr)
        || "ReconcileMembership".equals(opr)
        || "GetMembershipVersion".equals(opr)
        || "AddPeer".equals(opr)
        || "TombstonePeer".equals(opr)
        || "UpdatePeer".equals(opr))
    {
      final DomId dst = UserSubstrateIds.membership(actId, prvId);

      msg.addRequestHead(srcDomId, dst, context);
      msg.addClsId("domatar", "membership");
      msg.addRequestBody(opr, membershipAttrs(opr, req));
    }
    else if ("GetUserApps".equals(opr)
        || "GetUserAppsVersion".equals(opr)
        || "ReconcileUserApps".equals(opr))
    {
      final DomId dst = UserSubstrateIds.userApps(actId, prvId);

      msg.addRequestHead(srcDomId, dst, context);
      msg.addClsId("domatar", "userApps");
      msg.addRequestBody(opr, null);
    }
    else if ("GetShells".equals(opr)
        || "SetShell".equals(opr)
        || "ResetShellsToDefaults".equals(opr))
    {
      final DomId dst = UserSubstrateIds.shells(actId, prvId);
      final ObjAttrs attrs = new ObjAttrs();

      if ("GetShells".equals(opr))
      {
        final String loginPrv = getParam(req, "LoginPrvId");

        if (loginPrv != null)
          attrs.addAttr("LoginPrvId", loginPrv);
      }
      else if ("SetShell".equals(opr))
      {
        attrs.addAttr("RoleId", getParam(req, "RoleId"));
        attrs.addAttr("AppId", getParam(req, "AppId"));
        attrs.addAttr("AppHstId", getParam(req, "AppHstId"));
        attrs.addAttr("LaunchPath", getParam(req, "LaunchPath"));
        attrs.addAttr("IconPath", getParam(req, "IconPath"));
      }

      msg.addRequestHead(srcDomId, dst, context);
      msg.addClsId("domatar", "shells");
      msg.addRequestBody(opr, attrs);
    }
    else if ("GetBinding".equals(opr)
        || "SetBinding".equals(opr)
        || "PutBinding".equals(opr))
    {
      final DomId dst = UserSubstrateIds.binding(actId, prvId);
      final String forward = "PutBinding".equals(opr) ? "SetBinding" : opr;

      msg.addRequestHead(srcDomId, dst, context);
      msg.addClsId("domatar", "binding");
      msg.addRequestBody(forward, bindingAttrs(forward, req));
    }
    else
      msg.addError(opr, "Unknown action: " + opr);

    return msg;
  }

  private ObjAttrs membershipAttrs(final String opr, final HttpServletRequest req)
      throws DomatarException
  {
    if ("ListMembership".equals(opr)
        || "ReconcileMembership".equals(opr)
        || "GetMembershipVersion".equals(opr))
      return null;

    final ObjAttrs attrs = new ObjAttrs();

    if ("AddPeer".equals(opr))
    {
      attrs.addAttr("UsrId", getParam(req, "UsrId"));
      attrs.addAttr("UsrName", getParam(req, "UsrName"));
      attrs.addAttr("AppId", getParam(req, "AppId"));
      attrs.addAttr("PrvId", getParam(req, "PrvId"));
      attrs.addAttr("IsRoot", getParam(req, "IsRoot"));
    }
    else if ("TombstonePeer".equals(opr))
      attrs.addAttr("UsrId", getParam(req, "UsrId"));
    else if ("UpdatePeer".equals(opr))
    {
      attrs.addAttr("PrevUsrId", getParam(req, "PrevUsrId"));
      attrs.addAttr("NewUsrId", getParam(req, "NewUsrId"));
      attrs.addAttr("NewUsrName", getParam(req, "NewUsrName"));
      attrs.addAttr("AppId", getParam(req, "AppId"));
    }

    return attrs;
  }

  private ObjAttrs bindingAttrs(final String opr, final HttpServletRequest req)
      throws DomatarException
  {
    if ("GetBinding".equals(opr))
      return null;

    final ObjAttrs attrs = new ObjAttrs();

    attrs.addAttr("ActId", getParam(req, "ActId"));
    attrs.addAttr("GenesisPubKey", getParam(req, "GenesisPubKey"));
    attrs.addAttr("OwnId", getParam(req, "OwnId"));
    attrs.addAttr("OwnPubKey", getParam(req, "OwnPubKey"));
    attrs.addAttr("Version", getParam(req, "Version"));
    attrs.addAttr("NotBefore", getParam(req, "NotBefore"));
    attrs.addAttr("GenesisSig", getParam(req, "GenesisSig"));
    return attrs;
  }
}
