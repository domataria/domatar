/*
 * Copyright (c) 2024 Domatar
 */

package com.domatar.install;

import com.domatar.core.Context;
import com.domatar.core.DomatarConfig;
import com.domatar.db.ObjDb;
import com.domatar.util.JsonMsg;
import com.domatar.util.Obj;
import com.domatar.util.ObjAttrs;
import com.domatar.util.DomId;
import com.domatar.util.DomatarException;
import com.domatar.util.DomatarMsgClient;

/**
 * Facade that dual-writes installed-app rows onto the user substrate
 * {@code userApps} container (Update-Mandatory-App-Rewrite.txt Phase 1).
 */
public final class UserAppRegistry
{
  private UserAppRegistry() {}

  public static void upsertUserApp(final String actId,
                                   final String homePrvId,
                                   final String appId,
                                   final String displayName,
                                   final String iconPath,
                                   final String launchPath,
                                   final String hostPrvId,
                                   final String appHstId,
                                   final String position,
                                   final DomatarMsgClient msgClient,
                                   final JsonMsg inMsg)
      throws DomatarException
  {
    if (msgClient == null || actId == null || homePrvId == null || appId == null)
      return;

    final String domain = DomatarConfig.getDomain();

    if (domain == null || domain.isEmpty())
      return;

    Context ctx = null;
    String usrId = actId;
    String usrName = actId;

    if (inMsg != null)
    {
      try { ctx = inMsg.getContext(); }
      catch (final Exception ignored) {}

      if (ctx != null)
      {
        if (ctx.usrId != null)
          usrId = ctx.usrId;

        if (ctx.usrName != null)
          usrName = ctx.usrName;
      }
    }

    final DomId userApps = UserSubstrateIds.userApps(actId, homePrvId);

    if (ObjDb.getObj(userApps) == null)
      UserSubstrateInstall.ensureUserSubstrate(
          actId, usrId, usrName, homePrvId, domain, msgClient);

    final ObjAttrs attrs = new ObjAttrs();

    attrs.addAttr("AppId", appId);
    attrs.addAttr("DisplayName",
        displayName != null ? displayName : appId);
    attrs.addAttr("IconPath", iconPath != null ? iconPath : "");
    attrs.addAttr("LaunchPath", launchPath != null ? launchPath : "");

    if (hostPrvId != null && !hostPrvId.isEmpty())
      attrs.addAttr("HostPrvId", hostPrvId);

    if (appHstId != null && !appHstId.isEmpty())
      attrs.addAttr("AppHstId", appHstId);

    if (position != null && !position.isEmpty())
      attrs.addAttr("Position", position);

    attrs.addAttr("Tombstone", "False");

    final JsonMsg req = new JsonMsg();
    DomId src = null;

    if (inMsg != null)
    {
      try { src = inMsg.getSrcId(); }
      catch (final Exception ignored) {}
    }

    if (src == null)
      src = msgClient.getSrcId();

    if (src != null && ctx != null)
      req.addRequestHead(src, userApps, ctx);

    req.addRequestBody("InstallUserApp", attrs);
    req.addClsId("domatar", "userApps");

    final JsonMsg resp = msgClient.send(userApps, req);

    if (resp != null && resp.isFailure())
    {
      System.out.println("WARN: UserAppRegistry.upsertUserApp InstallUserApp failed for "
          + appId + ": " + resp.getErrorMsg());
    }
  }

  public static void tombstoneUserApp(final String actId,
                                      final String homePrvId,
                                      final String appId,
                                      final DomatarMsgClient msgClient,
                                      final JsonMsg inMsg)
      throws DomatarException
  {
    if (msgClient == null || actId == null || homePrvId == null || appId == null)
      return;

    final DomId userApps = UserSubstrateIds.userApps(actId, homePrvId);
    final Obj existing = ObjDb.getObj(userApps);

    if (existing == null)
      return;

    final ObjAttrs attrs = new ObjAttrs();

    attrs.addAttr("AppId", appId);

    final JsonMsg req = new JsonMsg();
    Context ctx = null;
    DomId src = null;

    if (inMsg != null)
    {
      try { ctx = inMsg.getContext(); }
      catch (final Exception ignored) {}

      try { src = inMsg.getSrcId(); }
      catch (final Exception ignored) {}
    }

    if (src == null)
      src = msgClient.getSrcId();

    if (src != null && ctx != null)
      req.addRequestHead(src, userApps, ctx);

    req.addRequestBody("UninstallUserApp", attrs);
    req.addClsId("domatar", "userApps");

    final JsonMsg resp = msgClient.send(userApps, req);

    if (resp != null && resp.isFailure())
    {
      System.out.println("WARN: UserAppRegistry.tombstoneUserApp failed for "
          + appId + ": " + resp.getErrorMsg());
    }
  }
}
