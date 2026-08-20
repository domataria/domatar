package com.quippin.webui;

import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;

import com.domatar.core.Context;
import com.domatar.servlet.DomatarServlet;
import com.domatar.util.Act;
import com.domatar.util.JsonMsg;
import com.domatar.util.ObjAttrs;
import com.domatar.util.DomId;
import com.domatar.util.DomatarException;
import com.domatar.util.DomatarMsgClient;

@WebServlet("/BansWui/*")
public class BansWui extends DomatarServlet
{
  /**
   *
   */
  private static final long serialVersionUID = -6372970655773315920L;

  @Override
  protected JsonMsg getMsg(HttpServletRequest req,
                           DomId srcDomId,
                           Context context,
                           Act srcAct,
                           DomatarMsgClient msgClient) throws DomatarException
  {
    String opr = getParam(req, "Action");

    JsonMsg msg = new JsonMsg();

    ObjAttrs attrs = new ObjAttrs();

    if ("Ban".equals(opr) || "Unban".equals(opr))
    {
      String actId = getParam(req, "ActId");

      String ip = getParam(req, "Ip");

      if (actId != null)
        attrs.addAttr("ActId", actId);

      if (ip != null)
        attrs.addAttr("Ip", ip);
    }
    else if ("GetActBans".equals(opr) || "GetIpBans".equals(opr))
    {
    }
    else
    {
      msg.addError(opr, "Unknown action");

      return msg;
    }

    String actId = srcAct.actId;

    DomId domId = new DomId(DomId.subHstId("quippin", actId), "quippin", actId, "bans");

    msg.addRequestHead(srcDomId, domId, context);

    msg.addClsId("quippin", "bans");

    msg.addRequestBody(opr, attrs);

    return msg;
  }
}
