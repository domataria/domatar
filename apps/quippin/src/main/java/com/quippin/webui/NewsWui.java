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

@WebServlet("/NewsWui/*")
public class NewsWui extends DomatarServlet
{
  private static final long serialVersionUID = 1750100310076734996L;

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
      msg.addError(opr, "Missing Action");
      return msg;
    }

    final String prvId = getParam(req, "PrvId");

    if (prvId == null)
    {
      msg.addError(opr, "Missing PrvId");
      return msg;
    }

    final String actsStr = getParam(req, "Acts");

    if (actsStr == null)
    {
      msg.addError(opr, "Missing Acts");
      return msg;
    }

    if ("GetNews".equals(opr))
    {
      final ObjAttrs attrs = new ObjAttrs(actsStr);
      final DomId domId = new DomId(prvId, "quippin", "quippin@quippin", "news");
      msg.addRequestHead(srcDomId, domId, context);
      msg.addRequestBody(opr, attrs);
      msg.addClsId("quippin", "news");
    }
    else
      msg.addError(opr, "Unknown action");

    return msg;
  }
}
