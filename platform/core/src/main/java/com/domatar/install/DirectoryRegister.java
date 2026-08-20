/*
 * Copyright (c) 2024 Domatar
 */

package com.domatar.install;

import com.domatar.core.DomatarConfig;
import com.domatar.db.HstDb;
import com.domatar.util.Hst;
import com.domatar.util.JsonMsg;
import com.domatar.util.ObjAttrs;
import com.domatar.util.DomId;
import com.domatar.util.DomatarException;
import com.domatar.util.DomatarMsgClient;

/**
 * Publishes a sub-host to the global directory (and the local hst cache).
 *
 * <p>K10 / Spec-Login-Multiple.txt PART 5: provider-qualified replicas
 * ({@code login-&lt;actId&gt;-&lt;prvId&gt;}, etc.) must be resolvable by peers
 * via GetHst. Local {@link HstDb#addHst} alone is not enough for
 * cross-provider routing.
 *
 * <p>Idempotent and non-fatal on transport failure: the local cache row
 * is still written so in-process (sendLocal) routing keeps working.
 */
public final class DirectoryRegister
{
  private DirectoryRegister() {}

  /**
   * Ensure {@code hstId} is in the local hst cache and published to the
   * directory via UpdateHst (Spec-Domatar.txt PART 4.2).
   */
  public static void registerHst(final String hstId,
                                 final String domain,
                                 final String prvId,
                                 final DomatarMsgClient msgClient)
  {
    if (hstId == null || domain == null || prvId == null)
      return;

    try
    {
      final Hst existing = HstDb.getHst(hstId);

      if (existing == null)
        HstDb.addHst(hstId, domain, prvId);
    }
    catch (final DomatarException e)
    {
      System.out.println("WARN: DirectoryRegister local HstDb.addHst failed for "
          + hstId + ": " + e);
    }

    publishHst(hstId, domain, prvId, msgClient);
  }

  /**
   * Publish {@code hstId} to the directory only — no local {@link HstDb#addHst}.
   * Used for cross-provider InstallApp so the home node does not claim the
   * portable app host (Spec-AppStore / Update-AppStore Phase 3).
   */
  public static void publishHst(final String hstId,
                                final String domain,
                                final String prvId,
                                final DomatarMsgClient msgClient)
  {
    if (hstId == null || domain == null || prvId == null || msgClient == null)
      return;

    try
    {
      final DomId directoryId = new DomId("domatar", "hst", "domatar@hst", "hsts");

      final JsonMsg updateMsg = new JsonMsg();
      final ObjAttrs attrs = new ObjAttrs();

      attrs.addAttr("HstId",  hstId);
      attrs.addAttr("Domain", domain);
      attrs.addAttr("PrvId",  prvId);

      updateMsg.addRequestBody("UpdateHst", attrs);
      updateMsg.addClsId("hst", "hsts");

      final JsonMsg ret = msgClient.send(directoryId, updateMsg);

      if (ret != null && "Failure".equals(ret.getError()))
      {
        System.out.println("WARN: DirectoryRegister UpdateHst failed for "
            + hstId + ": " + ret.getErrorMsg()
            + " (directory=" + DomatarConfig.getDirectory() + ")");
      }
    }
    catch (final Exception e)
    {
      System.out.println("WARN: DirectoryRegister UpdateHst transport failed for "
          + hstId + ": " + e);
    }
  }
}
