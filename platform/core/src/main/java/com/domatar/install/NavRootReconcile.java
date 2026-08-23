/*
 * Copyright (c) 2024 Domatar
 */

package com.domatar.install;

import java.util.logging.Level;
import java.util.logging.Logger;

import com.domatar.db.LnkDb;
import com.domatar.util.IdGen;
import com.domatar.util.JsonList;
import com.domatar.util.JsonMap;
import com.domatar.util.JsonMsg;
import com.domatar.util.Lnk;
import com.domatar.util.ObjAttrs;
import com.domatar.util.DomId;
import com.domatar.util.DomatarException;
import com.domatar.util.DomatarMsgClient;

/**
 * N1 navigator reconcile: ensure root → app entry lnks exist for every
 * live {@code GetUserApps} row (Spec-Mandatory-App-Rewrite / Update Phase 3).
 *
 * <p>Phase 3 policy: <b>add missing lnks only</b>; do not delete existing
 * root links (placeholders retired in Phase 7).
 */
public final class NavRootReconcile
{
  private static final Logger LOG =
      Logger.getLogger(NavRootReconcile.class.getName());

  /** SeqNums for newly reconciled apps stay above stock installs (1–9). */
  private static final long RECONCILE_SEQ_BASE = 200L;

  private NavRootReconcile() {}

  /**
   * Build the Navigator entry DomId for an installed app
   * {@code (AppHstId, AppId, actId, app-&lt;AppId&gt;)}.
   */
  public static DomId entryDomId(final String actId,
                                 final String appId,
                                 final String appHstId) throws DomatarException
  {
    if (actId == null || appId == null || appHstId == null
        || actId.isEmpty() || appId.isEmpty() || appHstId.isEmpty())
      throw new DomatarException("entryDomId requires actId, appId, appHstId");

    return new DomId(appHstId, appId, actId, IdGen.createId("app", appId));
  }

  /**
   * Fetch GetUserApps and add any missing root → app lnks.
   * Failures are logged by the caller; this method throws on hard errors
   * so Open can degrade gracefully.
   */
  public static void reconcileRootFromUserApps(final DomId rootId,
                                               final String actId,
                                               final String prvId,
                                               final DomatarMsgClient msgClient)
      throws DomatarException
  {
    if (rootId == null || actId == null || prvId == null || msgClient == null)
      return;

    final DomId userApps = UserSubstrateIds.userApps(actId, prvId);
    final JsonMsg req = new JsonMsg();

    req.addRequestBody("GetUserApps", null);
    req.addClsId("domatar", "userApps");

    final JsonMsg resp;

    try
    {
      resp = msgClient.send(userApps, req);
    }
    catch (final DomatarException e)
    {
      LOG.log(Level.WARNING,
          "NavRootReconcile GetUserApps failed for actId=" + actId, e);
      throw e;
    }

    if (resp == null || resp.isFailure())
    {
      final String detail = resp != null ? resp.getErrorMsg() : "null response";

      LOG.warning("NavRootReconcile GetUserApps Failure actId=" + actId
          + ": " + detail);
      throw new DomatarException(
          detail != null && !detail.isEmpty() ? detail : "GetUserApps failed");
    }

    final ObjAttrs body = resp.getAttrs();
    final JsonList apps = body != null ? body.getAttrList("Apps") : null;

    if (apps == null || apps.isEmpty())
      return;

    long seq = RECONCILE_SEQ_BASE;

    for (int i = 0; i < apps.size(); i++)
    {
      final Object item = apps.get(i);

      if (!(item instanceof JsonMap))
        continue;

      final JsonMap row = (JsonMap) item;
      final String appId = row.getString("AppId");
      final String appHstId = row.getString("AppHstId");
      String displayName = row.getString("DisplayName");

      if (appId == null || appId.isEmpty()
          || appHstId == null || appHstId.isEmpty())
        continue;

      if (displayName == null || displayName.isEmpty())
        displayName = appId;

      final DomId entry = entryDomId(actId, appId, appHstId);

      if (NavAppEntry.repairRootLnk(rootId, entry, appId, displayName,
          "Installed application", seq))
        seq++;
    }
  }
}
