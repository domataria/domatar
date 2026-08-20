package com.quippin.webui;

import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;

import com.domatar.core.Context;
import com.domatar.core.LoginRemote;
import com.domatar.servlet.DomatarServlet;
import com.domatar.util.Act;
import com.domatar.util.JsonMsg;
import com.domatar.util.ObjAttrs;
import com.domatar.util.DomId;
import com.domatar.util.DomatarException;
import com.domatar.util.DomatarMsgClient;

/**
 * Servlet implementation class Quips
 */
@WebServlet("/QuipsWui/*")
public class QuipsWui extends DomatarServlet
{
  private static final long serialVersionUID = -444415578422179582L;

  @Override
  protected JsonMsg getMsg(HttpServletRequest req,
                           DomId srcDomId,
                           Context context,
                           Act srcAct,
                           DomatarMsgClient msgClient) throws DomatarException
  {
    String usrId = getParam(req, "UsrId");

    String opr = getParam(req, "Action");

    String actId;

    JsonMsg msg = new JsonMsg();

    ObjAttrs attrs = new ObjAttrs();

    // if no usrHandle, get actHandle from current actId
    // else get actHandle from usrHandle passed in

    if ("GetQuips".equals(opr))
    {
      String usrName;

      if (usrId == null)
      {
        actId = srcDomId.actId;
        usrName = srcAct.usrName;
        usrId = srcAct.usrId;
      }
      else
      {
        Act dstAct = LoginRemote.getAct(null, usrId, msgClient);

        if (dstAct == null)
        {
          msg.addError(opr, "Unknown act");

          return msg;
        }

        actId = dstAct.actId;
        usrName = dstAct.usrName;
      }

      attrs.addAttr("UsrName", usrName);
      attrs.addAttr("UsrId", usrId);
    }
    else if ("AddQuip".equals(opr))
    {
      actId = srcDomId.actId;

      String text = getParam(req, "Text");
      String reQuipId = getParam(req, "ReQuipId");
      String parentIdStr = getParam(req, "ParentId");

      if (text != null)
        attrs.addAttr("Text", text);

      if (reQuipId != null)
        attrs.addAttr("ReQuipId", reQuipId);

      if (parentIdStr != null)
        attrs.addAttr("ParentId", parentIdStr);
    }
    else if ("BanUser".equals(opr))
    {
      String quipIdStr = getParam(req, "QuipId");

      if (quipIdStr == null)
      {
        msg.addError(opr, "Missing QuipId");

        return msg;
      }

      // Derive the actId to ban from the quip's DomId.
      DomId quipDomId = new DomId(quipIdStr);

      actId = srcDomId.actId;

      attrs.addAttr("ActId", quipDomId.actId);

      DomId domId = new DomId(DomId.subHstId("quippin", actId), "quippin", actId, "quips");

      msg.addRequestHead(srcDomId, domId, context);
      msg.addClsId("quippin", "quips");
      msg.addRequestBody(opr, attrs);
    }
    else
    {
      msg.addError(opr, "Unknown action");

      return msg;
    }

    DomId domId = new DomId(DomId.subHstId("quippin", actId), "quippin", actId, "quips");

    msg.addRequestHead(srcDomId, domId, context);

    msg.addClsId("quippin", "quips");

    msg.addRequestBody(opr, attrs);

    return msg;
  }
}
