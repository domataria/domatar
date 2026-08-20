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
import com.domatar.util.DomatarException;

import java.util.Map;
import java.util.logging.Logger;

/**
 * Single-provider rebind (Spec-OwnIds.txt PART 10.2; Update-OwnIds.txt
 * Phase 5 Task 5.1). Rotates the ownership key under the genesis key,
 * writes a higher-Version binding, and re-issues the local delegation.
 *
 * <p>Sim path: genesis private key is loaded from {@link GenesisVault}.
 * A production build would instead verify a genesis-signed request from
 * the user's device (Spec-OwnIds.txt PART 7.4).
 */
public final class OwnIdsRebind
{
  private static final Logger LOG = Logger.getLogger(OwnIdsRebind.class.getName());

  private OwnIdsRebind()
  {
  }

  /**
   * Rebinds {@code actId} to a freshly minted ownership key.
   *
   * @return a short success summary
   * @throws DomatarException if the binding is missing, the genesis key is
   *         unavailable, or persistence fails
   */
  public static String rebind(final String actId) throws DomatarException
  {
    if (actId == null || actId.isEmpty())
      throw new DomatarException("ActId required for rebind");

    final ActDb.BindingRow current = ActDb.getBindingRow(actId);
    if (current == null)
      throw new DomatarException("No binding for actId=" + actId + " — cannot rebind");

    final Map<String, byte[]> vault = GenesisVault.loadPrivKeysByActId();
    final byte[] genesisPriv = vault.get(actId);
    if (genesisPriv == null)
      throw new DomatarException(
          "genesis key not available - cannot rebind (actId=" + actId + ")");

    final AccountKeys genesis = AccountKeys.fromPrivKey(genesisPriv);
    if (!actId.equals(genesis.actId))
      throw new DomatarException(
          "genesis key fingerprint mismatch for actId=" + actId
          + " (got " + genesis.actId + ")");

    final AccountKeys own = AccountKeys.generate();
    final long newVersion = Math.max(System.currentTimeMillis(),
                                     current.version + 1);
    final Binding binding = Binding.sign(genesis, own.rootPubKey, newVersion);

    ActDb.setBinding(actId,
                     binding.genesisPubKeyB64,
                     binding.ownPubKeyB64,
                     binding.version,
                     binding.notBefore,
                     binding.genesisSig);
    ActDb.setOwnPrvKey(actId, MasterKey.seal(own.rootPrivKey));

    final String prvId    = DomatarConfig.getPrvId();
    final long   notAfter = System.currentTimeMillis() + DomatarConfig.getDelegTtlMs();
    final Delegation deleg = Delegation.issue(actId, own, prvId, notAfter);
    ActDb.setDelegation(actId, deleg.toJson(), deleg.delegSig, deleg.notAfter);

    final String summary = "Rebind ok actId=" + actId
        + " version=" + binding.version
        + " ownId=" + binding.ownId;
    LOG.info(summary);
    return summary;
  }
}
