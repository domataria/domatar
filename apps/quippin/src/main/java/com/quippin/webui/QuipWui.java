package com.quippin.webui;

import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;

import com.domatar.core.Context;
import com.domatar.core.LoginRemote;
import com.domatar.servlet.DomatarServlet;
import com.domatar.util.Act;
import com.domatar.util.IdGen;
import com.domatar.util.JsonMsg;
import com.domatar.util.ObjAttrs;
import com.domatar.util.DomId;
import com.domatar.util.DomatarException;
import com.domatar.util.DomatarMsgClient;

/**
 * Servlet implementation class Quips
 */
@WebServlet("/QuipWui/*")
public class QuipWui extends DomatarServlet
{
  private static final long serialVersionUID = -1682539771435139004L;

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

    if ("GetQuip".equals(opr))
    {
      String usrId = getParam(req, "UsrId");

      Act act = LoginRemote.getAct(null, usrId, msgClient);

      if (act == null)
      {
        msg.addError(opr, "Unknown UsrId");

        return msg;
      }

      String quipId = getParam(req, "Quip");

      if (quipId == null)
      {
        msg.addError(opr, "Missing quipId");

        return msg;
      }

      String objId = IdGen.getIdFromBase10("quip", quipId);

      ObjAttrs attrs = new ObjAttrs();

      attrs.addAttr("UsrName", act.usrName);
      attrs.addAttr("UsrId", usrId);
      attrs.addAttr("QuipId", objId);

      DomId domId = new DomId(DomId.subHstId("quippin", act.actId), "quippin", act.actId, objId);

      msg.addRequestHead(srcDomId, domId, context);
      msg.addRequestBody(opr, attrs);
    }
    else if ("GetQuipChildren".equals(opr))
    {
      String quipIdStr = getParam(req, "QuipId");

      if (quipIdStr == null)
      {
        msg.addError(opr, "Missing quipId");

        return msg;
      }

      DomId dstDomId = new DomId(quipIdStr);

      msg.addRequestHead(srcDomId, dstDomId, context);
      msg.addRequestBody(opr, null);
    }
    else if ("RemoveQuipChild".equals(opr))
    {
      String parentQuipIdStr = getParam(req, "ParentQuipId");

      if (parentQuipIdStr == null)
      {
        msg.addError(opr, "Missing ParentQuipId");

        return msg;
      }

      String childQuipIdStr = getParam(req, "ChildQuipId");

      if (childQuipIdStr == null)
      {
        msg.addError(opr, "Missing ChildQuipId");

        return msg;
      }

      DomId dstDomId = new DomId(parentQuipIdStr);

      ObjAttrs attrs = new ObjAttrs();

      attrs.addAttr("ChildQuipId", childQuipIdStr);

      msg.addRequestHead(srcDomId, dstDomId, context);
      msg.addRequestBody(opr, attrs);
    }
    else if ("AddSentiment".equals(opr))
    {
      String quipIdStr = getParam(req, "QuipId");

      if (quipIdStr == null)
      {
        msg.addError(opr, "Missing quipId");

        return msg;
      }

      String sentiment = getParam(req, "Sentiment");

      if (sentiment == null)
      {
        msg.addError(opr, "Missing sentiment");

        return msg;
      }

      DomId dstDomId = new DomId(quipIdStr);

      ObjAttrs attrs = new ObjAttrs();

      attrs.addAttr("Sentiment", sentiment);

      msg.addRequestHead(srcDomId, dstDomId, context);
      msg.addRequestBody(opr, attrs);
    }
    else if ("DeleteQuip".equals(opr))
    {
      String quipIdStr = getParam(req, "QuipId");

      if (quipIdStr == null)
      {
        msg.addError(opr, "Missing QuipId");

        return msg;
      }

      DomId dstDomId = new DomId(quipIdStr);

      msg.addRequestHead(srcDomId, dstDomId, context);
      msg.addRequestBody(opr, null);
    }
    else if ("BanChild".equals(opr))
    {
      String parentQuipIdStr = getParam(req, "ParentQuipId");

      if (parentQuipIdStr == null)
      {
        msg.addError(opr, "Missing ParentQuipId");

        return msg;
      }

      String childQuipIdStr = getParam(req, "ChildQuipId");

      if (childQuipIdStr == null)
      {
        msg.addError(opr, "Missing ChildQuipId");

        return msg;
      }

      String banIp = getParam(req, "BanIp");

      DomId dstDomId = new DomId(parentQuipIdStr);

      ObjAttrs attrs = new ObjAttrs();

      attrs.addAttr("ChildQuipId", childQuipIdStr);

      if (banIp != null)
        attrs.addAttr("BanIp", banIp);

      msg.addRequestHead(srcDomId, dstDomId, context);
      msg.addRequestBody(opr, attrs);
    }
    else
      msg.addError(opr, "Unknown action");

    return msg;
  }
}
