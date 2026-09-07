/*
 * Copyright (c) 2024 Domatar
 */

package com.domatar.crypto;

import com.domatar.core.DomatarConfig;
import com.domatar.util.Base64Encoder;
import com.domatar.util.JsonHashMap;
import com.domatar.util.JsonMap;
import com.domatar.util.DomatarException;

import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;

/**
 * An account delegation certificate (Spec-Security.txt PART 6.2 /
 * Spec-OwnIds.txt PART 5 / PART 11).
 *
 * A delegation authorises a specific provider to sign origin messages on
 * behalf of an account for the duration given by {@code notAfter}.
 *
 * Wire format (JSON fields, canonical-sorted for signing):
 *   ActId      — fingerprint actId of the account
 *   DelegSig   — Base64Encoder Ed25519 signature (excluded when computing
 *                the signed bytes, see {@link CanonicalJson#canonicalizeExcluding})
 *   Nonce      — Base64Encoder 12-byte random value
 *   NotAfter   — expiry timestamp in milliseconds since the Unix epoch
 *   PrvId      — the provider authorised to sign on behalf of ActId
 *   OwnPubKey  — Base64Encoder 32-byte Ed25519 ownership public key
 *                (formerly RootPubKey under the two-level model)
 *
 * Under the three-level model the delegation is signed by the OWNERSHIP
 * key and validated against a Binding (see {@link #issue(String, AccountKeys,
 * String, long)} and {@link #verify(Binding)}).
 */
public class Delegation
{
    /** Timestamp skew allowed when checking NotAfter (5 minutes). */
    private static final long SKEW_MS = 5L * 60L * 1000L;

    public final String actId;
    public final String ownPubKeyB64;
    public final String prvId;
    public final long   notAfter;
    public final String nonce;
    public final String delegSig;

    private Delegation(final String actId,
                       final String ownPubKeyB64,
                       final String prvId,
                       final long   notAfter,
                       final String nonce,
                       final String delegSig)
    {
        this.actId        = actId;
        this.ownPubKeyB64 = ownPubKeyB64;
        this.prvId        = prvId;
        this.notAfter     = notAfter;
        this.nonce        = nonce;
        this.delegSig     = delegSig;
    }

    // -------------------------------------------------------------------------
    // Factory — issue (ownership-signed, three-level)
    // -------------------------------------------------------------------------

    /**
     * Issues a delegation for account {@code actId} signed by the OWNERSHIP
     * key {@code ownKeys}. {@code actId} is the real account id (genesis
     * fingerprint); {@code ownKeys.actId} is the ownId and must not be used
     * as the account id (Update-OwnIds.txt K1 note).
     */
    public static Delegation issue(final String actId,
                                   final AccountKeys ownKeys,
                                   final String prvId,
                                   final long notAfter)
    {
        final byte[]  nonceBytes = new byte[12];
        new SecureRandom().nextBytes(nonceBytes);
        final String nonce = Base64Encoder.encode(nonceBytes);

        final String ownPubKeyB64 = Base64Encoder.encode(ownKeys.rootPubKey);

        final Delegation unsigned = new Delegation(actId, ownPubKeyB64,
                                                   prvId, notAfter, nonce, null);

        final byte[]     canonical = CanonicalJson.canonicalizeExcluding(
                                         unsigned.toMap(), "DelegSig");
        final PrivateKey privKey   = KeyOps.privateKeyFromBytes(ownKeys.rootPrivKey);
        final byte[]     sigBytes  = KeyOps.sign(privKey, canonical);
        final String     delegSig  = Base64Encoder.encode(sigBytes);

        return new Delegation(actId, ownPubKeyB64, prvId, notAfter, nonce, delegSig);
    }

    // -------------------------------------------------------------------------
    // Verification
    // -------------------------------------------------------------------------

    /**
     * Verifies this delegation against a genesis-signed Binding under a
     * specific fingerprint version (Spec-ActId-Versioning.txt PART 5.1):
     *   (a) OwnPubKey fingerprints to binding.ownId under fpVersion
     *   (b) this.actId == binding.actId
     *   (c) DelegSig verifies under OwnPubKey
     *   (d) NotAfter is in the future (with skew)
     *
     * Provider match (PrvId == hop 0 SignerPrv) remains the caller's job.
     */
    public boolean verify(final Binding binding, final int fpVersion)
    {
        if (binding == null)
            return false;

        if (actId == null || ownPubKeyB64 == null || prvId == null
                || nonce == null || delegSig == null)
            return false;

        if (!actId.equals(binding.actId))
            return false;

        final byte[] pubBytes;
        try
        {
            pubBytes = Base64Encoder.decode(ownPubKeyB64);
        }
        catch (final RuntimeException e)
        {
            return false;
        }

        if (pubBytes.length != 32)
            return false;

        if (!AccountKeys.fingerprintsTo(pubBytes, binding.ownId, fpVersion))
            return false;

        final PublicKey pubKey;
        try
        {
            pubKey = KeyOps.publicKeyFromBytes(pubBytes);
        }
        catch (final RuntimeException e)
        {
            return false;
        }

        final byte[] canonical = CanonicalJson.canonicalizeExcluding(toMap(), "DelegSig");
        final byte[] sigBytes;
        try
        {
            sigBytes = Base64Encoder.decode(delegSig);
        }
        catch (final RuntimeException e)
        {
            return false;
        }

        if (!KeyOps.verify(pubKey, canonical, sigBytes))
            return false;

        if (System.currentTimeMillis() > notAfter + SKEW_MS)
            return false;

        return true;
    }

    /**
     * Version-tolerant verify (KD2): tries every registered fingerprint version.
     */
    public boolean verify(final Binding binding)
    {
        for (final int v : AccountKeys.registeredVersions())
            if (verify(binding, v))
                return true;

        return false;
    }

    // -------------------------------------------------------------------------
    // Serialization
    // -------------------------------------------------------------------------

    /** Returns the delegation as a JSON-compatible map (includes DelegSig). */
    public JsonMap toMap()
    {
        final JsonMap m = new JsonHashMap();
        m.put("ActId",     actId);
        m.put("DelegSig",  delegSig);
        m.put("Nonce",     nonce);
        m.put("NotAfter",  notAfter);
        m.put("OwnPubKey", ownPubKeyB64);
        m.put("PrvId",     prvId);
        return m;
    }

    /**
     * Serializes to a compact JSON string suitable for the {@code Delegation}
     * database column.
     */
    public String toJson() throws DomatarException
    {
        return com.domatar.util.Json.toJson(toMap());
    }

    /**
     * Deserializes from the JSON string stored in the database column.
     *
     * @param json the JSON delegation string
     * @return a {@code Delegation} instance (may be invalid — call verify)
     * @throws DomatarException if the JSON cannot be parsed
     */
    public static Delegation fromJson(final String json) throws DomatarException
    {
        final JsonMap m = com.domatar.util.Json.parseMap(json);
        return fromMap(m);
    }

    /**
     * Constructs from a pre-parsed JSON map. Accepts either {@code OwnPubKey}
     * (current) or {@code RootPubKey} (legacy stored rows) so existing
     * delegations can be re-read until they are re-issued.
     */
    public static Delegation fromMap(final JsonMap m)
    {
        if (m == null)
            return null;

        final Object notAfterObj = m.get("NotAfter");
        long notAfter = 0L;
        if (notAfterObj instanceof Number)
            notAfter = ((Number) notAfterObj).longValue();
        else if (notAfterObj instanceof String)
        {
            try { notAfter = Long.parseLong((String) notAfterObj); }
            catch (final NumberFormatException ignored) {}
        }

        String ownPub = m.getString("OwnPubKey");
        if (ownPub == null)
            ownPub = m.getString("RootPubKey");

        return new Delegation(
            m.getString("ActId"),
            ownPub,
            m.getString("PrvId"),
            notAfter,
            m.getString("Nonce"),
            m.getString("DelegSig"));
    }

    // -------------------------------------------------------------------------
    // Lazy-issuance helper
    // -------------------------------------------------------------------------

    /**
     * Default TTL for delegations: 1 hour.  Configurable via
     * {@code DOMATAR_DELEG_TTL_MS} / {@code DelegTtlMs} in
     * {@code provider.config.txt}.
     */
    public static final long DEFAULT_TTL_MS = 60L * 60L * 1000L;

    /**
     * Ensures a valid ownership-signed delegation exists in the database for
     * {@code actId} at {@code prvId}. If no delegation exists or the existing
     * one has expired (or expires within the next minute), a new one is issued
     * and stored.
     *
     * <p>Requires a Binding (Phase 5: every account has one after migration).
     * Returns {@code null} if the binding or sealed ownership key is absent.
     *
     * @param actId the account fingerprint
     * @param prvId the provider to be authorised in the new delegation
     * @return a valid {@code Delegation}, or {@code null} if unavailable
     */
    public static Delegation ensureValid(final String actId, final String prvId)
    {
        try
        {
            final Binding binding = com.domatar.db.ActDb.getBinding(actId);
            if (binding == null)
            {
                System.out.println("WARN: Delegation.ensureValid: no binding for actId="
                                   + actId);
                return null;
            }

            // Load existing delegation.
            final com.domatar.db.ActDb.DelegationRow row =
                com.domatar.db.ActDb.getDelegationRow(actId);

            final long renewBefore = System.currentTimeMillis() + 60_000L; // 1 min buffer

            if (row != null && row.delegNotAfter > renewBefore)
            {
                final Delegation existing = Delegation.fromJson(row.delegation);
                if (existing != null && prvId.equals(existing.prvId)
                    && existing.verify(binding))
                    return existing;
            }

            // No valid delegation — issue a new one with the ownership key.
            final String sealed = com.domatar.db.ActDb.getOwnPrvKey(actId);
            if (sealed == null)
                return null;

            final byte[]      privBytes = com.domatar.crypto.MasterKey.open(sealed);
            final AccountKeys keys      = AccountKeys.fromPrivKey(privBytes);

            final long ttlMs    = DomatarConfig.getDelegTtlMs();
            final long notAfter = System.currentTimeMillis() + ttlMs;

            final Delegation fresh = Delegation.issue(actId, keys, prvId, notAfter);

            com.domatar.db.ActDb.setDelegation(actId,
                                                fresh.toJson(),
                                                fresh.delegSig,
                                                fresh.notAfter);
            return fresh;
        }
        catch (final Exception e)
        {
            System.out.println("WARN: Delegation.ensureValid failed for actId=" + actId + ": " + e);
            return null;
        }
    }
}
