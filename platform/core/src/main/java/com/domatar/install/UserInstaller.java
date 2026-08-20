/*
 * Copyright (c) 2024 Domatar
 */

package com.domatar.install;

import com.domatar.util.DomatarException;
import com.domatar.util.DomatarMsgClient;

/**
 * Legacy hook once used with {@code AppInstallRegistry}; user install is now
 * driven by the **InstallUser** message and {@link UserInstallDispatch}
 * (Spec-Installation.txt PART 11.6 / Pass 1). Kept as documentation of the parameter
 * bundle; new code should not reference this type.
 *
 * Every app's install class implements (or is wrapped to implement) this
 * shape so handlers stay idempotent.
 *
 * Spec-Installation.txt PART 4.4.
 */
@FunctionalInterface
public interface UserInstaller
{
  /**
   * Creates the per-user objects, sub-host, and Navigator links for one
   * application on one user account.
   *
   * @param actId     the new user's account ID (e.g. "dave@quippin")
   * @param usrId     the user's login identity  (e.g. "dave@quippin")
   * @param usrName   the user's display name    (e.g. "Dave")
   * @param prvId     the provider ID            (e.g. "prv1")
   * @param domain    the provider's domain      (e.g. "quippin:8080")
   * @param msgClient message client for side-effect dispatches
   */
  void installUser(String actId,
                   String usrId,
                   String usrName,
                   String prvId,
                   String domain,
                   DomatarMsgClient msgClient) throws DomatarException;
}
