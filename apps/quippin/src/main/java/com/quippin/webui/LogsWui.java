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

@WebServlet("/LogsWui")
public class LogsWui extends DomatarServlet
{
  private static final long serialVersionUID = -6585175861641151452L;

  @Override
  protected JsonMsg getMsg(final HttpServletRequest req,
                           final DomId srcDomId,
                           final Context context,
                           final Act srcAct,
                           final DomatarMsgClient msgClient) throws DomatarException
  {
    final String opr = getParam(req, "Action");
    final JsonMsg msg = new JsonMsg();
    final ObjAttrs attrs = new ObjAttrs();

    if ("GetLogs".equals(opr))
    {
    }
    else
    {
      msg.addError(opr, "Unknown action");
      return msg;
    }

    final String actId = srcAct.actId;
    final DomId domId = new DomId(DomId.subHstId("quippin", actId), "quippin", actId, "logs");

    msg.addRequestHead(srcDomId, domId, context);
    msg.addClsId("quippin", "logs");
    msg.addRequestBody(opr, attrs);

    return msg;
  }
}
