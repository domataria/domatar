/*
 * Copyright (c) 2024 Domatar
 */

package com.spreadsheet.objimpl;

import java.util.List;

import com.domatar.core.Auth;
import com.domatar.db.LnkDb;
import com.domatar.db.ObjDb;
import com.domatar.util.JsonArrayList;
import com.domatar.util.JsonList;
import com.domatar.util.JsonMsg;
import com.domatar.util.Lnk;
import com.domatar.util.Obj;
import com.domatar.util.ObjAttrs;
import com.domatar.util.ObjImpl;
import com.domatar.util.DomId;
import com.domatar.util.DomatarException;
import com.domatar.util.DomatarMsgClient;

/**
 * LLM-native facade for the Spreadsheet app.
 *
 * Handles class (spreadsheet, app) — the app-spreadsheet entry-point object.
 * All operations accept human-readable spreadsheet names so the AI Agent
 * never needs to navigate to a SheetId before acting.
 *
 * Operations: GetSpreadsheets, GetSpreadsheet(Name),
 *             SetCell(Name, CellRef, Raw), DeleteSpreadsheet(Name).
 *
 * Delegates to SheetsImpl and SheetImpl package-private helpers so the
 * data layer is shared and consistent.
 *
 * Spec: Spec-Spreadsheet.txt PART 6.0
 */
public class SpreadsheetAppImpl extends ObjImpl
{
  @Override
  public String handleMsg(final String msg, final Obj obj, final String contextPath,
                          final String contextRealPath, final DomatarMsgClient msgClient)
      throws DomatarException
  {
    final JsonMsg inMsg  = new JsonMsg(msg);
    final String  opr    = inMsg.getOperation();
    final JsonMsg outMsg = new JsonMsg();

    if (!hasRights(inMsg, obj, msgClient))
      return notAuthorized(inMsg);

    final DomId appDomId = inMsg.getDstId();

    if ("GetSpreadsheets".equals(opr))
      getSpreadsheets(opr, outMsg, appDomId);
    else if ("GetSpreadsheet".equals(opr))
      getSpreadsheet(opr, inMsg, outMsg, appDomId, msgClient);
    else if ("SetCell".equals(opr))
      setCell(opr, inMsg, outMsg, appDomId, msgClient);
    else if ("DeleteSpreadsheet".equals(opr))
      deleteSpreadsheet(opr, inMsg, outMsg, appDomId);
    else
      return super.handleMsg(msg, obj, contextPath, contextRealPath, msgClient);

    return outMsg.toString();
  }

  @Override
  public boolean hasRights(final JsonMsg inMsg, final Obj obj,
                           final DomatarMsgClient msgClient)
      throws DomatarException
  {
    return Auth.isVerified(inMsg);
  }

  // ---------------------------------------------------------------------------

  private void getSpreadsheets(final String opr, final JsonMsg outMsg,
                                final DomId appDomId)
      throws DomatarException
  {
    final DomId sheetsId = sheetsIdFor(appDomId);
    final List<Lnk> lnks = LnkDb.getLnks(sheetsId, "spreadsheet", "sheet",
                                          null, null, 1000, true);

    final JsonList sheets = new JsonArrayList(lnks.size());

    for (final Lnk lnk : lnks)
    {
      final Obj sheet = ObjDb.getObj(lnk.lnkDomId);
      if (sheet == null)
        continue;

      final ObjAttrs entry = new ObjAttrs();
      entry.addAttr("Name",      sheet.attrs.getAttr("Name"));
      entry.addAttr("Cols",      sheet.attrs.getAttr("Cols"));
      entry.addAttr("Rows",      sheet.attrs.getAttr("Rows"));
      entry.addAttr("CreatedAt", sheet.attrs.getAttr("CreatedAt"));
      sheets.add(entry.toMap());
    }

    final ObjAttrs out = new ObjAttrs();
    out.addAttr("Sheets", sheets);
    outMsg.addResponseBody(opr, out);
  }

  private void getSpreadsheet(final String opr, final JsonMsg inMsg, final JsonMsg outMsg,
                               final DomId appDomId, final DomatarMsgClient msgClient)
      throws DomatarException
  {
    final String name = inMsg.getAttrs().getAttr("Name");
    if (name == null)
    {
      outMsg.addError(opr, "Missing Name");
      return;
    }

    final DomId sheetsId = sheetsIdFor(appDomId);
    final DomId sheetId  = SheetsImpl.findSheetByName(sheetsId, name);
    if (sheetId == null)
    {
      outMsg.addError(opr, "No spreadsheet named '" + name + "'");
      return;
    }

    final Obj sheet = ObjDb.getObj(sheetId);
    if (sheet == null)
    {
      outMsg.addError(opr, "No spreadsheet named '" + name + "'");
      return;
    }

    final SheetImpl helper = new SheetImpl();
    final JsonList cells = helper.recalcAndPersist(sheetId, msgClient);

    final ObjAttrs out = new ObjAttrs();
    out.addAttr("Name",      sheet.attrs.getAttr("Name"));
    out.addAttr("Cols",      sheet.attrs.getAttr("Cols"));
    out.addAttr("Rows",      sheet.attrs.getAttr("Rows"));
    out.addAttr("ColLabels", sheet.attrs.getAttr("ColLabels"));
    out.addAttr("Cells",     cells);
    outMsg.addResponseBody(opr, out);
  }

  private void setCell(final String opr, final JsonMsg inMsg, final JsonMsg outMsg,
                       final DomId appDomId, final DomatarMsgClient msgClient)
      throws DomatarException
  {
    final ObjAttrs in    = inMsg.getAttrs();
    final String name    = in.getAttr("Name");
    final String cellRef = in.getAttr("CellRef");
    String raw           = in.getAttr("Raw");
    final String bold    = in.getAttr("Bold");

    if (name == null)
    {
      outMsg.addError(opr, "Missing Name");
      return;
    }
    if (cellRef == null)
    {
      outMsg.addError(opr, "Missing CellRef");
      return;
    }
    if (raw == null && bold == null)
      raw = "";

    final DomId sheetsId = sheetsIdFor(appDomId);
    final DomId sheetId  = SheetsImpl.findSheetByName(sheetsId, name);
    if (sheetId == null)
    {
      outMsg.addError(opr, "No spreadsheet named '" + name + "'");
      return;
    }

    final SheetImpl helper = new SheetImpl();
    final ObjAttrs result  = helper.setCellCore(sheetId, cellRef, raw, bold, msgClient);

    if (result.getAttr("Error") != null)
    {
      outMsg.addError(opr, result.getAttr("Error"));
      return;
    }

    final ObjAttrs out = new ObjAttrs();
    out.addAttr("CellRef", result.getAttr("CellRef"));
    out.addAttr("Value",   result.getAttr("Value"));
    out.addAttr("Bold",    result.getAttr("Bold"));
    outMsg.addResponseBody(opr, out);
  }

  private void deleteSpreadsheet(final String opr, final JsonMsg inMsg,
                                  final JsonMsg outMsg, final DomId appDomId)
      throws DomatarException
  {
    final String name = inMsg.getAttrs().getAttr("Name");
    if (name == null)
    {
      outMsg.addError(opr, "Missing Name");
      return;
    }

    final DomId sheetsId = sheetsIdFor(appDomId);
    final DomId sheetId  = SheetsImpl.findSheetByName(sheetsId, name);
    if (sheetId == null)
    {
      outMsg.addError(opr, "No spreadsheet named '" + name + "'");
      return;
    }

    SheetsImpl.deleteSheetById(sheetsId, sheetId);

    final ObjAttrs out = new ObjAttrs();
    out.addAttr("Deleted", "True");
    outMsg.addResponseBody(opr, out);
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  /**
   * Derives the sheets container DomId from the app-spreadsheet object's DomId.
   * Both live on the same host; only the ObjId differs.
   */
  private static DomId sheetsIdFor(final DomId appDomId) throws DomatarException
  {
    return new DomId(appDomId.hstId, "spreadsheet", appDomId.actId, "sheets");
  }
}
