/*
 * Copyright (c) 2024 Domatar
 */

package com.domatar.install;

import com.domatar.crypto.Binding;
import com.domatar.db.ActDb;
import com.domatar.db.HstDb;
import com.domatar.db.ObjDb;
import com.domatar.util.ObjAttrs;
import com.domatar.util.DomId;
import com.domatar.util.DomatarException;
import com.domatar.util.DomatarMsgClient;

/**
 * Shared helper that creates a per-provider membership replica
 * {@code login-&lt;actId&gt;-&lt;prvId&gt;} (Spec-Login-Multiple.txt PART 5/6;
 * Update-Login-Multiple.txt Tasks 1.4 / 3.1f).
 *
 * <p>Idempotent: a second call is a no-op when the membership container
 * already exists.
 */
public final class MembershipReplica
{
  private MembershipReplica() {}

  /**
   * Ensure hst + directory registration + membership container + binding
   * obj (+ srv/cls descriptors) exist for {@code (actId, prvId)}.
   *
   * @param domain provider domain for the replica hst row (e.g. tomcat2:8080)
   * @param prvId  provider id of the replica (e.g. prv2)
   */
  public static void ensure(final String actId,
                            final String domain,
                            final String prvId,
                            final DomatarMsgClient msgClient) throws DomatarException
  {
    if (actId == null || domain == null || prvId == null)
      throw new DomatarException("MembershipReplica.ensure requires actId, domain, prvId");

    final String loginRepId = DomId.subHstId("login", actId, prvId);

    if (HstDb.getHst(loginRepId) == null)
      HstDb.addHst(loginRepId, domain, prvId);

    DirectoryRegister.registerHst(loginRepId, domain, prvId, msgClient);

    final DomId membershipId = new DomId(loginRepId, "login", actId, "membership");
    final DomId bindingObjId = new DomId(loginRepId, "login", actId, "binding");

    ObjDb.addObjIfMissing(membershipId, "login", "membership",
        "Membership", "Providers and logins linked to this account");

    final ObjAttrs bindingAttrs = new ObjAttrs();
    final Binding binding = ActDb.getBinding(actId);

    if (binding != null)
    {
      bindingAttrs.addAttr("ActId",         binding.actId);
      bindingAttrs.addAttr("GenesisPubKey", binding.genesisPubKeyB64);
      bindingAttrs.addAttr("OwnId",         binding.ownId);
      bindingAttrs.addAttr("OwnPubKey",     binding.ownPubKeyB64);
      bindingAttrs.addAttr("Version",       Long.toString(binding.version));
      bindingAttrs.addAttr("NotBefore",     Long.toString(binding.notBefore));
      bindingAttrs.addAttr("GenesisSig",    binding.genesisSig);
    }

    ObjDb.addObjIfMissing(bindingObjId, "login", "binding",
        "Binding", "actId->ownId binding (replicated)", bindingAttrs);

    // Prefer membership container as descriptor host (post-cutover).
    // Legacy login-<actId> logins container is retired.
    installDescriptors(membershipId);
  }

  private static void installDescriptors(final DomId descriptorHost) throws DomatarException
  {
    SrvInstall.addSrvObj(descriptorHost, "login", "membership",
        "Per-provider membership directory of peers for an account",
        "{\"SrvAppId\":\"login\",\"SrvId\":\"membership\",\"Attrs\":[{\"Name\":\"Version\",\"Type\":\"String\"}]," +
        "\"Msgs\":[" +
        "{\"Name\":\"ListMembership\",\"Type\":{\"Peers\":[{\"UsrId\":\"String\",\"UsrName\":\"String\",\"AppId\":\"String\",\"PrvId\":\"String\",\"IsRoot\":\"String\",\"AddedAt\":\"String\"}]},\"Parms\":[]}," +
        "{\"Name\":\"AddPeer\",\"Parms\":[{\"Name\":\"UsrId\",\"Type\":\"String\"},{\"Name\":\"UsrName\",\"Type\":\"String?\"},{\"Name\":\"AppId\",\"Type\":\"String\"},{\"Name\":\"PrvId\",\"Type\":\"String\"},{\"Name\":\"IsRoot\",\"Type\":\"String?\"},{\"Name\":\"AddedAt\",\"Type\":\"String?\"},{\"Name\":\"PeerVersion\",\"Type\":\"String?\"}],\"Type\":{}}," +
        "{\"Name\":\"TombstonePeer\",\"Parms\":[{\"Name\":\"UsrId\",\"Type\":\"String\"},{\"Name\":\"PeerVersion\",\"Type\":\"String?\"}],\"Type\":{}}," +
        "{\"Name\":\"UpdatePeer\",\"Parms\":[{\"Name\":\"PrevUsrId\",\"Type\":\"String\"},{\"Name\":\"NewUsrId\",\"Type\":\"String?\"},{\"Name\":\"NewUsrName\",\"Type\":\"String?\"},{\"Name\":\"AppId\",\"Type\":\"String?\"}],\"Type\":{}}," +
        "{\"Name\":\"GetMembershipVersion\",\"Type\":{\"Version\":\"String\"},\"Parms\":[]}," +
        "{\"Name\":\"ReconcileMembership\",\"Parms\":[],\"Type\":{}}]}");

    ClsInstall.upsertClsImplementing(descriptorHost, "login", "membership",
        "Per-provider membership directory of peers for an account",
        "{\"ClsAppId\":\"login\",\"ClsId\":\"membership\"," +
        "\"Implements\":[\"login.membership\"]," +
        "\"MsgPolicy\":[" +
        "{\"Srv\":\"login.membership\",\"Name\":\"ListMembership\",\"SideEffect\":\"Read\"}," +
        "{\"Srv\":\"login.membership\",\"Name\":\"GetMembershipVersion\",\"SideEffect\":\"Read\"}," +
        "{\"Srv\":\"login.membership\",\"Name\":\"ReconcileMembership\",\"SideEffect\":\"Read\"}," +
        "{\"Srv\":\"login.membership\",\"Name\":\"TombstonePeer\",\"SideEffect\":\"Destructive\"}" +
        "]}");

    SrvInstall.addSrvObj(descriptorHost, "login", "peer",
        "One peer (app-login) in the membership directory",
        "{\"SrvAppId\":\"login\",\"SrvId\":\"peer\"," +
        "\"Attrs\":[{\"Name\":\"UsrId\",\"Type\":\"String\"},{\"Name\":\"UsrName\",\"Type\":\"String\"},{\"Name\":\"AppId\",\"Type\":\"String\"},{\"Name\":\"PrvId\",\"Type\":\"String\"},{\"Name\":\"IsRoot\",\"Type\":\"String\"},{\"Name\":\"AddedAt\",\"Type\":\"String\"},{\"Name\":\"PeerVersion\",\"Type\":\"String\"},{\"Name\":\"Tombstone\",\"Type\":\"String\"}]," +
        "\"Msgs\":[{\"Name\":\"GetObj\",\"Type\":{\"ObjName\":\"String\",\"ObjDesc\":\"String\"},\"Parms\":[]}]}");

    ClsInstall.upsertClsImplementing(descriptorHost, "login", "peer",
        "One peer (app-login) in the membership directory",
        "{\"ClsAppId\":\"login\",\"ClsId\":\"peer\"," +
        "\"Implements\":[\"login.peer\"]," +
        "\"MsgPolicy\":[" +
        "{\"Srv\":\"login.peer\",\"Name\":\"GetObj\",\"SideEffect\":\"Read\"}" +
        "]}");

    SrvInstall.addSrvObj(descriptorHost, "login", "binding",
        "Replicated actId->ownId binding on a membership replica",
        "{\"SrvAppId\":\"login\",\"SrvId\":\"binding\"," +
        "\"Attrs\":[{\"Name\":\"ActId\",\"Type\":\"String\"},{\"Name\":\"GenesisPubKey\",\"Type\":\"String\"},{\"Name\":\"OwnId\",\"Type\":\"String\"},{\"Name\":\"OwnPubKey\",\"Type\":\"String\"},{\"Name\":\"Version\",\"Type\":\"String\"},{\"Name\":\"NotBefore\",\"Type\":\"String\"},{\"Name\":\"GenesisSig\",\"Type\":\"String\"}]," +
        "\"Msgs\":[{\"Name\":\"GetObj\",\"Type\":{\"ObjName\":\"String\",\"ObjDesc\":\"String\"},\"Parms\":[]}]}");

    ClsInstall.upsertClsImplementing(descriptorHost, "login", "binding",
        "Replicated actId->ownId binding on a membership replica",
        "{\"ClsAppId\":\"login\",\"ClsId\":\"binding\"," +
        "\"Implements\":[\"login.binding\"]," +
        "\"MsgPolicy\":[" +
        "{\"Srv\":\"login.binding\",\"Name\":\"GetObj\",\"SideEffect\":\"Read\"}" +
        "]}");
  }
}
