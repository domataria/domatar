/*
 * Copyright (c) 2024 Domatar
 */

package com.domatar.objimpl;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.domatar.core.Auth;
import com.domatar.core.Context;
import com.domatar.core.DomatarConfig;
import com.domatar.db.LnkDb;
import com.domatar.db.ObjDb;
import com.domatar.install.IconPaths;
import com.domatar.install.LaunchPaths;
import com.domatar.install.ShellRolePolicy;
import com.domatar.install.UserSubstrateIds;
import com.domatar.install.UserSubstrateInstall;
import com.domatar.util.IdGen;
import com.domatar.util.JsonArrayList;
import com.domatar.util.JsonHashMap;
import com.domatar.util.JsonList;
import com.domatar.util.JsonMap;
import com.domatar.util.JsonMsg;
import com.domatar.util.Lnk;
import com.domatar.util.Obj;
import com.domatar.util.ObjAttrs;
import com.domatar.util.ObjImpl;
import com.domatar.util.DomId;
import com.domatar.util.DomatarException;
import com.domatar.util.DomatarMsgClient;

/**
 * Handler for {@code (domatar, shells)} on the user substrate host
 * (Update-Mandatory-App-Rewrite.txt Phase 1 / KD3 / KD6).
 */
public class ShellsImpl extends ObjImpl
{
  private static final Logger LOG = Logger.getLogger(ShellsImpl.class.getName());

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

    if ("GetShells".equals(opr))
      getShells(opr, inMsg, outMsg);
    else if ("SetShell".equals(opr))
      setShell(opr, inMsg, outMsg);
    else if ("ResetShellsToDefaults".equals(opr))
      resetShellsToDefaults(opr, inMsg, outMsg);
    else
      return super.handleMsg(msg, obj, contextPath, contextRealPath, msgClient);

    return outMsg.toString();
  }

  @Override
  public boolean hasRights(final JsonMsg inMsg, final Obj obj,
                           final DomatarMsgClient msgClient) throws DomatarException
  {
    if (!Auth.isVerified(inMsg))
      return false;

    final Context ctx = inMsg.getContext();
    final DomId   dst = inMsg.getDstId();

    return ctx != null && ctx.actId != null && dst != null
        && ctx.actId.equals(dst.actId);
  }

  private void getShells(final String opr, final JsonMsg inMsg, final JsonMsg outMsg)
      throws DomatarException
  {
    final DomId dst = inMsg.getDstId();
    String loginPrvId = inMsg.getAttr("LoginPrvId");

    if (loginPrvId == null || loginPrvId.isEmpty())
      loginPrvId = DomatarConfig.getPrvId();

    // Ensure stock shells exist; also repairs login LaunchPath → account.html.
    // Must not fail the read: missing PrvActId (lost provider.config after a
    // container recreate) used to NPE here and Desktop painted no shells.
    if (dst != null && dst.actId != null && loginPrvId != null)
    {
      try
      {
        UserSubstrateInstall.ensureDefaultShells(dst.actId, loginPrvId);
      }
      catch (final Exception e)
      {
        LOG.log(Level.WARNING, "ensureDefaultShells skipped for actId="
            + dst.actId, e);
      }
    }

    final List<Obj> all = ObjDb.getObjPrefix(dst.hstId, "domatar", dst.actId, "shell",
        null, 1000);
    final JsonList shells = new JsonArrayList();

    for (final Obj row : all)
    {
      if (!"domatar".equals(row.clsAppId) || !"shell".equals(row.clsId))
        continue;

      final ObjAttrs a = row.attrs;

      if (a == null)
        continue;

      final String rowLogin = a.getAttr("LoginPrvId");

      if (loginPrvId != null && !loginPrvId.equals(rowLogin))
        continue;

      final JsonMap entry = new JsonHashMap();

      entry.put("LoginPrvId", rowLogin);
      entry.put("RoleId", a.getAttr("RoleId"));
      entry.put("AppId", a.getAttr("AppId"));
      entry.put("AppHstId", a.getAttr("AppHstId"));
      entry.put("LaunchPath", a.getAttr("LaunchPath"));
      entry.put("IconPath", a.getAttr("IconPath"));
      entry.put("Version", a.getAttr("Version"));
      shells.add(entry);
    }

    final ObjAttrs outAttrs = new ObjAttrs();

    outAttrs.addAttr("Shells", shells);
    outMsg.addResponseBody(opr, outAttrs);
  }

  private void setShell(final String opr, final JsonMsg inMsg, final JsonMsg outMsg)
      throws DomatarException
  {
    final DomId dst = inMsg.getDstId();
    final String roleId = inMsg.getAttr("RoleId");
    final String appId = inMsg.getAttr("AppId");
    final String localPrv = DomatarConfig.getPrvId();

    if (roleId == null || roleId.isEmpty())
    {
      outMsg.addError(opr, "Missing RoleId");
      return;
    }

    if (appId == null || appId.isEmpty())
    {
      outMsg.addError(opr, "AppId must not be empty");
      return;
    }

    if (!ShellRolePolicy.allows(appId, roleId))
    {
      outMsg.addError(opr,
          "App \"" + appId + "\" does not declare shell role \"" + roleId + "\"");
      return;
    }

    final String now = IdGen.getCurTimeBase64();
    final DomId row = UserSubstrateIds.shellRow(dst.actId, localPrv, localPrv, roleId);
    String appHstId = inMsg.getAttr("AppHstId");
    String launchPath = inMsg.getAttr("LaunchPath");
    String iconPath = inMsg.getAttr("IconPath");

    if (appHstId == null || appHstId.isEmpty())
      appHstId = DomId.subHstId(appId, dst.actId, localPrv);

    if (launchPath == null || launchPath.isEmpty())
      launchPath = LaunchPaths.forShell(roleId, appId);

    if (iconPath == null || iconPath.isEmpty())
      iconPath = IconPaths.launcher(appId);

    final Obj existing = ObjDb.getObj(row);

    if (existing != null)
      ObjDb.deleteObj(row);

    final ObjAttrs attrs = new ObjAttrs();

    attrs.addAttr("LoginPrvId", localPrv);
    attrs.addAttr("RoleId", roleId);
    attrs.addAttr("AppId", appId);
    attrs.addAttr("AppHstId", appHstId);
    attrs.addAttr("LaunchPath", launchPath);
    attrs.addAttr("IconPath", iconPath);
    attrs.addAttr("Version", now);

    ObjDb.addObj(new Obj(row, "domatar", "shell", capitalize(roleId),
        "Shell binding", attrs));

    if (LnkDb.getLnk(dst, row, "domatar", "shell") == null)
      LnkDb.addLnk(new Lnk(dst, row,
          "domatar", "shell",
          capitalize(roleId), "Shell binding",
          "domatar", "shell",
          roleId, 0));

    final ObjAttrs outAttrs = new ObjAttrs();

    outAttrs.addAttr("Set", "True");
    outMsg.addResponseBody(opr, outAttrs);
  }

  private void resetShellsToDefaults(final String opr, final JsonMsg inMsg,
                                     final JsonMsg outMsg) throws DomatarException
  {
    final DomId dst = inMsg.getDstId();
    final String localPrv = DomatarConfig.getPrvId();

    // Drop existing shell rows for this login home, then reseed stock trio.
    final List<Obj> all = ObjDb.getObjPrefix(dst.hstId, "domatar", dst.actId, "shell",
        null, 1000);
    final List<DomId> toDelete = new ArrayList<>();

    for (final Obj row : all)
    {
      if (!"domatar".equals(row.clsAppId) || !"shell".equals(row.clsId))
        continue;

      final String loginPrv = row.attrs != null ? row.attrs.getAttr("LoginPrvId") : null;

      if (localPrv != null && localPrv.equals(loginPrv))
        toDelete.add(row.domId);
    }

    for (final DomId id : toDelete)
    {
      LnkDb.deleteLnks(dst, id, "domatar", "shell", null, null);
      ObjDb.deleteObj(id);
    }

    UserSubstrateInstall.ensureDefaultShells(dst.actId, localPrv);

    final ObjAttrs outAttrs = new ObjAttrs();

    outAttrs.addAttr("Reset", "True");
    outMsg.addResponseBody(opr, outAttrs);
  }

  private static String capitalize(final String s)
  {
    if (s == null || s.isEmpty())
      return s;

    return Character.toUpperCase(s.charAt(0)) + s.substring(1);
  }
}
