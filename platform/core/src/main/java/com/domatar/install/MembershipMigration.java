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
import com.domatar.crypto.Binding;
import com.domatar.db.ActDb;
import com.domatar.db.ObjDb;
import com.domatar.util.Act;
import com.domatar.util.IdGen;
import com.domatar.util.Obj;
import com.domatar.util.ObjAttrs;
import com.domatar.util.DomId;
import com.domatar.util.DomatarException;
import com.domatar.util.DomatarMsgClient;

/**
 * One-time, idempotent backfill of per-provider membership replicas for
 * accounts that pre-date Spec-Login-Multiple (Update-Login-Multiple.txt
 * Phase 4 Task 4.1).
 *
 * <p>Trigger via {@code GET /Setup?action=migrate-membership} (localhost
 * only). Each node migrates only the accounts in its own act table; it
 * does not fan out cross-prv (K6 — reconcile converges later).
 */
public final class MembershipMigration
{
  private static final Logger LOG = Logger.getLogger(MembershipMigration.class.getName());

  private MembershipMigration() {}

  /**
   * Backfill membership replicas for every distinct actId on this node.
   * Idempotent: skips when the membership container already exists.
   *
   * @param msgClient optional; used to publish replica hst rows to the
   *                  directory (may be null — local hst cache still written)
   */
  public static String migrate(final DomatarMsgClient msgClient) throws DomatarException
  {
    final String prvId  = DomatarConfig.getPrvId();
    final String domain = DomatarConfig.getDomain();

    int migrated = 0;
    int skipped  = 0;
    int failed   = 0;
    final StringBuilder failures = new StringBuilder();

    final Set<String> actIds = distinctActIds();

    for (final String actId : actIds)
    {
      try
      {
        final String loginRepId = DomId.subHstId("login", actId, prvId);
        final DomId membershipId = new DomId(loginRepId, "login", actId, "membership");

        if (ObjDb.getObj(membershipId) != null)
        {
          skipped++;
          continue;
        }

        MembershipReplica.ensure(actId, domain != null ? domain : "localhost",
                                 prvId, msgClient);

        backfillPeersFromLoginsDirectory(actId, prvId, loginRepId);
        refreshBindingObj(actId, loginRepId);

        migrated++;
        LOG.info("MembershipMigration migrated actId=" + actId);
      }
      catch (final Exception e)
      {
        failed++;
        failures.append("  actId=").append(actId).append(": ").append(e).append('\n');
        LOG.log(Level.WARNING, "MembershipMigration failed for actId=" + actId, e);
      }
    }

    final StringBuilder summary = new StringBuilder();
    summary.append("MembershipMigration complete (prvId=").append(prvId).append(").\n");
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

  /** Convenience for SetupServlet when no HttpClient is handy. */
  public static String migrate() throws DomatarException
  {
    return migrate(null);
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

  /**
   * For each legacy login-<actId> directory row (cls login), create a peer
   * on the new replica with PrvId=this node.
   */
  private static void backfillPeersFromLoginsDirectory(final String actId,
                                                       final String prvId,
                                                       final String loginRepId)
      throws DomatarException
  {
    final String oldLoginHst = DomId.subHstId("login", actId);
    final List<Obj> rows = ObjDb.getObjPrefix(oldLoginHst, "login", actId, "login", null, 1000);

    if (rows == null || rows.isEmpty())
    {
      // No legacy directory — seed a peer from the act row(s) themselves.
      for (final Act act : ActDb.getAllActs())
      {
        if (!actId.equals(act.actId) || act.usrId == null)
          continue;

        final String appId = extractAppId(act.usrId);
        writePeer(loginRepId, actId, act.usrId,
            act.usrName != null ? act.usrName : act.usrId,
            appId != null ? appId : "login",
            prvId, "True");
      }
      return;
    }

    for (final Obj row : rows)
    {
      if (row == null || row.attrs == null)
        continue;

      final String usrId = row.attrs.getAttr("UsrId");
      if (usrId == null || usrId.isEmpty())
        continue;

      final String usrName = row.attrs.getAttr("UsrName");
      final String appId   = row.attrs.getAttr("AppId");
      final String isRoot  = row.attrs.getAttr("IsRoot");

      writePeer(loginRepId, actId, usrId,
          usrName != null ? usrName : usrId,
          appId != null ? appId : (extractAppId(usrId) != null ? extractAppId(usrId) : "login"),
          prvId,
          isRoot != null ? isRoot : "False");
    }
  }

  private static void writePeer(final String loginRepId,
                                final String actId,
                                final String usrId,
                                final String usrName,
                                final String appId,
                                final String prvId,
                                final String isRoot) throws DomatarException
  {
    final String now = IdGen.getCurTimeBase64();
    final String objId = IdGen.createId("peer", usrId);
    final DomId peerId = new DomId(loginRepId, "login", actId, objId);

    if (ObjDb.getObj(peerId) != null)
      return;

    final ObjAttrs attrs = new ObjAttrs();

    attrs.addAttr("UsrId",       usrId);
    attrs.addAttr("UsrName",     usrName);
    attrs.addAttr("AppId",       appId);
    attrs.addAttr("PrvId",       prvId);
    attrs.addAttr("IsRoot",      isRoot);
    attrs.addAttr("AddedAt",     now);
    attrs.addAttr("PeerVersion", now);
    attrs.addAttr("Tombstone",   "False");

    ObjDb.addObj(new Obj(peerId, "login", "peer", usrName, "peer", attrs));
  }

  /**
   * Ensure the binding obj mirrors ActDb when a verified binding exists.
   */
  private static void refreshBindingObj(final String actId, final String loginRepId)
      throws DomatarException
  {
    final Binding binding = ActDb.getBinding(actId);

    if (binding == null || !binding.verify())
    {
      if (binding != null)
        LOG.warning("MembershipMigration skip binding for actId=" + actId
            + " (Binding.verify failed)");
      return;
    }

    final DomId bindingId = new DomId(loginRepId, "login", actId, "binding");
    final ObjAttrs attrs = new ObjAttrs();

    attrs.addAttr("ActId",         binding.actId);
    attrs.addAttr("GenesisPubKey", binding.genesisPubKeyB64);
    attrs.addAttr("OwnId",         binding.ownId);
    attrs.addAttr("OwnPubKey",     binding.ownPubKeyB64);
    attrs.addAttr("Version",       Long.toString(binding.version));
    attrs.addAttr("NotBefore",     Long.toString(binding.notBefore));
    attrs.addAttr("GenesisSig",    binding.genesisSig);

    final Obj existing = ObjDb.getObj(bindingId);

    if (existing != null)
      ObjDb.modifyObj(existing.modify(null, null, null, null, null, attrs));
    else
      ObjDb.addObj(new Obj(bindingId, "login", "binding",
                           "Binding", "actId->ownId binding (replicated)", attrs));
  }

  private static String extractAppId(final String usrId)
  {
    if (usrId == null)
      return null;
    final int at = usrId.lastIndexOf('@');
    if (at <= 0 || at >= usrId.length() - 1)
      return null;
    return usrId.substring(at + 1);
  }
}
