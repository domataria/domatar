/*
 * Copyright (c) 2024 Domatar
 */

package com.domatar.act;

import com.domatar.core.DomatarConfig;
import com.domatar.crypto.AccountKeys;
import com.domatar.crypto.Binding;
import com.domatar.crypto.Delegation;
import com.domatar.crypto.MasterKey;
import com.domatar.db.ActDb;
import com.domatar.install.MembershipFanout;
import com.domatar.install.MembershipMigrator;
import com.domatar.install.UserSubstrateIds;
import com.domatar.util.IdGen;
import com.domatar.util.JsonMsg;
import com.domatar.util.ObjAttrs;
import com.domatar.util.DomId;
import com.domatar.util.DomatarException;
import com.domatar.util.DomatarMsgClient;

/**
 * Home-side driver: issue a Delegation for N and HostProvision that
 * provider as an object-only host (KD4 / KD13).
 */
public final class HostProvisionDriver
{
  private HostProvisionDriver() {}

  public static void ensure(final String actId, final String nPrvId,
                            final String nDomain, final DomatarMsgClient msgClient,
                            final JsonMsg inMsg)
      throws DomatarException
  {
    if (actId == null || nPrvId == null || nDomain == null)
      throw new DomatarException(
          "HostProvisionDriver.ensure requires actId, nPrvId, nDomain");

    if (msgClient == null)
      throw new DomatarException("HostProvisionDriver.ensure requires msgClient");

    if (inMsg == null)
      throw new DomatarException("HostProvisionDriver.ensure requires inMsg");

    if (HostPresence.hasPeer(actId, nPrvId))
      return;

    final Binding binding = ActDb.getBinding(actId);

    if (binding == null || !binding.verify())
      throw new DomatarException("Local binding missing or invalid");

    final String sealedOwn = ActDb.getOwnPrvKey(actId);

    if (sealedOwn == null)
      throw new DomatarException(
          "No ownership key on this provider — cannot provision host");

    byte[] rawOwn = null;
    final Delegation deleg;

    try
    {
      rawOwn = MasterKey.open(sealedOwn);
      final AccountKeys own = AccountKeys.fromPrivKey(rawOwn);
      final long notAfter = System.currentTimeMillis() + DomatarConfig.getDelegTtlMs();

      deleg = Delegation.issue(actId, own, nPrvId, notAfter);
    }
    finally
    {
      if (rawOwn != null)
        java.util.Arrays.fill(rawOwn, (byte) 0);
    }

    final ObjAttrs attrs = new ObjAttrs();

    attrs.addAttr("ActId",                actId);
    attrs.addAttr("BindingActId",         binding.actId);
    attrs.addAttr("BindingGenesisPubKey", binding.genesisPubKeyB64);
    attrs.addAttr("BindingOwnId",         binding.ownId);
    attrs.addAttr("BindingOwnPubKey",     binding.ownPubKeyB64);
    attrs.addAttr("BindingVersion",       Long.toString(binding.version));
    attrs.addAttr("BindingNotBefore",     Long.toString(binding.notBefore));
    attrs.addAttr("BindingSig",           binding.genesisSig);
    attrs.addAttr("Delegation",           deleg.toJson());
    attrs.addAttr("Domain",               nDomain);
    attrs.addAttr("PrvId",                nPrvId);

    final DomId dst = new DomId(nPrvId, "act", "act@act", "actManager");
    final JsonMsg req = new JsonMsg();

    req.addRequestHead(inMsg.getSrcId(), dst, inMsg.getContext());
    req.addRequestBody("HostProvision", attrs);
    req.addClsId("act", "actManager");

    final JsonMsg resp = msgClient.send(dst, req);

    if (resp == null || !"Success".equals(resp.getError()))
    {
      final String detail = resp != null && resp.getErrorMsg() != null
          ? resp.getErrorMsg() : "null response";

      throw new DomatarException("HostProvision failed: " + detail);
    }

    final String now = IdGen.getCurTimeBase64();
    final String homePrv = DomatarConfig.getPrvId();

    MembershipMigrator.upsertObjectOnlyPeer(
        UserSubstrateIds.membership(actId, homePrv),
        nPrvId, now, now, "False",
        Integer.toString(ActDb.getFpVersion(actId)));

    final ObjAttrs peerAttrs = new ObjAttrs();

    peerAttrs.addAttr("PrvId", nPrvId);
    peerAttrs.addAttr("IsLoginHome", "False");
    peerAttrs.addAttr("AddedAt", now);
    peerAttrs.addAttr("PeerVersion", now);
    peerAttrs.addAttr("Tombstone", "False");
    MembershipFanout.push(actId, "AddPeer", peerAttrs, msgClient);
  }
}
