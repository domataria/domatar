/*
 * Copyright (c) 2024 Domatar
 */

package com.quippin.objimpl;

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
 * Handler for class (quippin, directory).
 *
 * Implements Register (called by QuippinInstall at sign-up time) and
 * GetDirectory (called by QuippinDirectoryWui to populate the lookup
 * dialog in follows.html).
 *
 * Spec: Spec-QuippinDirectory.txt PART 3.1
 */
public class QuippinDirectoryImpl extends ObjImpl
{
  private static final String DIR_HST_ID = "quippin";
  private static final String DIR_APP_ID = "quippin";
  private static final String DIR_ACT_ID = "quippin@quippin";
  private static final String DIR_OBJ_ID = "directory";

  @Override
  public String handleMsg(final String msg, final Obj obj, final String contextPath,
                          final String contextRealPath, final DomatarMsgClient msgClient)
      throws DomatarException
  {
    final JsonMsg inMsg = new JsonMsg(msg);
    final String  opr   = inMsg.getOperation();

    if ("Compensate".equals(opr))
      return super.handleMsg(msg, obj, contextPath, contextRealPath, msgClient);

    final JsonMsg outMsg = new JsonMsg();

    if (!hasRights(inMsg, obj, msgClient))
      return notAuthorized(inMsg);

    if ("Register".equals(opr))
      register(opr, inMsg, outMsg);
    else if ("GetDirectory".equals(opr))
      getDirectory(opr, outMsg);
    else
      return super.handleMsg(msg, obj, contextPath, contextRealPath, msgClient);

    return outMsg.toString();
  }

  @Override
  public boolean hasRights(final JsonMsg inMsg, final Obj obj, final DomatarMsgClient msgClient)
      throws DomatarException
  {
    return Auth.isVerified(inMsg);
  }

  // -------------------------------------------------------------------------

  private void register(final String opr, final JsonMsg inMsg, final JsonMsg outMsg) throws DomatarException
  {
    final String actId   = inMsg.getAttrs().getAttr("ActId");
    final String usrId   = inMsg.getAttrs().getAttr("UsrId");
    final String usrName = inMsg.getAttrs().getAttr("UsrName");

    if (actId == null || actId.isEmpty())
    {
      outMsg.addError(opr, "Missing ActId");
      return;
    }

    final DomId dirId   = new DomId(DIR_HST_ID, DIR_APP_ID, DIR_ACT_ID, DIR_OBJ_ID);
    final DomId entryId = new DomId(DIR_HST_ID, DIR_APP_ID, DIR_ACT_ID, "dir-" + actId);
    final DomId appQpId = new DomId(DomId.subHstId("navigator", actId), "navigator", actId, "app-quippin");

    // 1. Ensure directory container exists.
    if (ObjDb.getObj(dirId) == null)
      ObjDb.addObj(new Obj(dirId, "quippin", "directory",
                           "Directory", "Quippin user directory", new ObjAttrs()));

    // 2. Ensure entry object exists.
    if (ObjDb.getObj(entryId) == null)
    {
      final ObjAttrs attrs = new ObjAttrs();
      attrs.addAttr("ActId",   actId);
      attrs.addAttr("UsrId",   usrId != null ? usrId : actId);
      attrs.addAttr("UsrName", usrName != null ? usrName : actId);
      ObjDb.addObj(new Obj(entryId, "quippin", "dirEntry",
                           usrName != null ? usrName : actId,
                           usrId   != null ? usrId   : actId,
                           attrs));
    }

    // 3. Ensure directory -> dirEntry link exists.
    if (LnkDb.getLnk(dirId, entryId, "quippin", "dirEntry") == null)
      LnkDb.addLnk(new Lnk(dirId, entryId,
                            "quippin", "dirEntry",
                            usrName != null ? usrName : actId,
                            usrId   != null ? usrId   : actId,
                            "quippin", "dirEntry",
                            actId, System.currentTimeMillis()));

    // 4. Ensure dirEntry -> app-quippin link exists.
    if (LnkDb.getLnk(entryId, appQpId, "quippin", "appQuippin") == null)
      LnkDb.addLnk(new Lnk(entryId, appQpId,
                            "navigator", "app",
                            usrName != null ? usrName : actId,
                            "Quippin",
                            "quippin", "appQuippin",
                            actId, 1));

    final ObjAttrs outAttrs = new ObjAttrs();
    outAttrs.addAttr("Registered", "True");
    outMsg.addResponseBody(opr, outAttrs);
  }

  private void getDirectory(final String opr, final JsonMsg outMsg) throws DomatarException
  {
    final DomId dirId = new DomId(DIR_HST_ID, DIR_APP_ID, DIR_ACT_ID, DIR_OBJ_ID);
    final List<Lnk> lnks = LnkDb.getLnks(dirId, "quippin", "dirEntry", null, null, 500, false);
    final JsonList users = new JsonArrayList(lnks.size());

    for (final Lnk lnk : lnks)
    {
      final ObjAttrs entry = new ObjAttrs();
      entry.addAttr("ActId",   lnk.val);
      entry.addAttr("UsrId",   lnk.lnkObjDesc);
      entry.addAttr("UsrName", lnk.lnkObjName);
      users.add(entry.toMap());
    }

    final ObjAttrs outAttrs = new ObjAttrs();
    outAttrs.addAttr("Users", users);
    outMsg.addResponseBody(opr, outAttrs);
  }
}
