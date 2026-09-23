/*
 * Copyright (c) 2024 Domatar
 */

package com.spreadsheet.objimpl;

import java.time.Instant;
import java.util.List;

import com.domatar.core.Auth;
import com.domatar.db.LnkDb;
import com.domatar.db.ObjDb;
import com.domatar.util.IdGen;
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
 * Handler for class (spreadsheet, sheets).
 *
 * The sheets container is a per-user list of spreadsheets.
 * Operations: CreateSheet, GetSheets, DeleteSheet.
 * Spec: Spec-Spreadsheet.txt PART 6.1
 */
public class SheetsImpl extends ObjImpl
{
  @Override
  public String handleMsg(final String msg, final Obj obj, final String contextPath,
                          final String contextRealPath, final DomatarMsgClient msgClient)
      throws DomatarException
  {
    final JsonMsg inMsg  = new JsonMsg(msg);
    final String  opr    = inMsg.getOperation();

    if ("Compensate".equals(opr))
      return super.handleMsg(msg, obj, contextPath, contextRealPath, msgClient);

    final JsonMsg outMsg = new JsonMsg();

    if (!hasRights(inMsg, obj, msgClient))
      return notAuthorized(inMsg);

    final DomId sheetsId = inMsg.getDstId();

    if ("CreateSheet".equals(opr))
      createSheet(opr, inMsg, outMsg, sheetsId);
    else if ("GetSheets".equals(opr))
      getSheets(opr, outMsg, sheetsId);
    else if ("DeleteSheet".equals(opr))
      deleteSheet(opr, inMsg, outMsg, sheetsId);
    else
      return super.handleMsg(msg, obj, contextPath, contextRealPath, msgClient);

    return outMsg.toString();
  }

  @Override
  public boolean hasRights(final JsonMsg inMsg, final Obj obj,
                           final DomatarMsgClient msgClient)
      throws DomatarException
  {
    return Auth.isVerified(msgClient);
  }

  // ---------------------------------------------------------------------------

  private void createSheet(final String opr, final JsonMsg inMsg, final JsonMsg outMsg,
                           final DomId sheetsId)
      throws DomatarException
  {
    final ObjAttrs in    = inMsg.getAttrs();
    final String name    = in.getAttr("Name");
    final String colsStr = in.getAttr("Cols");
    final String rowsStr = in.getAttr("Rows");

    if (name == null || colsStr == null || rowsStr == null)
    {
      outMsg.addError(opr, "Missing Name, Cols, or Rows");
      return;
    }

    int cols, rows;
    try
    {
      cols = Integer.parseInt(colsStr);
      rows = Integer.parseInt(rowsStr);
    }
    catch (NumberFormatException e)
    {
      outMsg.addError(opr, "Cols and Rows must be integers");
      return;
    }

    if (cols < 1 || rows < 1)
    {
      outMsg.addError(opr, "Cols and Rows must be >= 1");
      return;
    }

    if (findSheetByName(sheetsId, name) != null)
    {
      outMsg.addError(opr, "A spreadsheet named '" + name + "' already exists");
      return;
    }

    final String sheetObjId = IdGen.createIdFromCurTime("sheet");
    final DomId  sheetId    = new DomId(sheetsId.hstId, "spreadsheet", sheetsId.actId, sheetObjId);

    final ObjAttrs attrs = new ObjAttrs();
    attrs.addAttr("Name",      name);
    attrs.addAttr("Cols",      String.valueOf(cols));
    attrs.addAttr("Rows",      String.valueOf(rows));
    attrs.addAttr("ColLabels", buildColLabels(cols));
    attrs.addAttr("CreatedAt", Instant.now().toString());

    ObjDb.addObj(new Obj(sheetId, "spreadsheet", "sheet", name, "Spreadsheet", attrs));

    LnkDb.addLnk(new Lnk(sheetsId, sheetId,
                          "spreadsheet", "sheet",
                          name, "Spreadsheet",
                          "spreadsheet", "sheet",
                          null, System.currentTimeMillis()));

    final ObjAttrs out = new ObjAttrs();
    out.addAttr("SheetId", sheetObjId);
    outMsg.addResponseBody(opr, out);
  }

  private void getSheets(final String opr, final JsonMsg outMsg, final DomId sheetsId)
      throws DomatarException
  {
    final List<Lnk> lnks = LnkDb.getLnks(sheetsId, "spreadsheet", "sheet",
                                          null, null, 1000, true);

    final JsonList sheets = new JsonArrayList(lnks.size());

    for (final Lnk lnk : lnks)
    {
      final Obj sheet = ObjDb.getObj(lnk.lnkDomId);
      if (sheet == null)
        continue;

      final ObjAttrs entry = new ObjAttrs();
      entry.addAttr("SheetId",   lnk.lnkDomId.objId);
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

  private void deleteSheet(final String opr, final JsonMsg inMsg, final JsonMsg outMsg,
                           final DomId sheetsId)
      throws DomatarException
  {
    final String sheetObjId = inMsg.getAttrs().getAttr("SheetId");

    if (sheetObjId == null)
    {
      outMsg.addError(opr, "Missing SheetId");
      return;
    }

    final DomId sheetId = new DomId(sheetsId.hstId, "spreadsheet", sheetsId.actId, sheetObjId);

    final Obj sheet = ObjDb.getObj(sheetId);
    if (sheet == null)
    {
      outMsg.addError(opr, "Sheet not found");
      return;
    }

    deleteSheetById(sheetsId, sheetId);

    final ObjAttrs out = new ObjAttrs();
    out.addAttr("Deleted", "True");
    outMsg.addResponseBody(opr, out);
  }

  // ---------------------------------------------------------------------------
  // Package-private helpers (also used by SpreadsheetAppImpl)
  // ---------------------------------------------------------------------------

  /**
   * Returns the DomId of the sheet whose Name attribute matches {@code name}
   * (case-insensitive), or {@code null} if no match is found.
   */
  static DomId findSheetByName(final DomId sheetsId, final String name)
      throws DomatarException
  {
    if (name == null)
      return null;

    final List<Lnk> lnks = LnkDb.getLnks(sheetsId, "spreadsheet", "sheet",
                                          null, null, 1000, false);
    for (final Lnk lnk : lnks)
    {
      final Obj sheet = ObjDb.getObj(lnk.lnkDomId);
      if (sheet == null)
        continue;
      final String n = sheet.attrs.getAttr("Name");
      if (name.equalsIgnoreCase(n))
        return lnk.lnkDomId;
    }
    return null;
  }

  /**
   * Core delete logic extracted for reuse by SpreadsheetAppImpl.
   * Deletes the sheet, all its cells, and the sheets→sheet link.
   */
  static void deleteSheetById(final DomId sheetsId, final DomId sheetId)
      throws DomatarException
  {
    final List<Lnk> cellLnks = LnkDb.getLnks(sheetId, "spreadsheet", "cell",
                                              null, null, 100000, false);
    for (final Lnk lnk : cellLnks)
      ObjDb.deleteObj(lnk.lnkDomId);

    LnkDb.deleteLnks(sheetId, null, null, null, null, null);
    LnkDb.deleteLnks(sheetsId, sheetId, "spreadsheet", "sheet", null, null);
    ObjDb.deleteObj(sheetId);
  }

  // ---------------------------------------------------------------------------
  // Utilities
  // ---------------------------------------------------------------------------

  /**
   * Build a comma-separated column-label string for the given column count.
   * 1->A, 2->A,B, 26->A,…,Z, 27->A,…,Z,AA, …
   */
  static String buildColLabels(final int cols)
  {
    final StringBuilder sb = new StringBuilder();
    for (int i = 1; i <= cols; i++)
    {
      if (i > 1)
        sb.append(',');
      sb.append(colLabel(i));
    }
    return sb.toString();
  }

  /** Convert a 1-based column index to a spreadsheet label (A, B, … Z, AA, …). */
  static String colLabel(int col)
  {
    final StringBuilder sb = new StringBuilder();
    while (col > 0)
    {
      col--;
      sb.insert(0, (char) ('A' + (col % 26)));
      col /= 26;
    }
    return sb.toString();
  }
}
