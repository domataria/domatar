/*
 * Copyright (c) 2024 Domatar
 */

package com.domatar.install;

import java.util.List;

import com.domatar.app.App;
import com.domatar.app.AppRegistry;
import com.domatar.core.DomatarConfig;
import com.domatar.crypto.AccountKeys;
import com.domatar.crypto.Binding;
import com.domatar.crypto.Delegation;
import com.domatar.crypto.GenesisVault;
import com.domatar.crypto.MasterKey;
import com.domatar.db.ActDb;
import com.domatar.db.HstDb;
import com.domatar.db.LnkDb;
import com.domatar.db.ObjDb;
import com.domatar.util.Act;
import com.domatar.util.Hst;
import com.domatar.util.IdGen;
import com.domatar.util.Lnk;
import com.domatar.util.Obj;
import com.domatar.util.ObjAttrs;
import com.domatar.util.DomId;
import com.domatar.util.DomatarException;

/**
 * One-time provider bootstrap routine (Spec-DomatarApp.txt PART 9).
 *
 * Creates:
 *   Phase 1  — standard account for the provider (same chain as
 *              ActManagerImpl.addAct for a root sign-up, but limited
 *              to the three provider-appropriate apps).
 *   Phase 2  — provider-layer extras: actManager obj, accounts
 *              container, domatar-<prvActId> sub-host with app-domatar, hosts,
 *              host children, clss, and all class descriptors.
 *
 * Every step is idempotent: a second call is a no-op.
 *
 * Called once at deploy time (Option A: protected setup servlet;
 * Option B: auto-detected in Msg.init() — see PART 9.2).
 */
public class DomatarProviderInstall
{
  /**
   * Config-driven entry point: reads prvId, domain, and adminPassword from
   * DomatarConfig (provider.config.txt / env vars).  Called by the platform
   * {@code /Setup} servlet (Spec-Installation.txt).
   */
  public static void install() throws DomatarException
  {
    final String prvId    = DomatarConfig.getPrvId();
    final String domain   = DomatarConfig.getDomain();
    final String password = DomatarConfig.getAdminPassword();

    if (domain == null || domain.isEmpty())
      throw new IllegalStateException(
          "Domain is not set. Set DOMATAR_DOMAIN env var or Domain in provider.config.txt.");

    install(prvId, domain, password);
  }

  /**
   * Explicit-parameter entry point (used by the SQL seed and tests).
   */
  public static void install(final String prvId,
                             final String domain,
                             final String password) throws DomatarException
  {
    // Resolve the provider's fingerprint actId. Prefer the existing act row
    // for <prvId>@<prvId> (login identity) over a stale /opt PrvActId left by
    // a prior Setup that minted after a DB restore + container recreate.
    final String prvUsrId = prvId + "@" + prvId; // usrId stays unchanged (D3)
    final String prvActId;
    final String sealedProviderKey;
    final AccountKeys genesisKeys;
    final AccountKeys ownKeys;

    final Act existingAct = ActDb.getActByUsrId(prvUsrId);
    final String configured = DomatarConfig.getPrvActId();

    if (existingAct != null && existingAct.actId != null && !existingAct.actId.isEmpty())
    {
      prvActId          = existingAct.actId;
      sealedProviderKey = null;
      genesisKeys       = null;
      ownKeys           = null;
      if (configured == null || !prvActId.equals(configured))
        DomatarConfig.reconcilePrvActId(prvActId);
    }
    else if (configured != null && !configured.isEmpty())
    {
      prvActId          = configured;
      sealedProviderKey = null;
      genesisKeys       = null;
      ownKeys           = null;
    }
    else
    {
      genesisKeys       = AccountKeys.generate();
      ownKeys           = AccountKeys.generate();
      prvActId          = genesisKeys.actId;
      sealedProviderKey = MasterKey.seal(ownKeys.rootPrivKey);
      DomatarConfig.setPrvActIdOnce(prvActId);
    }

    final String ssHstId  = DomId.subHstId("domatar", prvActId);

    // ==========================================================
    // Phase 1: standard account bootstrap
    // ==========================================================

    // Step 1 — Provider act row (idempotent: skip if already present).
    if (existingAct == null)
      ActDb.addAct(prvId, domain, prvId,
                   prvActId, prvUsrId, prvId,
                   password, "127.0.0.1", sealedProviderKey);

    // Step 2 — Standard minimal install chain (provider-appropriate only).
    // Uses AppRegistry to avoid direct compile-time dependencies on app classes.
    runInstallForApp("navigator", prvActId, prvId, domain, prvId);
    runInstallForApp("domatar",   prvActId, prvId, domain, prvId);
    runInstallForApp("login",     prvActId, prvId, domain, prvId);
    runInstallForApp("desktop",   prvActId, prvId, domain, prvId);

    // Step 3 — Record the root peer on the local membership replica
    //           (no msg dispatch; mirrors MembershipImpl.addPeer).
    recordPeerDirect(prvActId, prvUsrId, prvId);

    // Step 3b (OwnIds Phase 3) — binding, genesis export, ownership delegation.
    if (sealedProviderKey != null && genesisKeys != null && ownKeys != null)
    {
      try
      {
        final Binding binding = Binding.sign(genesisKeys, ownKeys.rootPubKey,
                                             System.currentTimeMillis());
        ActDb.setBinding(prvActId, binding.genesisPubKeyB64, binding.ownPubKeyB64,
                         binding.version, binding.notBefore, binding.genesisSig);
      }
      catch (Exception e)
      {
        System.out.println("WARN: could not store provider binding for prvActId=" + prvActId + ": " + e);
      }

      try
      {
        GenesisVault.export(prvActId, prvUsrId, genesisKeys.rootPubKey, genesisKeys.rootPrivKey);
      }
      catch (Exception e)
      {
        System.out.println("WARN: could not export provider genesis key for prvActId=" + prvActId + ": " + e);
      }

      try
      {
        final long ttlMs    = DomatarConfig.getDelegTtlMs();
        final long notAfter = System.currentTimeMillis() + ttlMs;
        final Delegation deleg = Delegation.issue(prvActId, ownKeys, prvId, notAfter);
        ActDb.setDelegation(prvActId, deleg.toJson(), deleg.delegSig, deleg.notAfter);
      }
      catch (Exception e)
      {
        System.out.println("WARN: could not issue provider delegation for prvActId=" + prvActId + ": " + e);
      }
    }

    // ==========================================================
    // Phase 2: provider-layer additions
    // ==========================================================

    // Step 5 — actManager obj on the domatar sub-host.
    // Created before the sub-host hst row so ImplMap can dispatch
    // once the hst row appears below.
    final DomId actMgrId = new DomId(ssHstId, "domatar", prvActId, "actManager");
    ObjDb.addObjIfMissing(actMgrId,
        "act", "actManager", "ActManager", "Account manager for " + prvId);

    // Step 6 — Link actManager from the provider's app-login node (on replica).
    final DomId appLoginId = new DomId(DomId.subHstId("login", prvActId, prvId),
                                        "login", prvActId, "app-login");
    if (LnkDb.getLnk(appLoginId, actMgrId, "navigator", "container") == null)
      LnkDb.addLnk(new Lnk(appLoginId, actMgrId,
                            "act", "actManager",
                            "ActManager", "Account manager for " + prvId,
                            "navigator", "container",
                            null, 2));

    // Step 7 — accounts container on login-<prvActId>-<prvId> (replica).
    final DomId accountsId = new DomId(DomId.subHstId("login", prvActId, prvId),
                                        "login", prvActId, "accounts");
    ObjDb.addObjIfMissing(accountsId,
        "login", "accounts", "Accounts", "All accounts on this provider");
    if (LnkDb.getLnk(appLoginId, accountsId, "navigator", "container") == null)
      LnkDb.addLnk(new Lnk(appLoginId, accountsId,
                            "login", "accounts",
                            "Accounts", "All accounts on this provider",
                            "navigator", "container",
                            null, 3));

    // Step 8 — Domatar App sub-host.
    if (HstDb.getHst(ssHstId) == null)
      HstDb.addHst(ssHstId, domain, prvId);

    // Step 9 — app-domatar obj on domatar-<prvActId>.
    final DomId appDomatarId = new DomId(ssHstId, "domatar", prvActId, "app-domatar");
    ObjDb.addObjIfMissing(appDomatarId,
        "domatar", "app", "Domatar", "Platform infrastructure for this provider");

    // Step 10 — root → app-domatar link (seqNum 10: after all user-facing apps).
    final DomId rootId = new DomId(DomId.subHstId("navigator", prvActId, prvId), "navigator", prvActId, "root");
    if (LnkDb.getLnk(rootId, appDomatarId, "navigator", "app") == null)
      LnkDb.addLnk(new Lnk(rootId, appDomatarId,
                            "domatar", "app",
                            "Domatar", "Platform infrastructure for this provider",
                            "navigator", "app",
                            null, 10));

    // Step 11 — Hosts container.
    final DomId hostsId = new DomId(ssHstId, "domatar", prvActId, "hosts");
    ObjDb.addObjIfMissing(hostsId,
        "domatar", "hosts", "Hosts", "All hosts registered on this provider");
    if (LnkDb.getLnk(appDomatarId, hostsId, "navigator", "container") == null)
      LnkDb.addLnk(new Lnk(appDomatarId, hostsId,
                            "domatar", "hosts",
                            "Hosts", "All hosts registered on this provider",
                            "navigator", "container",
                            null, 1));

    // Step 12 — Seed one (domatar, host) child obj per hst row.
    final List<Hst> allHsts = HstDb.getAllHsts();
    int seqNum = 1;
    for (final Hst h : allHsts)
    {
      final DomId    childId = new DomId(ssHstId, "domatar", prvActId, "host-" + h.hstId);
      final String   desc    = h.domain + "  prv:" + h.prvId;
      final ObjAttrs attrs   = new ObjAttrs();
      attrs.addAttr("HstId",   h.hstId);
      attrs.addAttr("Domain",  h.domain);
      attrs.addAttr("PrvId",   h.prvId);
      attrs.addAttr("Version", String.valueOf(h.version));

      ObjDb.addObjIfMissing(childId, "domatar", "host", h.hstId, desc, attrs);

      if (LnkDb.getLnk(hostsId, childId, "navigator", "container") == null)
        LnkDb.addLnk(new Lnk(hostsId, childId,
                              "domatar", "host",
                              h.hstId, desc,
                              "navigator", "container",
                              h.hstId, seqNum++));
    }

    // Step 13 — clss container + all Domatar class descriptors.
    // ensureClssContainer links app-domatar → clss (seqNum 2).
    ClsInstall.ensureClssContainer(appDomatarId,
        "Core Domatar class descriptors", "domatar", 2);

    // installOnBase writes descriptor objs onto the domatar-<prvActId> sub-host,
    // which is an independent copy from each user's navigator~ copies
    // (Spec-DomatarApp.txt PART 8, principle P2).
    DomatarInstall.installOnBase(new DomId(ssHstId, "domatar", prvActId, ""));

    // Step 14 — App Catalog: ensure the catalog container and register the
    // platform (domatar) app.  Every other application registers itself via
    // its own /Setup endpoint, which DomatarSetupServlet calls automatically
    // via loopback HTTP after this method returns (onSetupComplete).
    CatalogInstall.ensureAppCatalog(prvId, domain);
    CatalogInstall.registerInCatalog(prvId, "domatar");

    // Step 15 (Phase 3) — Publish this provider's operational public key into
    // the directory for the provider's own routable hstId.  The key is stored
    // by ProviderKeyStore (generated on first call).  If this node is also the
    // global directory server (DirectoryRootPrivKey is configured), the record
    // is signed immediately; otherwise RecordSig stays null until the directory
    // node signs it externally.
    publishProviderKey(prvId, domain);
  }

  /**
   * Publishes the provider's operational public key into the local hst row for
   * {@code prvId} and signs the record if this node is the directory server.
   *
   * <p>Safe to call multiple times (idempotent: key bytes are stable per
   * {@code ProviderKeyStore.getOrCreate()}, and HstDb.updateHstKeys is an
   * overwrite).
   */
  static void publishProviderKey(final String prvId, final String domain)
      throws DomatarException
  {
    ProviderKeyPublish.publish(prvId, domain);
  }

  /**
   * Invokes installUser for the given app via AppRegistry.  Used during
   * provider bootstrap (Phase 1) to bootstrap the provider account's
   * per-app structure without compile-time imports.
   */
  private static void runInstallForApp(final String appId,
                                       final String actId,
                                       final String usrName,
                                       final String domain,
                                       final String prvId) throws DomatarException
  {
    final App app = AppRegistry.get(appId);
    if (app == null)
      throw new DomatarException(
          "DomatarProviderInstall: app '" + appId + "' not loaded in AppRegistry");
    app.installInstance.installUser(actId, actId, usrName, prvId, domain, null);
  }

  /**
   * Writes the provider's own root peer directly to the membership replica,
   * bypassing message dispatch (no auth context exists yet at bootstrap).
   * Mirrors the DB-level logic of MembershipImpl.addPeer / MembershipMigration.writePeer.
   */
  private static void recordPeerDirect(final String prvActId,
                                       final String prvUsrId,
                                       final String prvId) throws DomatarException
  {
    final String loginRepId = DomId.subHstId("login", prvActId, prvId);
    final String objId = IdGen.createId("peer", prvUsrId);
    final DomId peerId = new DomId(loginRepId, "login", prvActId, objId);

    if (ObjDb.getObj(peerId) != null)
      return;

    final String now = IdGen.getCurTimeBase64();
    final ObjAttrs rowAttrs = new ObjAttrs();

    rowAttrs.addAttr("UsrId",       prvUsrId);
    rowAttrs.addAttr("UsrName",     prvId);
    rowAttrs.addAttr("AppId",       prvId);
    rowAttrs.addAttr("PrvId",       prvId);
    rowAttrs.addAttr("IsRoot",      "True");
    rowAttrs.addAttr("AddedAt",     now);
    rowAttrs.addAttr("PeerVersion", now);
    rowAttrs.addAttr("Tombstone",   "False");

    ObjDb.addObj(new Obj(peerId, "login", "peer",
        "Linked login at " + prvId, prvId + " " + prvUsrId, rowAttrs));

    final DomId membershipId = new DomId(loginRepId, "login", prvActId, "membership");

    if (LnkDb.getLnk(membershipId, peerId, "login", "peer") == null)
      LnkDb.addLnk(new Lnk(membershipId, peerId,
                            "login", "peer",
                            "Linked login at " + prvId, prvId + " " + prvUsrId,
                            "login", "peer",
                            prvUsrId, 0));
  }
}
