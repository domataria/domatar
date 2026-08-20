/*
 * Copyright (c) 2024 Domatar
 */

package com.login.webui;

import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;

import java.util.Arrays;

import com.domatar.core.Context;
import com.domatar.core.DomatarConfig;
import com.domatar.crypto.AccountKeys;
import com.domatar.crypto.Binding;
import com.domatar.crypto.Delegation;
import com.domatar.crypto.MasterKey;
import com.domatar.db.ActDb;
import com.domatar.install.HostPresence;
import com.domatar.install.MembershipFanout;
import com.domatar.install.MembershipMigrator;
import com.domatar.install.ObjectOnlyPeers;
import com.domatar.install.UserSubstrateIds;
import com.domatar.servlet.DomatarServlet;
import com.domatar.util.Act;
import com.domatar.util.Base64Encoder;
import com.domatar.util.IdGen;
import com.domatar.util.JsonList;
import com.domatar.util.JsonMap;
import com.domatar.util.JsonMsg;
import com.domatar.util.ObjAttrs;
import com.domatar.util.DomId;
import com.domatar.util.DomatarException;
import com.domatar.util.DomatarMsgClient;

/**
 * Login-app browser-facing servlet. Handles every identity-management
 * UI action that requires a verified session (Spec-LoginApp.txt PART
 * 5 / PART 8). Public actions live in ActWui:
 *
 *   - Anonymous SignUp + SignIn + Logout-of-expired-session : ActWui.
 *   - Authenticated AddLogin / Rename / ChangePwd / DeleteAct /
 *     Import / ListMembership / SignOut                       : here.
 *
 * DomatarServlet's pre-verification gate (the parent's doAction) takes
 * care of authenticating the cookies on every request that reaches
 * this servlet, so getMsg can assume context.actId is non-null and
 * verified. AccountsWui then routes to the appropriate Domatar
 * primitive on the appropriate destination per spec PART 5.
 */
@WebServlet("/AccountsWui/*")
public class AccountsWui extends DomatarServlet
{
  private static final long serialVersionUID = 7184213213213857811L;

  @Override
  protected JsonMsg getMsg(final HttpServletRequest req,
                           final DomId srcDomId,
                           final Context context,
                           final Act srcAct,
                           final DomatarMsgClient msgClient) throws DomatarException
  {
    final String opr = getParam(req, "Action");

    final JsonMsg msg = new JsonMsg();

    if (opr == null)
    {
      msg.addError("", "Missing Action");
      return msg;
    }

    if ("ListMembership".equals(opr))
      return buildListMembership(srcDomId, context);

    if ("ReconcileMembership".equals(opr))
      return buildReconcileMembership(srcDomId, context);

    if ("AddLogin".equals(opr))
      return buildAddLogin(req, srcDomId, context);

    if ("RenameUsrId".equals(opr))
      return buildRenameUsrId(req, srcDomId, context);

    if ("ChangeUsrName".equals(opr))
      return buildChangeUsrName(req, srcDomId, context);

    if ("ChangePwd".equals(opr))
      return buildChangePwd(req, srcDomId, context);

    if ("RemoveLogin".equals(opr))
      return buildRemoveLogin(req, srcDomId, context);

    if ("SignOut".equals(opr))
      return buildSignOut(req, srcDomId, context);

    if ("ImportLogin".equals(opr))
      return runImportLogin(req, srcDomId, context, msgClient);

    if ("AttachProvider".equals(opr))
      return runAttachProvider(req, srcDomId, context, msgClient);

    if ("RemoveProvider".equals(opr))
      return runRemoveProvider(req, srcDomId, context, msgClient);

    msg.addError(opr, "Unknown action");
    return msg;
  }

  // ------------------------------------------------------------------
  // ListMembership (Spec-Login-Multiple PART 7 - authoritative peer list)
  // ------------------------------------------------------------------

  private JsonMsg buildListMembership(final DomId srcDomId,
                                       final Context context) throws DomatarException
  {
    final String actId = context.actId;
    final String prvId = DomatarConfig.getPrvId();

    final DomId dst = new DomId(DomId.subHstId("login", actId, prvId),
                                 "login", actId, "membership");

    final JsonMsg msg = new JsonMsg();

    msg.addRequestHead(srcDomId, dst, context);
    msg.addRequestBody("ListMembership", null);
    msg.addClsId("login", "membership");

    return msg;
  }

  // ------------------------------------------------------------------
  // ReconcileMembership (Spec-Login-Multiple PART 10.4 - display-time pull)
  // ------------------------------------------------------------------

  private JsonMsg buildReconcileMembership(final DomId srcDomId,
                                            final Context context) throws DomatarException
  {
    final String actId = context.actId;
    final String prvId = DomatarConfig.getPrvId();

    final DomId dst = new DomId(DomId.subHstId("login", actId, prvId),
                                 "login", actId, "membership");

    final JsonMsg msg = new JsonMsg();

    msg.addRequestHead(srcDomId, dst, context);
    msg.addRequestBody("ReconcileMembership", null);
    msg.addClsId("login", "membership");

    return msg;
  }

  // ------------------------------------------------------------------
  // AddLogin (PART 8.3 - link a new app login to existing actId)
  // ------------------------------------------------------------------

  private JsonMsg buildAddLogin(final HttpServletRequest req,
                                 final DomId srcDomId,
                                 final Context context) throws DomatarException
  {
    final JsonMsg msg = new JsonMsg();

    final String appId     = getParam(req, "AppId");
    final String localname = getParam(req, "Localname");
    String usrName   = getParam(req, "UsrName");
    final String pwd       = getParam(req, "Pwd");

    if (appId == null || localname == null || pwd == null)
    {
      msg.addError("AddLogin", "Missing AppId / Localname / Pwd");
      return msg;
    }

    if (usrName == null)
      usrName = localname;

    final String actId = context.actId;
    final String hstId = DomId.subHstId(appId, actId);
    final String prvId = srcDomId.hstId;

    final ObjAttrs attrs = new ObjAttrs();

    // Carry the EXISTING actId in body.ActId so addAct takes the
    // link-mode path (handle contains '@', verified+owner-match).
    attrs.addAttr("ActId",   actId);
    attrs.addAttr("UsrId",   localname);
    attrs.addAttr("UsrName", usrName);
    attrs.addAttr("Pwd",     pwd);
    attrs.addAttr("HstId",   hstId);
    attrs.addAttr("Domain",  req.getServerName());
    attrs.addAttr("PrvId",   prvId);
    attrs.addAttr("Ip",      context.usrIp);

    final DomId dst = new DomId(appId, "act", "act@act", "actManager");

    msg.addRequestHead(srcDomId, dst, context);
    msg.addRequestBody("AddAct", attrs);
    msg.addClsId("act", "actManager");

    return msg;
  }

  // ------------------------------------------------------------------
  // RenameUsrId (PART 8.4 - <localname> change only)
  // ------------------------------------------------------------------

  private JsonMsg buildRenameUsrId(final HttpServletRequest req,
                                    final DomId srcDomId,
                                    final Context context) throws DomatarException
  {
    final JsonMsg msg = new JsonMsg();

    final String currentUsrId = getParam(req, "UsrId");
    final String newLocalname = getParam(req, "NewLocalname");

    if (currentUsrId == null || newLocalname == null)
    {
      msg.addError("RenameUsrId", "Missing UsrId / NewLocalname");
      return msg;
    }

    final String rowAppId = DomId.getAppId(currentUsrId);

    if (rowAppId == null)
    {
      msg.addError("RenameUsrId", "UsrId must be of the form <localname>@<appId>");
      return msg;
    }

    final ObjAttrs attrs = new ObjAttrs();

    attrs.addAttr("ActId",        context.actId);
    attrs.addAttr("UsrId",        currentUsrId);
    attrs.addAttr("NewLocalname", newLocalname);

    final DomId dst = new DomId(rowAppId, "act", "act@act", "actManager");

    msg.addRequestHead(srcDomId, dst, context);
    msg.addRequestBody("UpdateAct", attrs);
    msg.addClsId("act", "actManager");

    return msg;
  }

  // ------------------------------------------------------------------
  // ChangeUsrName (PART 8.5 - display name only)
  // ------------------------------------------------------------------

  private JsonMsg buildChangeUsrName(final HttpServletRequest req,
                                      final DomId srcDomId,
                                      final Context context) throws DomatarException
  {
    final JsonMsg msg = new JsonMsg();

    final String currentUsrId = getParam(req, "UsrId");
    final String newUsrName   = getParam(req, "NewUsrName");

    if (currentUsrId == null || newUsrName == null)
    {
      msg.addError("ChangeUsrName", "Missing UsrId / NewUsrName");
      return msg;
    }

    final String rowAppId = DomId.getAppId(currentUsrId);

    if (rowAppId == null)
    {
      msg.addError("ChangeUsrName", "UsrId must be of the form <localname>@<appId>");
      return msg;
    }

    final ObjAttrs attrs = new ObjAttrs();

    attrs.addAttr("ActId",      context.actId);
    attrs.addAttr("UsrId",      currentUsrId);
    attrs.addAttr("NewUsrName", newUsrName);

    final DomId dst = new DomId(rowAppId, "act", "act@act", "actManager");

    msg.addRequestHead(srcDomId, dst, context);
    msg.addRequestBody("UpdateAct", attrs);
    msg.addClsId("act", "actManager");

    return msg;
  }

  // ------------------------------------------------------------------
  // ChangePwd (PART 8.6)
  // ------------------------------------------------------------------

  private JsonMsg buildChangePwd(final HttpServletRequest req,
                                  final DomId srcDomId,
                                  final Context context) throws DomatarException
  {
    final JsonMsg msg = new JsonMsg();

    final String currentUsrId = getParam(req, "UsrId");
    final String oldPwd       = getParam(req, "OldPwd");
    final String newPwd       = getParam(req, "NewPwd");

    if (currentUsrId == null || oldPwd == null || newPwd == null)
    {
      msg.addError("ChangePwd", "Missing UsrId / OldPwd / NewPwd");
      return msg;
    }

    final String rowAppId = DomId.getAppId(currentUsrId);

    if (rowAppId == null)
    {
      msg.addError("ChangePwd", "UsrId must be of the form <localname>@<appId>");
      return msg;
    }

    final ObjAttrs attrs = new ObjAttrs();

    attrs.addAttr("ActId",  context.actId);
    attrs.addAttr("UsrId",  currentUsrId);
    attrs.addAttr("OldPwd", oldPwd);
    attrs.addAttr("NewPwd", newPwd);

    final DomId dst = new DomId(rowAppId, "act", "act@act", "actManager");

    msg.addRequestHead(srcDomId, dst, context);
    msg.addRequestBody("ChangePwd", attrs);
    msg.addClsId("act", "actManager");

    return msg;
  }

  // ------------------------------------------------------------------
  // RemoveLogin (PART 8.8 - delete the act row at <appId>'s central host)
  // ------------------------------------------------------------------

  private JsonMsg buildRemoveLogin(final HttpServletRequest req,
                                    final DomId srcDomId,
                                    final Context context) throws DomatarException
  {
    final JsonMsg msg = new JsonMsg();

    final String currentUsrId = getParam(req, "UsrId");

    if (currentUsrId == null)
    {
      msg.addError("RemoveLogin", "Missing UsrId");
      return msg;
    }

    final String rowAppId = DomId.getAppId(currentUsrId);

    if (rowAppId == null)
    {
      msg.addError("RemoveLogin", "UsrId must be of the form <localname>@<appId>");
      return msg;
    }

    final ObjAttrs attrs = new ObjAttrs();

    attrs.addAttr("ActId", context.actId);
    attrs.addAttr("UsrId", currentUsrId);

    final DomId dst = new DomId(rowAppId, "act", "act@act", "actManager");

    msg.addRequestHead(srcDomId, dst, context);
    msg.addRequestBody("DeleteAct", attrs);
    msg.addClsId("act", "actManager");

    return msg;
  }

  // ------------------------------------------------------------------
  // SignOut (PART 8.9 - dispatches Logout to current session's verifier)
  // ------------------------------------------------------------------

  private JsonMsg buildSignOut(final HttpServletRequest req,
                                final DomId srcDomId,
                                final Context context) throws DomatarException
  {
    final JsonMsg msg = new JsonMsg();

    final String usrId = context.usrId;

    if (usrId == null)
    {
      msg.addError("SignOut", "No active session");
      return msg;
    }

    String rowAppId = DomId.getAppId(usrId);

    if (rowAppId == null)
      rowAppId = srcDomId.hstId;

    final ObjAttrs attrs = new ObjAttrs();

    attrs.addAttr("UsrId", usrId);
    attrs.addAttr("Ip",    context.usrIp);

    final DomId dst = new DomId(rowAppId, "act", "act@act", "actManager");

    msg.addRequestHead(srcDomId, dst, context);
    msg.addRequestBody("Logout", attrs);
    msg.addClsId("act", "actManager");

    return msg;
  }

  // ------------------------------------------------------------------
  // ImportLogin (PART 8.7 - VerifyLogin then write directory mirror)
  // ------------------------------------------------------------------

  /**
   * Two-step: first verify the supplied (usrId, pwd) at its app's
   * central host, then - if the returned actId matches the verified
   * caller's actId - write an AddPeer to the local membership replica.
   * Returns a synthetic response so DomatarServlet does not try to
   * dispatch again (we already did the dispatches inline).
   */
  private JsonMsg runImportLogin(final HttpServletRequest req,
                                  final DomId srcDomId,
                                  final Context context,
                                  final DomatarMsgClient msgClient) throws DomatarException
  {
    final JsonMsg out = new JsonMsg();

    final String importUsrId = getParam(req, "UsrId");
    final String pwd         = getParam(req, "Pwd");

    if (importUsrId == null || pwd == null)
    {
      out.addError("ImportLogin", "Missing UsrId / Pwd");
      return out;
    }

    final String rowAppId = DomId.getAppId(importUsrId);

    if (rowAppId == null)
    {
      out.addError("ImportLogin", "UsrId must be of the form <localname>@<appId>");
      return out;
    }

    // Step 1: VerifyLogin at the supplied appId's central host using the
    // (usrId, pwd) pair. Note the public-action style: we pass Pwd, not
    // a token (the user is proving they own this row right now).
    final JsonMsg verifyMsg = new JsonMsg();

    final ObjAttrs verifyAttrs = new ObjAttrs();

    verifyAttrs.addAttr("UsrId", importUsrId);
    verifyAttrs.addAttr("Pwd",   pwd);
    verifyAttrs.addAttr("Ip",    context.usrIp);

    final DomId verifyDst = new DomId(rowAppId, "act", "act@act", "actManager");

    verifyMsg.addRequestHead(srcDomId, verifyDst, context);
    verifyMsg.addRequestBody("Login", verifyAttrs);
    verifyMsg.addClsId("act", "actManager");

    final JsonMsg verifyResp = msgClient.send(verifyDst, verifyMsg);

    final String returnedActId  = verifyResp.getAttr("ActId");
    final String returnedUsrName = verifyResp.getAttr("UsrName");
    final String loggedIn       = verifyResp.getAttr("LoggedIn");

    if (!"True".equals(loggedIn) || returnedActId == null)
    {
      out.addError("ImportLogin", "Wrong UsrId / Pwd at " + rowAppId);
      return out;
    }

    if (!returnedActId.equals(context.actId))
    {
      // Refuse: PART 8.7. The credentials are valid but they refer to
      // a DIFFERENT actId; the user would have to sign in as that
      // actId to manage it.
      out.addError("ImportLogin", "This is a different account");
      return out;
    }

    // Step 2: AddPeer on the local membership replica.
    final String now = IdGen.getCurTimeBase64();
    final String localPrvId = DomatarConfig.getPrvId();
    final DomId membershipDst = new DomId(
        DomId.subHstId("login", context.actId, localPrvId),
        "login", context.actId, "membership");

    final ObjAttrs peerAttrs = new ObjAttrs();

    peerAttrs.addAttr("UsrId",       importUsrId);
    peerAttrs.addAttr("UsrName",     returnedUsrName != null ? returnedUsrName : importUsrId);
    peerAttrs.addAttr("AppId",       rowAppId);
    peerAttrs.addAttr("PrvId",       localPrvId);
    peerAttrs.addAttr("IsRoot",      "False");
    peerAttrs.addAttr("AddedAt",     now);
    peerAttrs.addAttr("PeerVersion", now);
    peerAttrs.addAttr("FpVersion",   Integer.toString(ActDb.getFpVersion(context.actId)));

    final JsonMsg peerMsg = new JsonMsg();

    peerMsg.addRequestHead(srcDomId, membershipDst, context);
    peerMsg.addRequestBody("AddPeer", peerAttrs);
    peerMsg.addClsId("login", "membership");

    msgClient.send(membershipDst, peerMsg);
    MembershipFanout.push(context.actId, "AddPeer", peerAttrs, msgClient);

    final ObjAttrs outAttrs = new ObjAttrs();

    outAttrs.addAttr("Imported", "True");
    outAttrs.addAttr("UsrId",    importUsrId);

    out.addResponseBody("ImportLogin", outAttrs);

    return out;
  }

  // ------------------------------------------------------------------
  // AttachProvider (Spec-Login-Multiple PART 8 - driven from SOURCE)
  // ------------------------------------------------------------------

  /**
   * Attach a NEW provider N to the verified caller's account.
   *
   * <p>Runs entirely on the SOURCE provider (where the session cookie is
   * valid). Order: re-verify → issue Delegation(N) → link-mode AddAct on N
   * → AttachProvision on N → AddPeer locally + fan-out.
   *
   * <p>Routing: AddAct / AttachProvision are sent to
   * {@code new DomId(NewPrvId, "act", "act@act", "actManager")} so HttpClient
   * resolves N's domain; body {@code AppId=NewAppId} sets the login suffix.
   * Returns a synthetic response (ImportLogin style).
   */
  private JsonMsg runAttachProvider(final HttpServletRequest req,
                                     final DomId srcDomId,
                                     final Context context,
                                     final DomatarMsgClient msgClient) throws DomatarException
  {
    final JsonMsg out = new JsonMsg();

    final String newPrvId    = getParam(req, "NewPrvId");
    final String newDomain   = getParam(req, "NewDomain");
    final String newAppId    = getParam(req, "NewAppId");
    final String localname   = getParam(req, "Localname");
    final String usrName     = getParam(req, "UsrName");
    final String pwd         = getParam(req, "Pwd");
    final String reverifyPwd = getParam(req, "ReverifyPwd");
    String isSigning         = getParam(req, "IsSigning");

    if (isSigning == null || isSigning.isEmpty())
      isSigning = "True";

    if (newPrvId == null || newDomain == null || newAppId == null
        || localname == null || pwd == null || reverifyPwd == null)
    {
      out.addError("AttachProvider",
          "Missing NewPrvId / NewDomain / NewAppId / Localname / Pwd / ReverifyPwd");
      return out;
    }

    final String actId = context.actId;
    final String usrId = context.usrId;

    if (actId == null || usrId == null)
    {
      out.addError("AttachProvider", "Not signed in");
      return out;
    }

    // --- a. RE-VERIFY (PART 8.2) ---
    final String rowAppId = DomId.getAppId(usrId);

    if (rowAppId == null)
    {
      out.addError("AttachProvider", "Context usrId must be <localname>@<appId>");
      return out;
    }

    final JsonMsg verifyMsg = new JsonMsg();
    final ObjAttrs verifyAttrs = new ObjAttrs();

    verifyAttrs.addAttr("UsrId", usrId);
    verifyAttrs.addAttr("Pwd",   reverifyPwd);
    verifyAttrs.addAttr("Ip",    context.usrIp);

    final DomId verifyDst = new DomId(rowAppId, "act", "act@act", "actManager");

    verifyMsg.addRequestHead(srcDomId, verifyDst, context);
    verifyMsg.addRequestBody("Login", verifyAttrs);
    verifyMsg.addClsId("act", "actManager");

    final JsonMsg verifyResp = msgClient.send(verifyDst, verifyMsg);
    final String loggedIn = verifyResp.getAttr("LoggedIn");
    final String returnedActId = verifyResp.getAttr("ActId");

    if (!"True".equals(loggedIn) || returnedActId == null || !returnedActId.equals(actId))
    {
      System.out.println("WARN: AttachProvider re-verify failed for actId=" + actId
          + " usrId=" + usrId);
      out.addError("AttachProvider", "Re-verify failed");
      return out;
    }

    // --- b. ISSUE DELEGATION naming N ---
    final String sealedOwn = ActDb.getOwnPrvKey(actId);

    if (sealedOwn == null)
    {
      out.addError("AttachProvider", "No ownership key on this provider — cannot attach");
      return out;
    }

    byte[] rawOwn = null;
    final Delegation deleg;
    final Binding binding;

    try
    {
      rawOwn = MasterKey.open(sealedOwn);
      final AccountKeys own = AccountKeys.fromPrivKey(rawOwn);
      final long notAfter = System.currentTimeMillis() + DomatarConfig.getDelegTtlMs();

      deleg = Delegation.issue(actId, own, newPrvId, notAfter);
      binding = ActDb.getBinding(actId);

      if (binding == null || !binding.verify())
      {
        out.addError("AttachProvider", "Local binding missing or invalid");
        return out;
      }

      // --- c. LINK-MODE AddAct on N (must precede AttachProvision) ---
      final String newUsrId = localname + "@" + newAppId;
      final DomId nActMgr = new DomId(newPrvId, "act", "act@act", "actManager");

      final ObjAttrs addAttrs = new ObjAttrs();

      addAttrs.addAttr("ActId",   actId);
      addAttrs.addAttr("AppId",   newAppId);
      addAttrs.addAttr("UsrId",   localname);
      addAttrs.addAttr("UsrName", usrName != null && !usrName.isEmpty() ? usrName : localname);
      addAttrs.addAttr("Pwd",     pwd);
      addAttrs.addAttr("HstId",   DomId.subHstId(newAppId, actId));
      addAttrs.addAttr("Domain",  newDomain);
      addAttrs.addAttr("PrvId",   newPrvId);
      addAttrs.addAttr("Ip",      context.usrIp);

      final JsonMsg addMsg = new JsonMsg();

      addMsg.addRequestHead(srcDomId, nActMgr, context);
      addMsg.addRequestBody("AddAct", addAttrs);
      addMsg.addClsId("act", "actManager");

      final JsonMsg addResp = msgClient.send(nActMgr, addMsg);

      if (!"Success".equals(addResp.getError()))
      {
        out.addError("AttachProvider",
            "AddAct on N failed: " + (addResp.getErrorMsg() != null ? addResp.getErrorMsg() : addResp.getError()));
        return out;
      }

      // --- d. PROVISION (AttachProvision on N) ---
      final ObjAttrs provAttrs = new ObjAttrs();

      provAttrs.addAttr("ActId",                 actId);
      provAttrs.addAttr("Delegation",            deleg.toJson());
      provAttrs.addAttr("BindingActId",          binding.actId);
      provAttrs.addAttr("BindingGenesisPubKey",  binding.genesisPubKeyB64);
      provAttrs.addAttr("BindingOwnId",          binding.ownId);
      provAttrs.addAttr("BindingOwnPubKey",      binding.ownPubKeyB64);
      provAttrs.addAttr("BindingVersion",        Long.toString(binding.version));
      provAttrs.addAttr("BindingNotBefore",      Long.toString(binding.notBefore));
      provAttrs.addAttr("BindingSig",            binding.genesisSig);
      provAttrs.addAttr("NewUsrId",              newUsrId);
      provAttrs.addAttr("NewUsrName",            usrName != null && !usrName.isEmpty() ? usrName : localname);
      provAttrs.addAttr("Domain",                newDomain);
      provAttrs.addAttr("PrvId",                 newPrvId);
      provAttrs.addAttr("IsSigning",             isSigning);

      if (!"False".equalsIgnoreCase(isSigning))
        provAttrs.addAttr("RawOwnPrvKeyB64", Base64Encoder.encode(rawOwn));

      final JsonMsg provMsg = new JsonMsg();

      provMsg.addRequestHead(srcDomId, nActMgr, context);
      provMsg.addRequestBody("AttachProvision", provAttrs);
      provMsg.addClsId("act", "actManager");

      final JsonMsg provResp = msgClient.send(nActMgr, provMsg);

      if (!"Success".equals(provResp.getError()))
      {
        out.addError("AttachProvider",
            "AttachProvision failed: " + (provResp.getErrorMsg() != null ? provResp.getErrorMsg() : provResp.getError()));
        return out;
      }

      // --- e. RECORD EVERYWHERE: AddPeer on local replica + fan-out ---
      final String now = IdGen.getCurTimeBase64();
      final String localPrvId = DomatarConfig.getPrvId();
      final DomId localMembership = new DomId(
          DomId.subHstId("login", actId, localPrvId), "login", actId, "membership");

      final ObjAttrs peerAttrs = new ObjAttrs();

      peerAttrs.addAttr("UsrId",       newUsrId);
      peerAttrs.addAttr("UsrName",     usrName != null && !usrName.isEmpty() ? usrName : localname);
      peerAttrs.addAttr("AppId",       newAppId);
      peerAttrs.addAttr("PrvId",       newPrvId);
      peerAttrs.addAttr("IsRoot",      "False");
      peerAttrs.addAttr("AddedAt",     now);
      peerAttrs.addAttr("PeerVersion", now);
      peerAttrs.addAttr("FpVersion",   Integer.toString(ActDb.getFpVersion(actId)));

      final JsonMsg peerMsg = new JsonMsg();

      peerMsg.addRequestHead(srcDomId, localMembership, context);
      peerMsg.addRequestBody("AddPeer", peerAttrs);
      peerMsg.addClsId("login", "membership");

      msgClient.send(localMembership, peerMsg);
      MembershipFanout.push(actId, "AddPeer", peerAttrs, msgClient);

      tombstoneObjectOnlyPeer(srcDomId, context, msgClient, actId, localPrvId,
          newPrvId, now);

      // Seed N with pre-existing peers so it can discover SOURCE (and others)
      // under reconcile — otherwise N only knows itself and MergedPeers stays 0.
      try
      {
        final JsonMsg fullReq = new JsonMsg();

        fullReq.addRequestHead(srcDomId, localMembership, context);
        fullReq.addRequestBody("ListPeersFull", null);
        fullReq.addClsId("login", "membership");

        final JsonMsg fullResp = msgClient.send(localMembership, fullReq);
        final JsonList peers = fullResp.getAttrs() != null
            ? fullResp.getAttrs().getAttrList("Peers") : null;

        if (peers != null)
        {
          for (int i = 0; i < peers.size(); i++)
          {
            final Object el = peers.get(i);

            if (!(el instanceof JsonMap))
              continue;

            final JsonMap entry = (JsonMap) el;

            if ("True".equals(entry.getString("Tombstone")))
              continue;
            if (newPrvId.equals(entry.getString("PrvId")))
              continue;

            final ObjAttrs seedAttrs = new ObjAttrs();

            seedAttrs.addAttr("UsrId",       entry.getString("UsrId"));
            seedAttrs.addAttr("UsrName",     entry.getString("UsrName"));
            seedAttrs.addAttr("AppId",       entry.getString("AppId"));
            seedAttrs.addAttr("PrvId",       entry.getString("PrvId"));
            seedAttrs.addAttr("IsRoot",      entry.getString("IsRoot") != null ? entry.getString("IsRoot") : "False");
            seedAttrs.addAttr("AddedAt",     entry.getString("AddedAt") != null ? entry.getString("AddedAt") : now);
            seedAttrs.addAttr("PeerVersion", entry.getString("PeerVersion") != null ? entry.getString("PeerVersion") : now);
            seedAttrs.addAttr("FpVersion",   entry.getString("FpVersion") != null ? entry.getString("FpVersion") : "1");

            MembershipFanout.push(actId, "AddPeer", seedAttrs, msgClient);
          }
        }
      }
      catch (final Exception e)
      {
        System.out.println("WARN: AttachProvider seed-existing-peers fan-out failed: " + e);
      }

      final ObjAttrs outAttrs = new ObjAttrs();

      outAttrs.addAttr("Attached", "True");
      outAttrs.addAttr("PrvId",    newPrvId);
      outAttrs.addAttr("UsrId",    newUsrId);

      out.addResponseBody("AttachProvider", outAttrs);
      return out;
    }
    finally
    {
      if (rawOwn != null)
        Arrays.fill(rawOwn, (byte) 0);
    }
  }

  // ------------------------------------------------------------------
  // RemoveProvider (Spec-Login-Multiple PART 11 - cooperative)
  // ------------------------------------------------------------------

  /**
   * Cooperative stop-using-provider: tombstone peer row(s) for {@code PrvId}
   * on the local membership replica and fan out so LWW converges.
   *
   * <p>This does NOT cryptographically evict a dishonest N — that requires
   * a REBIND (Spec-OwnIds.txt PART 10). Cooperative removal only stops
   * renewing N's delegation; N's last Delegation lapses at NotAfter.
   *
   * <p>TODO (optional PART 11.2c): best-effort DropReplica on N to delete
   * login-&lt;actId&gt;-N objs + act row + OwnPrvKey — deferred.
   */
  private JsonMsg runRemoveProvider(final HttpServletRequest req,
                                     final DomId srcDomId,
                                     final Context context,
                                     final DomatarMsgClient msgClient) throws DomatarException
  {
    final JsonMsg out = new JsonMsg();
    final String targetPrvId = getParam(req, "PrvId");

    if (targetPrvId == null || targetPrvId.isEmpty())
    {
      out.addError("RemoveProvider", "Missing PrvId");
      return out;
    }

    final String actId = context.actId;

    if (actId == null)
    {
      out.addError("RemoveProvider", "Not signed in");
      return out;
    }

    final String localPrvId = DomatarConfig.getPrvId();
    final DomId localMembership = new DomId(
        DomId.subHstId("login", actId, localPrvId), "login", actId, "membership");

    final JsonList loginHomes = listLoginHomePeers(srcDomId, context, msgClient,
        actId, localPrvId, localMembership);

    int loginHomeCount = 0;
    int targetLoginHomeCount = 0;

    if (loginHomes != null)
    {
      for (int i = 0; i < loginHomes.size(); i++)
      {
        final Object el = loginHomes.get(i);

        if (!(el instanceof JsonMap))
          continue;

        final JsonMap entry = (JsonMap) el;

        if ("True".equals(entry.getString("Tombstone")))
          continue;

        if (!isLoginHomePeer(entry))
          continue;

        loginHomeCount++;

        if (targetPrvId.equals(entry.getString("PrvId")))
          targetLoginHomeCount++;
      }
    }

    if (targetLoginHomeCount == 0)
    {
      out.addError("RemoveProvider", "No active peer for provider " + targetPrvId);
      return out;
    }

    if (loginHomeCount - targetLoginHomeCount <= 0)
    {
      out.addError("RemoveProvider", "cannot remove your last login");
      return out;
    }

    final String now = IdGen.getCurTimeBase64();
    final JsonList loginReplicaPeers = listLoginReplicaPeers(srcDomId, context,
        msgClient, localMembership);

    if (loginReplicaPeers != null)
    {
      for (int i = 0; i < loginReplicaPeers.size(); i++)
      {
        final Object el = loginReplicaPeers.get(i);

        if (!(el instanceof JsonMap))
          continue;

        final JsonMap entry = (JsonMap) el;

        if ("True".equals(entry.getString("Tombstone")))
          continue;

        if (!targetPrvId.equals(entry.getString("PrvId")))
          continue;

        final String usrId = entry.getString("UsrId");

        if (usrId == null || usrId.isEmpty())
          continue;

        final ObjAttrs peerAttrs = new ObjAttrs();

        peerAttrs.addAttr("UsrId",       usrId);
        peerAttrs.addAttr("PeerVersion", now);

        final JsonMsg tombMsg = new JsonMsg();

        tombMsg.addRequestHead(srcDomId, localMembership, context);
        tombMsg.addRequestBody("TombstonePeer", peerAttrs);
        tombMsg.addClsId("login", "membership");

        msgClient.send(localMembership, tombMsg);
        MembershipFanout.push(actId, "TombstonePeer", peerAttrs, msgClient);
      }
    }

    if (HostPresence.hasPortableAppHosts(actId, targetPrvId))
      keepObjectOnlyPresence(msgClient, actId, localPrvId, targetPrvId, now);

    final ObjAttrs outAttrs = new ObjAttrs();

    outAttrs.addAttr("Removed", "True");
    outAttrs.addAttr("PrvId",   targetPrvId);

    out.addResponseBody("RemoveProvider", outAttrs);
    return out;
  }

  /**
   * Promote-to-login: drop {@code peer-prv-N} on domatar membership.
   * Missing object-only row is success (plain attach, no prior foreign install).
   */
  private static void tombstoneObjectOnlyPeer(final DomId srcDomId,
                                              final Context context,
                                              final DomatarMsgClient msgClient,
                                              final String actId,
                                              final String localPrvId,
                                              final String newPrvId,
                                              final String now)
  {
    try
    {
      final DomId domatarMem = UserSubstrateIds.membership(actId, localPrvId);
      final ObjAttrs tombAttrs = new ObjAttrs();

      tombAttrs.addAttr("PrvId", newPrvId);
      tombAttrs.addAttr("IsLoginHome", "False");
      tombAttrs.addAttr("ObjId", ObjectOnlyPeers.objId(newPrvId));
      tombAttrs.addAttr("PeerVersion", now);

      final JsonMsg tombMsg = new JsonMsg();

      tombMsg.addRequestHead(srcDomId, domatarMem, context);
      tombMsg.addRequestBody("TombstonePeer", tombAttrs);
      tombMsg.addClsId("domatar", "membership");

      msgClient.send(domatarMem, tombMsg);
      MembershipFanout.push(actId, "TombstonePeer", tombAttrs, msgClient);
    }
    catch (final Exception e)
    {
      System.out.println("WARN: AttachProvider tombstone object-only peer: " + e);
    }
  }

  /**
   * After removing a login home, keep object-only presence when portable
   * app hosts remain on that provider. Does not delete app hst/obj.
   */
  private static void keepObjectOnlyPresence(final DomatarMsgClient msgClient,
                                             final String actId,
                                             final String localPrvId,
                                             final String targetPrvId,
                                             final String now)
  {
    try
    {
      final DomId domatarMem = UserSubstrateIds.membership(actId, localPrvId);
      final String fp = Integer.toString(ActDb.getFpVersion(actId));

      MembershipMigrator.upsertObjectOnlyPeer(domatarMem, targetPrvId,
          now, now, "False", fp);

      final ObjAttrs ooAttrs = new ObjAttrs();

      ooAttrs.addAttr("PrvId", targetPrvId);
      ooAttrs.addAttr("IsLoginHome", "False");
      ooAttrs.addAttr("AddedAt", now);
      ooAttrs.addAttr("PeerVersion", now);
      ooAttrs.addAttr("Tombstone", "False");
      ooAttrs.addAttr("FpVersion", fp);

      MembershipFanout.push(actId, "AddPeer", ooAttrs, msgClient);
    }
    catch (final Exception e)
    {
      System.out.println("WARN: RemoveProvider keep object-only presence: " + e);
    }
  }

  /** Last-login count uses domatar membership (object-only + login-homes). */
  private static JsonList listLoginHomePeers(final DomId srcDomId,
                                             final Context context,
                                             final DomatarMsgClient msgClient,
                                             final String actId,
                                             final String localPrvId,
                                             final DomId localMembership)
  {
    try
    {
      final DomId domatarMem = UserSubstrateIds.membership(actId, localPrvId);
      final JsonMsg listMsg = new JsonMsg();

      listMsg.addRequestHead(srcDomId, domatarMem, context);
      listMsg.addRequestBody("ListPeersFull", null);
      listMsg.addClsId("domatar", "membership");

      final JsonMsg listResp = msgClient.send(domatarMem, listMsg);

      if (listResp != null && listResp.getAttrs() != null)
      {
        final JsonList peers = listResp.getAttrs().getAttrList("Peers");

        if (peers != null)
          return peers;
      }
    }
    catch (final Exception e)
    {
      System.out.println("WARN: RemoveProvider domatar ListPeersFull: " + e);
    }

    return listLoginReplicaPeers(srcDomId, context, msgClient, localMembership);
  }

  private static JsonList listLoginReplicaPeers(final DomId srcDomId,
                                                final Context context,
                                                final DomatarMsgClient msgClient,
                                                final DomId localMembership)
  {
    try
    {
      final JsonMsg listMsg = new JsonMsg();

      listMsg.addRequestHead(srcDomId, localMembership, context);
      listMsg.addRequestBody("ListPeersFull", null);
      listMsg.addClsId("login", "membership");

      final JsonMsg listResp = msgClient.send(localMembership, listMsg);

      if (listResp != null && listResp.getAttrs() != null)
        return listResp.getAttrs().getAttrList("Peers");
    }
    catch (final Exception e)
    {
      System.out.println("WARN: RemoveProvider login ListPeersFull: " + e);
    }

    return null;
  }

  private static boolean isLoginHomePeer(final JsonMap entry)
  {
    return entry != null && !"False".equals(entry.getString("IsLoginHome"));
  }
}
