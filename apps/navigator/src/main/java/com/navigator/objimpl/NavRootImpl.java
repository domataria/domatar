/*
 * Copyright (c) 2024 Domatar
 */

package com.navigator.objimpl;

import java.util.logging.Level;
import java.util.logging.Logger;

import com.domatar.core.Auth;
import com.domatar.core.DomatarConfig;
import com.domatar.db.ObjDb;
import com.domatar.install.NavRootReconcile;
import com.domatar.install.NavigatorReplica;
import com.domatar.util.JsonMsg;
import com.domatar.util.Obj;
import com.domatar.util.ObjImpl;
import com.domatar.util.DomId;
import com.domatar.util.DomatarException;
import com.domatar.util.DomatarMsgClient;

/**
 * Handler for class (navigator, root).
 *
 * On Open (and other ops), reconciles root → app lnks from the local
 * domatar userApps registry (N1 / Update-Mandatory-App-Rewrite Phase 3)
 * so cross-provider installs appear on this login home's Navigator.
 *
 * Placeholder Search/Chat/Mail stubs were removed in Phase 7 — those
 * appIds are real JARs with InstallUser.
 */
public class NavRootImpl extends ObjImpl
{
  private static final Logger LOG = Logger.getLogger(NavRootImpl.class.getName());

  @Override
  public String handleMsg(final String msg, final Obj obj, final String contextPath,
                          final String contextRealPath, final DomatarMsgClient msgClient)
      throws DomatarException
  {
    Obj openObj = obj;

    if (obj != null && obj.domId != null)
    {
      try
      {
        NavigatorReplica.ensureRootDisplayName(obj.domId);

        // Re-read so Open/GetLnks returns the repaired ObjName.
        final Obj fresh = ObjDb.getObj(obj.domId);

        if (fresh != null)
          openObj = fresh;

        final String actId = obj.domId.actId;
        String prvId = DomId.replicaPrvId(obj.domId.hstId);

        if (prvId == null || prvId.isEmpty())
          prvId = DomatarConfig.getPrvId();

        if (actId != null && prvId != null)
          NavRootReconcile.reconcileRootFromUserApps(
              obj.domId, actId, prvId, msgClient);
      }
      catch (final Exception e)
      {
        LOG.log(Level.WARNING,
            "NavRootReconcile degraded; continuing Open for "
                + obj.domId, e);
      }
    }

    return super.handleMsg(msg, openObj, contextPath, contextRealPath, msgClient);
  }

  @Override
  public boolean hasRights(final JsonMsg inMsg, final Obj obj,
                           final DomatarMsgClient msgClient)
      throws DomatarException
  {
    return Auth.isVerified(inMsg);
  }
}
