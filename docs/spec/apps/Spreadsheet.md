# SPREADSHEET APP — SPECIFICATION

A personal spreadsheet application built on the Domatar platform.  Users
create named, grid-based spreadsheets whose cells hold literal values or
formulas.  Formulas may reference other cells in the same sheet, or reach
across the entire Domatar object graph by citing any object's attribute via
its DomId.

The Spreadsheet app runs on prv2 (tomcat2 / db2).  Each user's data lives
entirely on their own provider sub-host; there is no shared central catalog.

## PART 1 — OVERVIEW

1.1  Goals
----------
  * A user can create any number of named spreadsheets.
  * A spreadsheet has a configurable number of columns (A, B, C, …) and rows
    (1, 2, 3, …).  Both the name and dimensions can be changed at any time.
  * Each cell is a first-class Domatar object.  Its ObjName is the column-row
    label (A1, B3, Z12, …).
  * A cell stores either a raw value (number or quoted string) or an
    = formula.  Formulas may:
      - Perform arithmetic  (+  −  *  /)
      - Reference other cells in the same sheet  ($A1, $C5)
      - Read an attribute from any Domatar object by DomId
        ([hstId.appId.actId.objId]attrName)
  * When a sheet is viewed, all formulas are evaluated server-side and their
    computed values are shown in the grid.
  * A "Parse" button forces re-evaluation (useful when referenced objects
    have changed).

1.2  LLM-native operations layer
---------------------------------
  In addition to the core object graph (sheets container → individual sheet
  objects → cell objects), the Spreadsheet app exposes a set of LLM-native
  operations on the app-spreadsheet entry-point object.  These operations are
  designed for consumption by AI Agent tool calls:

  * Parameters are human-readable names, not opaque object IDs.
  * A single call returns all the data an LLM needs to answer a question
    (e.g. GetSpreadsheet(Name) returns the full sheet in one hop).
  * Write operations identify the target spreadsheet by name, so the LLM
    never needs to carry an opaque SheetId across call boundaries.

  The handler is SpreadsheetAppImpl (class (spreadsheet, app)).  It delegates
  to the same underlying DB helpers as SheetsImpl and SheetImpl, so the data
  layer is shared and consistent.

  The generic object graph (SheetsImpl, SheetImpl) is retained unchanged for
  the Navigator UI, inter-app calls, and the WUI servlets.  The LLM-native
  layer is an additional facade, not a replacement.

  Name uniqueness (see PART 3.4) is a prerequisite: when the LLM passes a
  name, the server must be able to resolve it to at most one spreadsheet.

1.3  Non-goals (deferred)
--------------------------
  * Cross-sheet cell references (future: $Sheet2!A1 syntax).
  * Built-in aggregate functions (SUM, AVERAGE, IF, …).
  * Cell formatting (bold, colour, number format).
  * Collaborative real-time editing.
  * Charts or pivot tables.
  * Export to CSV / XLSX.
  * Access control beyond "owner only" (the owner's own prv serves all data).

## PART 2 — PROVIDER AND HOSTING

2.1  Central host (placeholder — no global data in v1)
-------------------------------------------------------
  hstId  : spreadsheet
  prvId  : prv2
  domain : domatar.avatarvia.com
  address: tomcat2:8080

  The central host holds only the system account.  User data lives on
  per-user sub-hosts (see 2.2).

  tomcat2 must expose the alias "spreadsheet" on domatar_net:

    tomcat2:
      networks:
        domatar_net:
          aliases:
            - tomcat2
            - bookstore       (already present)
            - spreadsheet     ← add this

2.2  Per-user sub-hosts
-----------------------
  Each user who installs Spreadsheet gets a personal sub-host:

    hstId : spreadsheet-<actId>
    prvId : <the prv the user is on when they install>
    domain: same as the user's prv (e.g. domatar.quippin.com for prv1)

  The sub-host is created by SpreadsheetInstall (see PART 5).

2.3  Nginx routing
------------------
  domatar.avatarvia.com already routes to tomcat2:8080 (added for Bookstore).
  No additional Nginx block is required.

2.4  System account
-------------------
  actId  : spreadsheet@spreadsheet
  usrId  : spreadsheet@spreadsheet
  usrName: Spreadsheet
  password: "123"  (SHA-1 hash: GBp05MC8NxDHPJAUdVySNhkRkjx — same as others)

## PART 3 — DATA MODEL

3.1  Object classes
-------------------

  (spreadsheet, sheets)
    One per user.  Container for all of the user's spreadsheets.
    ObjId : sheets
    HstId : spreadsheet-<actId>
    Attrs : {}

  (spreadsheet, sheet)
    One per spreadsheet.  Also acts as the link-source for its cells.
    ObjId : sheet-<base64Timestamp>   IdGen.createId("sheet", timestamp)
    HstId : spreadsheet-<actId>
    Attrs :
      Name    : string  (user-supplied label, e.g. "Budget 2026")
      Cols    : string  (integer ≥ 1, number of columns, e.g. "5")
      Rows    : string  (integer ≥ 1, number of rows, e.g. "20")
      ColLabels: string  (comma-separated, e.g. "A,B,C,D,E" — derived from Cols,
                         stored for quick retrieval)
      CreatedAt: string  (ISO-8601 timestamp)

  (spreadsheet, cell)
    One per non-empty cell in a sheet.  Created lazily on first write.
    ObjId : <sheetObjId>-<cellRef>
            e.g. "sheet-0Pt4QeS_-A1", "sheet-0Pt4QeS_-B12"
    HstId : spreadsheet-<actId>
    Attrs :
      SheetId : string  (ObjId of the parent sheet, e.g. "sheet-0Pt4QeS_")
      CellRef : string  (column+row label, e.g. "A1")
      Raw     : string  (the user's raw input: "42", '"hello"', "=$A1+$B1",
                         "[domId]attr", or "" for empty)
      Value   : string  (last computed value; updated on parse; empty if Raw
                         contains no formula)

    Cells are stored only when they carry a value or formula (Raw ≠ "").
    An absent cell is treated as the empty string "" in formulas.

3.2  Link types
---------------

  sheets  →  sheet
    Tag   : (spreadsheet, sheet)
    Val   : ""
    SeqNum: <millisecond timestamp at creation time>
    (ordered newest-first when SeqNum desc)

  sheet   →  cell
    Tag   : (spreadsheet, cell)
    Val   : <cellRef>   e.g. "A1"
    SeqNum: 0
    (unordered; the grid is reconstructed from CellRef attrs, not SeqNum)

  Navigator links — see PART 4.

3.4  Name uniqueness
---------------------
  Spreadsheet ObjNames (the Name attribute and ObjName of a sheet object) are
  case-insensitively unique per user within the sheets container.

  Enforcement is at the application level, not the database level:

    * SheetsImpl.CreateSheet: before creating a new sheet, checks whether any
      existing link from the sheets container has a matching ObjName
      (case-insensitive).  If so, returns error:
        "A spreadsheet named '<Name>' already exists"

    * SheetImpl.EditSheet: when the Name parameter is supplied, checks whether
      any OTHER sheet in the same container already has the new name
      (case-insensitive).  If so, returns the same error.

  The shared helper SheetsImpl.findSheetByName(DomId sheetsId, String name)
  (package-private) performs this lookup and is also used by SpreadsheetAppImpl
  to resolve names passed to GetSpreadsheet, SetCell, and DeleteSpreadsheet.

3.5  Column labelling
---------------------
  Columns 1..26 → A..Z.
  Columns 27..702 → AA, AB, … AZ, BA, … ZZ  (base-26 extension; v1 may
  limit the UI to 26 columns but the data model is not restricted).

  ColLabels in the sheet Attrs stores the pre-computed label string so the
  server and browser do not need to re-derive it on every render.

  Row labels are the decimal integers 1, 2, 3, …, Rows.

## PART 4 — NAVIGATOR INTEGRATION

SpreadsheetInstall adds the following nodes to the user's Navigator tree:

  navigator-<actId> / navigator / <actId> / root
    └─ [navigator/app] app-spreadsheet                  seqNum=7
         └─ [navigator/container] spreadsheet-<actId>/sheets  seqNum=1

seqNum=7 on root→app-spreadsheet means Spreadsheet appears after
Quippin(1)/Login(2)/Desktop(3)/Navigator(4)/AiAgent(5)/Bookstore(6) in the
Navigator root.

The Desktop tile seeded by AppsImpl.seedDefaultCatalog already has the
Spreadsheet placeholder entry at Position=6 with LaunchPath="#".  When
SpreadsheetInstall is implemented, update that LaunchPath to
"/domatar/spreadsheet.html".

## PART 5 — INSTALL ROUTINE  (SpreadsheetInstall.java)

Package: com.spreadsheet.install

Static method:
  public static void install(String actId,
                             String usrId,
                             String usrName,
                             String domain,
                             String prvId,
                             DomatarMsgClient msgClient)

Steps (all idempotent via addObjIfMissing / addLnkIfMissing / upsertClsObj):

  1. Register sub-host:
       HstDb.addHstIfMissing("spreadsheet-" + actId, prvId, domain)

  2. Create container object on spreadsheet-<actId>:
       (spreadsheet, sheets)  ObjId=sheets  "Spreadsheets"  "Your spreadsheets"

  3. Create app-spreadsheet object on spreadsheet-<actId>:
       (spreadsheet, app)  ObjId=app-spreadsheet  "Spreadsheet"  "Work with tabular data"
       Attrs: { DisplayName:"Spreadsheet",
                IconPath:"/domatar/icons/spreadsheet.svg",
                LaunchPath:"/domatar/spreadsheet.html" }

  4. Add Navigator root → app-spreadsheet link  (navigator/app, seqNum=7)

  5. Add app-spreadsheet → sheets link  (navigator/container, seqNum=1)

  6. Upsert class descriptors (ClsInstall.upsertClsObj) so AI agents always
     see up-to-date documentation even after code updates.  Upsert four
     descriptors:

     a. (spreadsheet, app)  — the entry-point class
          .Description  = "Spreadsheet app. Entry point for LLM-native
                           operations on the user's spreadsheets."
          .Msgs         = "GetSpreadsheets, GetSpreadsheet(Name),
                           SetCell(Name,CellRef,Raw),
                           DeleteSpreadsheet(Name)"
          .Attrs        = "DisplayName, IconPath, LaunchPath"
          .SideEffect   = "GetSpreadsheets:None, GetSpreadsheet:None,
                           SetCell:Write, DeleteSpreadsheet:Delete"
          .Auth         = "isVerified"

     b. (spreadsheet, sheets)  — the container class
          .Description  = "Container for all of the user's spreadsheets."
          .Msgs         = "CreateSheet(Name,Cols,Rows),
                           GetSheets, DeleteSheet(SheetId)"
          .Attrs        = "(none)"
          .SideEffect   = "CreateSheet:Write, GetSheets:None,
                           DeleteSheet:Delete"
          .Auth         = "isVerified"

     c. (spreadsheet, sheet)  — individual spreadsheet class
          .Description  = "A named grid spreadsheet.  Cells are child objects."
          .Msgs         = "GetSheet, EditSheet(Name?,Cols?,Rows?),
                           SetCell(CellRef,Raw), ParseSheet"
          .Attrs        = "Name, Cols, Rows, ColLabels, CreatedAt"
          .SideEffect   = "GetSheet:None, EditSheet:Write,
                           SetCell:Write, ParseSheet:Write"
          .Auth         = "isVerified"

     d. (spreadsheet, cell)  — cell class
          .Description  = "One cell in a spreadsheet. Holds a literal value
                           or = formula."
          .Msgs         = "GetObj"
          .Attrs        = "SheetId, CellRef, Raw, Value"
          .SideEffect   = "GetObj:None"
          .Auth         = "isVerified"

ActManagerImpl changes:
  Call SpreadsheetInstall.install(actId, usrId, usrName, domain, prvId, sideClient)
  from addAct(), after BookstoreInstall.

## PART 6 — OBJECT IMPLEMENTATIONS

6.0  SpreadsheetAppImpl   (com.spreadsheet.objimpl.SpreadsheetAppImpl)
-----------------------------------------------------------------------
ImplMap: put("spreadsheet", "app", new SpreadsheetAppImpl())

hasRights: Auth.isVerified(inMsg)

Purpose:
  LLM-native facade over the spreadsheet data.  All operations accept names
  (not opaque IDs) so the AI Agent can call them directly using the user's
  natural-language query without a prior navigation step.

  The handler resolves names to IDs via SheetsImpl.findSheetByName and then
  delegates to the same DB helpers used by SheetsImpl and SheetImpl.

  DomId of the entry-point object:
    HstId : spreadsheet-<actId>
    AppId : spreadsheet
    ActId : <actId>
    ObjId : app-spreadsheet

Operations:

  GetSpreadsheets
    In:  (none)
    Out: { Sheets: [ {Name, Cols, Rows, CreatedAt}, … ] }

    Returns all spreadsheets owned by the caller, newest first.
    Identical to SheetsImpl.GetSheets but omits opaque SheetId from the
    response — the LLM should use names for subsequent calls.

    Steps:
      a. Locate the sheets container: build sheetsId from the same host/actId.
      b. Read all lnks from sheets with Tag=(spreadsheet, sheet).
      c. For each lnk fetch sheet attrs and build the response.

  GetSpreadsheet
    In:  Name  (string, case-insensitive)
    Out: { Name, Cols, Rows, ColLabels,
           Cells: [ {CellRef, Raw, Value}, … ] }

    Returns the full content of the named spreadsheet in one call.

    Steps:
      a. Resolve name → sheet DomId via SheetsImpl.findSheetByName.
         If not found: error "No spreadsheet named '<Name>'".
      b. Delegate to SheetImpl.getSheetData(sheetId) (package-private helper)
         and return its result without exposing SheetId.

  SetCell
    In:  Name (spreadsheet name), CellRef (e.g. "B3"), Raw
    Out: { CellRef, Value }

    Writes a single cell in the named spreadsheet.

    Steps:
      a. Resolve Name → sheetId (same as GetSpreadsheet step a).
      b. Delegate to SheetImpl.setCell(sheetId, cellRef, raw) and return
         the result.

  DeleteSpreadsheet
    In:  Name (string, case-insensitive)
    Out: { Deleted: "True" }

    Deletes the named spreadsheet and all its cells.

    Steps:
      a. Resolve Name → SheetId via SheetsImpl.findSheetByName.
         If not found: error "No spreadsheet named '<Name>'".
      b. Delegate to SheetsImpl.deleteSheet(sheetsId, sheetId).

Private helpers (package-private, reused by SheetsImpl and SheetImpl):

  SheetsImpl.findSheetByName(DomId sheetsId, String name) → DomId | null
    Iterates all lnks from the sheets container and returns the DomId of
    the sheet whose ObjName matches name (case-insensitive), or null.

  SheetImpl.getSheetData(DomId sheetId) → JsonObject
    Reads sheet attrs + cells and builds the standard GetSheet response body.
    Used by both SheetImpl.GetSheet and SpreadsheetAppImpl.GetSpreadsheet.

  SheetImpl.setCell(DomId sheetId, String cellRef, String raw) → JsonObject
    Core logic of SheetImpl.SetCell, extracted so SpreadsheetAppImpl can
    call it without going through the message dispatch layer.

  SheetImpl.recalcAndPersist(DomId sheetId)
    Existing private method; made package-private so SpreadsheetAppImpl
    can trigger recalculation after SetCell if desired.

6.1  SheetsImpl   (com.spreadsheet.objimpl.SheetsImpl)
-------------------------------------------------------
ImplMap: put("spreadsheet", "sheets", new SheetsImpl())

hasRights: Auth.isVerified(inMsg)

Package-private helper (used also by SpreadsheetAppImpl):

  findSheetByName(DomId sheetsId, String name) → DomId | null
    Iterates all lnks from sheetsId with Tag=(spreadsheet, sheet).
    Returns the DomId of the first sheet whose ObjName matches name
    case-insensitively, or null if not found.

Operations:

  CreateSheet
    In:  Name, Cols (int string), Rows (int string)
    Out: { SheetId: <objId> }

    Steps:
      a. Validate Cols ≥ 1, Rows ≥ 1.
      b. Check name uniqueness: call findSheetByName(sheetsId, Name).
         If non-null: return error "A spreadsheet named '<Name>' already exists".
      c. Compute ColLabels string from Cols count (A,B,C,…).
      d. objId = IdGen.createId("sheet", IdGen.getCurTimeBase64())
      e. Build and store (spreadsheet, sheet) object with Attrs:
           Name, Cols, Rows, ColLabels, CreatedAt=now.
      f. LnkDb.addLnk(sheets → sheet, seqNum=currentTimeMillis)
         Tag=(spreadsheet, sheet), Val="".

  GetSheets
    In:  (none)
    Out: { Sheets: [ {SheetId, Name, Cols, Rows, CreatedAt}, … ] }

    Steps:
      Read all lnks from sheets with Tag=(spreadsheet, sheet), ordered by
      SeqNum descending (newest first).
      For each lnk fetch the sheet object and return its key attrs.

  DeleteSheet
    In:  SheetId
    Out: { Deleted: "True" }

    Package-private overload deleteSheet(DomId sheetsId, DomId sheetId)
    contains the core logic and is called by SpreadsheetAppImpl.

    Steps:
      a. Verify SheetId is a valid sheet owned by the caller.
      b. Fetch all cell lnks from sheet and delete each cell object.
      c. Delete all lnks from the sheet object.
      d. Delete the sheet object itself.
      e. LnkDb.deleteLnks(sheets → sheet, spreadsheet, sheet, val=null).

6.2  SheetImpl   (com.spreadsheet.objimpl.SheetImpl)
-----------------------------------------------------
ImplMap: put("spreadsheet", "sheet", new SheetImpl())

hasRights: Auth.isVerified(inMsg)

Package-private helpers (used also by SpreadsheetAppImpl):

  getSheetData(DomId sheetId) → JsonObject
    Core logic of GetSheet.  Reads attrs + cells and returns the standard
    response body.  Extracted so SpreadsheetAppImpl.GetSpreadsheet can
    delegate without duplicating code.

  setCell(DomId sheetId, String cellRef, String raw) → JsonObject
    Core logic of SetCell.  Validates bounds, creates/updates the cell
    object, evaluates the formula, and returns { CellRef, Value }.
    Called by SpreadsheetAppImpl.SetCell.

  recalcAndPersist(DomId sheetId)
    Previously private; made package-private.  Re-evaluates all formulas
    and persists updated Values.  Called by SheetImpl.ParseSheet and
    optionally by SpreadsheetAppImpl after a batch of SetCell calls.

Operations:

  GetSheet
    In:  (none)
    Out: { Name, Cols, Rows, ColLabels,
           Cells: [ {CellRef, Raw, Value}, … ] }

    Delegates to getSheetData(this.sheetId).

  EditSheet
    In:  Name(opt), Cols(opt, int string), Rows(opt, int string)
    Out: { Updated: "True" }

    When Cols or Rows shrink, cells outside the new bounds are deleted
    (cell objects + lnks removed).  When they grow, no new cell objects are
    created (lazy: cells start empty).

    Steps:
      a. If Name supplied: check uniqueness in the parent sheets container
         (build sheetsId from host/actId; call SheetsImpl.findSheetByName
         excluding this sheet).  If conflict: error "A spreadsheet named
         '<Name>' already exists".
      b. Merge supplied fields into existing sheet attrs (ObjDb.modifyObj).
      c. Recompute ColLabels if Cols changed.
      d. If Cols decreased: delete cells whose column index > new Cols.
      e. If Rows decreased: delete cells whose row index > new Rows.

  SetCell
    In:  CellRef (e.g. "B3"), Raw (the raw string to store)
    Out: { CellRef, Value }   (Value = immediate computed result or Raw for
                                non-formula values)

    Delegates to setCell(this.sheetId, cellRef, raw).

  ParseSheet
    In:  (none)
    Out: { Cells: [ {CellRef, Raw, Value}, … ] }

    Re-evaluates every formula in the sheet in dependency order and updates
    each cell's stored Value.  Returns the complete updated cell list.

    Steps:
      a. Call recalcAndPersist(this.sheetId).
      b. Return the updated cell list (same format as GetSheet Cells array).

## PART 7 — CELL FORMULA LANGUAGE

7.1  Raw value formats
----------------------
  A cell's Raw field may be:

  a) Empty string  ""
       Cell has no value.  Contributes "" to string concat, 0 to arithmetic.

  b) A literal number  e.g.  42  or  3.14  or  -7
       Stored and displayed as-is.  Numeric in formulas.

  c) A quoted string  e.g.  "hello world"
       The outer double-quotes are part of the Raw; the stored value is the
       content between them.  String in formulas.

  d) A formula  starting with  =
       The rest is an expression (see 7.2).

7.2  Formula grammar  (EBNF)
----------------------------

  formula    = "=" expr .

  expr       = term { addOp term } .
  addOp      = "+" | "-" .

  term       = factor { mulOp factor } .
  mulOp      = "*" | "/" .

  factor     = "(" expr ")"
             | number
             | quotedString
             | cellRef
             | objAttr .

  number     = [ "-" ] digit { digit } [ "." digit { digit } ] .
  digit      = "0" | … | "9" .

  quotedString = '"' { anyCharExceptDquote } '"' .

  cellRef    = "$" colLabel rowNum .
  colLabel   = letter { letter } .      (* A, B, … Z, AA, AB, … *)
  rowNum     = digit { digit } .        (* 1, 2, … Rows *)

  objAttr    = "[" domId "]" attrName .
  domId      = hstId "." appId "." actId "." objId .
               (* all four parts, separated by dots;
                  actId contains '@' but no dot *)
  attrName   = letter { letter | digit | "_" } .

  letter     = "A" | … | "Z" | "a" | … | "z" .

7.3  Cell references  ($ColRow)
--------------------------------
  $A1, $B12, $Z99 — absolute references to another cell in the same sheet.

  Resolution:
    1. Verify ColRow is within sheet bounds; if not, return #REF.
    2. Recursively evaluate the referenced cell's Raw.
    3. Return its computed value (number or string).

  Circular dependencies ($A1 → $B1 → $A1) yield #CIRC in all participating
  cells.

7.4  Object attribute references  ([domId]attrName)
-----------------------------------------------------
  Retrieves a named attribute from any Domatar object reachable by DomId.

  Syntax:
    [quippin-micha@quippin.quippin.micha@quippin.quip-0NX6oBrH]text

  Resolution:
    1. Parse the DomId between [ and ].
    2. Send an Open message to that DomId using the caller's msgClient.
    3. Read the named attribute from the response Attrs.
    4. If the object is unreachable or the attribute is absent, return #N/A.
    5. The returned attribute value is treated as a string in formulas.
       To use it as a number, wrap in a numeric coercion (future arithmetic
       on strings with numeric content is attempted automatically; if it
       fails, #VALUE is returned).

  Security:
    The Open call propagates the authenticated caller's context.  Only
    objects the caller has rights to read will succeed.  Failures return
    #N/A rather than exposing error details.

7.5  Operator semantics
-----------------------
  +   Numbers: addition.   Strings or mixed: concatenation.
  -   Numbers only.  If either operand is non-numeric: #VALUE.
  *   Numbers only.  If either operand is non-numeric: #VALUE.
  /   Numbers only.  Division by zero: #DIV0.
               Non-numeric operand: #VALUE.

  String + number → coerce number to string, concatenate.
  Number + string → coerce number to string, concatenate.

7.6  Error values
-----------------
  #CIRC   Circular dependency detected.
  #REF    Cell reference out of sheet bounds.
  #N/A    Object attribute unreachable or attribute absent.
  #VALUE  Type mismatch in arithmetic.
  #DIV0   Division by zero.
  #ERR    Formula syntax error (unparseable expression).

  Errors propagate: a cell whose formula references an error cell returns
  the same error token (first encountered, left-to-right, in expressions).

7.7  Evaluation order
---------------------
  ParseSheet evaluates cells in dependency-topological order:
    1. Collect all cells containing a "=" formula.
    2. For each, extract $ColRow references as dependencies.
       ObjAttr references are treated as having no sheet-local dependency
       (they are fetched fresh at evaluation time).
    3. Kahn's algorithm to topological-sort.  Any cell in a cycle is marked
       #CIRC before evaluation; its dependents propagate #CIRC.
    4. Evaluate leaf cells first, then consumers.
    5. Non-formula cells need no re-evaluation (Raw IS the Value).

## PART 8 — FRONTEND UI

8.1  Spreadsheet list page  (spreadsheet.html)
-----------------------------------------------
  URL   : /domatar/spreadsheet.html
  Auth  : must be logged in (DomatarServlet pattern)

  Layout:
    - Page title "Spreadsheets" + logged-in user name.
    - "+ New Spreadsheet" button — opens a modal asking for:
        Name (text, required)
        Columns (number, 1–26, default 5)
        Rows    (number, 1–100, default 10)
      On confirm: POST CreateSheet → on success, navigate to sheet editor.
    - List of existing spreadsheets as cards or table rows showing:
        Name, Cols × Rows, CreatedAt.
      Each row has an "Open" button and a "Delete" button.

  AJAX calls:
    GET  /domatar/SheetsWui?Action=GetSheets
    POST /domatar/SheetsWui  Action=CreateSheet  Name=… Cols=… Rows=…
    POST /domatar/SheetsWui  Action=DeleteSheet  SheetId=…

8.2  Sheet editor page  (sheet.html)
--------------------------------------
  URL   : /domatar/sheet.html?SheetId=<sheetObjId>
  Auth  : must be logged in

  Layout:
    - Sheet name as editable heading (click to rename, calls EditSheet).
    - "← My Spreadsheets" back button.
    - "⟳ Parse" button — calls ParseSheet, refreshes the grid with computed
      values.
    - Grid:
        Column headers A, B, C, … (derived from ColLabels).
        Row headers 1, 2, 3, … .
        Each cell is an <input> or <textarea>.
        Initially shows the computed Value; when a cell is focused, switches
        to show Raw so the user can edit the formula.
        On blur (leaving a cell): POST SetCell with the new Raw.  The
        returned Value is immediately shown in the cell.
    - "Resize" button — opens a modal to change Cols and/or Rows (calls
      EditSheet).  Warns if shrinking will delete data.

  AJAX calls:
    GET  /domatar/SheetWui?Action=GetSheet&SheetId=…
    POST /domatar/SheetWui  Action=SetCell   SheetId=… CellRef=… Raw=…
    POST /domatar/SheetWui  Action=ParseSheet SheetId=…
    POST /domatar/SheetWui  Action=EditSheet  SheetId=… [Name=…] [Cols=…] [Rows=…]

  Formula display:
    Non-formula cell: Raw is displayed directly.
    Formula cell: Computed Value is shown in the grid; Raw is shown in the
    cell when it has focus, prefixed with "=" as stored.
    Error values (#ERR, #CIRC, #REF, #N/A, #VALUE, #DIV0) are shown in red.

## PART 9 — WUI SERVLETS

9.1  SheetsWui   (com.spreadsheet.webui.SheetsWui)
---------------------------------------------------
  @WebServlet("/SheetsWui/*")
  Extends DomatarServlet.

  getMsg():
    Action=GetSheets  →  dst = spreadsheet-<actId> / sheets / GetSheets
    Action=CreateSheet → dst = spreadsheet-<actId> / sheets / CreateSheet
                          Params: Name, Cols, Rows
    Action=DeleteSheet → dst = spreadsheet-<actId> / sheets / DeleteSheet
                          Params: SheetId

  The dst DomId:  DomId("spreadsheet-"+actId, "spreadsheet", actId, "sheets")
  ClsId is set:   addClsId("spreadsheet", "sheets")

  NOTE: do NOT set ClsId in the message (see PART 9.3 rationale).

9.2  SheetWui   (com.spreadsheet.webui.SheetWui)
-------------------------------------------------
  @WebServlet("/SheetWui/*")
  Extends DomatarServlet.

  getMsg():
    Action=GetSheet   → dst = spreadsheet-<actId> / <SheetId> / GetSheet
    Action=SetCell    → dst = (same); Params: CellRef, Raw
    Action=ParseSheet → dst = (same)
    Action=EditSheet  → dst = (same); Params: Name, Cols, Rows (each optional)

  The dst DomId:  DomId("spreadsheet-"+actId, "spreadsheet", actId, sheetId)

  NOTE: do NOT set ClsId in the message (see PART 9.3 rationale).

9.3  IMPORTANT — ClsId in messages
------------------------------------
  When a WUI sets addClsId(appId, clsId) in the outgoing message, the
  Msg / HttpClient dispatch layer skips ObjDb.getObj and passes obj=null to
  the handler.  Handlers must derive their DomId from inMsg.getDstId()
  rather than obj.domId.  Either:

    a) Do NOT call addClsId() in the WUI — the dispatcher will load the
       object from the DB and pass it correctly; or
    b) Call addClsId() and derive the DomId from inMsg.getDstId() inside
       the handler (as CatalogImpl does after the PART 7 fix).

  Approach (a) is simpler and preferred for new handlers.

## PART 10 — IMPLMAP ENTRIES

  In com.domatar.core.ImplMap:

    import com.spreadsheet.objimpl.SpreadsheetAppImpl;
    import com.spreadsheet.objimpl.SheetsImpl;
    import com.spreadsheet.objimpl.SheetImpl;

    put("spreadsheet", "app",    new SpreadsheetAppImpl());
    put("spreadsheet", "sheets", new SheetsImpl());
    put("spreadsheet", "sheet",  new SheetImpl());

  (spreadsheet, cell) is handled by the default ObjImpl — no custom entry.

## PART 11 — DATABASE SEEDING

File: mySQL/dump-2024-01-23-spreadsheet.sql

  1. System account:
       actId   = "spreadsheet@spreadsheet"
       usrId   = "spreadsheet@spreadsheet"
       usrName = "Spreadsheet"
       password hash = GBp05MC8NxDHPJAUdVySNhkRkjx

  2. Central HST row:
       hstId  = "spreadsheet"
       prvId  = "prv2"
       domain = "spreadsheet:8080"   (Docker alias)

  3. Per-user sub-HST rows for dave and micha:
       ("spreadsheet-dave@quippin",  "prv1", "tomcat1:8080")
       ("spreadsheet-micha@quippin", "prv2", "tomcat2:8080")

  4. Container objects  (sheets) for dave and micha on their sub-hosts:
       ("spreadsheet-dave@quippin",  "spreadsheet", "dave@quippin",  "sheets",
        "spreadsheet", "sheets", "Spreadsheets", "Your spreadsheets", '{}')
       ("spreadsheet-micha@quippin", "spreadsheet", "micha@quippin", "sheets",
        "spreadsheet", "sheets", "Spreadsheets", "Your spreadsheets", '{}')

  5. app-spreadsheet objects in the Navigator:
       ("navigator-dave@quippin",  "navigator", "dave@quippin",  "app-spreadsheet",
        "navigator", "app", "Spreadsheet", "Work with tabular data",
        '{"DisplayName":"Spreadsheet","IconPath":"/domatar/icons/spreadsheet.svg",
          "LaunchPath":"/domatar/spreadsheet.html"}')
       ("navigator-micha@quippin", "navigator", "micha@quippin", "app-spreadsheet",
        same attrs)

  6. Navigator links for dave and micha:
       root → app-spreadsheet          (navigator/app,       seqNum=7)
       app-spreadsheet → sheets        (navigator/container, seqNum=1)

## PART 12 — IMPLEMENTATION TASKS

Pass A — Infrastructure
  A1. docker-compose.yml: add "spreadsheet" alias to tomcat2 on domatar_net.
  A2. Write mySQL/dump-2024-01-23-spreadsheet.sql (PART 11).

Pass B — Backend (original spreadsheet)
  B1. Write SpreadsheetInstall.java.
  B2. Write SheetsImpl.java   (CreateSheet, GetSheets, DeleteSheet).
  B3. Write SheetImpl.java    (GetSheet, EditSheet, SetCell, ParseSheet).
      B3a. Write FormulaParser.java — recursive-descent parser for PART 7
           grammar; returns an expression AST.
      B3b. Write FormulaEvaluator.java — walks the AST; resolves cellRefs
           and objAttrs; handles error propagation.
  B4. Write SheetsWui.java.
  B5. Write SheetWui.java.
  B6. ImplMap.java: add entries for (spreadsheet, sheets) and (spreadsheet, sheet).
  B7. ActManagerImpl.addAct(): call SpreadsheetInstall.install() after
      BookstoreInstall.

Pass C — Frontend
  C1. Write spreadsheet.html  (list + create + delete).
  C2. Write sheet.html        (grid editor, SetCell on blur, ParseSheet button).

Pass D — Build and Smoke Test
  D1. Maven package, docker cp WAR to both Tomcats, restart.
  D2. Log in as dave, create "Test Sheet" (3 cols, 3 rows).
      Set A1=10, B1=20, C1=$A1+$B1.  Verify C1 shows 30 after parse.
  D3. Set A2="Hello ", B2="World", C2=$A2+$B2.
      Verify C2 shows "Hello World".
  D4. Set a cell to an objAttr formula referencing a known quip.
      Verify the attribute value appears in the cell.
  D5. Verify circular reference (#CIRC) by setting A3=$B3 and B3=$A3.

Pass E — LLM-native layer

  SpreadsheetAppImpl exposes GetSpreadsheets, GetSpreadsheet(Name),
  SetCell(Name,CellRef,Raw), DeleteSpreadsheet(Name). Sheet names are
  unique per user (SheetsImpl.findSheetByName; CreateSheet / EditSheet
  reject duplicates). ImplMap maps (spreadsheet, app) to
  SpreadsheetAppImpl. SpreadsheetInstall upserts the four class
  descriptors. Acceptance: AI Agent walkthroughs in ReadOnly and
  Agent modes ([AI Agent](AIAgent.md)).

## PART 13 — OPEN QUESTIONS

  Q1. Column limit: the grammar supports multi-letter columns (AA, AB, …).
      The v1 UI may cap the "Columns" input at 26 (A–Z).  A future "wide
      spreadsheet" mode could support up to 702 (ZZ).

  Q2. Row limit: no hard limit is specified; the UI input should enforce a
      reasonable maximum (e.g. 500 rows) to keep server-side evaluation
      responsive.

  Q3. Persistence of computed values: Value is stored on each SetCell and
      ParseSheet.  An alternative is to store only Raw and always compute
      on read — this saves storage but adds latency.  v1 stores Value.

  Q4. Object attribute caching: every ParseSheet triggers live Open calls
      for all [domId]attr references.  Future: per-session caching with a
      configurable TTL.

  Q5. Formula editor UX: showing Raw on focus and Value on blur requires the
      browser to track which cells are currently "active".  The sheet.html
      implementation must handle rapid tab-through gracefully.
