/*
 * Copyright (c) 2024 Domatar
 */

package com.domatar.install;

import com.domatar.crypto.AccountKeys;
import com.domatar.crypto.MasterKey;
import com.domatar.db.SecurityMigrationDb;
import com.domatar.util.Base64Encoder;
import com.domatar.util.DomId;
import com.domatar.util.DomatarException;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * One-time, idempotent data migration from legacy {@code name@appId} actIds
 * to self-certifying 32-character fingerprint actIds, and from the {@code ~}
 * host-id separator to {@code -}.
 *
 * <p>Trigger via {@code GET /Setup?migrate=security} (localhost only).
 * A second run is a no-op: accounts whose ActId is already a fingerprint are
 * skipped.
 *
 * <h2>Algorithm</h2>
 * <ol>
 *   <li><b>Load dev keyset</b> (Task 2.13): if {@code mySQL/dev-keys/dev-keyset.txt}
 *       is on the filesystem (relative to CWD) or as classpath resource
 *       {@code /dev-keyset.txt}, build an {@code oldActId -> (newActId, sealedKey)}
 *       map from it.  This ensures dev DB fingerprints match the rewritten seed SQL.</li>
 *   <li><b>Build account map</b>: SELECT every non-fingerprint ActId from
 *       {@code act}. Look up in dev keyset; if absent, generate random
 *       {@link AccountKeys}. Record {@code oldActId -> newActId}.</li>
 *   <li><b>Build host-id map</b>: SELECT every HstId from {@code hst} that
 *       contains the old separator {@code '~'}. For each, split on the FIRST
 *       {@code '~'} into {@code <prefix>} and {@code <oldActId>}. If
 *       {@code <oldActId>} is in the account map, record
 *       {@code oldHstId -> newHstId} (separator flips to {@code '-'}).</li>
 *   <li><b>Persist sealed keys</b>: UPDATE act SET OwnPrvKey = {@code <sealed>}
 *       WHERE ActId = {@code <oldActId>} AND OwnPrvKey IS NULL (idempotent).</li>
 *   <li><b>Remap identifiers</b>:
 *     <ul>
 *       <li>Apply HOST-ID map (longest key first) to all host-id columns.</li>
 *       <li>Apply ACCOUNT map to all bare actId columns.</li>
 *       <li>Free-text columns (Attrs/Val JSON): HOST-ID map first, then ACCOUNT map.</li>
 *       <li>Remap {@code host-<oldHstId>} ObjId tokens in obj and lnk.</li>
 *       <li>act.ActId updated LAST (so DB lookups during the run still resolve).</li>
 *     </ul>
 *   </li>
 *   <li><b>Verify</b>: assert no old actId / host-id token remains in any
 *       relevant column.</li>
 * </ol>
 *
 * <p>Token-safety: old actIds contain {@code '@'} (unique across new fingerprints);
 * old host ids contain {@code '~'} (the host-id map is applied before the account
 * map).  Do NOT do a blind {@code '~'}->{@code '-'} replace; new fingerprints may
 * legitimately contain {@code '~'}.
 *
 * Spec-Security.txt PART 3.3; Update-Security.txt Task 2.12.
 */
public class SecurityMigration
{
    private static final Logger LOG = Logger.getLogger(SecurityMigration.class.getName());

    private static final String DEV_KEYSET_RESOURCE   = "/dev-keyset.txt";
    private static final String DEV_KEYSET_FILESYSTEM = "mySQL/dev-keys/dev-keyset.txt";

    // -------------------------------------------------------------------------
    // Entry point
    // -------------------------------------------------------------------------

    /**
     * Run the full migration.  Returns a multi-line summary suitable for
     * printing to an HTTP response.
     */
    public static String migrateAll() throws DomatarException
    {
        LOG.info("SecurityMigration: starting");

        // Step 0: load dev keyset ─────────────────────────────────────────────
        final Map<String, String[]> devKeyset = loadDevKeyset();
        LOG.info("SecurityMigration: dev keyset loaded (" + devKeyset.size() + " entries)");

        // Step 1a: build account map ───────────────────────────────────────────
        final Map<String, String> actIdMap   = new LinkedHashMap<>();
        final Map<String, String> sealedKeys = new LinkedHashMap<>();

        buildAccountMap(devKeyset, actIdMap, sealedKeys);
        LOG.info("SecurityMigration: " + actIdMap.size() + " accounts to migrate");

        if (actIdMap.isEmpty())
        {
            LOG.info("SecurityMigration: nothing to do (all actIds already fingerprints)");
            return "SecurityMigration: nothing to do (all actIds are already fingerprints).";
        }

        // Step 1b: build host-id map ───────────────────────────────────────────
        final Map<String, String> hstIdMap = buildHstIdMap(actIdMap);
        LOG.info("SecurityMigration: " + hstIdMap.size() + " host ids to migrate");

        // Step 2: persist sealed keys ──────────────────────────────────────────
        final int keysPersisted = SecurityMigrationDb.persistSealedKeys(sealedKeys);
        LOG.info("SecurityMigration: persisted " + keysPersisted + " sealed keys");

        // Step 3: remap identifiers ────────────────────────────────────────────
        final RemapStats stats = remapIdentifiers(actIdMap, hstIdMap);
        LOG.info("SecurityMigration: remap done. " + stats);

        // Step 4: verify ───────────────────────────────────────────────────────
        final List<String> violations = verify(actIdMap, hstIdMap);

        if (!violations.isEmpty())
        {
            for (final String v : violations)
                LOG.warning("SecurityMigration VERIFY FAIL: " + v);

            throw new DomatarException(
                "SecurityMigration verification failed: " + violations.size()
                    + " old tokens still present. See server logs.");
        }

        LOG.info("SecurityMigration: verification passed");

        return "SecurityMigration complete.\n"
            + "  Accounts migrated : " + actIdMap.size()  + "\n"
            + "  Host IDs remapped : " + hstIdMap.size()  + "\n"
            + "  Sealed keys stored: " + keysPersisted     + "\n"
            + "  " + stats;
    }

    // -------------------------------------------------------------------------
    // Step 0: load dev keyset
    // -------------------------------------------------------------------------

    /** Returns map: oldActId -> [newActId, rootPubKeyB64, rootPrvKeyB64]. */
    private static Map<String, String[]> loadDevKeyset()
    {
        final Map<String, String[]> map = new LinkedHashMap<>();

        // Filesystem (relative to CWD)
        try
        {
            if (Files.exists(Paths.get(DEV_KEYSET_FILESYSTEM)))
            {
                parseKeysetLines(
                    Files.readAllLines(Paths.get(DEV_KEYSET_FILESYSTEM), StandardCharsets.UTF_8),
                    map);
                return map;
            }
        }
        catch (final Exception e)
        {
            LOG.fine("dev keyset filesystem not readable: " + e.getMessage());
        }

        // Classpath fallback
        try (final InputStream is = SecurityMigration.class.getResourceAsStream(DEV_KEYSET_RESOURCE))
        {
            if (is != null)
            {
                final List<String> lines = new ArrayList<>();

                try (final BufferedReader br =
                         new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8)))
                {
                    String line;

                    while ((line = br.readLine()) != null)
                        lines.add(line);
                }

                parseKeysetLines(lines, map);
            }
        }
        catch (final Exception e)
        {
            LOG.fine("dev keyset classpath not readable: " + e.getMessage());
        }

        return map;
    }

    private static void parseKeysetLines(final List<String> lines, final Map<String, String[]> map)
    {
        for (final String raw : lines)
        {
            final String line = raw.trim();

            if (line.isEmpty() || line.startsWith("#"))
                continue;

            final String[] cols = line.split("\t");

            if (cols.length < 5)
                continue;

            // cols: usrId[0], oldActId[1], newActId[2], rootPubKeyB64[3], rootPrvKeyB64[4]
            final String oldActId  = cols[1].trim();
            final String newActId  = cols[2].trim();
            final String pubKeyB64 = cols[3].trim();
            final String prvKeyB64 = cols[4].trim();

            map.put(oldActId, new String[]{ newActId, pubKeyB64, prvKeyB64 });
        }
    }

    // -------------------------------------------------------------------------
    // Step 1a: build account map
    // -------------------------------------------------------------------------

    private static void buildAccountMap(
        final Map<String, String[]> devKeyset,
        final Map<String, String>   actIdMap,
        final Map<String, String>   sealedKeys) throws DomatarException
    {
        for (final String oldActId : SecurityMigrationDb.getAllActIds())
        {
            if (DomId.isFingerprintActId(oldActId))
                continue; // act.ActId already a fingerprint; skip act-column update

            if (devKeyset.containsKey(oldActId))
            {
                final String[] entry  = devKeyset.get(oldActId);
                final String newActId = entry[0];
                final byte[]  privKey = Base64Encoder.decode(entry[2]);
                final String  sealed  = MasterKey.seal(privKey);

                actIdMap.put(oldActId, newActId);
                sealedKeys.put(oldActId, sealed);
            }
            else
            {
                final AccountKeys keys  = AccountKeys.generate();
                final String      sealed = MasterKey.seal(keys.privateKeyCopy());

                actIdMap.put(oldActId, keys.actId);
                sealedKeys.put(oldActId, sealed);
            }
        }

        // Re-run support: if act.ActId is already fingerprinted (e.g., a previous
        // partial run updated act but not sub-hosts in obj/lnk), add dev-keyset
        // entries to the map so the sub-host step can still remap them.
        // These will produce 0 act.ActId updates (correct – nothing to change there).
        for (final Map.Entry<String, String[]> e : devKeyset.entrySet())
        {
            final String oldActId = e.getKey();

            if (!actIdMap.containsKey(oldActId))
            {
                // oldActId not in map means it was skipped above (already fingerprint).
                // Add it so buildHstIdMap can construct sub-host mappings.
                actIdMap.put(oldActId, e.getValue()[0]);
            }
        }
    }

    // -------------------------------------------------------------------------
    // Step 1b: build host-id map
    // -------------------------------------------------------------------------

    private static Map<String, String> buildHstIdMap(
        final Map<String, String> actIdMap) throws DomatarException
    {
        final Map<String, String> hstIdMap = new LinkedHashMap<>();

        // Top-level provider host IDs from the hst table.
        for (final String oldHstId : SecurityMigrationDb.getOldSepHstIds())
            addHstMapping(hstIdMap, actIdMap, oldHstId);

        // Sub-host IDs stored only in obj.HstId / lnk.HstId (never in hst).
        for (final String oldHstId : SecurityMigrationDb.getOldSepSubHstIds())
            addHstMapping(hstIdMap, actIdMap, oldHstId);

        return hstIdMap;
    }

    private static void addHstMapping(
        final Map<String, String> hstIdMap,
        final Map<String, String> actIdMap,
        final String              oldHstId)
    {
        if (hstIdMap.containsKey(oldHstId))
            return; // already mapped

        final int tilde = oldHstId.indexOf('~');

        if (tilde < 0)
            return;

        final String prefix   = oldHstId.substring(0, tilde);
        final String oldActId = oldHstId.substring(tilde + 1);
        final String newActId = actIdMap.get(oldActId);

        if (newActId != null)
            hstIdMap.put(oldHstId, prefix + DomId.HOST_SEP + newActId);
    }

    // -------------------------------------------------------------------------
    // Step 3: remap identifiers
    // -------------------------------------------------------------------------

    /** Counts of rows touched per table/column. */
    private static class RemapStats
    {
        int hst, objHstId, objActId, objObjId, objAttrs,
            lnkHstId, lnkActId, lnkLnkHstId, lnkLnkActId,
            lnkObjId, lnkLnkObjId, lnkVal, actActId;

        @Override
        public String toString()
        {
            return "hst=" + hst
                + " obj(HstId=" + objHstId + " ActId=" + objActId
                + " ObjId=" + objObjId + " Attrs=" + objAttrs + ")"
                + " lnk(HstId=" + lnkHstId + " ActId=" + lnkActId
                + " LnkHstId=" + lnkLnkHstId + " LnkActId=" + lnkLnkActId
                + " ObjId=" + lnkObjId + " LnkObjId=" + lnkLnkObjId
                + " Val=" + lnkVal + ")"
                + " act.ActId=" + actActId;
        }
    }

    private static RemapStats remapIdentifiers(
        final Map<String, String> actIdMap,
        final Map<String, String> hstIdMap) throws DomatarException
    {
        final RemapStats stats = new RemapStats();

        // Sort both maps longest-key-first (prevents short keys matching inside longer ones)
        final List<Map.Entry<String, String>> hstEntries = sortedByKeyLengthDesc(hstIdMap);
        final List<Map.Entry<String, String>> actEntries = sortedByKeyLengthDesc(actIdMap);

        // (a) hst.HstId
        stats.hst = SecurityMigrationDb.remapWholeColumn("hst", "HstId", hstEntries);

        // (b) obj
        stats.objHstId  = SecurityMigrationDb.remapWholeColumn("obj", "HstId",  hstEntries);
        stats.objActId  = SecurityMigrationDb.remapWholeColumn("obj", "ActId",  actEntries);
        stats.objObjId  = SecurityMigrationDb.remapObjIdHostRefs("obj", "ObjId", hstEntries);
        stats.objAttrs  = SecurityMigrationDb.remapJsonColumn("obj",  "Attrs",  hstEntries, actEntries, true);

        // (c) lnk
        stats.lnkHstId    = SecurityMigrationDb.remapWholeColumn("lnk", "HstId",    hstEntries);
        stats.lnkActId    = SecurityMigrationDb.remapWholeColumn("lnk", "ActId",    actEntries);
        stats.lnkLnkHstId = SecurityMigrationDb.remapWholeColumn("lnk", "LnkHstId", hstEntries);
        stats.lnkLnkActId = SecurityMigrationDb.remapWholeColumn("lnk", "LnkActId", actEntries);
        stats.lnkObjId    = SecurityMigrationDb.remapObjIdHostRefs("lnk", "ObjId",    hstEntries);
        stats.lnkLnkObjId = SecurityMigrationDb.remapObjIdHostRefs("lnk", "LnkObjId", hstEntries);
        stats.lnkVal      = SecurityMigrationDb.remapJsonColumn("lnk",  "Val",     hstEntries, actEntries, false);

        // (d) act.ActId — LAST so DB remains lookupable during earlier steps
        stats.actActId = SecurityMigrationDb.remapActIdColumn(actEntries);

        return stats;
    }

    // -------------------------------------------------------------------------
    // Step 4: verify
    // -------------------------------------------------------------------------

    private static List<String> verify(
        final Map<String, String> actIdMap,
        final Map<String, String> hstIdMap) throws DomatarException
    {
        final List<String> violations = new ArrayList<>();

        final String[][] colSpecs = {
            { "obj",  "HstId"    }, { "obj",  "ActId"  },
            { "obj",  "ObjId"    }, { "obj",  "Attrs"  },
            { "lnk",  "HstId"    }, { "lnk",  "ActId"  },
            { "lnk",  "LnkHstId" }, { "lnk",  "LnkActId" },
            { "lnk",  "ObjId"    }, { "lnk",  "LnkObjId" },
            { "lnk",  "Val"      }, { "hst",  "HstId"  },
            { "act",  "ActId"    },
        };

        for (final String oldHstId : hstIdMap.keySet())
        {
            for (final String[] cs : colSpecs)
            {
                final long cnt = SecurityMigrationDb.countTokenOccurrences(cs[0], cs[1], oldHstId);

                if (cnt > 0)
                    violations.add(cs[0] + "." + cs[1] + " still has old hstId '"
                        + oldHstId + "' (" + cnt + " rows)");
            }
        }

        for (final String oldActId : actIdMap.keySet())
        {
            for (final String[] cs : colSpecs)
            {
                final long cnt = SecurityMigrationDb.countTokenOccurrences(cs[0], cs[1], oldActId);

                if (cnt > 0)
                    violations.add(cs[0] + "." + cs[1] + " still has old actId '"
                        + oldActId + "' (" + cnt + " rows)");
            }
        }

        return violations;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static List<Map.Entry<String, String>> sortedByKeyLengthDesc(
        final Map<String, String> map)
    {
        final List<Map.Entry<String, String>> list = new ArrayList<>(map.entrySet());
        list.sort(Comparator.<Map.Entry<String, String>>comparingInt(e -> e.getKey().length())
                            .reversed());
        return list;
    }
}
