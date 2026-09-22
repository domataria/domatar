/*
 * Copyright (c) 2024 Domatar
 */

package com.login.objimpl;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.domatar.core.Auth;
import com.domatar.core.Context;
import com.domatar.core.DomatarConfig;
import com.domatar.crypto.Binding;
import com.domatar.db.ActDb;
import com.domatar.db.LnkDb;
import com.domatar.db.ObjDb;
import com.domatar.install.MembershipMigrator;
import com.domatar.util.Base64Encoder;
import com.domatar.util.IdGen;
import com.domatar.util.JsonArrayList;
import com.domatar.util.JsonHashMap;
import com.domatar.util.JsonList;
import com.domatar.util.JsonMap;
import com.domatar.util.JsonMsg;
import com.domatar.util.Lnk;
import com.domatar.util.Obj;
import com.domatar.util.ObjAttrs;
import com.domatar.util.ObjImpl;
import com.domatar.util.DomId;
import com.domatar.util.DomatarException;
import com.domatar.util.DomatarMsgClient;

/**
 * Per-provider membership container handler. Lives at
 *   (HstId=login-&lt;actId&gt;-&lt;prvId&gt;, AppId=login, ActId=&lt;actId&gt;,
 *    ObjId=membership), cls (login, membership).
 *
 * Spec-Login-Multiple.txt PART 6 / 7 / 10.
 *
 * Authorization (PART 10.5 / 13): verified session AND owner-match
 * (ctx.actId equals the membership container's actId).
 */
public class MembershipImpl extends ObjImpl
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
      addPeer(opr, inMsg, outMsg, msgClient);
    else if ("TombstonePeer".equals(opr))
      tombstonePeer(opr, inMsg, outMsg, msgClient);
    else if ("UpdatePeer".equals(opr))
      updatePeer(opr, inMsg, outMsg, msgClient);
    else if ("GetMembershipVersion".equals(opr))
      getMembershipVersion(opr, inMsg, outMsg);
    else if ("ReconcileMembership".equals(opr))
      reconcileMembership(opr, inMsg, outMsg, msgClient);
    else if ("GetBinding".equals(opr))
      getBinding(opr, inMsg, outMsg);
    else if ("SetBinding".equals(opr))
      setBinding(opr, inMsg, outMsg);
    else
      return super.handleMsg(msg, obj, contextPath, contextRealPath, msgClient);

    return outMsg.toString();
  }

  @Override
  public boolean hasRights(final JsonMsg inMsg, final Obj obj,
                           final DomatarMsgClient msgClient)
      throws DomatarException
  {
    if (!Auth.isVerified(inMsg))
      return false;

    final Context ctx = inMsg.getContext();
    final DomId   dst = inMsg.getDstId();

    return ctx != null && ctx.actId != null && dst != null && ctx.actId.equals(dst.actId);
  }

  /**
   * Read every non-tombstoned "peer-" row and emit them as a JSON list
   * for the account page. PeerVersion / Tombstone are not exposed to
   * the browser.
   */
  private void listMembership(final String opr, final JsonMsg inMsg,
                               final JsonMsg outMsg)
      throws DomatarException
  {
    final DomId dst = inMsg.getDstId();

    final List<Obj> rows = ObjDb.getObjPrefix(dst.hstId, dst.appId, dst.actId, "peer", null, 1000);

    final JsonList list = new JsonArrayList();

    for (final Obj row : rows)
    {
      final ObjAttrs a = row.attrs;

      if ("True".equals(a.getAttr("Tombstone")))
        continue;

      list.add(peerPublicEntry(a));
    }

    final ObjAttrs outAttrs = new ObjAttrs();

    outAttrs.addAttr("Peers", list);

    outMsg.addResponseBody(opr, outAttrs);
  }

  /**
   * Internal / cross-replica: all peer rows including PeerVersion + Tombstone.
   */
  private void listPeersFull(final String opr, final JsonMsg inMsg,
                              final JsonMsg outMsg)
      throws DomatarException
  {
    final DomId dst = inMsg.getDstId();

    final List<Obj> rows = ObjDb.getObjPrefix(dst.hstId, dst.appId, dst.actId, "peer", null, 1000);

    final JsonList list = new JsonArrayList();

    for (final Obj row : rows)
    {
      final ObjAttrs a = row.attrs;
      final JsonMap entry = peerPublicEntry(a);

      entry.put("PeerVersion", a.getAttr("PeerVersion"));
      entry.put("Tombstone",   a.getAttr("Tombstone") != null ? a.getAttr("Tombstone") : "False");
      entry.put("FpVersion",   resolveFpVersion(a.getAttr("FpVersion"), dst.actId));

      list.add(entry);
    }

    final ObjAttrs outAttrs = new ObjAttrs();

    outAttrs.addAttr("Peers", list);

    outMsg.addResponseBody(opr, outAttrs);
  }

  /**
   * Insert or LWW-overwrite a "peer-&lt;usrId&gt;" row. Idempotent.
   */
  private void addPeer(final String opr, final JsonMsg inMsg,
                        final JsonMsg outMsg, final DomatarMsgClient msgClient)
      throws DomatarException
  {
    final DomId dst = inMsg.getDstId();

    final String usrId       = inMsg.getAttr("UsrId");
    final String usrName     = inMsg.getAttr("UsrName");
    final String appId       = inMsg.getAttr("AppId");
    final String prvId       = inMsg.getAttr("PrvId");
    final String isRoot      = inMsg.getAttr("IsRoot");
    final String addedAt     = inMsg.getAttr("AddedAt");
    final String peerVersion = inMsg.getAttr("PeerVersion");
    final String tombstone   = inMsg.getAttr("Tombstone");
    final String fpVersion   = inMsg.getAttr("FpVersion");

    if (usrId == null || appId == null || prvId == null)
    {
      outMsg.addError(opr, "Missing UsrId / AppId / PrvId");
      return;
    }

    final String now = IdGen.getCurTimeBase64();
    final String resolvedName = usrName != null ? usrName : usrId;
    final String resolvedRoot = isRoot != null ? isRoot : "False";
    final String resolvedAdded = addedAt != null ? addedAt : now;
    final String resolvedVer = peerVersion != null ? peerVersion : now;
    final String resolvedTomb = tombstone != null ? tombstone : "False";
    final String resolvedFp = resolveFpVersion(fpVersion, dst.actId);

    mergePeerRow(dst, usrId, resolvedName, appId, prvId,
        resolvedRoot, resolvedAdded, resolvedVer, resolvedTomb, resolvedFp);

    dualWriteDomatar(dst, msgClient, usrId, resolvedName, appId, prvId,
        resolvedRoot, resolvedAdded, resolvedVer, resolvedTomb, resolvedFp);

    outMsg.addResponseBody(opr, null);
  }

  /**
   * Soft-delete: Tombstone=True + fresh PeerVersion. Missing row = success.
   */
  private void tombstonePeer(final String opr, final JsonMsg inMsg,
                              final JsonMsg outMsg,
                              final DomatarMsgClient msgClient)
      throws DomatarException
  {
    final DomId dst = inMsg.getDstId();

    final String usrId = inMsg.getAttr("UsrId");

    if (usrId == null)
    {
      outMsg.addError(opr, "Missing UsrId");
      return;
    }

    final String objId = IdGen.createId("peer", usrId);
    final DomId rowDomId = new DomId(dst.hstId, "login", dst.actId, objId);

    final Obj existing = ObjDb.getObj(rowDomId);

    if (existing == null)
    {
      outMsg.addResponseBody(opr, null);
      return;
    }

    final String now = IdGen.getCurTimeBase64();
    final String incomingVersion = inMsg.getAttr("PeerVersion");
    final String resolvedVersion = incomingVersion != null ? incomingVersion : now;

    final String storedVersion = existing.attrs.getAttr("PeerVersion");

    if (comparePeerVersion(resolvedVersion, storedVersion) < 0)
    {
      outMsg.addResponseBody(opr, null);
      return;
    }

    mergePeerRow(dst, usrId,
        existing.attrs.getAttr("UsrName"),
        existing.attrs.getAttr("AppId"),
        existing.attrs.getAttr("PrvId"),
        existing.attrs.getAttr("IsRoot"),
        existing.attrs.getAttr("AddedAt"),
        resolvedVersion, "True",
        resolveFpVersion(existing.attrs.getAttr("FpVersion"), dst.actId));

    dualWriteDomatar(dst, msgClient, usrId,
        existing.attrs.getAttr("UsrName"),
        existing.attrs.getAttr("AppId"),
        existing.attrs.getAttr("PrvId"),
        existing.attrs.getAttr("IsRoot"),
        existing.attrs.getAttr("AddedAt"),
        resolvedVersion, "True",
        resolveFpVersion(existing.attrs.getAttr("FpVersion"), dst.actId));

    outMsg.addResponseBody(opr, null);
  }

  /**
   * Patch UsrId / UsrName / AppId. When UsrId changes, delete-old + add-new
   * (ObjId is peer-&lt;usrId&gt;). Preserves AddedAt / IsRoot / PrvId / FpVersion.
   */
  private void updatePeer(final String opr, final JsonMsg inMsg,
                           final JsonMsg outMsg,
                           final DomatarMsgClient msgClient)
      throws DomatarException
  {
    final DomId dst = inMsg.getDstId();

    final String prevUsrId  = inMsg.getAttr("PrevUsrId");
    final String newUsrId   = inMsg.getAttr("NewUsrId");
    final String newUsrName = inMsg.getAttr("NewUsrName");
    final String appId      = inMsg.getAttr("AppId");

    if (prevUsrId == null)
    {
      outMsg.addError(opr, "Missing PrevUsrId");
      return;
    }

    final String oldObjId = IdGen.createId("peer", prevUsrId);
    final DomId oldDomId = new DomId(dst.hstId, "login", dst.actId, oldObjId);

    final Obj oldObj = ObjDb.getObj(oldDomId);

    if (oldObj == null)
    {
      outMsg.addResponseBody(opr, null);
      return;
    }

    final String resolvedUsrId   = newUsrId   != null ? newUsrId   : prevUsrId;
    final String resolvedUsrName = newUsrName != null ? newUsrName : oldObj.attrs.getAttr("UsrName");
    final String resolvedAppId   = appId      != null ? appId      : oldObj.attrs.getAttr("AppId");
    final String prvId           = oldObj.attrs.getAttr("PrvId");
    final String isRoot          = oldObj.attrs.getAttr("IsRoot");
    final String addedAt         = oldObj.attrs.getAttr("AddedAt");
    final String now             = IdGen.getCurTimeBase64();

    if (!resolvedUsrId.equals(prevUsrId))
    {
      LnkDb.deleteLnks(dst, oldDomId, "login", "peer", null, null);
      ObjDb.deleteObj(oldDomId);
    }

    mergePeerRow(dst, resolvedUsrId,
        resolvedUsrName != null ? resolvedUsrName : resolvedUsrId,
        resolvedAppId, prvId,
        isRoot != null ? isRoot : "False",
        addedAt != null ? addedAt : now,
        now, "False",
        resolveFpVersion(oldObj.attrs.getAttr("FpVersion"), dst.actId));

    dualWriteDomatar(dst, msgClient, resolvedUsrId,
        resolvedUsrName != null ? resolvedUsrName : resolvedUsrId,
        resolvedAppId, prvId,
        isRoot != null ? isRoot : "False",
        addedAt != null ? addedAt : now,
        now, "False",
        resolveFpVersion(oldObj.attrs.getAttr("FpVersion"), dst.actId));

    // When UsrId renamed, tombstone the previous domatar peer row.
    if (!resolvedUsrId.equals(prevUsrId))
      dualWriteDomatar(dst, msgClient, prevUsrId,
          oldObj.attrs.getAttr("UsrName"),
          oldObj.attrs.getAttr("AppId"),
          prvId,
          isRoot != null ? isRoot : "False",
          addedAt != null ? addedAt : now,
          now, "True",
          resolveFpVersion(oldObj.attrs.getAttr("FpVersion"), dst.actId));

    outMsg.addResponseBody(opr, null);
  }

  /**
   * Cheap changed-since probe: return the membership container's Version attr.
   */
  private void getMembershipVersion(final String opr, final JsonMsg inMsg,
                                     final JsonMsg outMsg)
      throws DomatarException
  {
    final DomId dst = inMsg.getDstId();

    final ObjAttrs outAttrs = new ObjAttrs();

    outAttrs.addAttr("Version", readContainerVersion(dst));

    outMsg.addResponseBody(opr, outAttrs);
  }

  /**
   * Pull-merge from peer replicas (PART 10). Non-fatal per remote.
   */
  private void reconcileMembership(final String opr, final JsonMsg inMsg,
                                    final JsonMsg outMsg,
                                    final DomatarMsgClient msgClient)
      throws DomatarException
  {
    final DomId dst = inMsg.getDstId();
    final String actId = dst.actId;
    final String localPrvId = replicaPrvIdOrConfig(dst.hstId);

    final String localVersion = readContainerVersion(dst);

    final Set<String> remotePrvIds = resolveRemotePrvIds(inMsg, dst, localPrvId);

    int mergedPeers = 0;
    int skipped = 0;
    int failed = 0;
    boolean bindingUpdated = false;

    for (final String remotePrvId : remotePrvIds)
    {
      try
      {
        final DomId remoteContainer = new DomId(
            DomId.subHstId("login", actId, remotePrvId),
            "login", actId, "membership");

        final JsonMsg verReq = new JsonMsg();

        verReq.addRequestBody("GetMembershipVersion", null);
        verReq.addClsId("login", "membership");

        final JsonMsg verResp = msgClient.send(remoteContainer, verReq);

        if (verResp == null || !"Success".equals(verResp.getError()))
        {
          failed++;
          final String err = verResp != null ? verResp.getErrorMsg() : "null response";
          System.out.println("WARN: ReconcileMembership GetMembershipVersion failed for prvId="
              + remotePrvId + " actId=" + actId + ": " + err);
          continue;
        }

        final String remoteVersion = verResp.getAttr("Version");

        if (comparePeerVersion(remoteVersion, localVersion) <= 0)
        {
          skipped++;
          continue;
        }

        final JsonMsg fullReq = new JsonMsg();

        fullReq.addRequestBody("ListPeersFull", null);
        fullReq.addClsId("login", "membership");

        final JsonMsg fullResp = msgClient.send(remoteContainer, fullReq);

        if (fullResp == null || !"Success".equals(fullResp.getError()))
        {
          failed++;
          continue;
        }

        final JsonList peers = fullResp.getAttrs().getAttrList("Peers");

        if (peers != null)
        {
          for (int i = 0; i < peers.size(); i++)
          {
            final Object item = peers.get(i);

            if (!(item instanceof JsonMap))
              continue;

            final JsonMap entry = (JsonMap) item;

            if (mergePeerFromMap(dst, entry))
              mergedPeers++;
          }
        }

        if (pullAndMergeBinding(dst, actId, remotePrvId, msgClient))
          bindingUpdated = true;
      }
      catch (final Exception e)
      {
        failed++;
        System.out.println("WARN: ReconcileMembership skip remote prvId="
            + remotePrvId + " actId=" + actId + ": " + e);
      }
    }

    final ObjAttrs outAttrs = new ObjAttrs();

    outAttrs.addAttr("MergedPeers",    Integer.toString(mergedPeers));
    outAttrs.addAttr("Skipped",        Integer.toString(skipped));
    outAttrs.addAttr("Failed",         Integer.toString(failed));
    outAttrs.addAttr("BindingUpdated", bindingUpdated ? "True" : "False");
    outAttrs.addAttr("Version",        readContainerVersion(dst));

    outMsg.addResponseBody(opr, outAttrs);
  }

  /**
   * Return the local replica's binding obj attrs (for cross-replica pull).
   */
  private void getBinding(final String opr, final JsonMsg inMsg,
                           final JsonMsg outMsg)
      throws DomatarException
  {
    final DomId dst = inMsg.getDstId();
    final DomId bindingId = bindingDomId(dst);

    final Obj bindingObj = ObjDb.getObj(bindingId);

    final ObjAttrs outAttrs = new ObjAttrs();

    if (bindingObj != null && bindingObj.attrs != null)
      outAttrs.addAll(bindingObj.attrs.toMap());

    outMsg.addResponseBody(opr, outAttrs);
  }

  /**
   * Accept a higher-Version verified binding (K7 / Task 2.6).
   */
  private void setBinding(final String opr, final JsonMsg inMsg,
                           final JsonMsg outMsg)
      throws DomatarException
  {
    final DomId dst = inMsg.getDstId();

    final String actId         = inMsg.getAttr("ActId") != null ? inMsg.getAttr("ActId") : dst.actId;
    final String genesisPubKey = inMsg.getAttr("GenesisPubKey");
    final String ownId         = inMsg.getAttr("OwnId");
    final String ownPubKey     = inMsg.getAttr("OwnPubKey");
    final String versionStr    = inMsg.getAttr("Version");
    final String notBeforeStr  = inMsg.getAttr("NotBefore");
    final String genesisSig    = inMsg.getAttr("GenesisSig");

    if (actId == null || genesisPubKey == null || ownPubKey == null
        || versionStr == null || notBeforeStr == null || genesisSig == null)
    {
      outMsg.addError(opr, "Missing binding fields");
      return;
    }

    if (!actId.equals(dst.actId))
    {
      outMsg.addError(opr, "Binding ActId does not match sub-host");
      return;
    }

    final long version;
    final long notBefore;

    try
    {
      version   = Long.parseLong(versionStr);
      notBefore = Long.parseLong(notBeforeStr);
    }
    catch (final NumberFormatException e)
    {
      outMsg.addError(opr, "Invalid Version / NotBefore");
      return;
    }

    final Binding binding = Binding.fromStored(actId, genesisPubKey, ownPubKey,
                                               version, notBefore, genesisSig);

    if (ownId != null && !ownId.equals(binding.ownId))
    {
      outMsg.addError(opr, "OwnId does not match OwnPubKey");
      return;
    }

    if (!binding.verify())
    {
      outMsg.addError(opr, "Binding.verify failed");
      return;
    }

    if (!applyBindingIfNewer(dst, binding))
    {
      outMsg.addResponseBody(opr, null);
      return;
    }

    outMsg.addResponseBody(opr, null);
  }

  // ------------------------------------------------------------------
  // merge helpers
  // ------------------------------------------------------------------

  private static void dualWriteDomatar(final DomId loginMembership,
                                       final DomatarMsgClient msgClient,
                                       final String usrId,
                                       final String usrName,
                                       final String appId,
                                       final String peerPrvId,
                                       final String isRoot,
                                       final String addedAt,
                                       final String peerVersion,
                                       final String tombstone,
                                       final String fpVersion)
  {
    final String homePrv = replicaPrvIdOrConfig(loginMembership.hstId);

    MembershipMigrator.dualWritePeer(loginMembership.actId, homePrv,
        usrId, usrName, appId, peerPrvId, isRoot, addedAt, peerVersion,
        tombstone, fpVersion, msgClient);
  }

  private static boolean mergePeerFromMap(final DomId dst, final JsonMap entry)
      throws DomatarException
  {
    final String usrId = entry.getString("UsrId");
    final String appId = entry.getString("AppId");
    final String prvId = entry.getString("PrvId");

    if (usrId == null || appId == null || prvId == null)
      return false;

    final String peerVersion = entry.getString("PeerVersion");
    final String now = IdGen.getCurTimeBase64();
    final String fpVersion = entry.getString("FpVersion");

    return mergePeerRow(dst, usrId,
        entry.getString("UsrName") != null ? entry.getString("UsrName") : usrId,
        appId, prvId,
        entry.getString("IsRoot") != null ? entry.getString("IsRoot") : "False",
        entry.getString("AddedAt") != null ? entry.getString("AddedAt") : now,
        peerVersion != null ? peerVersion : now,
        entry.getString("Tombstone") != null ? entry.getString("Tombstone") : "False",
        resolveFpVersion(fpVersion, dst.actId));
  }

  /**
   * LWW upsert of a peer row. Returns true when the local row was written.
   */
  private static boolean mergePeerRow(final DomId dst,
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
    final String objId = IdGen.createId("peer", usrId);
    final DomId rowDomId = new DomId(dst.hstId, "login", dst.actId, objId);

    final Obj existing = ObjDb.getObj(rowDomId);

    if (existing != null)
    {
      final String storedVersion = existing.attrs.getAttr("PeerVersion");

      if (comparePeerVersion(peerVersion, storedVersion) <= 0)
        return false;
    }

    final ObjAttrs rowAttrs = peerAttrs(usrId, usrName, appId, prvId,
                                        isRoot, addedAt, peerVersion, tombstone,
                                        fpVersion);

    final String objName = usrName;
    final String objDesc = "Login at " + appId + " on " + prvId;

    if (existing != null)
    {
      ObjDb.modifyObj(existing.modify(null, null, null, objName, objDesc, rowAttrs));
    }
    else
    {
      ObjDb.addObj(new Obj(rowDomId, "login", "peer", objName, objDesc, rowAttrs));
      LnkDb.addLnk(new Lnk(dst, rowDomId,
                            "login", "peer",
                            objName, objDesc,
                            "login", "peer",
                            usrId, 0));
    }

    bumpContainerVersion(dst, peerVersion);

    return true;
  }

  private static boolean pullAndMergeBinding(final DomId localMembership,
                                              final String actId,
                                              final String remotePrvId,
                                              final DomatarMsgClient msgClient)
  {
    try
    {
      final DomId remoteMembership = new DomId(
          DomId.subHstId("login", actId, remotePrvId),
          "login", actId, "membership");

      final JsonMsg req = new JsonMsg();

      req.addRequestBody("GetBinding", null);
      req.addClsId("login", "membership");

      final JsonMsg resp = msgClient.send(remoteMembership, req);

      if (resp == null || !"Success".equals(resp.getError()))
        return false;

      final ObjAttrs a = resp.getAttrs();

      if (a == null || a.getAttr("GenesisSig") == null)
        return false;

      final long version;
      final long notBefore;

      try
      {
        version   = Long.parseLong(a.getAttr("Version"));
        notBefore = Long.parseLong(a.getAttr("NotBefore"));
      }
      catch (final Exception e)
      {
        return false;
      }

      final Binding binding = Binding.fromStored(
          actId,
          a.getAttr("GenesisPubKey"),
          a.getAttr("OwnPubKey"),
          version,
          notBefore,
          a.getAttr("GenesisSig"));

      if (!binding.verify())
      {
        System.out.println("WARN: ReconcileMembership rejected unverified binding from prvId="
            + remotePrvId);
        return false;
      }

      return applyBindingIfNewer(localMembership, binding);
    }
    catch (final Exception e)
    {
      System.out.println("WARN: pullAndMergeBinding failed from prvId="
          + remotePrvId + ": " + e);
      return false;
    }
  }

  /**
   * Apply {@code binding} if its Version is strictly greater than the local
   * binding obj (and act-row) Version. Writes both stores.
   */
  static boolean applyBindingIfNewer(final DomId membershipDomId,
                                      final Binding binding)
      throws DomatarException
  {
    final DomId bindingId = bindingDomId(membershipDomId);
    final Obj existing = ObjDb.getObj(bindingId);

    long localVersion = -1L;

    if (existing != null && existing.attrs != null
        && existing.attrs.getAttr("Version") != null)
    {
      try
      {
        localVersion = Long.parseLong(existing.attrs.getAttr("Version"));
      }
      catch (final NumberFormatException ignored)
      {
        localVersion = -1L;
      }
    }

    if (binding.version <= localVersion)
      return false;

    final ObjAttrs attrs = new ObjAttrs();

    attrs.addAttr("ActId",         binding.actId);
    attrs.addAttr("GenesisPubKey", binding.genesisPubKeyB64);
    attrs.addAttr("OwnId",         binding.ownId);
    attrs.addAttr("OwnPubKey",     binding.ownPubKeyB64);
    attrs.addAttr("Version",       Long.toString(binding.version));
    attrs.addAttr("NotBefore",     Long.toString(binding.notBefore));
    attrs.addAttr("GenesisSig",    binding.genesisSig);

    if (existing != null)
      ObjDb.modifyObj(existing.modify(null, null, null, null, null, attrs));
    else
      ObjDb.addObj(new Obj(bindingId, "login", "binding",
                           "Binding", "actId->ownId binding (replicated)", attrs));

    // Keep the home provider's act-row binding in step (K7).
    try
    {
      ActDb.setBinding(binding.actId, binding.genesisPubKeyB64, binding.ownPubKeyB64,
                       binding.version, binding.notBefore, binding.genesisSig);
    }
    catch (final Exception e)
    {
      // Act row may not exist on a pure object-only replica yet; binding obj
      // is still the replicated truth for membership.
      System.out.println("WARN: ActDb.setBinding failed for actId="
          + binding.actId + ": " + e);
    }

    return true;
  }

  private static Set<String> resolveRemotePrvIds(final JsonMsg inMsg,
                                                  final DomId dst,
                                                  final String localPrvId)
      throws DomatarException
  {
    final Set<String> result = new LinkedHashSet<String>();

    // Optional explicit replica list in the request body.
    final JsonList replicas = inMsg.getAttrs() != null
        ? inMsg.getAttrs().getAttrList("Replicas")
        : null;

    if (replicas != null && replicas.size() > 0)
    {
      for (int i = 0; i < replicas.size(); i++)
      {
        final Object item = replicas.get(i);

        if (!(item instanceof JsonMap))
          continue;

        final String prvId = ((JsonMap) item).getString("PrvId");

        if (prvId != null && !prvId.isEmpty()
            && (localPrvId == null || !localPrvId.equals(prvId)))
          result.add(prvId);
      }

      return result;
    }

    // Default: distinct PrvId from local peer rows.
    final List<Obj> rows = ObjDb.getObjPrefix(dst.hstId, dst.appId, dst.actId, "peer", null, 1000);

    for (final Obj row : rows)
    {
      final String prvId = row.attrs.getAttr("PrvId");

      if (prvId == null || prvId.isEmpty())
        continue;

      if (localPrvId != null && localPrvId.equals(prvId))
        continue;

      result.add(prvId);
    }

    return result;
  }

  private static String readContainerVersion(final DomId containerDomId)
      throws DomatarException
  {
    final Obj container = ObjDb.getObj(containerDomId);

    if (container == null || container.attrs == null)
      return "";

    final String version = container.attrs.getAttr("Version");

    return version != null ? version : "";
  }

  private static void bumpContainerVersion(final DomId containerDomId,
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

  private static DomId bindingDomId(final DomId membershipDomId) throws DomatarException
  {
    return new DomId(membershipDomId.hstId, "login", membershipDomId.actId, "binding");
  }

  private static String replicaPrvIdOrConfig(final String hstId)
  {
    final String parsed = DomId.replicaPrvId(hstId);

    return parsed != null ? parsed : DomatarConfig.getPrvId();
  }

  private static JsonMap peerPublicEntry(final ObjAttrs a) throws DomatarException
  {
    final JsonMap entry = new JsonHashMap();

    entry.put("UsrId",   a.getAttr("UsrId"));
    entry.put("UsrName", a.getAttr("UsrName"));
    entry.put("AppId",   a.getAttr("AppId"));
    entry.put("PrvId",   a.getAttr("PrvId"));
    entry.put("IsRoot",  a.getAttr("IsRoot"));
    entry.put("AddedAt", a.getAttr("AddedAt"));

    return entry;
  }

  private static ObjAttrs peerAttrs(final String usrId, final String usrName,
                                    final String appId, final String prvId,
                                    final String isRoot, final String addedAt,
                                    final String peerVersion, final String tombstone,
                                    final String fpVersion)
      throws DomatarException
  {
    final ObjAttrs rowAttrs = new ObjAttrs();

    rowAttrs.addAttr("UsrId",       usrId);
    rowAttrs.addAttr("UsrName",     usrName);
    rowAttrs.addAttr("AppId",       appId);
    rowAttrs.addAttr("PrvId",       prvId);
    rowAttrs.addAttr("IsRoot",      isRoot);
    rowAttrs.addAttr("AddedAt",     addedAt);
    rowAttrs.addAttr("PeerVersion", peerVersion);
    rowAttrs.addAttr("Tombstone",   tombstone);
    rowAttrs.addAttr("FpVersion",   fpVersion);

    return rowAttrs;
  }

  /**
   * Prefer the value already on the peer row; otherwise the account's stored
   * act.FpVersion; otherwise "1" (Spec-ActId-Versioning.txt PART 3.4).
   */
  private static String resolveFpVersion(final String stored, final String actId)
      throws DomatarException
  {
    if (stored != null && !stored.isEmpty())
      return stored;

    return Integer.toString(ActDb.getFpVersion(actId));
  }

  /**
   * Compare PeerVersion stamps. Prefer numeric decode of the base64 time;
   * fall back to lexicographic compare if decode fails.
   */
  static int comparePeerVersion(final String a, final String b)
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
}
