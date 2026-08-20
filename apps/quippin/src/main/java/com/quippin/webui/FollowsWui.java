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

@WebServlet("/FollowsWui/*")
public class FollowsWui extends DomatarServlet
{
  private static final long serialVersionUID = -7732071220530088439L;

  @Override
  protected JsonMsg getMsg(final HttpServletRequest req,
                           final DomId srcDomId,
                           final Context context,
                           final Act srcAct,
                           final DomatarMsgClient msgClient) throws DomatarException
  {
    final JsonMsg msg = new JsonMsg();
    final String opr = getParam(req, "Action");

    if (opr == null)
    {
      msg.addError(opr, "Missing action");
      return msg;
    }

    if ("Follow".equals(opr) || "Unfollow".equals(opr) || "IsFollowed".equals(opr))
    {
      final String followActId;
      final String followUsrId;
      final String followUsrName;

      // Unfollow may supply ActId directly (from the follows list) to skip a remote lookup.
      final String directActId = getParam(req, "ActId");

      if ("Unfollow".equals(opr) && directActId != null && !directActId.isEmpty())
      {
        followActId  = directActId;
        followUsrId  = "";
        followUsrName = "";
      }
      else
      {
        followUsrId = getParam(req, "UsrId");

        final Act followAct = LoginRemote.getAct(null, followUsrId, msgClient);

        if (followAct == null)
        {
          msg.addError(opr, "Unknown UsrId");
          return msg;
        }

        followActId  = followAct.actId;
        followUsrName = followAct.usrName;
      }

      final String srcActId = srcAct.actId;
      final ObjAttrs attrs = new ObjAttrs();
      attrs.addAttr("FollowUsrName", followUsrName);
      attrs.addAttr("FollowUsrId",   followUsrId);
      attrs.addAttr("FollowActId",   followActId);

      final DomId domId = new DomId(DomId.subHstId("quippin", srcActId), "quippin", srcActId, "follows");
      msg.addRequestHead(srcDomId, domId, context);
      msg.addRequestBody(opr, attrs);
      msg.addClsId("quippin", "follows");
    }
    else if ("GetFollows".equals(opr))
    {
      final String actId = srcDomId.actId;
      final DomId domId = new DomId(DomId.subHstId("quippin", actId), "quippin", actId, "follows");
      msg.addRequestHead(srcDomId, domId, context);
      msg.addRequestBody(opr, null);
      msg.addClsId("quippin", "follows");
    }
    else
      msg.addError(opr, "Unknown action");

    return msg;
  }
}
