/*
 * Copyright (c) 2024 Domatar
 */

package com.navigator.webui;

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
 * Browser-facing endpoint backing the Navigator page.
 * Spec-Navigator.txt PART 7; Spec-Desktop-Synchronized.txt PART 3.1.
 *
 * Single action: GetLnks(DomId) - dispatches to ObjImpl.openObj on the
 * target object, which returns the object's own metadata plus all of
 * its outgoing lnks.  The lnk list is what navigator.html uses to
 * populate tree children.
 *
 * Default dst is the LOCAL provider-qualified navigator root
 * (navigator-&lt;actId&gt;-&lt;prvId&gt;, with bare-host fallback until Phase 4).
 *
 * Credential checking is handled upstream by DomatarServlet's outer
 * dispatch; NavWui never touches credentials directly.
 */
@WebServlet("/NavWui/*")
public class NavWui extends DomatarServlet
{
  private static final long serialVersionUID = 1L;

  @Override
  protected JsonMsg getMsg(HttpServletRequest req,
                           DomId srcDomId,
                           Context context,
                           Act srcAct,
                           DomatarMsgClient msgClient) throws DomatarException
  {
    JsonMsg msg = new JsonMsg();

    String opr = getParam(req, "Action");

    if (opr == null)
    {
      msg.addError(opr, "Missing action");
      return msg;
    }

    String actId = srcDomId.actId;

    if ("GetLnks".equals(opr))
    {
      String domIdStr = getParam(req, "DomId");
      String maxLnks  = getParam(req, "MaxLnks");

      DomId dstDomId;

      if (domIdStr == null || domIdStr.isEmpty())
        dstDomId = new DomId(
            DomId.localSubHstId("navigator", actId, DomatarConfig.getPrvId()),
            "navigator", actId, "root");
      else
        dstDomId = new DomId(domIdStr);

      ObjAttrs attrs = null;

      if (maxLnks != null)
      {
        attrs = new ObjAttrs();
        attrs.addAttr("MaxLnks", maxLnks);
      }

      msg.addRequestHead(srcDomId, dstDomId, context);
      msg.addRequestBody(opr, attrs);
    }
    else
      msg.addError(opr, "Unknown action");

    return msg;
  }
}
