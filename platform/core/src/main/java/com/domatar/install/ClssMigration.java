/*
 * Copyright (c) 2024 Domatar
 */

package com.domatar.install;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;

import com.domatar.db.LnkDb;
import com.domatar.db.ObjDb;
import com.domatar.util.Lnk;
import com.domatar.util.Obj;
import com.domatar.util.DomId;
import com.domatar.util.DomatarException;

/**
 * Adds the missing "clss" (Classes) container objects and Navigator links for
 * each app sub-host on this provider.
 *
 * <p>Background: every app's {@code installUser} creates cls descriptor objects
 * via {@link ClsInstall#upsertClsImplementing} but, before the clss-container
 * fix, did not call {@link ClsInstall#ensureClssContainer}. As a result, the
 * clss object and the {@code app-xxx → clss} Navigator link were never created,
 * leaving the cls descriptors orphaned (not visible in the Navigator tree).
 *
 * <p>This migration iterates every cls descriptor row on the provider database
 * and, for each unique (hstId, appId, actId) namespace that owns cls objects,
 * ensures:
 * <ol>
 *   <li>The {@code clss} container object exists.</li>
 *   <li>The {@code app-xxx → clss} Navigator link exists (when an {@code app-xxx}
 *       node with the matching AppId is found).</li>
 *   <li>Each {@code clss → <clsId>Cls} child link exists.</li>
 * </ol>
 *
 * Run once per provider via:  GET /Setup?migrate=classes
 *
 * The migration is idempotent: a second run is a no-op.
 */
public class ClssMigration
{
  private static final Logger LOG = Logger.getLogger(ClssMigration.class.getName());

  private static final String CLS_APP_ID  = "domatar";
  private static final String CLS_CLS_ID  = "cls";
  private static final String CLSS_CLS_ID = "clss";
  private static final String CLSS_OBJ_ID = "clss";
  private static final String CLSS_OBJ_NAME = "Classes";

  /**
   * Run the migration against every cls descriptor on this provider's database.
   * Returns a one-line summary suitable for printing to an HTTP response.
   */
  public static String migrateAll() throws DomatarException
  {
    List<Obj> allCls = ObjDb.listClsDescriptors();

    int containersAdded = 0;
    int appLinksAdded   = 0;
    int clsLinksAdded   = 0;
    int errors          = 0;

    Set<String> processedContainers = new HashSet<>();

    for (Obj clsObj : allCls)
    {
      try
      {
        String hstId = clsObj.domId.hstId;
        String appId = clsObj.domId.appId;
        String actId = clsObj.domId.actId;

        // ---- Step 1: ensure clss container + app link (once per namespace) ----

        String containerKey = hstId + "/" + appId + "/" + actId;
        if (!processedContainers.contains(containerKey))
        {
          processedContainers.add(containerKey);

          DomId clssId = new DomId(hstId, appId, actId, CLSS_OBJ_ID);

          boolean wasNew = ObjDb.getObj(clssId) == null;
          ObjDb.addObjIfMissing(clssId, CLS_APP_ID, CLSS_CLS_ID,
                                CLSS_OBJ_NAME, "Class descriptors for " + appId);
          if (wasNew)
          {
            containersAdded++;
            LOG.info("Created clss container on " + hstId + " (appId=" + appId + ")");
          }

          // Wire app-xxx → clss if the app node exists and the link is missing.
          DomId appNode = new DomId(hstId, appId, actId, "app-" + appId);
          if (ObjDb.getObj(appNode) != null
              && LnkDb.getLnk(appNode, clssId, appId, CLSS_CLS_ID) == null)
          {
            LnkDb.addLnk(new Lnk(appNode, clssId,
                                  CLS_APP_ID, CLSS_CLS_ID,
                                  CLSS_OBJ_NAME, "Class descriptors for " + appId,
                                  appId, CLSS_CLS_ID,
                                  null, 99));
            appLinksAdded++;
            LOG.info("Linked app-" + appId + " → clss on " + hstId);
          }
        }

        // ---- Step 2: wire clss → <clsId>Cls link ----

        DomId clssId = new DomId(hstId, appId, actId, CLSS_OBJ_ID);

        if (ObjDb.getObj(clssId) != null
            && LnkDb.getLnk(clssId, clsObj.domId, CLS_APP_ID, CLS_CLS_ID) == null)
        {
          LnkDb.addLnk(new Lnk(clssId, clsObj.domId,
                                CLS_APP_ID, CLS_CLS_ID,
                                clsObj.objName, clsObj.objDesc,
                                CLS_APP_ID, CLS_CLS_ID,
                                clsObj.domId.objId, 0));
          clsLinksAdded++;
        }
      }
      catch (Exception e)
      {
        errors++;
        LOG.warning("ClssMigration failed for " + clsObj.domId + ": " + e.getMessage());
      }
    }

    return "Total cls objects: " + allCls.size()
        + "  Containers added: " + containersAdded
        + "  App links added: "  + appLinksAdded
        + "  Cls links added: "  + clsLinksAdded
        + "  Errors: "           + errors;
  }
}
