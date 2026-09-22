/*
 * Copyright (c) 2024 Domatar
 */

package com.domatar.objimpl;

import java.util.List;

import com.domatar.core.Auth;
import com.domatar.core.Context;
import com.domatar.core.DomatarConfig;
import com.domatar.db.LnkDb;
import com.domatar.db.ObjDb;
import com.domatar.install.MembershipMigrator;
import com.domatar.install.ObjectOnlyPeers;
import com.domatar.install.UserSubstrateIds;
import com.domatar.util.IdGen;
import com.domatar.util.JsonArrayList;
import com.domatar.util.JsonHashMap;
import com.domatar.util.JsonList;
import com.domatar.util.JsonMap;
import com.domatar.util.JsonMsg;
import com.domatar.util.Obj;
import com.domatar.util.ObjAttrs;
import com.domatar.util.ObjImpl;
import com.domatar.util.DomId;
import com.domatar.util.DomatarException;
import com.domatar.util.DomatarMsgClient;

/**
 * Handler for {@code (domatar, membership)} on the user substrate
 * (Update-Mandatory-App-Rewrite.txt Phase 4 / KD6).
 *
 * <p>Peer ObjId is {@code peer-&lt;usrId&gt;} (login-home) or
 * {@code peer-prv-&lt;prvId&gt;} (object-only host, no sign-in).
 */
public class DomatarMembershipImpl extends ObjImpl
{
  @Override
  public String handleMsg(final String msg, final Obj obj, final String contextPath,
                          final String contextRealPath, final DomatarMsgClient msgClient)
      throws DomatarException
  {
    final JsonMsg inMsg  = new JsonMsg(msg);
    final String  opr    = inMsg.getOperation();

    if ("Compensate".equals(opr))
      return super.handleMsg(msg, obj, contextPath, contextRealPath, msgClient);

    final JsonMsg outMsg = new JsonMsg();

    if (!hasRights(inMsg, obj, msgClient))
      return notAuthorized(inMsg);

    if ("ListMembership".equals(opr))
      listMembership(opr, inMsg, outMsg);
    else if ("ListPeersFull".equals(opr))
      listPeersFull(opr, inMsg, outMsg);
    else if ("AddPeer".equals(opr))
      addPeer(opr, inMsg, outMsg);
    else if ("TombstonePeer".equals(opr))
      tombstonePeer(opr, inMsg, outMsg);
    else if ("UpdatePeer".equals(opr))
      updatePeer(opr, inMsg, outMsg);
    else if ("GetMembershipVersion".equals(opr))
      getMembershipVersion(opr, inMsg, outMsg);
    else if ("ReconcileMembership".equals(opr))
      reconcileMembership(opr, inMsg, outMsg, msgClient);
    else if ("GetBinding".equals(opr) || "SetBinding".equals(opr) || "PutBinding".equals(opr))
    {
      // Binding lives on sibling obj; forward for callers that still target membership.
      final DomId binding = UserSubstrateIds.binding(
          inMsg.getDstId().actId, DomatarConfig.getPrvId());
      final JsonMsg req = new JsonMsg();
      final String forwardOpr = "PutBinding".equals(opr) ? "SetBinding" : opr;

      req.addRequestBody(forwardOpr, inMsg.getAttrs());
      req.addClsId("domatar", "binding");

      final JsonMsg resp = msgClient.send(binding, req);

      if (resp == null)
        outMsg.addError(opr, "Binding unavailable");
      else if (resp.isFailure())
        outMsg.addError(opr, resp.getErrorMsg());
      else
        outMsg.addResponseBody(opr,
            resp.getAttrs() != null ? resp.getAttrs() : new ObjAttrs());
    }
    else
      return super.handleMsg(msg, obj, contextPath, contextRealPath, msgClient);

    return outMsg.toString();
  }

  @Override
  public boolean hasRights(final JsonMsg inMsg, final Obj obj,
                           final DomatarMsgClient msgClient) throws DomatarException
  {
    if (!Auth.isVerified(inMsg))
      return false;

    final Context ctx = inMsg.getContext();
    final DomId   dst = inMsg.getDstId();

    return ctx != null && ctx.actId != null && dst != null
        && ctx.actId.equals(dst.actId);
  }

  private void listMembership(final String opr, final JsonMsg inMsg,
                              final JsonMsg outMsg) throws DomatarException
  {
    final DomId dst = inMsg.getDstId();
    final List<Obj> rows = ObjDb.getObjPrefix(dst.hstId, "domatar", dst.actId, "peer",
        null, 1000);
    final JsonList list = new JsonArrayList();

    for (final Obj row : rows)
    {
      if (!"domatar".equals(row.clsAppId) || !"peer".equals(row.clsId))
        continue;

      final ObjAttrs a = row.attrs;

      if (a == null || "True".equals(a.getAttr("Tombstone")))
        continue;

      list.add(peerPublicEntry(a));
    }

    final ObjAttrs outAttrs = new ObjAttrs();

    outAttrs.addAttr("Peers", list);
    outMsg.addResponseBody(opr, outAttrs);
  }

  private void listPeersFull(final String opr, final JsonMsg inMsg,
                             final JsonMsg outMsg) throws DomatarException
  {
    final DomId dst = inMsg.getDstId();
    final List<Obj> rows = ObjDb.getObjPrefix(dst.hstId, "domatar", dst.actId, "peer",
        null, 1000);
    final JsonList list = new JsonArrayList();

    for (final Obj row : rows)
    {
      if (!"domatar".equals(row.clsAppId) || !"peer".equals(row.clsId))
        continue;

      final ObjAttrs a = row.attrs;

      if (a == null)
        continue;

      final JsonMap entry = peerPublicEntry(a);

      entry.put("PeerVersion", a.getAttr("PeerVersion"));
      entry.put("Tombstone",
          a.getAttr("Tombstone") != null ? a.getAttr("Tombstone") : "False");
      entry.put("FpVersion", a.getAttr("FpVersion"));
      list.add(entry);
    }

    final ObjAttrs outAttrs = new ObjAttrs();

    outAttrs.addAttr("Peers", list);
    outMsg.addResponseBody(opr, outAttrs);
  }

  private void addPeer(final String opr, final JsonMsg inMsg, final JsonMsg outMsg)
      throws DomatarException
  {
    final DomId dst = inMsg.getDstId();
    final String usrId = inMsg.getAttr("UsrId");
    final String appId = inMsg.getAttr("AppId");
    final String prvId = inMsg.getAttr("PrvId");

    if ("False".equals(inMsg.getAttr("IsLoginHome")))
    {
      if (prvId == null)
      {
        outMsg.addError(opr, "Missing PrvId");
        return;
      }

      final String now = IdGen.getCurTimeBase64();

      MembershipMigrator.upsertObjectOnlyPeer(dst, prvId,
          inMsg.getAttr("AddedAt") != null ? inMsg.getAttr("AddedAt") : now,
          inMsg.getAttr("PeerVersion") != null ? inMsg.getAttr("PeerVersion") : now,
          inMsg.getAttr("Tombstone") != null ? inMsg.getAttr("Tombstone") : "False",
          inMsg.getAttr("FpVersion"));

      outMsg.addResponseBody(opr, null);
      return;
    }

    if (usrId == null || appId == null || prvId == null)
    {
      outMsg.addError(opr, "Missing UsrId / AppId / PrvId");
      return;
    }

    final String now = IdGen.getCurTimeBase64();

    MembershipMigrator.upsertPeer(dst, usrId,
        inMsg.getAttr("UsrName") != null ? inMsg.getAttr("UsrName") : usrId,
        appId, prvId,
        inMsg.getAttr("IsRoot") != null ? inMsg.getAttr("IsRoot") : "False",
        inMsg.getAttr("AddedAt") != null ? inMsg.getAttr("AddedAt") : now,
        inMsg.getAttr("PeerVersion") != null ? inMsg.getAttr("PeerVersion") : now,
        inMsg.getAttr("Tombstone") != null ? inMsg.getAttr("Tombstone") : "False",
        inMsg.getAttr("FpVersion"));

    outMsg.addResponseBody(opr, null);
  }

  private void tombstonePeer(final String opr, final JsonMsg inMsg,
                             final JsonMsg outMsg) throws DomatarException
  {
    final DomId dst = inMsg.getDstId();
    final String usrId = inMsg.getAttr("UsrId");
    final String prvId = inMsg.getAttr("PrvId");
    final boolean objectOnly = "False".equals(inMsg.getAttr("IsLoginHome"))
        || ObjectOnlyPeers.isObjectOnlyObjId(inMsg.getAttr("ObjId"))
        || (prvId != null && (usrId == null || usrId.isEmpty()));

    if (objectOnly)
    {
      if (prvId == null || prvId.isEmpty())
      {
        outMsg.addError(opr, "Missing PrvId");
        return;
      }

      tombstoneObjectOnlyPeer(dst, prvId, inMsg);
      outMsg.addResponseBody(opr, null);
      return;
    }

    if (usrId == null)
    {
      outMsg.addError(opr, "Missing UsrId");
      return;
    }

    final DomId rowDomId = new DomId(dst.hstId, "domatar", dst.actId,
        IdGen.createId("peer", usrId));
    final Obj existing = ObjDb.getObj(rowDomId);

    if (existing == null)
    {
      outMsg.addResponseBody(opr, null);
      return;
    }

    final String now = IdGen.getCurTimeBase64();
    final String incomingVersion = inMsg.getAttr("PeerVersion");
    final String resolvedVersion = incomingVersion != null ? incomingVersion : now;
    final String storedVersion = existing.attrs != null
        ? existing.attrs.getAttr("PeerVersion") : null;

    if (MembershipMigrator.comparePeerVersion(resolvedVersion, storedVersion) < 0)
    {
      outMsg.addResponseBody(opr, null);
      return;
    }

    MembershipMigrator.upsertPeer(dst, usrId,
        existing.attrs.getAttr("UsrName"),
        existing.attrs.getAttr("AppId"),
        existing.attrs.getAttr("PrvId"),
        existing.attrs.getAttr("IsRoot"),
        existing.attrs.getAttr("AddedAt"),
        resolvedVersion, "True",
        existing.attrs.getAttr("FpVersion"));

    outMsg.addResponseBody(opr, null);
  }

  private void tombstoneObjectOnlyPeer(final DomId dst, final String prvId,
                                       final JsonMsg inMsg)
      throws DomatarException
  {
    final DomId rowDomId = ObjectOnlyPeers.rowDomId(dst, prvId);
    final Obj existing = ObjDb.getObj(rowDomId);

    if (existing == null)
      return;

    final String now = IdGen.getCurTimeBase64();
    final String incomingVersion = inMsg.getAttr("PeerVersion");
    final String resolvedVersion = incomingVersion != null ? incomingVersion : now;
    final String storedVersion = existing.attrs != null
        ? existing.attrs.getAttr("PeerVersion") : null;

    if (MembershipMigrator.comparePeerVersion(resolvedVersion, storedVersion) < 0)
      return;

    MembershipMigrator.upsertObjectOnlyPeer(dst, prvId,
        existing.attrs != null ? existing.attrs.getAttr("AddedAt") : now,
        resolvedVersion, "True",
        existing.attrs != null ? existing.attrs.getAttr("FpVersion") : null);
  }

  private void updatePeer(final String opr, final JsonMsg inMsg, final JsonMsg outMsg)
      throws DomatarException
  {
    final DomId dst = inMsg.getDstId();
    final String prevUsrId = inMsg.getAttr("PrevUsrId");

    if (prevUsrId == null)
    {
      outMsg.addError(opr, "Missing PrevUsrId");
      return;
    }

    final DomId oldDomId = new DomId(dst.hstId, "domatar", dst.actId,
        IdGen.createId("peer", prevUsrId));
    final Obj oldObj = ObjDb.getObj(oldDomId);

    if (oldObj == null)
    {
      outMsg.addResponseBody(opr, null);
      return;
    }

    final String newUsrId = inMsg.getAttr("NewUsrId");
    final String resolvedUsrId = newUsrId != null ? newUsrId : prevUsrId;
    final String resolvedUsrName = inMsg.getAttr("NewUsrName") != null
        ? inMsg.getAttr("NewUsrName") : oldObj.attrs.getAttr("UsrName");
    final String resolvedAppId = inMsg.getAttr("AppId") != null
        ? inMsg.getAttr("AppId") : oldObj.attrs.getAttr("AppId");
    final String now = IdGen.getCurTimeBase64();

    if (!resolvedUsrId.equals(prevUsrId))
    {
      LnkDb.deleteLnks(dst, oldDomId, "domatar", "peer", null, null);
      ObjDb.deleteObj(oldDomId);
    }

    MembershipMigrator.upsertPeer(dst, resolvedUsrId,
        resolvedUsrName != null ? resolvedUsrName : resolvedUsrId,
        resolvedAppId,
        oldObj.attrs.getAttr("PrvId"),
        oldObj.attrs.getAttr("IsRoot") != null ? oldObj.attrs.getAttr("IsRoot") : "False",
        oldObj.attrs.getAttr("AddedAt") != null ? oldObj.attrs.getAttr("AddedAt") : now,
        now, "False",
        oldObj.attrs.getAttr("FpVersion"));

    outMsg.addResponseBody(opr, null);
  }

  private void getMembershipVersion(final String opr, final JsonMsg inMsg,
                                    final JsonMsg outMsg) throws DomatarException
  {
    final ObjAttrs outAttrs = new ObjAttrs();

    outAttrs.addAttr("Version",
        MembershipMigrator.readContainerVersion(inMsg.getDstId()));
    outMsg.addResponseBody(opr, outAttrs);
  }

  /**
   * Phase 4: pull peers from the local login replica into domatar
   * (UI refresh path). Login↔login fanout stays on MembershipImpl.
   */
  private void reconcileMembership(final String opr, final JsonMsg inMsg,
                                   final JsonMsg outMsg,
                                   final DomatarMsgClient msgClient)
      throws DomatarException
  {
    final DomId dst = inMsg.getDstId();
    final String actId = dst.actId;
    final String prvId = DomatarConfig.getPrvId();
    final String detail = MembershipMigrator.copyFromLoginReplica(actId, prvId, msgClient);
    final ObjAttrs outAttrs = new ObjAttrs();

    outAttrs.addAttr("Reconciled", "True");
    outAttrs.addAttr("Detail", detail);
    outAttrs.addAttr("Version", MembershipMigrator.readContainerVersion(dst));
    outMsg.addResponseBody(opr, outAttrs);
  }

  private static JsonMap peerPublicEntry(final ObjAttrs a) throws DomatarException
  {
    final JsonMap entry = new JsonHashMap();

    entry.put("UsrId", a.getAttr("UsrId"));
    entry.put("UsrName", a.getAttr("UsrName"));
    entry.put("AppId", a.getAttr("AppId"));
    entry.put("PrvId", a.getAttr("PrvId"));
    entry.put("IsRoot", a.getAttr("IsRoot"));
    entry.put("AddedAt", a.getAttr("AddedAt"));
    entry.put("IsLoginHome", ObjectOnlyPeers.isLoginHome(a) ? "True" : "False");

    final String storedSigning = a.getAttr("IsSigning");

    if (storedSigning != null)
      entry.put("IsSigning", storedSigning);
    else
      entry.put("IsSigning", ObjectOnlyPeers.isLoginHome(a) ? "True" : "False");

    return entry;
  }
}
