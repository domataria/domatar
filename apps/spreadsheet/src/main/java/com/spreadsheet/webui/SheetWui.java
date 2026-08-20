/*
 * Copyright (c) 2024 Domatar
 */

package com.spreadsheet.webui;

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
 * Routes Sheet operations to a specific sheet object.
 *
 * Supported actions: GetSheet, EditSheet, SetCell, ParseSheet.
 * The SheetId (objId of the sheet) must be supplied as a request parameter.
 * Spec: Spec-Spreadsheet.txt PART 9.2
 *
 * Note: addClsId() is intentionally NOT called so the dispatcher loads the
 * target object from ObjDb, avoiding the obj=null dispatch issue.
 */
@WebServlet("/SheetWui/*")
public class SheetWui extends DomatarServlet
{
  private static final long serialVersionUID = 1827364910283746591L;

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

    final String actId   = srcAct.actId;
    final String sheetId = getParam(req, "SheetId");

    if (sheetId == null)
    {
      msg.addError(opr, "Missing SheetId");
      return msg;
    }

    final DomId sheetDomId = new DomId(DomId.subHstId("spreadsheet", actId), "spreadsheet", actId, sheetId);

    final ObjAttrs attrs = new ObjAttrs();

    if ("GetSheet".equals(opr))
    {
      // no attrs needed
    }
    else if ("EditSheet".equals(opr))
    {
      final String name = getParam(req, "Name");
      final String cols = getParam(req, "Cols");
      final String rows = getParam(req, "Rows");
      if (name != null)
        attrs.addAttr("Name", name);
      if (cols != null)
        attrs.addAttr("Cols", cols);
      if (rows != null)
        attrs.addAttr("Rows", rows);
    }
    else if ("SetCell".equals(opr))
    {
      // Use req.getParameter directly for Raw to avoid the double-URL-decode
      // in getParam(): the servlet container already decodes %2B to '+', and a
      // second URLDecoder.decode() would incorrectly convert the literal '+'
      // inside formula expressions (e.g. =$A1+$B1) to a space.
      final String rawParam = req.getParameter("Raw");
      attrs.addAttr("CellRef", getParam(req, "CellRef"));
      attrs.addAttr("Raw",     rawParam != null ? rawParam.trim() : "");
    }
    else if ("ParseSheet".equals(opr))
    {
      // no attrs needed
    }
    else
    {
      msg.addError(opr, "Unknown action");
      return msg;
    }

    msg.addRequestHead(srcDomId, sheetDomId, context);
    msg.addRequestBody(opr, attrs);

    return msg;
  }
}
