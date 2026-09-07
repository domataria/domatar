package com.domatar.act;

import com.domatar.app.App;
import com.domatar.app.AppRegistry;
import com.domatar.core.Auth;
import com.domatar.core.Context;
import com.domatar.core.HttpClient;
import com.domatar.core.DomatarConfig;
import com.domatar.crypto.AccountKeys;
import com.domatar.crypto.Binding;
import com.domatar.crypto.Delegation;
import com.domatar.crypto.GenesisVault;
import com.domatar.crypto.MasterKey;
import com.domatar.db.ActDb;
import com.domatar.install.CatalogInstall;
import com.domatar.install.DefaultShellBindings;
import com.domatar.install.MembershipFanout;
import com.domatar.install.MembershipMigrator;
import com.domatar.install.MembershipReplica;
import com.domatar.install.OwnIdsRebind;
import com.domatar.install.UserInstallDispatch;
import com.domatar.install.UserSubstrateIds;
import com.domatar.install.UserSubstrateInstall;
import com.domatar.util.Act;
import com.domatar.util.Base64Encoder;
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

import java.util.LinkedHashSet;
import java.util.Map;

public class ActManagerImpl extends ObjImpl
{
  @Override
  public String handleMsg(final String msg, final Obj obj, final String contextPath,
                          final String contextRealPath, final DomatarMsgClient msgClient)
      throws DomatarException
  {
    final JsonMsg inMsg = new JsonMsg(msg);
    final String  opr   = inMsg.getOperation();
    final JsonMsg outMsg = new JsonMsg();

    if (!hasRights(inMsg, obj, msgClient))
      return notAuthorized(inMsg);

    if ("AddAct".equals(opr))
      addAct(opr, inMsg, outMsg, msgClient);
    else if ("GetAct".equals(opr))
      getAct(opr, inMsg, outMsg, msgClient);
    else if ("Login".equals(opr))
      login(opr, inMsg, outMsg, msgClient);
    else if ("Logout".equals(opr))
      logout(opr, inMsg, outMsg, msgClient);
    else if ("VerifyLogin".equals(opr))
      verifyLogin(opr, inMsg, outMsg, msgClient);
    else if ("UpdateAct".equals(opr))
      updateAct(opr, inMsg, outMsg, msgClient);
    else if ("ChangePwd".equals(opr))
      changePwd(opr, inMsg, outMsg, msgClient);
    else if ("DeleteAct".equals(opr))
      deleteAct(opr, inMsg, outMsg, msgClient);
    else if ("InstallApp".equals(opr))
      installApp(opr, inMsg, outMsg, msgClient);
    else if ("Rebind".equals(opr))
      rebind(opr, inMsg, outMsg, msgClient);
    else if ("AttachProvision".equals(opr))
      attachProvision(opr, inMsg, outMsg, msgClient);
    else if ("HostProvision".equals(opr))
      hostProvision(opr, inMsg, outMsg, msgClient);
    else
      return super.handleMsg(msg, obj, contextPath, contextRealPath, msgClient);

    return outMsg.toString();
  }

  /**
   * Per-op policy:
   *   - Login, AddAct, VerifyLogin: public. Each carries its own
   *     credentials in the body and self-checks against ActDb. They are
   *     called BEFORE a verified context exists - login is what creates
   *     the session, AddAct creates a new account, and VerifyLogin is
   *     the call by which the trust boundary establishes verification
   *     in the first place. They cannot themselves require a verified
   *     caller. (AddAct's two modes - first-time sign-up vs linking a
   *     login to an existing actId - are distinguished INSIDE addAct
   *     by inspecting the body's ActId attr; the link-mode path
   *     enforces verified+owner-match itself, see addAct.)
   *   - GetAct, Logout: verified. Looking up someone's account or
   *     logging a user out requires a real local session.
   *   - UpdateAct, ChangePwd, DeleteAct, Rebind, AttachProvision,
   *     HostProvision: verified (+ owner-match / ActId checks inside
   *     the handler). AttachProvision and HostProvision require
   *     Auth.isVerified; the Sec chain proves the caller holds a valid
   *     delegation for ActId (K4 / PART 8.3). HostProvision does not
   *     require an act row (object-only host).
   *     Spec-LoginApp.txt PART 7 / Spec-OwnIds.txt PART 10 /
   *     Spec-Login-Multiple.txt PART 8 /
   *     Spec-Foreign-Provider-Installation.txt PART 6.
   */
  @Override
  public boolean hasRights(final JsonMsg inMsg, final Obj obj,
                           final DomatarMsgClient msgClient)
      throws DomatarException
  {
    final String op = inMsg.getOperation();

    if ("Login".equals(op))
      return true;
    if ("AddAct".equals(op))
      return true;
    if ("VerifyLogin".equals(op))
      return true;

    return Auth.isVerified(inMsg);
  }

  /**
   * Owner-match check used by UpdateAct / ChangePwd / DeleteAct.
   * Returns true iff the verified caller's actId equals targetActId.
   * Each of those ops calls this AFTER the framework hasRights gate
   * already confirmed Auth.isVerified.
   */
  private static boolean callerOwnsAct(final JsonMsg inMsg, final String targetActId)
      throws DomatarException
  {
    final Context ctx = inMsg.getContext();

    return ctx != null && ctx.actId != null && ctx.actId.equals(targetActId);
  }

  /**
   * Mints (or links) an account.
   *
   *   - SIGN-UP (body ActId absent or empty): public. Generates a new
   *     Ed25519 key pair; actId = 32-char Base64 fingerprint of the
   *     public key. Sub-host id is derived server-side from the minted
   *     actId. usrId = usrHandle + "@" + appId (D3 - unchanged).
   *
   *   - LINK (body ActId present and is a fingerprint): verified+owner-match.
   *     Reuses the supplied fingerprint actId on another app's central host.
   *     The caller must be verified AS that actId.
   *     Spec-LoginApp.txt PART 8.3.
   *
   * The appId is taken from inMsg.getDstId().hstId.
   * Spec-Login.txt PART 11.1.
   */
  private void addAct(final String opr, final JsonMsg inMsg, final JsonMsg outMsg,
                      final DomatarMsgClient msgClient) throws DomatarException
  {
    final String actHandle = inMsg.getAttr("ActId");
    final String usrHandle = inMsg.getAttr("UsrId");
    final String prvId     = inMsg.getAttr("PrvId");
    // Prefer explicit AppId (cross-provider attach routes via NewPrvId as
    // the destination hstId so the message lands on N; AppId carries the
    // login's @suffix). Fall back to dst.hstId for ordinary AddAct.
    String appId = inMsg.getAttr("AppId");
    if (appId == null || appId.isEmpty())
      appId = inMsg.getDstId().hstId;

    if (appId == null || appId.length() == 0)
      throw new DomatarException("Missing appId on AddAct dispatch destination");

    final String identityErr = SignupAppId.rejectIdentity(appId);
    if (identityErr != null)
    {
      outMsg.addError(opr, identityErr);
      return;
    }

    if (usrHandle == null)
      throw new DomatarException("Missing usrId");

    if (prvId == null)
      throw new DomatarException("Missing prvId");

    // Reject old-format actId supplied for sign-up by mistake.
    if (actHandle != null && !actHandle.isEmpty() && actHandle.indexOf('@') >= 0)
    {
      outMsg.addError(opr,
          "Account ID \"" + actHandle + "\" should be a 32-character fingerprint, not \"name@domain\". " +
          "Leave Account ID blank for a new account, or supply the fingerprint for link mode.");
      return;
    }

    final boolean linkMode = (actHandle != null && !actHandle.isEmpty());

    final String  actId;
    final String  sealedRootKey;
    final boolean isRoot;
    final AccountKeys genesisKeys;
    final AccountKeys ownKeys;

    if (linkMode)
    {
      // Linking a new login on this app to an existing fingerprint actId.
      // The caller must be verified AS the supplied actId.
      if (!Auth.isVerified(inMsg) || !callerOwnsAct(inMsg, actHandle))
      {
        outMsg.addError(opr, "Not authorized: you must be logged in as the account you are linking.");
        return;
      }

      actId         = actHandle;
      sealedRootKey = null;
      isRoot        = false;
      genesisKeys   = null;
      ownKeys       = null;
    }
    else
    {
      // Sign-up: mint genesis (defines actId) + ownership (operative key).
      genesisKeys   = AccountKeys.generate();
      ownKeys       = AccountKeys.generate();
      actId         = genesisKeys.actId;
      sealedRootKey = MasterKey.seal(ownKeys.rootPrivKey);
      isRoot        = true;
    }

    final String usrId   = usrHandle + "@" + appId;
    final String hstId   = DomId.subHstId(appId, actId);
    final String domain  = inMsg.getAttr("Domain");
    final String usrName = inMsg.getAttr("UsrName");
    final String pwd     = inMsg.getAttr("Pwd");
    final String ip      = inMsg.getAttr("Ip");

    if (domain == null)
      throw new DomatarException("Missing domain");

    if (usrName == null)
      throw new DomatarException("Missing usrName");

    if (pwd == null)
      throw new DomatarException("Missing pwd");

    if (ip == null)
      throw new DomatarException("Missing ip");

    final String newToken;
    try
    {
      newToken = ActDb.addAct(hstId, domain, prvId, actId, usrId, usrName, pwd, ip, sealedRootKey);
    }
    catch (DomatarException e)
    {
      final String detail = e.getMessage() != null ? e.getMessage() : "";
      if (detail.toLowerCase().contains("duplicate"))
        outMsg.addError(opr, "An account with that username already exists. Please choose a different handle.");
      else
        outMsg.addError(opr, "Could not create account: " + detail);
      return;
    }

    // OwnIds Phase 3: binding + genesis export + ownership-signed delegation
    // (sign-up only; link mode reuses an existing account's binding elsewhere).
    if (isRoot)
    {
      try
      {
        final Binding binding = Binding.sign(genesisKeys, ownKeys.rootPubKey,
                                             System.currentTimeMillis());
        ActDb.setBinding(actId, binding.genesisPubKeyB64, binding.ownPubKeyB64,
                         binding.version, binding.notBefore, binding.genesisSig);
      }
      catch (Exception e)
      {
        System.out.println("WARN: could not store binding for actId=" + actId + ": " + e);
      }

      try
      {
        GenesisVault.export(actId, usrId, genesisKeys.rootPubKey, genesisKeys.rootPrivKey);
      }
      catch (Exception e)
      {
        System.out.println("WARN: could not export genesis key for actId=" + actId + ": " + e);
      }

      try
      {
        final long ttlMs    = DomatarConfig.getDelegTtlMs();
        final long notAfter = System.currentTimeMillis() + ttlMs;
        final Delegation deleg = Delegation.issue(actId, ownKeys, prvId, notAfter);
        ActDb.setDelegation(actId, deleg.toJson(), deleg.delegSig, deleg.notAfter);
      }
      catch (Exception e)
      {
        // Delegation failure must not abort account creation — the account is
        // already persisted. The lazy ensureValid helper will issue one on
        // the first login.
        System.out.println("WARN: could not issue initial delegation for actId=" + actId + ": " + e);
      }
    }

    // For the FIRST sign-up the inbound caller has no session yet.
    // rootAs mints a new operation lineage for the just-created account
    // (hop 0 + that account's Binding and Delegation). The receiver's
    // own verification decides trust. Link-mode AddActs already have a
    // verified caller and reuse msgClient as-is.
    // Built here (before the install chain) because QuippinInstall
    // needs sideClient to call Register on the quippin central host.
    DomatarMsgClient sideClient = msgClient;

    if (isRoot && msgClient instanceof HttpClient)
    {
      sideClient = ((HttpClient) msgClient).rootAs(actId, usrId, usrName, ip, newToken);
    }

    JsonList defaultAppInstallFailures = null;

    if (isRoot)
    {
      // Root sign-up: run the per-app install chain driven by DefaultApps.
      //
      // Navigator MUST be first: it creates the navigator~<actId> hst row
      // and the root obj that all other installs link into.
      // Spec-Navigator.txt PART 10.2.
      runInstallForApp("navigator", actId, usrName, domain, prvId, sideClient);

      // Core Domatar class objects go on the already-created navigator sub-host.
      runInstallForApp("domatar", actId, usrName, domain, prvId, sideClient);

      // Remaining apps: InstallUser Msg routed to each app WAR (spec
      // Spec-Installation.txt PART 11.6 / Pass 1).  navigator and domatar are skipped
      // here because they were handled explicitly above.
      //
      // Pass 4: record catalog misses and transport/handler failures in the
      // AddAct response (DefaultAppInstallFailures) instead of stderr-only.
      defaultAppInstallFailures = new JsonArrayList();

      for (final String defaultAppId : ProviderDefaults.resolveDefaultApps(null))
      {
        if ("navigator".equals(defaultAppId) || "domatar".equals(defaultAppId))
          continue;

        if (!CatalogInstall.hasCatalogEntry(prvId, defaultAppId))
        {
          final String msg =
              "Not in provider app catalog; deploy the WAR and run /" + defaultAppId
              + "/Setup once (or register via app-catalog).";
          recordDefaultAppFailure(defaultAppInstallFailures, defaultAppId, msg);
          System.err.println("[ActManagerImpl] Default app skipped (" + defaultAppId
              + "): " + msg);
          continue;
        }

        try
        {
          UserInstallDispatch.sendInstallUser(defaultAppId, actId, usrId, usrName,
                                              prvId, domain, sideClient);
        }
        catch (DomatarException e)
        {
          final String detail = e.getMessage() != null ? e.getMessage() : e.toString();
          recordDefaultAppFailure(defaultAppInstallFailures, defaultAppId, detail);
          System.err.println("[ActManagerImpl] InstallUser failed for appId "
              + defaultAppId + ": " + detail);
        }
      }

      try
      {
        final Map<String, String> shellApps =
            DefaultShellBindings.resolve(DomatarConfig.getPrvActId());
        final LinkedHashSet<String> shellToInstall = new LinkedHashSet<>();

        for (final String roleId : UserSubstrateIds.ROLE_IDS)
        {
          String shellAppId = shellApps != null ? shellApps.get(roleId) : null;

          if (shellAppId == null || shellAppId.isEmpty())
            shellAppId = roleId;

          shellToInstall.add(shellAppId);
        }

        for (final String shellAppId : shellToInstall)
        {
          if ("navigator".equals(shellAppId) || "domatar".equals(shellAppId))
            continue;

          try
          {
            UserInstallDispatch.sendInstallUser(shellAppId, actId, usrId, usrName,
                prvId, domain, sideClient);
          }
          catch (final DomatarException e)
          {
            final String detail = e.getMessage() != null ? e.getMessage() : e.toString();
            recordDefaultAppFailure(defaultAppInstallFailures, shellAppId, detail);
          }
        }
      }
      catch (final Exception e)
      {
        System.out.println("WARN: AddAct shell InstallUser failed: " + e);
      }
    }
    else
    {
      // Link mode: ensure local membership replica exists (no legacy
      // login-<actId> host post-cutover).
      try
      {
        MembershipReplica.ensure(actId, domain, prvId, sideClient);
      }
      catch (final Exception e)
      {
        System.out.println("WARN: link-mode MembershipReplica.ensure failed for actId="
            + actId + ": " + e);
      }
    }

    recordPeer(actId, usrId, usrName, appId,
               isRoot ? prvId : DomatarConfig.getPrvId(),
               isRoot, sideClient);

    final ObjAttrs outAttrs = new ObjAttrs();

    outAttrs.addAttr("Token",   newToken);
    outAttrs.addAttr("ActId",   actId);
    outAttrs.addAttr("UsrId",   usrId);
    outAttrs.addAttr("UsrName", usrName);

    if (defaultAppInstallFailures != null && !defaultAppInstallFailures.isEmpty())
    {
      outAttrs.addAttr("DefaultAppInstallFailures", defaultAppInstallFailures);
      outAttrs.addAttr("DefaultAppInstallHadErrors", "True");
    }

    outMsg.addResponseBody(opr, outAttrs);
  }

  private static void recordDefaultAppFailure(final JsonList failures,
                                              final String appId,
                                              final String message)
  {
    final JsonMap row = new JsonHashMap();
    row.put("AppId", appId);
    row.put("Error", message);
    failures.add(row);
  }

  /**
   * Dispatch AddPeer to the local membership replica
   * login-&lt;actId&gt;-&lt;prvId&gt;. Fire-and-forward / non-fatal: a miss
   * must not fail AddAct. Spec-Login-Multiple.txt PART 7.
   */
  private static void recordPeer(final String actId,
                                 final String usrId,
                                 final String usrName,
                                 final String appId,
                                 final String prvId,
                                 final boolean isRoot,
                                 final DomatarMsgClient msgClient)
  {
    try
    {
      final String now = IdGen.getCurTimeBase64();

      final DomId membershipDomId = new DomId(
          DomId.subHstId("login", actId, prvId), "login", actId, "membership");

      final JsonMsg peerMsg = new JsonMsg();
      final ObjAttrs peerAttrs = new ObjAttrs();

      peerAttrs.addAttr("UsrId",       usrId);
      peerAttrs.addAttr("UsrName",     usrName);
      peerAttrs.addAttr("AppId",       appId);
      peerAttrs.addAttr("PrvId",       prvId);
      peerAttrs.addAttr("IsRoot",      isRoot ? "True" : "False");
      peerAttrs.addAttr("AddedAt",     now);
      peerAttrs.addAttr("PeerVersion", now);

      peerMsg.addRequestBody("AddPeer", peerAttrs);
      peerMsg.addClsId("login", "membership");

      msgClient.send(membershipDomId, peerMsg);

      // Best-effort fan-out to other replicas (K6 / Phase 2).
      MembershipFanout.push(actId, "AddPeer", peerAttrs, msgClient);
    }
    catch (final Exception e)
    {
      System.out.println("WARN: recordPeer failed for actId=" + actId
          + " usrId=" + usrId + " prvId=" + prvId + ": " + e);
    }
  }

  private void getAct(final String opr, final JsonMsg inMsg, final JsonMsg outMsg,
                      final DomatarMsgClient msgClient) throws DomatarException
  {
    final String actId = inMsg.getAttr("ActId");
    final String usrId = inMsg.getAttr("UsrId");
    final Context context = inMsg.getContext();

    final Act act;

    if (actId != null)
    {
      if (actId.equals(context.actId))
        act = new Act(context.actId, context.usrId, context.usrName, null);
      else
        act = ActDb.getAct(actId);
    }
    else
    {
      if (usrId.equals(context.usrId))
        act = new Act(context.actId, context.usrId, context.usrName, null);
      else
        act = ActDb.getActByUsrId(usrId);
    }

    if (act != null)
    {
      final ObjAttrs outAttrs = new ObjAttrs();
      outAttrs.addAttr("ActId",   act.actId);
      outAttrs.addAttr("UsrId",   act.usrId);
      outAttrs.addAttr("UsrName", act.usrName);
      outMsg.addResponseBody(opr, outAttrs);
    }
    else
      outMsg.addError(opr, "Act not found");
  }

  private void login(final String opr, final JsonMsg inMsg, final JsonMsg outMsg,
                     final DomatarMsgClient msgClient) throws DomatarException
  {
    final String usrId = inMsg.getAttr("UsrId");
    final String pwd   = inMsg.getAttr("Pwd");
    final String ip    = inMsg.getAttr("Ip");

    if (usrId == null)
      throw new DomatarException("Missing usrId");

    if (pwd == null)
      throw new DomatarException("Missing pwd");

    if (ip == null)
      throw new DomatarException("Missing ip");

    final Act act = ActDb.login(usrId, ip, pwd);

    if (act != null)
    {
      final ObjAttrs outAttrs = new ObjAttrs();
      outAttrs.addAttr("ActId",    act.actId);
      outAttrs.addAttr("UsrId",    act.usrId);
      outAttrs.addAttr("UsrName",  act.usrName);
      outAttrs.addAttr("Token",    act.token);
      outAttrs.addAttr("LoggedIn", "True");
      outMsg.addResponseBody(opr, outAttrs);
    }
    else
      outMsg.addError(opr, "Username or password is incorrect.");
  }

  private void logout(final String opr, final JsonMsg inMsg, final JsonMsg outMsg,
                      final DomatarMsgClient msgClient) throws DomatarException
  {
    final String usrId = inMsg.getAttr("UsrId");
    final String ip    = inMsg.getAttr("Ip");

    if (usrId == null)
      throw new DomatarException("Missing usrId");

    if (ip == null)
      throw new DomatarException("Missing ip");

    final boolean loggedOut = ActDb.logout(usrId, ip);

    if (loggedOut)
      outMsg.addResponseBody(opr, null);
    else
      outMsg.addError(opr, "Logout Unsuccesful");
  }

  /**
   * Patches usrId / usrName on an existing act row. Spec-LoginApp.txt
   * PART 7 / PART 8.4 / PART 8.5.
   *
   * Body attrs:
   *   ActId         : target row (must equal ctx.actId).
   *   UsrId         : current usrId of the row (kept for symmetry; not
   *                   used for matching - ActId is the PK).
   *   NewLocalname  : (optional) new &lt;localname&gt;; the @&lt;appId&gt;
   *                   suffix is preserved by the central host.
   *   NewUsrName    : (optional) new display name.
   *
   * Side-effect: dispatches UpdatePeer to the local membership replica
   * so peer rows track the new values (and fans out to other replicas).
   */
  private void updateAct(final String opr, final JsonMsg inMsg, final JsonMsg outMsg,
                         final DomatarMsgClient msgClient) throws DomatarException
  {
    final String actId        = inMsg.getAttr("ActId");
    final String currentUsrId = inMsg.getAttr("UsrId");
    final String newLocalname = inMsg.getAttr("NewLocalname");
    final String newUsrName   = inMsg.getAttr("NewUsrName");

    if (actId == null || currentUsrId == null)
      throw new DomatarException("Missing ActId / UsrId");

    if (!callerOwnsAct(inMsg, actId))
    {
      outMsg.addError(opr, "Not authorized");
      return;
    }

    final String appId   = inMsg.getDstId().hstId;
    final String newUsrId = (newLocalname != null) ? (newLocalname + "@" + appId) : null;

    if (newUsrId == null && newUsrName == null)
    {
      outMsg.addError(opr, "Nothing to update");
      return;
    }

    final boolean updated = ActDb.updateAct(actId, currentUsrId, newUsrId, newUsrName);

    if (!updated)
    {
      outMsg.addError(opr, "Act not found");
      return;
    }

    // Side-effect: keep membership peer rows in step (cutover: membership only).
    final ObjAttrs sideAttrs = new ObjAttrs();

    sideAttrs.addAttr("AppId", appId);

    if (newUsrId != null)
      sideAttrs.addAttr("NewUsrId", newUsrId);

    if (newUsrName != null)
      sideAttrs.addAttr("NewUsrName", newUsrName);

    sideAttrs.addAttr("PrevUsrId", currentUsrId);

    try
    {
      final String localPrvId = DomatarConfig.getPrvId();
      final DomId membershipDomId = new DomId(
          DomId.subHstId("login", actId, localPrvId), "login", actId, "membership");
      final JsonMsg peerMsg = new JsonMsg();

      peerMsg.addRequestBody("UpdatePeer", sideAttrs);
      peerMsg.addClsId("login", "membership");
      msgClient.send(membershipDomId, peerMsg);
      MembershipFanout.push(actId, "UpdatePeer", sideAttrs, msgClient);
    }
    catch (final Exception e)
    {
      System.out.println("WARN: updateAct UpdatePeer failed for actId="
          + actId + ": " + e);
    }

    final ObjAttrs outAttrs = new ObjAttrs();
    outAttrs.addAttr("ActId", actId);

    if (newUsrId != null)
      outAttrs.addAttr("UsrId", newUsrId);

    if (newUsrName != null)
      outAttrs.addAttr("UsrName", newUsrName);

    outMsg.addResponseBody(opr, outAttrs);
  }

  /**
   * Verifies oldPwd against the stored hash; on match, replaces the
   * hash with the encryption of newPwd. Spec-LoginApp.txt PART 7 /
   * PART 8.6.
   *
   * Returned body shape mirrors VerifyLogin: a wrong oldPwd is NOT an
   * Error - it is a normal answer "Changed=False" so the UI can say
   * "wrong password" without inspecting an exception. Errors are
   * reserved for transport / authorization failures.
   *
   * No directory side-effect: passwords are not mirrored to
   * login~&lt;actId&gt;.
   */
  private void changePwd(final String opr, final JsonMsg inMsg, final JsonMsg outMsg,
                         final DomatarMsgClient msgClient) throws DomatarException
  {
    final String actId  = inMsg.getAttr("ActId");
    final String usrId  = inMsg.getAttr("UsrId");
    final String oldPwd = inMsg.getAttr("OldPwd");
    final String newPwd = inMsg.getAttr("NewPwd");

    if (actId == null || usrId == null)
      throw new DomatarException("Missing ActId / UsrId");

    if (oldPwd == null || newPwd == null)
      throw new DomatarException("Missing OldPwd / NewPwd");

    if (!callerOwnsAct(inMsg, actId))
    {
      outMsg.addError(opr, "Not authorized");
      return;
    }

    final boolean changed = ActDb.changePwd(actId, usrId, oldPwd, newPwd);

    final ObjAttrs outAttrs = new ObjAttrs();
    outAttrs.addAttr("Changed", changed ? "True" : "False");
    outMsg.addResponseBody(opr, outAttrs);
  }

  /**
   * Deletes the act row. Spec-LoginApp.txt PART 7 / PART 8.8.
   *
   * Defensive last-login check is delegated to the Login app (the UI
   * counts the user's other linked logins before sending DeleteAct,
   * and the central host has no efficient way to do that without
   * dispatching to login~&lt;actId&gt;). The TODO in Spec-LoginApp.txt
   * PART 13 covers tightening this to a server-side check.
   *
   * Side-effect: dispatches TombstonePeer to the local membership replica
   * so the peer row is soft-deleted (and fans out to other replicas).
   */
  private void deleteAct(final String opr, final JsonMsg inMsg, final JsonMsg outMsg,
                         final DomatarMsgClient msgClient) throws DomatarException
  {
    final String actId = inMsg.getAttr("ActId");
    final String usrId = inMsg.getAttr("UsrId");

    if (actId == null || usrId == null)
      throw new DomatarException("Missing ActId / UsrId");

    if (!callerOwnsAct(inMsg, actId))
    {
      outMsg.addError(opr, "Not authorized");
      return;
    }

    final boolean deleted = ActDb.deleteAct(actId, usrId);

    if (!deleted)
    {
      outMsg.addError(opr, "Act not found");
      return;
    }

    // Side-effect: tombstone the matching membership peer (cutover: membership only).
    try
    {
      final String localPrvId = DomatarConfig.getPrvId();
      final DomId membershipDomId = new DomId(
          DomId.subHstId("login", actId, localPrvId), "login", actId, "membership");
      final ObjAttrs peerAttrs = new ObjAttrs();
      final String now = IdGen.getCurTimeBase64();

      peerAttrs.addAttr("UsrId", usrId);
      peerAttrs.addAttr("PeerVersion", now);

      final JsonMsg peerMsg = new JsonMsg();

      peerMsg.addRequestBody("TombstonePeer", peerAttrs);
      peerMsg.addClsId("login", "membership");
      msgClient.send(membershipDomId, peerMsg);
      MembershipFanout.push(actId, "TombstonePeer", peerAttrs, msgClient);
    }
    catch (final Exception e)
    {
      System.out.println("WARN: deleteAct TombstonePeer failed for actId="
          + actId + ": " + e);
    }

    outMsg.addResponseBody(opr, null);
  }

  /**
   * Installs an application for the currently authenticated user.
   *
   * Called by AppstoreWui when the user clicks "Install" in the App Store.
   * Dispatches **InstallUser** to the target app WAR (Spec-Installation.txt
   * PART 11.6 / Pass 1).
   *
   * Requires a verified session (hasRights → Auth.isVerified).
   */
  private void installApp(final String opr, final JsonMsg inMsg, final JsonMsg outMsg,
                          final DomatarMsgClient msgClient)
      throws DomatarException
  {
    // Spec-AppStore PART 7.4 / Update-AppStore Phase 3.
    MarketplaceInstall.install(opr, inMsg, outMsg, msgClient);
  }

  /**
   * Rotates the ownership key under the genesis key (Spec-OwnIds.txt PART 10).
   * Requires verified+owner-match. Sim resolves the genesis private key from
   * {@link GenesisVault}; production would verify a genesis-signed device
   * request instead (PART 7.4).
   *
   * Body attrs: ActId — target account (must equal ctx.actId).
   */
  private void rebind(final String opr, final JsonMsg inMsg, final JsonMsg outMsg,
                      final DomatarMsgClient msgClient)
      throws DomatarException
  {
    final String actId = inMsg.getAttr("ActId");

    if (actId == null || actId.isEmpty())
    {
      outMsg.addError(opr, "Missing ActId");
      return;
    }

    if (!callerOwnsAct(inMsg, actId))
    {
      outMsg.addError(opr, "Not authorized");
      return;
    }

    try
    {
      final String summary = OwnIdsRebind.rebind(actId);
      final Binding binding = ActDb.getBinding(actId);

      // Propagate the new binding into the local membership replica and
      // fan it out so every replica learns the rebind (K7 / PART 12).
      if (binding != null)
        MembershipFanout.publishBinding(actId, binding, msgClient);

      final ObjAttrs out = new ObjAttrs();
      out.addAttr("ActId",   actId);
      out.addAttr("OwnId",   binding != null ? binding.ownId : "");
      out.addAttr("Version", binding != null ? Long.toString(binding.version) : "");
      out.addAttr("Status",  summary);
      outMsg.addResponseBody(opr, out);
    }
    catch (final DomatarException e)
    {
      outMsg.addError(opr, e.getMessage() != null ? e.getMessage() : "Rebind failed");
    }
  }

  /**
   * Provision this provider (N) with binding + delegation (+ ownership key
   * when IsSigning) for an existing account, create the membership replica,
   * and AddPeer for the new login (Spec-Login-Multiple.txt PART 8.3–8.4).
   *
   * <p>Ordering (documented with AttachProvider): the caller MUST run
   * link-mode AddAct on N FIRST so the act row exists, THEN AttachProvision.
   */
  private void attachProvision(final String opr, final JsonMsg inMsg,
                               final JsonMsg outMsg, final DomatarMsgClient msgClient)
      throws DomatarException
  {
    final String actId = inMsg.getAttr("ActId");

    if (actId == null || actId.isEmpty())
    {
      outMsg.addError(opr, "Missing ActId");
      return;
    }

    if (!Auth.isVerified(inMsg) || !callerOwnsAct(inMsg, actId))
    {
      outMsg.addError(opr, "Not authorized");
      return;
    }

    final String bindingActId     = inMsg.getAttr("BindingActId");
    final String genesisPubKey    = inMsg.getAttr("BindingGenesisPubKey");
    final String bindingOwnId     = inMsg.getAttr("BindingOwnId");
    final String ownPubKey        = inMsg.getAttr("BindingOwnPubKey");
    final String versionStr       = inMsg.getAttr("BindingVersion");
    final String notBeforeStr     = inMsg.getAttr("BindingNotBefore");
    final String genesisSig       = inMsg.getAttr("BindingSig");
    final String delegJson        = inMsg.getAttr("Delegation");
    final String newUsrId         = inMsg.getAttr("NewUsrId");
    final String newUsrName       = inMsg.getAttr("NewUsrName");
    final String domain           = inMsg.getAttr("Domain");
    final String prvId            = inMsg.getAttr("PrvId");
    final String isSigning        = inMsg.getAttr("IsSigning");
    final String rawOwnPrvKeyB64  = inMsg.getAttr("RawOwnPrvKeyB64");

    if (genesisPubKey == null || ownPubKey == null || versionStr == null
        || notBeforeStr == null || genesisSig == null || delegJson == null
        || newUsrId == null || domain == null || prvId == null)
    {
      outMsg.addError(opr, "Missing AttachProvision fields");
      return;
    }

    if (bindingActId != null && !bindingActId.equals(actId))
    {
      outMsg.addError(opr, "BindingActId does not match ActId");
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
      outMsg.addError(opr, "Invalid BindingVersion / BindingNotBefore");
      return;
    }

    final Binding binding = Binding.fromStored(actId, genesisPubKey, ownPubKey,
                                               version, notBefore, genesisSig);

    if (bindingOwnId != null && !bindingOwnId.equals(binding.ownId))
    {
      outMsg.addError(opr, "BindingOwnId does not match OwnPubKey");
      return;
    }

    if (!binding.verify())
    {
      outMsg.addError(opr, "Binding.verify failed");
      return;
    }

    final Delegation deleg;

    try
    {
      deleg = Delegation.fromJson(delegJson);
    }
    catch (final Exception e)
    {
      outMsg.addError(opr, "Invalid Delegation JSON");
      return;
    }

    if (!deleg.verify(binding) || !actId.equals(deleg.actId) || !prvId.equals(deleg.prvId))
    {
      outMsg.addError(opr, "Delegation.verify failed or PrvId/ActId mismatch");
      return;
    }

    // Act row must exist (AddAct link-mode first). Soft-check for a clear error.
    if (ActDb.getAct(actId) == null)
    {
      outMsg.addError(opr, "Act row missing on this provider — run link-mode AddAct first");
      return;
    }

    ActDb.setBinding(actId, binding.genesisPubKeyB64, binding.ownPubKeyB64,
                     binding.version, binding.notBefore, binding.genesisSig);

    ActDb.setDelegation(actId, deleg.toJson(), deleg.delegSig, deleg.notAfter);

    if (!"False".equalsIgnoreCase(isSigning))
    {
      if (rawOwnPrvKeyB64 == null || rawOwnPrvKeyB64.isEmpty())
      {
        outMsg.addError(opr, "RawOwnPrvKeyB64 required when IsSigning");
        return;
      }

      byte[] raw = null;

      try
      {
        raw = Base64Encoder.decode(rawOwnPrvKeyB64);
        ActDb.setOwnPrvKey(actId, MasterKey.seal(raw));
      }
      finally
      {
        if (raw != null)
          java.util.Arrays.fill(raw, (byte) 0);
      }
    }

    MembershipReplica.ensure(actId, domain, prvId, msgClient);

    final String now = IdGen.getCurTimeBase64();
    final String appId = DomId.getAppId(newUsrId) != null
        ? DomId.getAppId(newUsrId) : "login";

    final ObjAttrs peerAttrs = new ObjAttrs();

    peerAttrs.addAttr("UsrId",       newUsrId);
    peerAttrs.addAttr("UsrName",     newUsrName != null ? newUsrName : newUsrId);
    peerAttrs.addAttr("AppId",       appId);
    peerAttrs.addAttr("PrvId",       prvId);
    peerAttrs.addAttr("IsRoot",      "False");
    peerAttrs.addAttr("AddedAt",     now);
    peerAttrs.addAttr("PeerVersion", now);

    final DomId membershipDomId = new DomId(
        DomId.subHstId("login", actId, prvId), "login", actId, "membership");

    final JsonMsg peerMsg = new JsonMsg();

    peerMsg.addRequestBody("AddPeer", peerAttrs);
    peerMsg.addClsId("login", "membership");

    try
    {
      msgClient.send(membershipDomId, peerMsg);
    }
    catch (final Exception e)
    {
      System.out.println("WARN: AttachProvision AddPeer failed for actId=" + actId + ": " + e);
    }

    // KD8: user substrate + default shell InstallUser on this login home (N).
    // Does not move hosts from L1 — creates local login/desktop/navigator/
    // appstore (or provider DefaultShells AppIds) on N.
    try
    {
      final String displayName = newUsrName != null ? newUsrName : newUsrId;

      UserSubstrateInstall.ensureUserSubstrate(
          actId, newUsrId, displayName, prvId, domain, msgClient);

      try
      {
        MembershipMigrator.copyFromLoginReplica(actId, prvId, msgClient);
      }
      catch (final Exception e)
      {
        System.out.println("WARN: AttachProvision membership migrate failed: " + e);
      }

      final Map<String, String> shellApps =
          DefaultShellBindings.resolve(DomatarConfig.getPrvActId());
      final LinkedHashSet<String> toInstall = new LinkedHashSet<>();

      for (final String roleId : UserSubstrateIds.ROLE_IDS)
      {
        String shellAppId = shellApps != null ? shellApps.get(roleId) : null;

        if (shellAppId == null || shellAppId.isEmpty())
          shellAppId = roleId;

        toInstall.add(shellAppId);
      }

      for (final String shellAppId : toInstall)
      {
        try
        {
          UserInstallDispatch.sendInstallUser(
              shellAppId, actId, newUsrId, displayName, prvId, domain, msgClient);
        }
        catch (final Exception e)
        {
          System.out.println("WARN: AttachProvision InstallUser " + shellAppId
              + " failed for actId=" + actId + ": " + e);
        }
      }
    }
    catch (final Exception e)
    {
      System.out.println("WARN: AttachProvision substrate/shells failed for actId="
          + actId + ": " + e);
    }

    final ObjAttrs out = new ObjAttrs();

    out.addAttr("Attached", "True");
    out.addAttr("ActId",    actId);
    out.addAttr("PrvId",    prvId);
    outMsg.addResponseBody(opr, out);
  }

  /**
   * Object-only host on this provider (N): substrate + binding +
   * delegation, no act row / OwnPrvKey / shells
   * (Spec-Foreign-Provider-Installation.txt PART 6 / KD4–KD6).
   */
  private void hostProvision(final String opr, final JsonMsg inMsg,
                             final JsonMsg outMsg, final DomatarMsgClient msgClient)
      throws DomatarException
  {
    final String actId = inMsg.getAttr("ActId");

    if (actId == null || actId.isEmpty())
    {
      outMsg.addError(opr, "Missing ActId");
      return;
    }

    if (!Auth.isVerified(inMsg) || !callerOwnsAct(inMsg, actId))
    {
      outMsg.addError(opr, "Not authorized");
      return;
    }

    final String bindingActId  = inMsg.getAttr("BindingActId");
    final String genesisPubKey = inMsg.getAttr("BindingGenesisPubKey");
    final String bindingOwnId  = inMsg.getAttr("BindingOwnId");
    final String ownPubKey     = inMsg.getAttr("BindingOwnPubKey");
    final String versionStr    = inMsg.getAttr("BindingVersion");
    final String notBeforeStr  = inMsg.getAttr("BindingNotBefore");
    final String genesisSig    = inMsg.getAttr("BindingSig");
    final String delegJson     = inMsg.getAttr("Delegation");
    final String domain        = inMsg.getAttr("Domain");
    final String prvId         = inMsg.getAttr("PrvId");

    if (genesisPubKey == null || ownPubKey == null || versionStr == null
        || notBeforeStr == null || genesisSig == null || delegJson == null
        || domain == null || prvId == null)
    {
      outMsg.addError(opr, "Missing HostProvision fields");
      return;
    }

    final String localPrv = DomatarConfig.getPrvId();

    if (localPrv != null && !localPrv.equals(prvId))
    {
      outMsg.addError(opr, "PrvId does not match this provider");
      return;
    }

    if (bindingActId != null && !bindingActId.equals(actId))
    {
      outMsg.addError(opr, "BindingActId does not match ActId");
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
      outMsg.addError(opr, "Invalid BindingVersion / BindingNotBefore");
      return;
    }

    final Binding binding = Binding.fromStored(actId, genesisPubKey, ownPubKey,
                                               version, notBefore, genesisSig);

    if (bindingOwnId != null && !bindingOwnId.equals(binding.ownId))
    {
      outMsg.addError(opr, "BindingOwnId does not match OwnPubKey");
      return;
    }

    if (!binding.verify())
    {
      outMsg.addError(opr, "Binding.verify failed");
      return;
    }

    final Delegation deleg;

    try
    {
      deleg = Delegation.fromJson(delegJson);
    }
    catch (final Exception e)
    {
      outMsg.addError(opr, "Invalid Delegation JSON");
      return;
    }

    if (!deleg.verify(binding) || !actId.equals(deleg.actId) || !prvId.equals(deleg.prvId))
    {
      outMsg.addError(opr, "Delegation.verify failed or PrvId/ActId mismatch");
      return;
    }

    try
    {
      if (ActDb.getAct(actId) != null)
      {
        ActDb.setBinding(actId, binding.genesisPubKeyB64, binding.ownPubKeyB64,
                         binding.version, binding.notBefore, binding.genesisSig);
        ActDb.setDelegation(actId, deleg.toJson(), deleg.delegSig, deleg.notAfter);
      }

      UserSubstrateInstall.ensureObjectOnlySubstrate(actId, prvId, domain, msgClient);
      MembershipFanout.writeLocalSubstrateBinding(actId, prvId, binding);
      MembershipFanout.writeLocalDelegation(actId, prvId, deleg.toJson(),
          Long.toString(deleg.notAfter), deleg.delegSig);

      final String now = IdGen.getCurTimeBase64();

      MembershipMigrator.upsertObjectOnlyPeer(
          UserSubstrateIds.membership(actId, prvId),
          prvId, now, now, "False", null);

      final ObjAttrs out = new ObjAttrs();

      out.addAttr("Status", "Provisioned");
      out.addAttr("PrvId", prvId);
      out.addAttr("HstId", UserSubstrateIds.hstId(actId, prvId));
      outMsg.addResponseBody(opr, out);
    }
    catch (final DomatarException e)
    {
      outMsg.addError(opr, e.getMessage() != null ? e.getMessage() : "HostProvision failed");
    }
  }

  /**
   * VerifyLogin is a normal request/reply: "given these credentials, is
   * this user logged in on this prv?". Both "yes" and "no" are valid
   * answers, so the reply is always a Success body - the verdict lives
   * in the body, not in the success/error flag.
   *
   *   - Yes: LoggedIn="True" PLUS the resolved ActId/UsrId/UsrName.
   *   - No:  LoggedIn="False" with no ActId.
   *
   * Errors (transport, malformed input, etc.) are the only thing that
   * gets reported via addError. Callers that want to know whether the
   * caller was actually verified read ActId from the reply body, NOT
   * isSuccess().
   */
  private void verifyLogin(final String opr, final JsonMsg inMsg, final JsonMsg outMsg,
                           final DomatarMsgClient msgClient) throws DomatarException
  {
    final String actId = inMsg.getAttr("ActId");
    final String usrId = inMsg.getAttr("UsrId");
    final String token = inMsg.getAttr("Token");
    final String ip    = inMsg.getContext().usrIp;

    final Act act = ActDb.verifyLogin(actId, usrId, ip, token);

    final ObjAttrs outAttrs = new ObjAttrs();

    if (act == null)
      outAttrs.addAttr("LoggedIn", "False");
    else
    {
      outAttrs.addAttr("LoggedIn", "True");
      outAttrs.addAttr("ActId",    act.actId);
      outAttrs.addAttr("UsrId",    act.usrId);
      outAttrs.addAttr("UsrName",  act.usrName);
    }

    outMsg.addResponseBody(opr, outAttrs);
  }

  // -------------------------------------------------------------------------

  /**
   * Invokes installUser for the named app via AppRegistry, avoiding direct
   * compile-time imports of per-app Install classes.
   */
  private static void runInstallForApp(final String appId,
                                       final String actId,
                                       final String usrName,
                                       final String domain,
                                       final String prvId,
                                       final DomatarMsgClient msgClient) throws DomatarException
  {
    final App app = AppRegistry.get(appId);
    if (app == null)
      throw new DomatarException("runInstallForApp: app '" + appId + "' not loaded");
    app.installInstance.installUser(actId, actId, usrName, prvId, domain, msgClient);
  }
}
