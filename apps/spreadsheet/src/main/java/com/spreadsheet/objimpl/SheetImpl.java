/*
 * Copyright (c) 2024 Domatar
 */

package com.spreadsheet.objimpl;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

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

import com.spreadsheet.formula.FormulaEvaluator;
import com.spreadsheet.formula.FormulaParser;
import com.spreadsheet.style.CellStyle;

/**
 * Handler for class (spreadsheet, sheet).
 *
 * Operations: GetSheet, EditSheet, SetCell, ParseSheet.
 * Spec: Spec-Spreadsheet.txt PART 6.2
 */
public class SheetImpl extends ObjImpl
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

    final DomId sheetId = inMsg.getDstId();

    if ("GetSheet".equals(opr))
      getSheet(opr, outMsg, sheetId, msgClient);
    else if ("EditSheet".equals(opr))
      editSheet(opr, inMsg, outMsg, sheetId);
    else if ("SetCell".equals(opr))
      setCell(opr, inMsg, outMsg, sheetId, msgClient);
    else if ("ParseSheet".equals(opr))
      parseSheet(opr, outMsg, sheetId, msgClient);
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

  private void getSheet(final String opr, final JsonMsg outMsg, final DomId sheetId,
                        final DomatarMsgClient msgClient)
      throws DomatarException
  {
    final Obj sheet = ObjDb.getObj(sheetId);
    if (sheet == null)
    {
      outMsg.addError(opr, "Sheet not found");
      return;
    }

    final ObjAttrs out = new ObjAttrs();
    out.addAttr("SheetId",   sheetId.objId);
    out.addAttr("Name",      sheet.attrs.getAttr("Name"));
    out.addAttr("Cols",      sheet.attrs.getAttr("Cols"));
    out.addAttr("Rows",      sheet.attrs.getAttr("Rows"));
    out.addAttr("ColLabels", sheet.attrs.getAttr("ColLabels"));
    out.addAttr("Cells",     recalcAndPersist(sheetId, msgClient));
    outMsg.addResponseBody(opr, out);
  }

  private void editSheet(final String opr, final JsonMsg inMsg, final JsonMsg outMsg,
                         final DomId sheetId)
      throws DomatarException
  {
    final Obj sheet = ObjDb.getObj(sheetId);
    if (sheet == null)
    {
      outMsg.addError(opr, "Sheet not found");
      return;
    }

    final ObjAttrs in    = inMsg.getAttrs();
    final String newName = in.getAttr("Name");
    final String newCols = in.getAttr("Cols");
    final String newRows = in.getAttr("Rows");

    if (newName != null)
    {
      final DomId sheetsId = new DomId(sheetId.hstId, "spreadsheet", sheetId.actId, "sheets");
      final DomId conflict = SheetsImpl.findSheetByName(sheetsId, newName);
      if (conflict != null && !conflict.objId.equals(sheetId.objId))
      {
        outMsg.addError(opr, "A spreadsheet named '" + newName + "' already exists");
        return;
      }
    }

    final int oldCols = parseInt(sheet.attrs.getAttr("Cols"), 0);
    final int oldRows = parseInt(sheet.attrs.getAttr("Rows"), 0);

    if (newName != null)
      sheet.attrs.addAttr("Name", newName);

    if (newCols != null)
    {
      int cols = parseInt(newCols, oldCols);
      if (cols < 1)
        cols = 1;
      sheet.attrs.addAttr("Cols",      String.valueOf(cols));
      sheet.attrs.addAttr("ColLabels", SheetsImpl.buildColLabels(cols));

      if (cols < oldCols)
        deleteCellsOutsideBounds(sheetId, cols, oldRows);
    }

    if (newRows != null)
    {
      int rows = parseInt(newRows, oldRows);
      if (rows < 1)
        rows = 1;
      sheet.attrs.addAttr("Rows", String.valueOf(rows));

      final int effectiveCols = parseInt(sheet.attrs.getAttr("Cols"), oldCols);
      if (rows < oldRows)
        deleteCellsOutsideBounds(sheetId, effectiveCols, rows);
    }

    ObjDb.modifyObj(sheet);

    final ObjAttrs out = new ObjAttrs();
    out.addAttr("Updated", "True");
    outMsg.addResponseBody(opr, out);
  }

  private void setCell(final String opr, final JsonMsg inMsg, final JsonMsg outMsg,
                       final DomId sheetId, final DomatarMsgClient msgClient)
      throws DomatarException
  {
    final ObjAttrs in    = inMsg.getAttrs();
    final String cellRef = in.getAttr("CellRef");
    final String raw     = in.getAttr("Raw");
    final String bold    = in.getAttr("Bold");

    if (cellRef == null)
    {
      outMsg.addError(opr, "Missing CellRef");
      return;
    }
    if (raw == null && bold == null)
    {
      outMsg.addError(opr, "Missing Raw or Bold");
      return;
    }

    final ObjAttrs result = setCellCore(sheetId, cellRef, raw, bold, msgClient);
    if (result.getAttr("Error") != null)
    {
      outMsg.addError(opr, result.getAttr("Error"));
      return;
    }

    final ObjAttrs out = new ObjAttrs();
    out.addAttr("CellRef",      result.getAttr("CellRef"));
    out.addAttr("Value",        result.getAttr("Value"));
    out.addAttr("Bold",         result.getAttr("Bold"));
    out.addAttr("UpdatedCells", result.getAttrList("UpdatedCells"));
    outMsg.addResponseBody(opr, out);
  }

  /**
   * Core cell-write logic.  Validates bounds, persists the cell, cascades
   * recalculation, and returns a result map with CellRef and Value.
   * {@code raw} or {@code bold} may be null to leave that field unchanged.
   * Returns an attrs map with an "Error" key if something goes wrong.
   * Package-private so SpreadsheetAppImpl can call it without duplicating code.
   */
  ObjAttrs setCellCore(final DomId sheetId, final String cellRef, final String raw,
                       final DomatarMsgClient msgClient)
      throws DomatarException
  {
    return setCellCore(sheetId, cellRef, raw, null, msgClient);
  }

  ObjAttrs setCellCore(final DomId sheetId, final String cellRef, String raw,
                       final String bold, final DomatarMsgClient msgClient)
      throws DomatarException
  {
    final ObjAttrs result = new ObjAttrs();

    final Obj sheet = ObjDb.getObj(sheetId);
    if (sheet != null && !isInBounds(cellRef, sheet))
    {
      result.addAttr("Error", "CellRef " + cellRef + " out of sheet bounds");
      return result;
    }

    final String cellObjId = sheetId.objId + "-" + cellRef;
    final DomId  cellId    = new DomId(sheetId.hstId, "spreadsheet", sheetId.actId, cellObjId);
    final Obj    existing  = ObjDb.getObj(cellId);

    final String existingRaw = existing != null
        ? nullToEmpty(existing.attrs.getAttr("Raw")) : "";
    final boolean existingBold = CellStyle.parse(
        existing != null ? existing.attrs.getAttr("Bold") : null);

    final boolean rawGiven = raw != null;
    if (!rawGiven)
      raw = existingRaw;
    final boolean newBold = bold != null ? CellStyle.parse(bold) : existingBold;
    final boolean styleOnly = !rawGiven;

    if (raw.isEmpty() && !newBold)
    {
      ObjDb.deleteObj(cellId);
      LnkDb.deleteLnks(sheetId, cellId, "spreadsheet", "cell", null, null);
      result.addAttr("CellRef", cellRef);
      result.addAttr("Value",   "");
      result.addAttr("Bold",    CellStyle.attr(false));
      return result;
    }

    final String value;
    if (styleOnly && existing != null)
      value = nullToEmpty(existing.attrs.getAttr("Value"));
    else if (raw.isEmpty())
      value = "";
    else
    {
      final Map<String, String> cellRaws = loadCellRaws(sheetId);
      cellRaws.put(cellRef, raw);
      value = new FormulaEvaluator(cellRaws, new HashSet<String>(), msgClient)
                  .evalRaw(raw);
    }

    final ObjAttrs cellAttrs = new ObjAttrs();
    cellAttrs.addAttr("SheetId", sheetId.objId);
    cellAttrs.addAttr("CellRef", cellRef);
    cellAttrs.addAttr("Raw",     raw);
    cellAttrs.addAttr("Value",   value);
    cellAttrs.addAttr("Bold",    CellStyle.attr(newBold));

    final Obj cellObj = new Obj(cellId, "spreadsheet", "cell", cellRef, "Cell", cellAttrs);

    if (existing != null)
      ObjDb.modifyObj(cellObj);
    else
    {
      ObjDb.addObj(cellObj);
      LnkDb.addLnk(new Lnk(sheetId, cellId,
                            "spreadsheet", "cell",
                            cellRef, "Cell",
                            "spreadsheet", "cell",
                            cellRef, 0));
    }

    final JsonList updatedCells;
    if (styleOnly)
    {
      updatedCells = new JsonArrayList();
      updatedCells.add(cellEntry(cellRef, raw, value, newBold).toMap());
    }
    else
      updatedCells = recalcAndPersist(sheetId, msgClient);

    result.addAttr("CellRef",      cellRef);
    result.addAttr("Value",        value);
    result.addAttr("Bold",         CellStyle.attr(newBold));
    result.addAttr("UpdatedCells", updatedCells);
    return result;
  }

  private static String nullToEmpty(final String s)
  {
    return s != null ? s : "";
  }

  private static ObjAttrs cellEntry(final String ref, final String raw,
                                    final String value, final boolean bold)
      throws DomatarException
  {
    final ObjAttrs entry = new ObjAttrs();
    entry.addAttr("CellRef", ref);
    entry.addAttr("Raw",     raw != null ? raw : "");
    entry.addAttr("Value",   value != null ? value : "");
    entry.addAttr("Bold",    CellStyle.attr(bold));
    return entry;
  }

  private void parseSheet(final String opr, final JsonMsg outMsg, final DomId sheetId,
                          final DomatarMsgClient msgClient)
      throws DomatarException
  {
    final JsonList cells = recalcAndPersist(sheetId, msgClient);
    final ObjAttrs out = new ObjAttrs();
    out.addAttr("Cells", cells);
    outMsg.addResponseBody(opr, out);
  }

  /**
   * Re-evaluate every cell in topological order, persist updated Values,
   * and return a JsonList of {CellRef, Raw, Value, Bold} for all cells.
   * Used by GetSheet (fresh load), SetCell (cascade), ParseSheet, and
   * SpreadsheetAppImpl (package-private access).
   */
  JsonList recalcAndPersist(final DomId sheetId, final DomatarMsgClient msgClient)
      throws DomatarException
  {
    final List<Lnk> lnks = LnkDb.getLnks(sheetId, "spreadsheet", "cell",
                                          null, null, 100000, false);

    final Map<String, String> cellRaws   = new LinkedHashMap<>();
    final Map<String, DomId>  cellDomIds = new LinkedHashMap<>();

    for (final Lnk lnk : lnks)
    {
      final Obj cell = ObjDb.getObj(lnk.lnkDomId);
      if (cell == null)
        continue;
      final String ref = cell.attrs.getAttr("CellRef");
      final String raw = cell.attrs.getAttr("Raw");
      if (ref == null)
        continue;
      cellRaws.put(ref, raw != null ? raw : "");
      cellDomIds.put(ref, lnk.lnkDomId);
    }

    final Map<String, Set<String>> deps = new LinkedHashMap<>();
    for (final String ref : cellRaws.keySet())
      deps.put(ref, extractCellDeps(cellRaws.get(ref), cellRaws.keySet()));

    final Map<String, Set<String>> revDeps = new LinkedHashMap<>();
    for (final Map.Entry<String, Set<String>> e : deps.entrySet())
      for (final String dep : e.getValue())
        revDeps.computeIfAbsent(dep, k -> new HashSet<>()).add(e.getKey());

    // Kahn's topological sort (in-degree = number of $-dependencies).
    final Map<String, Integer> inDegree = new LinkedHashMap<>();
    for (final String ref : cellRaws.keySet())
      inDegree.put(ref, deps.get(ref).size());

    final Queue<String> ready = new ArrayDeque<>();
    for (final Map.Entry<String, Integer> e : inDegree.entrySet())
      if (e.getValue() == 0)
        ready.add(e.getKey());

    final List<String> order = new ArrayList<>();
    while (!ready.isEmpty())
    {
      final String ref = ready.poll();
      order.add(ref);
      for (final String consumer : revDeps.getOrDefault(ref, Collections.emptySet()))
      {
        final int deg = inDegree.merge(consumer, -1, Integer::sum);
        if (deg == 0)
          ready.add(consumer);
      }
    }

    // Cells not in order are part of a cycle.
    final Set<String> cyclic = new HashSet<>(cellRaws.keySet());
    cyclic.removeAll(order);

    final Map<String, String> computed = new LinkedHashMap<>(cellRaws);
    final FormulaEvaluator ev = new FormulaEvaluator(computed, new HashSet<>(), msgClient);

    for (final String ref : order)
    {
      final String value = ev.evalRaw(cellRaws.get(ref));
      computed.put(ref, value);
    }
    for (final String ref : cyclic)
      computed.put(ref, FormulaEvaluator.ERR_CIRC);

    final JsonList cells = new JsonArrayList();
    for (final String ref : cellRaws.keySet())
    {
      final String raw   = cellRaws.get(ref);
      final String value = computed.get(ref);

      boolean bold = false;
      final DomId cellId = cellDomIds.get(ref);
      if (cellId != null)
      {
        final Obj cell = ObjDb.getObj(cellId);
        if (cell != null)
        {
          cell.attrs.addAttr("Value", value);
          ObjDb.modifyObj(cell);
          bold = CellStyle.parse(cell.attrs.getAttr("Bold"));
        }
      }
      cells.add(cellEntry(ref, raw, value, bold).toMap());
    }
    return cells;
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  /** Load all cell raws as a cellRef->raw map. */
  private Map<String, String> loadCellRaws(final DomId sheetId) throws DomatarException
  {
    final List<Lnk> lnks = LnkDb.getLnks(sheetId, "spreadsheet", "cell",
                                          null, null, 100000, false);
    final Map<String, String> map = new LinkedHashMap<>();
    for (final Lnk lnk : lnks)
    {
      final Obj cell = ObjDb.getObj(lnk.lnkDomId);
      if (cell == null)
        continue;
      final String ref = cell.attrs.getAttr("CellRef");
      final String raw = cell.attrs.getAttr("Raw");
      if (ref != null)
        map.put(ref, raw != null ? raw : "");
    }
    return map;
  }

  /**
   * Delete cell objects whose column or row index exceeds the given bounds.
   * Cells are identified by their CellRef (e.g. "A1", "Z10").
   */
  private void deleteCellsOutsideBounds(final DomId sheetId, final int maxCols,
                                        final int maxRows)
      throws DomatarException
  {
    final List<Lnk> lnks = LnkDb.getLnks(sheetId, "spreadsheet", "cell",
                                          null, null, 100000, false);
    for (final Lnk lnk : lnks)
    {
      final Obj cell = ObjDb.getObj(lnk.lnkDomId);
      if (cell == null)
        continue;
      final String ref = cell.attrs.getAttr("CellRef");
      if (ref == null)
        continue;

      final int colIdx = colIndex(ref);
      final int rowIdx = rowIndex(ref);

      if (colIdx > maxCols || rowIdx > maxRows)
      {
        ObjDb.deleteObj(lnk.lnkDomId);
        LnkDb.deleteLnks(sheetId, lnk.lnkDomId, "spreadsheet", "cell", null, null);
      }
    }
  }

  /**
   * Extract the set of $CellRef names referenced in a raw formula string,
   * restricted to refs that actually exist in the sheet.
   */
  private Set<String> extractCellDeps(final String raw, final Set<String> knownRefs)
  {
    final Set<String> deps = new HashSet<>();
    if (raw == null || !raw.startsWith("="))
      return deps;

    final FormulaParser.Node node = FormulaParser.parse(raw);
    collectCellRefs(node, deps);
    deps.retainAll(knownRefs);
    return deps;
  }

  private void collectCellRefs(final FormulaParser.Node node, final Set<String> out)
  {
    if (node instanceof FormulaParser.CellRefNode)
      out.add(((FormulaParser.CellRefNode) node).cellRef);
    else if (node instanceof FormulaParser.BinaryOpNode)
    {
      collectCellRefs(((FormulaParser.BinaryOpNode) node).left,  out);
      collectCellRefs(((FormulaParser.BinaryOpNode) node).right, out);
    }
    else if (node instanceof FormulaParser.UnaryMinusNode)
      collectCellRefs(((FormulaParser.UnaryMinusNode) node).operand, out);
    else if (node instanceof FormulaParser.CallNode)
    {
      for (final FormulaParser.Node arg : ((FormulaParser.CallNode) node).args)
        collectCellRefs(arg, out);
    }
  }

  /** Check whether a cellRef string falls within the sheet's declared bounds. */
  private boolean isInBounds(final String cellRef, final Obj sheet) throws DomatarException
  {
    final int maxCols = parseInt(sheet.attrs.getAttr("Cols"), Integer.MAX_VALUE);
    final int maxRows = parseInt(sheet.attrs.getAttr("Rows"), Integer.MAX_VALUE);
    return colIndex(cellRef) <= maxCols && rowIndex(cellRef) >= 1
           && rowIndex(cellRef) <= maxRows;
  }

  /** 1-based column index of a cellRef ("A"->1, "B"->2, "Z"->26, "AA"->27). */
  static int colIndex(final String cellRef)
  {
    int idx = 0;
    for (int i = 0; i < cellRef.length(); i++)
    {
      final char c = cellRef.charAt(i);
      if (!Character.isLetter(c))
        break;
      idx = idx * 26 + (Character.toUpperCase(c) - 'A' + 1);
    }
    return idx;
  }

  /** 1-based row number of a cellRef ("A1"->1, "B12"->12). */
  static int rowIndex(final String cellRef)
  {
    int start = 0;
    while (start < cellRef.length() && Character.isLetter(cellRef.charAt(start)))
      start++;
    try
    {
      return Integer.parseInt(cellRef.substring(start));
    }
    catch (NumberFormatException e)
    {
      return 0;
    }
  }

  private static int parseInt(final String s, final int def)
  {
    if (s == null)
      return def;
    try
    {
      return Integer.parseInt(s);
    }
    catch (NumberFormatException e)
    {
      return def;
    }
  }
}
