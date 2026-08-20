/*
 * Copyright (c) 2024 Domatar
 */

package com.domatar.install;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.domatar.core.DomatarConfig;
import com.domatar.db.ActDb;
import com.domatar.db.HstDb;
import com.domatar.db.LnkDb;
import com.domatar.db.ObjDb;
import com.domatar.util.Act;
import com.domatar.util.Hst;
import com.domatar.util.Lnk;
import com.domatar.util.Obj;
import com.domatar.util.DomId;
import com.domatar.util.DomatarException;
import com.domatar.util.DomatarMsgClient;

/**
 * Phase 5 cutover: move app-login onto the membership replica and DELETE
 * the legacy {@code login-&lt;actId&gt;} host (Update-Login-Multiple.txt
 * Task 5.0f). Idempotent per node.
 */
public final class LoginHostCutover
{
  private static final Logger LOG = Logger.getLogger(LoginHostCutover.class.getName());

  private LoginHostCutover() {}

  public static String cutover() throws DomatarException
  {
    return cutover(null);
  }

  public static String cutover(final DomatarMsgClient msgClient) throws DomatarException
  {
    final String prvId  = DomatarConfig.getPrvId();
    final String domain = DomatarConfig.getDomain();

    int migrated = 0;
    int skipped  = 0;
    int failed   = 0;
    final StringBuilder failures = new StringBuilder();

    for (final String actId : distinctActIds())
    {
      try
      {
        final String oldLoginHst = DomId.subHstId("login", actId);

        if (HstDb.getHst(oldLoginHst) == null)
        {
          skipped++;
          continue;
        }

        cutoverOne(actId, prvId, domain != null ? domain : "localhost", msgClient);
        migrated++;
        LOG.info("LoginHostCutover migrated actId=" + actId);
      }
      catch (final Exception e)
      {
        failed++;
        failures.append("  actId=").append(actId).append(": ").append(e).append('\n');
        LOG.log(Level.WARNING, "LoginHostCutover failed for actId=" + actId, e);
      }
    }

    try
    {
      final int orphans = purgeOrphanLoginHosts(prvId);
      if (orphans > 0)
        LOG.info("LoginHostCutover purged orphan login hosts=" + orphans);
    }
    catch (final Exception e)
    {
      failed++;
      failures.append("  orphan-purge: ").append(e).append('\n');
    }

    final StringBuilder summary = new StringBuilder();
    summary.append("LoginHostCutover complete (prvId=").append(prvId).append(").\n");
    summary.append("  migrated=").append(migrated)
           .append("  skipped=").append(skipped)
           .append("  failed=").append(failed).append('\n');
    if (failures.length() > 0)
    {
      summary.append("Failures:\n");
      summary.append(failures);
    }

    LOG.info(summary.toString());
    return summary.toString();
  }

  private static void cutoverOne(final String actId,
                                 final String prvId,
                                 final String domain,
                                 final DomatarMsgClient msgClient) throws DomatarException
  {
    MembershipReplica.ensure(actId, domain, prvId, msgClient);

    final String loginRepId  = DomId.subHstId("login", actId, prvId);
    final String oldLoginHst = DomId.subHstId("login", actId);
    final String navHstId    = DomId.subHstId("navigator", actId);

    final DomId rootId       = new DomId(navHstId,   "navigator", actId, "root");
    final DomId appLoginRep  = new DomId(loginRepId, "login",     actId, "app-login");
    final DomId membershipId = new DomId(loginRepId, "login",     actId, "membership");

    // 1. Ensure app-login on replica + link to membership.
    ObjDb.addObjIfMissing(appLoginRep, "domatar", "app", "Login",
        "Manage your Domatar identity");

    if (LnkDb.getLnk(appLoginRep, membershipId, "navigator", "container") == null)
      LnkDb.addLnk(new Lnk(appLoginRep, membershipId,
                            "login", "membership",
                            "Membership", "Providers and logins linked to this account",
                            "navigator", "container",
                            null, 1));

    SrvInstall.ensureSrvsContainer(appLoginRep,
        "Service descriptors for Login", "login", 2);
    ClsInstall.ensureClssContainer(appLoginRep,
        "Class descriptors for Login", "login", 3);

    // Provider-admin accounts container: move onto the replica when present.
    moveAccountsIfPresent(actId, oldLoginHst, loginRepId, appLoginRep);

    // Also re-link actManager from the replica app-login if the old link exists.
    moveActManagerLinkIfPresent(actId, oldLoginHst, appLoginRep);

    // 2. Repoint navigator root -> app-login@replica when this node hosts root.
    //    PK is (from, tag, seqNum, val) WITHOUT the target — must remove any
    //    existing root->app-login (seq 2) BEFORE inserting the replica one.
    if (ObjDb.getObj(rootId) != null)
    {
      final List<Lnk> rootApps = LnkDb.getLnks(rootId, "navigator", "app", null, null, 100, false);

      for (final Lnk existing : rootApps)
      {
        if (existing == null || existing.lnkDomId == null)
          continue;
        if (existing.seqNum != 2)
          continue;
        if (!"app-login".equals(existing.lnkDomId.objId))
          continue;
        if (appLoginRep.equals(existing.lnkDomId))
          continue;

        LnkDb.deleteLnks(rootId, existing.lnkDomId, "navigator", "app", null, null);
      }

      if (LnkDb.getLnk(rootId, appLoginRep, "navigator", "app") == null)
        LnkDb.addLnk(new Lnk(rootId, appLoginRep,
                              "domatar", "app",
                              "Login", "Manage your Domatar identity",
                              "navigator", "app",
                              null, 2));
    }

    // 3. DELETE entire old login-<actId> host (lnks first, then objs, then hst).
    deleteOldLoginHost(oldLoginHst);
  }

  private static void moveAccountsIfPresent(final String actId,
                                            final String oldLoginHst,
                                            final String loginRepId,
                                            final DomId appLoginRep) throws DomatarException
  {
    final DomId oldAccounts = new DomId(oldLoginHst, "login", actId, "accounts");
    final Obj existing = ObjDb.getObj(oldAccounts);

    if (existing == null)
      return;

    final DomId newAccounts = new DomId(loginRepId, "login", actId, "accounts");

    if (ObjDb.getObj(newAccounts) == null)
      ObjDb.addObjIfMissing(newAccounts, "login", "accounts",
          existing.objName != null ? existing.objName : "Accounts",
          existing.objDesc != null ? existing.objDesc : "All accounts on this provider",
          existing.attrs);

    if (LnkDb.getLnk(appLoginRep, newAccounts, "navigator", "container") == null)
      LnkDb.addLnk(new Lnk(appLoginRep, newAccounts,
                            "login", "accounts",
                            "Accounts", "All accounts on this provider",
                            "navigator", "container",
                            null, 3));
  }

  private static void moveActManagerLinkIfPresent(final String actId,
                                                  final String oldLoginHst,
                                                  final DomId appLoginRep) throws DomatarException
  {
    final DomId oldAppLogin = new DomId(oldLoginHst, "login", actId, "app-login");
    final DomId actMgrId = new DomId(DomId.subHstId("domatar", actId),
                                      "domatar", actId, "actManager");

    if (ObjDb.getObj(actMgrId) == null)
      return;

    // Only relevant for the provider account (actManager on domatar-<prvActId>).
    if (LnkDb.getLnk(appLoginRep, actMgrId, "navigator", "container") == null
        && LnkDb.getLnk(oldAppLogin, actMgrId, "navigator", "container") != null)
    {
      LnkDb.addLnk(new Lnk(appLoginRep, actMgrId,
                            "act", "actManager",
                            "ActManager", "Account manager",
                            "navigator", "container",
                            null, 2));
    }
  }

  private static void deleteOldLoginHost(final String oldLoginHst) throws DomatarException
  {
    LnkDb.deleteByHstId(oldLoginHst);
    ObjDb.deleteByHstId(oldLoginHst);

    if (HstDb.getHst(oldLoginHst) != null)
      HstDb.deleteHst(oldLoginHst);
  }

  /**
   * Best-effort: delete leftover {@code login-&lt;actId&gt;} hosts that have no
   * matching act row (orphans). Does not touch {@code login-*-&lt;prvId&gt;} replicas.
   */
  private static int purgeOrphanLoginHosts(final String prvId) throws DomatarException
  {
    int purged = 0;
    final List<Hst> all = HstDb.getAllHsts();
    final Set<String> localActs = distinctActIds();

    for (final Hst h : all)
    {
      if (h == null || h.hstId == null)
        continue;
      if (!isLegacyLoginHost(h.hstId, prvId))
        continue;

      final String actId = h.hstId.substring("login-".length());
      if (localActs.contains(actId))
        continue;

      deleteOldLoginHost(h.hstId);
      purged++;
    }

    return purged;
  }

  /**
   * True for {@code login-&lt;actId&gt;} (no provider suffix). False for
   * {@code login-&lt;actId&gt;-&lt;prvId&gt;} replicas.
   */
  static boolean isLegacyLoginHost(final String hstId, final String prvId)
  {
    if (hstId == null || !hstId.startsWith("login-"))
      return false;
    if (prvId != null && hstId.endsWith("-" + prvId))
      return false;
    // Replica suffix pattern: ...-prvN
    if (hstId.matches("login-.+-prv\\d+$"))
      return false;
    return true;
  }

  private static Set<String> distinctActIds() throws DomatarException
  {
    final Set<String> ids = new LinkedHashSet<String>();

    for (final Act act : ActDb.getAllActs())
    {
      if (act.actId != null && !act.actId.isEmpty())
        ids.add(act.actId);
    }

    return ids;
  }
}
