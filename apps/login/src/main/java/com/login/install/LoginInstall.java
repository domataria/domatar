/*
 * Copyright (c) 2024 Domatar
 */

package com.login.install;

import com.domatar.db.LnkDb;
import com.domatar.install.AppInstall;
import com.domatar.install.CatalogInstall;
import com.domatar.install.ClsInstall;
import com.domatar.install.MembershipReplica;
import com.domatar.install.NavAppEntry;
import com.domatar.install.SrvInstall;
import com.domatar.util.Lnk;
import com.domatar.util.DomId;
import com.domatar.util.DomatarException;
import com.domatar.util.DomatarMsgClient;

/**
 * Install routine for the Login app (Spec-Navigator PART 10.1;
 * Spec-Login-Multiple cutover K11).
 *
 * <p>Post-cutover: creates ONLY the per-provider replica
 * {@code login-&lt;actId&gt;-&lt;prvId&gt;} with app-login + membership.
 * The legacy {@code login-&lt;actId&gt;} host is no longer created.
 *
 * Every step is idempotent: a second call is a no-op.
 */
public class LoginInstall implements AppInstall
{
  @Override
  public void installProvider(final String prvId, final String domain)
      throws DomatarException
  {
    CatalogInstall.registerInCatalog(prvId, "login");
  }

  @Override
  public void installUser(final String actId,
                          final String usrId,
                          final String usrName,
                          final String prvId,
                          final String domain,
                          final DomatarMsgClient msgClient) throws DomatarException
  {
    install(actId, usrName, domain, prvId, msgClient);
  }

  private static void install(final String actId,
                               final String usrName,
                               final String domain,
                               final String prvId,
                               final DomatarMsgClient msgClient) throws DomatarException
  {
    // 1. Membership replica on login-<actId>-<prvId> (hst + membership + binding).
    MembershipReplica.ensure(actId, domain, prvId, msgClient);

    final String loginRepId = DomId.subHstId("login", actId, prvId);
    final String navHstId   = DomId.subHstId("navigator", actId, prvId);

    final DomId rootId       = new DomId(navHstId,   "navigator", actId, "root");
    final DomId appLoginId   = new DomId(loginRepId, "login",     actId, "app-login");
    final DomId membershipId = new DomId(loginRepId, "login",     actId, "membership");

    // 2–3. app-login on the replica, class (login, app), linked from root.
    NavAppEntry.ensureRootLnk(rootId, appLoginId, "login",
        "Login", "Manage your Domatar identity", 2);

    // 4. app-login -> membership (seqNum 1).
    if (LnkDb.getLnk(appLoginId, membershipId, "navigator", "container") == null)
      LnkDb.addLnk(new Lnk(appLoginId, membershipId,
                            "login", "membership",
                            "Membership", "Providers and logins linked to this account",
                            "navigator", "container",
                            null, 1));

    // 5. Services / class containers on app-login.
    SrvInstall.ensureSrvsContainer(appLoginId,
        "Service descriptors for Login", "login", 2);
    ClsInstall.ensureClssContainer(appLoginId,
        "Class descriptors for Login", "login", 3);
  }
}
