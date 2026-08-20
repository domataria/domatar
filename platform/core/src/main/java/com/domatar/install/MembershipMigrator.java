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
import com.domatar.db.LnkDb;
import com.domatar.db.ObjDb;
import com.domatar.util.Act;
import com.domatar.util.Base64Encoder;
import com.domatar.util.IdGen;
import com.domatar.util.Lnk;
import com.domatar.util.Obj;
import com.domatar.util.ObjAttrs;
import com.domatar.util.DomId;
import com.domatar.util.DomatarException;
import com.domatar.util.DomatarMsgClient;

/**
 * Peer / binding storage on the user domatar substrate membership
 * container (Update-Mandatory-App-Rewrite.txt Phase 4).
 *
 * <p>Login-home peer ObjId is {@code peer-&lt;usrId&gt;}; object-only is
 * {@code peer-prv-&lt;prvId&gt;} (Spec-Foreign-Provider-Installation.txt PART 8).
 */
public final class MembershipMigrator
{
  private static final Logger LOG =
      Logger.getLogger(MembershipMigrator.class.getName());

  private MembershipMigrator() {}

  /**
   * Copy peers (+ binding seed) from the local login replica into domatar
   * membership when the substrate membership has no peer rows yet.
   */
  public static String copyFromLoginReplica(final String actId,
                                            final String prvId,
                                            final DomatarMsgClient msgClient)
      throws DomatarException
  {
    if (actId == null || prvId == null)
      throw new DomatarException("copyFromLoginReplica requires actId, prvId");

    final String domain = DomatarConfig.getDomain();

    if (domain == null || domain.isEmpty())
      throw new DomatarException("copyFromLoginReplica requires Domain");

    final Act act = ActDb.getAct(actId);
    final String usrId = act != null ? act.usrId : actId;
    final String usrName = act != null ? act.usrName : actId;

    UserSubstrateInstall.ensureUserSubstrate(
        actId, usrId, usrName, prvId, domain, msgClient);

    final DomId domatarMem = UserSubstrateIds.membership(actId, prvId);
    final String loginHst = DomId.subHstId("login", actId, prvId);
    final List<Obj> loginPeers = ObjDb.getObjPrefix(loginHst, "login", actId, "peer",
        null, 1000);

    int written = 0;
    int skipped = 0;

    if (loginPeers != null)
    {
      for (final Obj row : loginPeers)
      {
        if (!"login".equals(row.clsAppId) || !"peer".equals(row.clsId))
          continue;

        final ObjAttrs a = row.attrs;

        if (a == null)
          continue;

        final String peerUsrId = a.getAttr("UsrId");

        if (peerUsrId == null || peerUsrId.isEmpty())
          continue;

        if (upsertPeer(domatarMem,
            peerUsrId,
            a.getAttr("UsrName") != null ? a.getAttr("UsrName") : peerUsrId,
            a.getAttr("AppId"),
            a.getAttr("PrvId"),
            a.getAttr("IsRoot") != null ? a.getAttr("IsRoot") : "False",
            a.getAttr("AddedAt") != null ? a.getAttr("AddedAt") : IdGen.getCurTimeBase64(),
            a.getAttr("PeerVersion") != null ? a.getAttr("PeerVersion")
                : IdGen.getCurTimeBase64(),
            a.getAttr("Tombstone") != null ? a.getAttr("Tombstone") : "False",
            a.getAttr("FpVersion")))
          written++;
        else
          skipped++;
      }
    }

    seedBindingFromAct(actId, prvId);

    return "copyFromLoginReplica actId=" + actId + " prvId=" + prvId
        + " written=" + written + " skipped=" + skipped;
  }

  /** Backfill every act on this node. */
  public static String migrateAll(final DomatarMsgClient msgClient)
      throws DomatarException
  {
    final String prvId = DomatarConfig.getPrvId();
    final Set<String> actIds = distinctActIds();
    int ok = 0;
    int failed = 0;
    final StringBuilder failures = new StringBuilder();
    final StringBuilder details = new StringBuilder();

    for (final String actId : actIds)
    {
      try
      {
        final String line = copyFromLoginReplica(actId, prvId, msgClient);

        details.append(line).append('\n');
        ok++;
      }
      catch (final Exception e)
      {
        failed++;
        failures.append("  actId=").append(actId).append(": ").append(e).append('\n');
        LOG.log(Level.WARNING, "MembershipMigrator failed for " + actId, e);
      }
    }

    final StringBuilder summary = new StringBuilder();

    summary.append("MembershipMigrator.migrateAll complete (prvId=").append(prvId)
        .append(").\n");
    summary.append("  ok=").append(ok).append("  failed=").append(failed).append('\n');
    summary.append(details);

    if (failures.length() > 0)
    {
      summary.append("Failures:\n");
      summary.append(failures);
    }

    LOG.info(summary.toString());
    return summary.toString();
  }

  /**
   * LWW upsert of a peer row on the domatar membership container.
   * Returns true when the local row was written.
   */
  public static boolean upsertPeer(final DomId membershipDst,
                                   final String usrId,
                                   final String usrName,
                                   final String appId,
                                   final String prvId,
                                   final String isRoot,
                                   final String addedAt,
                                   final String peerVersion,
                                   final String tombstone,
                                   final String fpVersion)
      throws DomatarException
  {
    if (membershipDst == null || usrId == null || appId == null || prvId == null)
      return false;

    final String objId = IdGen.createId("peer", usrId);
    final DomId rowDomId = new DomId(membershipDst.hstId, "domatar",
        membershipDst.actId, objId);
    final Obj existing = ObjDb.getObj(rowDomId);

    if (existing != null)
    {
      final String storedVersion = existing.attrs != null
          ? existing.attrs.getAttr("PeerVersion") : null;

      if (comparePeerVersion(peerVersion, storedVersion) <= 0)
        return false;
    }

    final String resolvedFp = resolveFpVersion(fpVersion, membershipDst.actId);
    final ObjAttrs rowAttrs = new ObjAttrs();

    rowAttrs.addAttr("UsrId", usrId);
    rowAttrs.addAttr("UsrName", usrName != null ? usrName : usrId);
    rowAttrs.addAttr("AppId", appId);
    rowAttrs.addAttr("PrvId", prvId);
    rowAttrs.addAttr("IsLoginHome", "True");
    rowAttrs.addAttr("IsRoot", isRoot != null ? isRoot : "False");
    rowAttrs.addAttr("AddedAt", addedAt != null ? addedAt : IdGen.getCurTimeBase64());
    rowAttrs.addAttr("PeerVersion",
        peerVersion != null ? peerVersion : IdGen.getCurTimeBase64());
    rowAttrs.addAttr("Tombstone", tombstone != null ? tombstone : "False");
    rowAttrs.addAttr("FpVersion", resolvedFp);

    final String objName = usrName != null ? usrName : usrId;
    final String objDesc = "Login at " + appId + " on " + prvId;

    if (existing != null)
      ObjDb.modifyObj(existing.modify(null, null, null, objName, objDesc, rowAttrs));
    else
    {
      ObjDb.addObj(new Obj(rowDomId, "domatar", "peer", objName, objDesc, rowAttrs));

      if (LnkDb.getLnk(membershipDst, rowDomId, "domatar", "peer") == null)
        LnkDb.addLnk(new Lnk(membershipDst, rowDomId,
            "domatar", "peer",
            objName, objDesc,
            "domatar", "peer",
            usrId, 0));
    }

    bumpContainerVersion(membershipDst,
        peerVersion != null ? peerVersion : IdGen.getCurTimeBase64());
    return true;
  }

  /**
   * LWW upsert of an object-only host peer ({@code peer-prv-&lt;prvId&gt;}).
   * No UsrId / AppId. Returns true when the local row was written.
   */
  public static boolean upsertObjectOnlyPeer(final DomId membershipDst,
                                             final String prvId,
                                             final String addedAt,
                                             final String peerVersion,
                                             final String tombstone,
                                             final String fpVersion)
      throws DomatarException
  {
    if (membershipDst == null || prvId == null || prvId.isEmpty())
      return false;

    final DomId rowDomId = ObjectOnlyPeers.rowDomId(membershipDst, prvId);
    final Obj existing = ObjDb.getObj(rowDomId);

    if (existing != null)
    {
      final String storedVersion = existing.attrs != null
          ? existing.attrs.getAttr("PeerVersion") : null;

      if (comparePeerVersion(peerVersion, storedVersion) <= 0)
        return false;
    }

    final String resolvedFp = resolveFpVersion(fpVersion, membershipDst.actId);
    final ObjAttrs rowAttrs = new ObjAttrs();

    rowAttrs.addAttr("PrvId", prvId);
    rowAttrs.addAttr("IsLoginHome", "False");
    rowAttrs.addAttr("IsSigning", "False");
    rowAttrs.addAttr("IsRoot", "False");
    rowAttrs.addAttr("AddedAt", addedAt != null ? addedAt : IdGen.getCurTimeBase64());
    rowAttrs.addAttr("PeerVersion",
        peerVersion != null ? peerVersion : IdGen.getCurTimeBase64());
    rowAttrs.addAttr("Tombstone", tombstone != null ? tombstone : "False");
    rowAttrs.addAttr("FpVersion", resolvedFp);

    final String objName = "Host on " + prvId;
    final String objDesc = "Object-only host (no sign-in)";

    if (existing != null)
      ObjDb.modifyObj(existing.modify(null, null, null, objName, objDesc, rowAttrs));
    else
    {
      ObjDb.addObj(new Obj(rowDomId, "domatar", "peer", objName, objDesc, rowAttrs));

      if (LnkDb.getLnk(membershipDst, rowDomId, "domatar", "peer") == null)
        LnkDb.addLnk(new Lnk(membershipDst, rowDomId,
            "domatar", "peer",
            objName, objDesc,
            "domatar", "peer",
            prvId, 0));
    }

    bumpContainerVersion(membershipDst,
        peerVersion != null ? peerVersion : IdGen.getCurTimeBase64());
    return true;
  }

  /** Dual-write helper for login MembershipImpl after a successful peer op. */
  public static void dualWritePeer(final String actId,
                                   final String homePrvId,
                                   final String usrId,
                                   final String usrName,
                                   final String appId,
                                   final String peerPrvId,
                                   final String isRoot,
                                   final String addedAt,
                                   final String peerVersion,
                                   final String tombstone,
                                   final String fpVersion,
                                   final DomatarMsgClient msgClient)
  {
    if (actId == null || homePrvId == null || usrId == null)
      return;

    try
    {
      final String domain = DomatarConfig.getDomain();

      if (domain == null || domain.isEmpty())
        return;

      final Act act = ActDb.getAct(actId);
      final String seedUsr = act != null ? act.usrId : actId;
      final String seedName = act != null ? act.usrName : actId;

      UserSubstrateInstall.ensureUserSubstrate(
          actId, seedUsr, seedName, homePrvId, domain, msgClient);

      upsertPeer(UserSubstrateIds.membership(actId, homePrvId),
          usrId, usrName, appId, peerPrvId, isRoot, addedAt, peerVersion,
          tombstone, fpVersion);
    }
    catch (final Exception e)
    {
      System.out.println("WARN: MembershipMigrator.dualWritePeer failed for actId="
          + actId + " usrId=" + usrId + ": " + e);
    }
  }

  public static void seedBindingFromAct(final String actId, final String prvId)
      throws DomatarException
  {
    final DomId bindingId = UserSubstrateIds.binding(actId, prvId);
    final Obj existing = ObjDb.getObj(bindingId);

    if (existing != null && existing.attrs != null
        && existing.attrs.getAttr("GenesisSig") != null)
      return;

    final Binding binding = ActDb.getBinding(actId);

    if (binding == null)
      return;

    final ObjAttrs attrs = new ObjAttrs();

    attrs.addAttr("ActId", binding.actId);
    attrs.addAttr("GenesisPubKey", binding.genesisPubKeyB64);
    attrs.addAttr("OwnId", binding.ownId);
    attrs.addAttr("OwnPubKey", binding.ownPubKeyB64);
    attrs.addAttr("Version", Long.toString(binding.version));
    attrs.addAttr("NotBefore", Long.toString(binding.notBefore));
    attrs.addAttr("GenesisSig", binding.genesisSig);

    if (existing != null)
      ObjDb.modifyObj(existing.modify(null, null, null, null, null, attrs));
    else
      ObjDb.addObj(new Obj(bindingId, "domatar", "binding",
          "Binding", "actId->ownId binding", attrs));
  }

  static void bumpContainerVersion(final DomId containerDomId,
                                   final String peerVersion)
      throws DomatarException
  {
    if (peerVersion == null)
      return;

    final Obj container = ObjDb.getObj(containerDomId);

    if (container == null)
      return;

    final String current = container.attrs != null
        ? container.attrs.getAttr("Version") : null;

    if (comparePeerVersion(peerVersion, current) <= 0)
      return;

    final ObjAttrs attrs = container.attrs != null ? container.attrs : new ObjAttrs();

    attrs.addAttr("Version", peerVersion);
    ObjDb.modifyObj(container.modify(null, null, null, null, null, attrs));
  }

  public static String readContainerVersion(final DomId containerDomId)
      throws DomatarException
  {
    final Obj container = ObjDb.getObj(containerDomId);

    if (container == null || container.attrs == null)
      return "";

    final String version = container.attrs.getAttr("Version");

    return version != null ? version : "";
  }

  public static int comparePeerVersion(final String a, final String b)
  {
    if (a == null && b == null)
      return 0;

    if (a == null || a.isEmpty())
      return -1;

    if (b == null || b.isEmpty())
      return 1;

    try
    {
      final long la = Base64Encoder.decodeToLong(a);
      final long lb = Base64Encoder.decodeToLong(b);

      if (la >= 0 && lb >= 0)
        return Long.compare(la, lb);
    }
    catch (final Exception ignored)
    {
      // fall through
    }

    return a.compareTo(b);
  }

  private static String resolveFpVersion(final String stored, final String actId)
      throws DomatarException
  {
    if (stored != null && !stored.isEmpty())
      return stored;

    return Integer.toString(ActDb.getFpVersion(actId));
  }

  private static Set<String> distinctActIds() throws DomatarException
  {
    final Set<String> ids = new LinkedHashSet<>();
    final List<Act> acts = ActDb.getAllActs();

    if (acts == null)
      return ids;

    for (final Act act : acts)
    {
      if (act != null && act.actId != null && !act.actId.isEmpty())
        ids.add(act.actId);
    }

    return ids;
  }
}
