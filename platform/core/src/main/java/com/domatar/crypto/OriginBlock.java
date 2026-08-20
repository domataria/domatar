/*
 * Copyright (c) 2024 Domatar
 */

package com.domatar.crypto;

import com.domatar.util.Base64Encoder;
import com.domatar.util.JsonHashMap;
import com.domatar.util.JsonMap;
import com.domatar.util.DomId;

import java.security.PublicKey;
import java.security.SecureRandom;

/**
 * An origin authentication block (Spec-Security.txt PART 7.1).
 *
 * The origin block authenticates the FIRST hop of a cross-provider message:
 * who sent it, from where, to where, and that the body has not been tampered.
 * It is signed with the home provider's operational private key
 * ({@link ProviderKeyStore}).
 *
 * Wire format (JSON fields, canonical-sorted for signing):
 *   ActId       — fingerprint actId of the sending account
 *   BodyHash    — Base64Encoder SHA-256 of the canonical message body
 *   DstDomId    — string-encoded destination DomId
 *   Nonce       — Base64Encoder 12-byte random replay-prevention value
 *   OriginSig   — Base64Encoder Ed25519 signature (excluded when computing
 *                 signed bytes; see {@link CanonicalJson#canonicalizeExcluding})
 *   SignerPrvId — hstId of the provider that signs (and thus implicitly
 *                 identifies which provider public key to look up for
 *                 verification)
 *   SrcDomId    — string-encoded source DomId
 *   Timestamp   — wall-clock milliseconds since the Unix epoch
 */
public class OriginBlock
{
    public final String actId;
    public final String srcDomId;
    public final String dstDomId;
    public final String bodyHash;
    public final String signerPrvId;
    public final long   timestamp;
    public final String nonce;
    public final String originSig;

    private OriginBlock(final String actId,
                        final String srcDomId,
                        final String dstDomId,
                        final String bodyHash,
                        final String signerPrvId,
                        final long   timestamp,
                        final String nonce,
                        final String originSig)
    {
        this.actId       = actId;
        this.srcDomId    = srcDomId;
        this.dstDomId    = dstDomId;
        this.bodyHash    = bodyHash;
        this.signerPrvId = signerPrvId;
        this.timestamp   = timestamp;
        this.nonce       = nonce;
        this.originSig   = originSig;
    }

    // -------------------------------------------------------------------------
    // Factory — sign
    // -------------------------------------------------------------------------

    /**
     * Builds and signs an {@code OriginBlock} for an outbound message.
     *
     * @param src        source DomId (the browser-facing UI object on this prv)
     * @param dst        destination DomId
     * @param body       the message Body map; its canonical JSON is hashed
     * @param actId      fingerprint actId of the account initiating the request
     * @param signerPrvId hstId of THIS provider (the signer)
     * @return a fully-signed {@code OriginBlock}
     */
    public static OriginBlock sign(final DomId src,
                                   final DomId dst,
                                   final JsonMap body,
                                   final String actId,
                                   final String signerPrvId)
    {
        final byte[] bodyBytes  = CanonicalJson.canonicalize(body);
        final byte[] hashBytes  = KeyOps.sha256(bodyBytes);
        final String bodyHashB64 = Base64Encoder.encode(hashBytes);

        final byte[]  nonceBytes = new byte[12];
        new SecureRandom().nextBytes(nonceBytes);
        final String nonce = Base64Encoder.encode(nonceBytes);

        final long timestamp = System.currentTimeMillis();

        // Build an unsigned origin for signing.
        final OriginBlock unsigned = new OriginBlock(
            actId, src.toString(), dst.toString(),
            bodyHashB64, signerPrvId, timestamp, nonce, null);

        final byte[] canonical = CanonicalJson.canonicalizeExcluding(unsigned.toMap(), "OriginSig");
        final byte[] sigBytes  = ProviderKeyStore.sign(canonical);
        final String originSig = Base64Encoder.encode(sigBytes);

        return new OriginBlock(actId, src.toString(), dst.toString(),
                               bodyHashB64, signerPrvId, timestamp, nonce, originSig);
    }

    // -------------------------------------------------------------------------
    // Verification
    // -------------------------------------------------------------------------

    /**
     * Verifies the origin signature and body hash.
     *
     * @param signerPubKey the provider's operational public key (from the
     *                     signed hst directory record, Phase 3+)
     * @param body         the message Body map; must match {@code BodyHash}
     * @return true iff the origin signature is valid and BodyHash matches
     */
    public boolean verify(final PublicKey signerPubKey, final JsonMap body)
    {
        if (actId == null || srcDomId == null || dstDomId == null
                || bodyHash == null || signerPrvId == null || nonce == null
                || originSig == null)
            return false;

        // Verify origin signature.
        final byte[] canonical = CanonicalJson.canonicalizeExcluding(toMap(), "OriginSig");

        final byte[] sigBytes;
        try { sigBytes = Base64Encoder.decode(originSig); }
        catch (final RuntimeException e) { return false; }

        if (!KeyOps.verify(signerPubKey, canonical, sigBytes))
            return false;

        // Verify body hash.
        final byte[] bodyBytes    = CanonicalJson.canonicalize(body);
        final byte[] expectedHash = KeyOps.sha256(bodyBytes);
        final byte[] claimedHash;
        try { claimedHash = Base64Encoder.decode(bodyHash); }
        catch (final RuntimeException e) { return false; }

        if (expectedHash.length != claimedHash.length)
            return false;

        for (int i = 0; i < expectedHash.length; i++)
        {
            if (expectedHash[i] != claimedHash[i])
                return false;
        }

        return true;
    }

    // -------------------------------------------------------------------------
    // Serialization
    // -------------------------------------------------------------------------

    /** Returns this block as a JSON-compatible map (includes OriginSig). */
    public JsonMap toMap()
    {
        final JsonMap m = new JsonHashMap();
        m.put("ActId",       actId);
        m.put("BodyHash",    bodyHash);
        m.put("DstDomId",    dstDomId);
        m.put("Nonce",       nonce);
        m.put("OriginSig",   originSig);
        m.put("SignerPrvId", signerPrvId);
        m.put("SrcDomId",    srcDomId);
        m.put("Timestamp",   timestamp);
        return m;
    }

    /** Constructs from a pre-parsed JSON map (e.g. a Sec.Origin sub-map). */
    public static OriginBlock fromMap(final JsonMap m)
    {
        if (m == null)
            return null;

        final Object tsObj = m.get("Timestamp");
        long timestamp = 0L;
        if (tsObj instanceof Number)
            timestamp = ((Number) tsObj).longValue();
        else if (tsObj instanceof String)
        {
            try { timestamp = Long.parseLong((String) tsObj); }
            catch (final NumberFormatException ignored) {}
        }

        return new OriginBlock(
            m.getString("ActId"),
            m.getString("SrcDomId"),
            m.getString("DstDomId"),
            m.getString("BodyHash"),
            m.getString("SignerPrvId"),
            timestamp,
            m.getString("Nonce"),
            m.getString("OriginSig"));
    }
}
