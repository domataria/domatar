/*
 * Copyright (c) 2024 Domatar
 */

package com.domatar.install;

import com.domatar.util.DomatarException;
import com.domatar.util.DomatarMsgClient;

/**
 * SPI implemented by every app's install class.
 *
 * Instances are created reflectively by AppLoader from the class named in
 * the app's META-INF/domatar/app.manifest (InstallClass key).
 * Implementations must have a public no-arg constructor.
 */
public interface AppInstall
{
  /**
   * Per-provider install.  Idempotent.  Called by PlatformSetupServlet once
   * per app per provider.  Must register the app in the catalog via
   * CatalogInstall.registerInCatalog.  May also seed any provider-scoped
   * hosts the app owns.
   */
  void installProvider(String prvId, String domain) throws DomatarException;

  /**
   * Per-user install.  Idempotent.  Called by AppUserInstallHandler (via the
   * (appId, "install") handler) when InstallUser is dispatched to this app on
   * the user's sub-host.
   */
  void installUser(String actId,
                   String usrId,
                   String usrName,
                   String prvId,
                   String domain,
                   DomatarMsgClient msgClient) throws DomatarException;

  /**
   * Optional extra HstId segment after actId (Spec-AppStore PART 9.3).
   * null/empty → portable {@code <appId>-<actId>}. Must not contain
   * {@link com.domatar.util.DomId#HOST_SEP}. Must NOT encode prvId for the
   * marketplace — ordinary installs stay movable by export/import.
   */
  default String userHostIdExtension(String actId,
                                     String usrId,
                                     String prvId)
  {
    return null;
  }
}
