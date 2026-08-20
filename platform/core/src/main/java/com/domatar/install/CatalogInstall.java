/*
 * Copyright (c) 2024 Domatar
 */

package com.domatar.install;

import com.domatar.app.App;
import com.domatar.app.AppRegistry;
import com.domatar.core.AppConfig;
import com.domatar.core.DomatarConfig;
import com.domatar.db.LnkDb;
import com.domatar.db.ObjDb;
import com.domatar.util.Lnk;
import com.domatar.util.ObjAttrs;
import com.domatar.util.DomId;
import com.domatar.util.DomatarException;

/**
 * Shared helpers for creating and populating the app catalog on the
 * Domatar App sub-host (Spec-Installation.txt PART 7.3).
 *
 * The catalog lives at:
 *   domatar-&lt;prvActId&gt; / domatar / &lt;prvActId&gt; / app-catalog
 *
 * Each installed app has a catalog-entry child object:
 *   domatar-&lt;prvActId&gt; / domatar / &lt;prvActId&gt; / catalog-&lt;appId&gt;
 *   ClsAppId=domatar, ClsId=catalogEntry
 *   Attrs: { AppId, AppName, AppDesc, Version }
 *
 * All calls are idempotent.
 */
public class CatalogInstall
{
  /**
   * Resolves the provider actId. Uses the fingerprint from config when
   * available (post-Phase-2 bootstrap), otherwise falls back to the
   * legacy {@code prvId+"@"+prvId} format (pre-migration / test seeds).
   */
  private static String resolvePrvActId(final String prvId)
  {
    final String configured = DomatarConfig.getPrvActId();
    if (configured != null && !configured.isEmpty())
      return configured;
    return prvId + "@" + prvId;
  }
  // -------------------------------------------------------------------------
  // Catalog container
  // -------------------------------------------------------------------------

  /**
   * Ensures the app-catalog container object exists on the Domatar App
   * sub-host and is linked from app-domatar (seqNum 3, after Hosts and Classes).
   *
   * @param prvId   the provider ID (e.g. "prv1")
   * @param domain  the provider's domain (for sub-host registration)
   */
  public static void ensureAppCatalog(String prvId, String domain) throws DomatarException
  {
    String prvActId      = resolvePrvActId(prvId);
    String ssHstId       = DomId.subHstId("domatar", prvActId);

    DomId  appDomatarId  = new DomId(ssHstId, "domatar", prvActId, "app-domatar");
    DomId  catalogId     = new DomId(ssHstId, "domatar", prvActId, "app-catalog");

    ObjDb.addObjIfMissing(catalogId,
        "domatar", "catalog",
        "App Catalog", "Applications installed on this provider");

    if (LnkDb.getLnk(appDomatarId, catalogId, "navigator", "container") == null)
      LnkDb.addLnk(new Lnk(appDomatarId, catalogId,
                            "domatar", "catalog",
                            "App Catalog", "Applications installed on this provider",
                            "navigator", "container",
                            null, 3));
  }

  // -------------------------------------------------------------------------
  // Catalog entry registration
  // -------------------------------------------------------------------------

  /**
   * Registers an application in the provider's app catalog.
   * Reads AppName, AppDesc, and Version from the app's app.config.txt.
   * If no config is found, falls back to appId as the name.
   *
   * @param prvId   the provider ID (e.g. "prv1")
   * @param appId   the application's appId
   */
  public static void registerInCatalog(String prvId,
                                        String appId) throws DomatarException
  {
    App    app = AppRegistry.get(appId);
    ClassLoader cl = (app != null) ? app.classLoader
                                   : Thread.currentThread().getContextClassLoader();
    AppConfig cfg     = AppConfig.load(appId, cl);
    String    appName = (cfg != null) ? cfg.getAppName() : appId;
    String    appDesc = (cfg != null) ? cfg.getAppDesc() : "";
    String    version = (cfg != null) ? cfg.getVersion() : "1.0";

    registerInCatalog(prvId, appId, appName, appDesc, version);
  }

  /**
   * Registers an application in the provider's app catalog with explicit values.
   * Creates the catalog-entry object and links it from app-catalog.
   *
   * @param prvId   the provider ID
   * @param appId   the application's appId
   * @param appName human-readable name
   * @param appDesc short description
   * @param version version string
   */
  public static void registerInCatalog(String prvId,
                                        String appId,
                                        String appName,
                                        String appDesc,
                                        String version) throws DomatarException
  {
    String prvActId  = resolvePrvActId(prvId);
    String ssHstId   = DomId.subHstId("domatar", prvActId);

    DomId catalogId = new DomId(ssHstId, "domatar", prvActId, "app-catalog");
    DomId entryId   = new DomId(ssHstId, "domatar", prvActId, "catalog-" + appId);

    ObjAttrs attrs = new ObjAttrs();
    attrs.addAttr("AppId",   appId);
    attrs.addAttr("AppName", appName);
    attrs.addAttr("AppDesc", appDesc);
    attrs.addAttr("Version", version);

    ObjDb.addObjIfMissing(entryId,
        "domatar", "catalogEntry",
        appName, appDesc, attrs);

    if (LnkDb.getLnk(catalogId, entryId, "domatar", "catalogEntry") == null)
      LnkDb.addLnk(new Lnk(catalogId, entryId,
                            "domatar", "catalogEntry",
                            appName, appDesc,
                            "domatar", "catalogEntry",
                            appId, 0));
  }

  /**
   * True if {@code appId} already has a catalog-entry link under app-catalog
   * (used by per-app {@code /Setup} idempotence, Spec-Installation.txt PART 11.11 Pass 2).
   */
  public static boolean hasCatalogEntry(String prvId, String appId)
      throws DomatarException
  {
    String prvActId  = resolvePrvActId(prvId);
    String ssHstId   = DomId.subHstId("domatar", prvActId);

    DomId catalogId = new DomId(ssHstId, "domatar", prvActId, "app-catalog");
    DomId entryId   = new DomId(ssHstId, "domatar", prvActId, "catalog-" + appId);

    return LnkDb.getLnk(catalogId, entryId, "domatar", "catalogEntry") != null;
  }
}
