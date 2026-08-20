package com.quippin.webui;

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

public class SentimentsWui extends DomatarServlet
{

  /**
   *
   */
  private static final long serialVersionUID = -721082134644661207L;

  @Override
  protected JsonMsg getMsg(HttpServletRequest req,
                           DomId srcDomId,
                           Context context,
                           Act srcAct,
                           DomatarMsgClient msgClient) throws DomatarException
  {
    String usrHandle = getParam(req, "Handle");

    String opr = getParam(req, "Action");

    String actId; // fingerprint actId used for routing to the user's quippin host

    JsonMsg msg = new JsonMsg();

    ObjAttrs attrs = new ObjAttrs();

    if ("GetQuips".equals(opr))
    {
      String usrName;

      if (usrHandle == null)
      {
        actId    = srcDomId.actId;
        usrName  = srcAct.usrName;
        usrHandle = DomId.getUsrHandle(srcAct.usrId);
      }
      else
      {
        Act dstAct = LoginRemote.getAct(null, usrHandle, msgClient);

        if (dstAct == null)
        {
          msg.addError(opr, "Unknown act");

          return msg;
        }

        actId    = dstAct.actId;
        usrName  = dstAct.usrName;
        usrHandle = DomId.getUsrHandle(dstAct.usrId);
      }

      attrs.addAttr("UsrName",   usrName);
      attrs.addAttr("UsrHandle", usrHandle);
    }
    else if ("AddQuip".equals(opr))
    {
      actId = srcDomId.actId;

      String text = getParam(req, "Text");
      String parentIdStr = getParam(req, "ParentId");

      attrs.addAttr("Text", text);

      if (parentIdStr != null)
        attrs.addAttr("ParentId", parentIdStr);
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
