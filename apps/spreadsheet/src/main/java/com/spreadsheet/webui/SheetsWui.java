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
 * Routes Sheets operations to the caller's sheets container object.
 *
 * Supported actions: GetSheets, CreateSheet, DeleteSheet.
 * Spec: Spec-Spreadsheet.txt PART 9.1
 *
 * Note: addClsId() is intentionally NOT called so the dispatcher loads the
 * target object from ObjDb, avoiding the obj=null dispatch issue.
 */
@WebServlet("/SheetsWui/*")
public class SheetsWui extends DomatarServlet
{
  private static final long serialVersionUID = 7392018374651920384L;

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
    final DomId sheetsId = new DomId(DomId.subHstId("spreadsheet", actId), "spreadsheet", actId, "sheets");

    final ObjAttrs attrs = new ObjAttrs();

    if ("GetSheets".equals(opr))
    {
      // no additional attrs
    }
    else if ("CreateSheet".equals(opr))
    {
      attrs.addAttr("Name", getParam(req, "Name"));
      attrs.addAttr("Cols", getParam(req, "Cols"));
      attrs.addAttr("Rows", getParam(req, "Rows"));
    }
    else if ("DeleteSheet".equals(opr))
    {
      attrs.addAttr("SheetId", getParam(req, "SheetId"));
    }
    else
    {
      msg.addError(opr, "Unknown action");
      return msg;
    }

    msg.addRequestHead(srcDomId, sheetsId, context);
    msg.addRequestBody(opr, attrs);

    return msg;
  }
}
