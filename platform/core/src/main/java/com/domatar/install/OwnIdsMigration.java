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
import com.domatar.util.Base64Encoder;
import com.domatar.util.DomatarException;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * One-time, idempotent migration from the two-level identity model (single
 * root key in {@code act.OwnPrvKey}) to the three-level OwnIds model
 * (genesis + ownership + binding). Spec-OwnIds.txt PART 10.5 legacy upgrade;
 * Update-OwnIds.txt Phase 4.
 *
 * <p>Trigger via {@code GET /Setup?action=migrate-ownids} (localhost only).
 * A second run is a no-op: accounts whose {@code GenesisPubKey} is already
 * set are skipped.
 *
 * <p>Algorithm (per account with a sealed key and no binding yet):
 * <ol>
 *   <li>Treat the current {@code OwnPrvKey} as the genesis private key
 *       (legacy upgrade). Sanity-check that it fingerprints to ActId.</li>
 *   <li>Mint a new ownership key pair; sign a Binding under genesis.</li>
 *   <li>Persist binding, replace {@code OwnPrvKey} with the sealed ownership
 *       key, re-issue an ownership-signed Delegation.</li>
 *   <li>Export the genesis private key to {@link GenesisVault} and to
 *       {@code mySQL/dev-keys/genesis-export-&lt;prvId&gt;.txt}.</li>
 * </ol>
 */
public final class OwnIdsMigration
{
  private static final Logger LOG = Logger.getLogger(OwnIdsMigration.class.getName());

  private OwnIdsMigration()
  {
  }

  /**
   * Runs the migration for every local account that still holds a legacy
   * single root key. Returns a multi-line summary suitable for an HTTP
   * response.
   */
  public static String migrate() throws DomatarException
  {
    LOG.info("OwnIdsMigration: starting");

    final String prvId = DomatarConfig.getPrvId();
    final long   ttlMs = DomatarConfig.getDelegTtlMs();

    final Map<String, String> candidates = loadCandidates();
    LOG.info("OwnIdsMigration: " + candidates.size() + " distinct ActIds with OwnPrvKey");

    int migrated = 0;
    int skipped  = 0;
    int failed   = 0;

    final List<ExportRow> exports = new ArrayList<>();
    final StringBuilder failures = new StringBuilder();

    for (final Map.Entry<String, String> e : candidates.entrySet())
    {
      final String actId = e.getKey();
      final String usrId = e.getValue();

      try
      {
        if (ActDb.getBindingRow(actId) != null)
        {
          skipped++;
          continue;
        }

        final String sealed = ActDb.getOwnPrvKey(actId);
        if (sealed == null || sealed.isEmpty())
        {
          skipped++;
          continue;
        }

        // Step 1 — treat stored key as genesis (legacy upgrade).
        final byte[] genesisPriv = MasterKey.open(sealed);
        final AccountKeys genesis = AccountKeys.fromPrivKey(genesisPriv);

        if (!actId.equals(genesis.actId))
        {
          failed++;
          final String msg = "SANITY FAIL actId=" + actId
              + " fingerprint(OwnPrvKey)=" + genesis.actId
              + " — stored key does not define ActId; skipped";
          LOG.severe(msg);
          failures.append(msg).append('\n');
          continue;
        }

        final AccountKeys own = AccountKeys.generate();
        final Binding binding = Binding.sign(genesis, own.rootPubKey,
                                             System.currentTimeMillis());

        // Step 2 — persist binding, then ownership key, then delegation.
        // Genesis priv is held in memory (and exported in Step 3) before
        // OwnPrvKey is overwritten.
        ActDb.setBinding(actId,
                         binding.genesisPubKeyB64,
                         binding.ownPubKeyB64,
                         binding.version,
                         binding.notBefore,
                         binding.genesisSig);

        // Step 3 — export genesis BEFORE overwriting OwnPrvKey.
        GenesisVault.export(actId, usrId, genesis.rootPubKey, genesisPriv);

        ActDb.setOwnPrvKey(actId, MasterKey.seal(own.rootPrivKey));

        final long notAfter = System.currentTimeMillis() + ttlMs;
        final Delegation deleg = Delegation.issue(actId, own, prvId, notAfter);
        ActDb.setDelegation(actId, deleg.toJson(), deleg.delegSig, deleg.notAfter);

        // Step 4 — verify.
        final ActDb.BindingRow row = ActDb.getBindingRow(actId);
        if (row == null)
          throw new DomatarException("post-migrate getBindingRow returned null");

        final Binding rebuilt = ActDb.getBinding(actId);
        if (rebuilt == null || !rebuilt.verify())
          throw new DomatarException("post-migrate Binding.verify() failed");

        final Delegation ensured = Delegation.ensureValid(actId, prvId);
        if (ensured == null || !ensured.verify(rebuilt))
          throw new DomatarException("post-migrate Delegation.verify(binding) failed");

        exports.add(new ExportRow(actId, usrId,
                                  Base64Encoder.encode(genesis.rootPubKey),
                                  Base64Encoder.encode(genesisPriv)));
        migrated++;
        LOG.info("OwnIdsMigration: migrated actId=" + actId + " usrId=" + usrId);
      }
      catch (final Exception ex)
      {
        failed++;
        final String msg = "FAILED actId=" + actId + ": " + ex.getMessage();
        LOG.log(Level.SEVERE, msg, ex);
        failures.append(msg).append('\n');
      }
    }

    // Step 5 — write paste-ready export block for Spec-NewUsers.txt.
    final Path exportPath = writeExportFile(prvId, exports);

    final StringBuilder summary = new StringBuilder();
    summary.append("OwnIdsMigration complete (prvId=").append(prvId).append(").\n");
    summary.append("  migrated=").append(migrated)
           .append("  skipped=").append(skipped)
           .append("  failed=").append(failed).append('\n');
    summary.append("  export=").append(exportPath.toAbsolutePath()).append('\n');
    if (failures.length() > 0)
    {
      summary.append("Failures:\n");
      summary.append(failures);
    }

    LOG.info(summary.toString());
    return summary.toString();
  }

  /**
   * Distinct ActId → one UsrId for accounts that have a sealed OwnPrvKey.
   * Multiple login rows sharing an ActId collapse to a single candidate.
   */
  private static Map<String, String> loadCandidates() throws DomatarException
  {
    final Map<String, String> map = new LinkedHashMap<>();
    for (final Act act : ActDb.getAllActs())
    {
      if (act.actId == null || act.actId.isEmpty())
        continue;
      if (map.containsKey(act.actId))
        continue;
      final String sealed = ActDb.getOwnPrvKey(act.actId);
      if (sealed == null || sealed.isEmpty())
        continue;
      map.put(act.actId, act.usrId);
    }
    return map;
  }

  private static Path writeExportFile(final String prvId,
                                      final List<ExportRow> exports)
  {
    final String label = (prvId == null || prvId.isEmpty()) ? "unknown" : prvId;
    final Path path = Paths.get("mySQL", "dev-keys", "genesis-export-" + label + ".txt");

    // Do not wipe a previous successful export on an idempotent no-op run.
    if (exports.isEmpty())
      return path;

    try
    {
      final Path parent = path.getParent();
      if (parent != null)
        Files.createDirectories(parent);

      final StringBuilder body = new StringBuilder();
      body.append("# Ready-to-paste genesis export for Spec-NewUsers.txt PART 15\n");
      body.append("# Provider: ").append(label).append('\n');
      body.append("# WARNING: genesis PRIVATE keys — SIM/TEST ONLY\n");
      body.append("# Format: actId  usrId  genesisPubKeyB64  genesisPrvKeyB64\n");
      body.append("#\n");
      for (final ExportRow row : exports)
      {
        body.append(row.actId).append('\t')
            .append(row.usrId != null ? row.usrId : "").append('\t')
            .append(row.genesisPubB64).append('\t')
            .append(row.genesisPrvB64).append('\n');
      }

      Files.writeString(path, body.toString(), StandardCharsets.UTF_8);
    }
    catch (final Exception e)
    {
      LOG.log(Level.WARNING, "OwnIdsMigration: could not write export file " + path, e);
    }

    return path;
  }

  private static final class ExportRow
  {
    final String actId;
    final String usrId;
    final String genesisPubB64;
    final String genesisPrvB64;

    ExportRow(final String actId,
              final String usrId,
              final String genesisPubB64,
              final String genesisPrvB64)
    {
      this.actId = actId;
      this.usrId = usrId;
      this.genesisPubB64 = genesisPubB64;
      this.genesisPrvB64 = genesisPrvB64;
    }
  }
}
