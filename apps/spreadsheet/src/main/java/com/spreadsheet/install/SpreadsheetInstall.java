/*
 * Copyright (c) 2024 Domatar
 */

package com.spreadsheet.install;

import com.domatar.db.HstDb;
import com.domatar.db.LnkDb;
import com.domatar.db.ObjDb;
import com.domatar.install.AppInstall;
import com.domatar.install.CatalogInstall;
import com.domatar.install.ClsInstall;
import com.domatar.install.SrvInstall;
import com.domatar.util.Lnk;
import com.domatar.util.DomId;
import com.domatar.util.DomatarException;
import com.domatar.util.DomatarMsgClient;

/**
 * Install routine for the Spreadsheet app (Spec-Spreadsheet.txt PART 5).
 *
 * Creates the per-user spreadsheet~<actId> sub-host and sheets container
 * object, the app-spreadsheet node in the Navigator, and the Navigator
 * skeleton links.
 *
 * Every step is idempotent: a second call is a no-op.
 */
public class SpreadsheetInstall implements AppInstall
{
  @Override
  public void installProvider(final String prvId, final String domain)
      throws DomatarException
  {
    CatalogInstall.registerInCatalog(prvId, "spreadsheet");
  }

  @Override
  public void installUser(final String actId,
                          final String usrId,
                          final String usrName,
                          final String prvId,
                          final String domain,
                          final DomatarMsgClient msgClient) throws DomatarException
  {
    install(actId, usrId, usrName, domain, prvId, msgClient);
  }

  private static void install(final String actId,
                               final String usrId,
                               final String usrName,
                               final String domain,
                               final String prvId,
                               final DomatarMsgClient msgClient) throws DomatarException
  {
    final String ssHstId  = DomId.subHstId("spreadsheet", actId);
    final String navHstId = DomId.subHstId("navigator",   actId, prvId);

    final DomId rootId   = new DomId(navHstId, "navigator",    actId, "root");
    final DomId appSsId  = new DomId(ssHstId,  "spreadsheet",  actId, "app-spreadsheet");
    final DomId sheetsId = new DomId(ssHstId,  "spreadsheet", actId, "sheets");

    if (HstDb.getHst(ssHstId) == null)
      HstDb.addHst(ssHstId, domain, prvId);

    ObjDb.addObjIfMissing(sheetsId, "spreadsheet", "sheets", "Spreadsheets",
                          "Your spreadsheets");

    // New installs create with the correct class; existing installs are
    // migrated by reclassObj so SpreadsheetAppImpl can handle them.
    ObjDb.addObjIfMissing(appSsId, "spreadsheet", "app", "Spreadsheet",
                          "Work with tabular data");
    ObjDb.reclassObj(appSsId, "spreadsheet", "app");

    // seqNum=7: after Bookstore at 6
    addLnkIfMissing(rootId, appSsId,
                    "spreadsheet", "app",
                    "Spreadsheet", "Work with tabular data",
                    "navigator", "app", null, 7);

    addLnkIfMissing(appSsId, sheetsId,
                    "spreadsheet", "sheets",
                    "Spreadsheets", "Your spreadsheets",
                    "navigator", "container", null, 1);

    SrvInstall.ensureSrvsContainer(appSsId,
        "Service descriptors for Spreadsheet", "spreadsheet", 2);
    ClsInstall.ensureClssContainer(appSsId,
        "Class descriptors for Spreadsheet", "spreadsheet", 3);

    // spreadsheet.app — structured LLM-native entry point with natural-language descriptions
    SrvInstall.upsertSrvObj(appSsId, "spreadsheet", "app",
        "Spreadsheet app entry point (LLM-native operations)",
        "{" +
        "\"Description\":\"Spreadsheet app entry point. Use these LLM-native operations to query and update the user's spreadsheets by name. Prefer these over the lower-level SheetsImpl/SheetImpl operations.\"," +
        "\"Msgs\":[" +
        "{\"Name\":\"GetSpreadsheets\",\"Description\":\"List all spreadsheets owned by the current user, with their name, dimensions, and creation date.\",\"SideEffect\":\"Read\"}," +
        "{\"Name\":\"GetSpreadsheet\",\"Description\":\"Get the full contents of a specific spreadsheet by name, including all cell values.\",\"SideEffect\":\"Read\"," +
        "\"Parms\":[{\"Name\":\"Name\",\"Type\":\"String\",\"Description\":\"The name of the spreadsheet to retrieve.\"}]}," +
        "{\"Name\":\"SetCell\",\"Description\":\"Set the value of a cell in a named spreadsheet. Use a formula (=A1+B1) or a plain value.\",\"SideEffect\":\"Write\"," +
        "\"Parms\":[{\"Name\":\"Name\",\"Type\":\"String\",\"Description\":\"The name of the spreadsheet.\"},{\"Name\":\"CellRef\",\"Type\":\"String\",\"Description\":\"The cell reference, e.g. A1 or B12.\"},{\"Name\":\"Raw\",\"Type\":\"String\",\"Description\":\"The value or formula to store.\"}]}," +
        "{\"Name\":\"DeleteSpreadsheet\",\"Description\":\"Permanently delete a spreadsheet by name.\",\"SideEffect\":\"Destructive\"," +
        "\"Parms\":[{\"Name\":\"Name\",\"Type\":\"String\",\"Description\":\"The name of the spreadsheet to delete.\"}]}" +
        "]," +
        "\"Attrs\":\"DisplayName, IconPath, LaunchPath\"" +
        "}");

    ClsInstall.upsertClsImplementing(appSsId, "spreadsheet", "app",
        "Spreadsheet app entry point (LLM-native operations)",
        "{\"ClsAppId\":\"spreadsheet\",\"ClsId\":\"app\"," +
        "\"Implements\":[\"spreadsheet.app\"]," +
        "\"Auth\":\"isVerified\"" +
        "}");

    // spreadsheet.sheets — container
    SrvInstall.addSrvObj(sheetsId, "spreadsheet", "sheets",
        "Container listing all spreadsheets for one user account",
        "{" +
        "\"Description\":\"Container for the user's spreadsheets. Names are case-insensitively unique. Use SpreadsheetAppImpl (on app-spreadsheet) for LLM-native access by name.\"," +
        "\"Msgs\":\"CreateSheet(Name,Cols,Rows) -> {SheetId}; GetSheets() -> {Sheets:[{SheetId,Name,Cols,Rows,CreatedAt}]}; DeleteSheet(SheetId) -> {Deleted}\"," +
        "\"Attrs\":\"(none)\"" +
        "}");

    ClsInstall.upsertClsImplementing(sheetsId, "spreadsheet", "sheets",
        "Container listing all spreadsheets for one user account",
        "{\"ClsAppId\":\"spreadsheet\",\"ClsId\":\"sheets\"," +
        "\"Implements\":[\"spreadsheet.sheets\"]," +
        "\"SideEffect\":\"CreateSheet:Write, GetSheets:None, DeleteSheet:Delete\"," +
        "\"Auth\":\"isVerified\"" +
        "}");

    // spreadsheet.sheet — individual spreadsheet
    SrvInstall.addSrvObj(sheetsId, "spreadsheet", "sheet",
        "A single named spreadsheet with cells",
        "{" +
        "\"Description\":\"A named spreadsheet grid. Cells are child objects identified by CellRef (A1, B12, ...). Raw stores user input; Value stores the last computed result.\"," +
        "\"Msgs\":\"GetSheet() -> {Name,Cols,Rows,ColLabels,Cells:[{CellRef,Raw,Value}]}; EditSheet(Name?,Cols?,Rows?) -> {Updated}; SetCell(CellRef,Raw) -> {CellRef,Value,UpdatedCells}; ParseSheet() -> {Cells}\"," +
        "\"Attrs\":\"Name, Cols, Rows, ColLabels, CreatedAt\"" +
        "}");

    ClsInstall.upsertClsImplementing(sheetsId, "spreadsheet", "sheet",
        "A single named spreadsheet with cells",
        "{\"ClsAppId\":\"spreadsheet\",\"ClsId\":\"sheet\"," +
        "\"Implements\":[\"spreadsheet.sheet\"]," +
        "\"SideEffect\":\"GetSheet:None, EditSheet:Write, SetCell:Write, ParseSheet:Write\"," +
        "\"Auth\":\"isVerified\"" +
        "}");

    // spreadsheet.cell — individual cell
    SrvInstall.addSrvObj(sheetsId, "spreadsheet", "cell",
        "One cell in a spreadsheet",
        "{" +
        "\"Description\":\"One cell in a spreadsheet. Raw is the user input (number, quoted string, or =formula). Value is the last computed result.\"," +
        "\"Msgs\":\"GetObj() -> {SheetId,CellRef,Raw,Value}\"," +
        "\"Attrs\":\"SheetId, CellRef, Raw, Value\"" +
        "}");

    ClsInstall.upsertClsImplementing(sheetsId, "spreadsheet", "cell",
        "One cell in a spreadsheet",
        "{\"ClsAppId\":\"spreadsheet\",\"ClsId\":\"cell\"," +
        "\"Implements\":[\"spreadsheet.cell\"]," +
        "\"SideEffect\":\"GetObj:None\"," +
        "\"Auth\":\"isVerified\"" +
        "}");
  }

  private static void addLnkIfMissing(final DomId domId,
                                      final DomId lnkDomId,
                                      final String lnkClsAppId,
                                      final String lnkClsId,
                                      final String lnkObjName,
                                      final String lnkObjDesc,
                                      final String tagAppId,
                                      final String tag,
                                      final String val,
                                      final long seqNum) throws DomatarException
  {
    if (LnkDb.getLnk(domId, lnkDomId, tagAppId, tag) == null)
      LnkDb.addLnk(new Lnk(domId, lnkDomId,
                            lnkClsAppId, lnkClsId,
                            lnkObjName, lnkObjDesc,
                            tagAppId, tag,
                            val, seqNum));
  }
}
