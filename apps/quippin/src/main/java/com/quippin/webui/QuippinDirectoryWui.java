/*
 * Copyright (c) 2024 Domatar
 */

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

/**
 * Browser-facing servlet for the Quippin Directory.
 *
 * Supports Action=GetDirectory, which returns the full list of
 * registered Quippin users for the lookup dialog in follows.html.
 *
 * Spec: Spec-QuippinDirectory.txt PART 3.2
 */
@WebServlet("/QuippinDirectoryWui")
public class QuippinDirectoryWui extends DomatarServlet
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

    if ("GetDirectory".equals(opr))
    {
      DomId dstDomId = new DomId("quippin", "quippin", "quippin@quippin", "directory");

      msg.addRequestHead(srcDomId, dstDomId, context);
      msg.addRequestBody(opr, new ObjAttrs());
      msg.addClsId("quippin", "directory");
    }
    else
    {
      msg.addError(opr, "Unknown action");
    }

    return msg;
  }
}
