/*
 * Copyright (c) 2024 Domatar
 */

package com.domatar.install;

import com.domatar.core.DomatarConfig;
import com.domatar.crypto.AccountKeys;
import com.domatar.crypto.Binding;
import com.domatar.crypto.Delegation;
import com.domatar.crypto.GenesisVault;
import com.domatar.crypto.MasterKey;
import com.domatar.db.ActDb;
import com.domatar.util.Act;
import com.domatar.util.DomatarException;

/**
 * Ensures the home user {@code <appId>@<appId>} on this node's offered
 * home hosts ([Domatar](docs/spec/Domatar.md) PART 4.1.1).
 *
 * <p>Called from {@link OfferedHostsInstall#ensure()} after the {@code hst}
 * row is upserted. Idempotent: an existing {@code act} row is left
 * untouched (stable fingerprint). Does not mint {@code domatar@domatar}
 * — the platform app has no global home host in this version.
 */
public final class HomeHostInstall
{
  private HomeHostInstall() {}

  /** Login label of the app's home user. */
  public static String usrId(final String appId)
  {
    if (appId == null || appId.isEmpty())
      return null;

    return appId + "@" + appId;
  }

  /**
   * Fingerprint actId of the home user, or {@code null} if the account
   * does not yet exist on this node.
   */
  public static String actId(final String appId) throws DomatarException
  {
    final String usrId = usrId(appId);

    if (usrId == null)
      return null;

    final Act act = ActDb.getActByUsrId(usrId);

    if (act == null || act.actId == null || act.actId.isEmpty())
      return null;

    return act.actId;
  }

  /**
   * True when this name is not an installable-app home host in this
   * version ({@code domatar} has no global home user).
   */
  public static boolean skipHomeUser(final String appId)
  {
    return appId == null || appId.isEmpty() || "domatar".equals(appId);
  }

  /**
   * Mint {@code <appId>@<appId>} on {@code hstId = appId} when missing.
   * No-op when {@link #skipHomeUser} or the act row already exists.
   */
  public static void ensureHomeUser(final String appId,
                                    final String domain,
                                    final String prvId) throws DomatarException
  {
    if (skipHomeUser(appId) || domain == null || domain.isEmpty()
        || prvId == null || prvId.isEmpty())
      return;

    final String usrId = usrId(appId);
    final Act existing = ActDb.getActByUsrId(usrId);

    if (existing != null && existing.actId != null && !existing.actId.isEmpty())
      return;

    String password = DomatarConfig.getAdminPassword();

    if (password == null)
      password = "";

    final AccountKeys genesisKeys = AccountKeys.generate();
    final AccountKeys ownKeys     = AccountKeys.generate();
    final String      actId       = genesisKeys.actId;
    final String      sealed      = MasterKey.seal(ownKeys.rootPrivKey);

    ActDb.addAct(appId, domain, prvId,
        actId, usrId, appId,
        password, "127.0.0.1", sealed);

    try
    {
      final Binding binding = Binding.sign(genesisKeys, ownKeys.rootPubKey,
          System.currentTimeMillis());
      ActDb.setBinding(actId, binding.genesisPubKeyB64, binding.ownPubKeyB64,
          binding.version, binding.notBefore, binding.genesisSig);
    }
    catch (final Exception e)
    {
      System.out.println("WARN: HomeHostInstall binding failed for " + usrId
          + ": " + e);
    }

    try
    {
      GenesisVault.export(actId, usrId, genesisKeys.rootPubKey,
          genesisKeys.rootPrivKey);
    }
    catch (final Exception e)
    {
      System.out.println("WARN: HomeHostInstall genesis vault failed for "
          + usrId + ": " + e);
    }

    try
    {
      final long ttlMs    = DomatarConfig.getDelegTtlMs();
      final long notAfter = System.currentTimeMillis() + ttlMs;
      final Delegation deleg = Delegation.issue(actId, ownKeys, prvId, notAfter);
      ActDb.setDelegation(actId, deleg.toJson(), deleg.delegSig, deleg.notAfter);
    }
    catch (final Exception e)
    {
      System.out.println("WARN: HomeHostInstall delegation failed for " + usrId
          + ": " + e);
    }
  }
}
