/*
 * Copyright (c) 2024 Domatar
 */

package com.domatar.crypto;

import com.domatar.util.Base64Encoder;
import com.domatar.util.JsonHashMap;
import com.domatar.util.JsonMap;

import java.security.SecureRandom;

/**
 * One signed link in the Q2 path-provenance hash chain
 * (Spec-Security.txt PART 8.2).
 *
 * Each time a provider performs a send that extends the causal path, it
 * appends a {@code Hop} to {@code Head.Sec.Path}.  Hop[0] is the ORIGIN hop
 * and its {@code SrcDomId} / {@code BodyHash} MUST match the Q1 Origin block.
 *
 * Wire format (JSON fields, canonical-sorted for signing):
 *   BodyHash    — Base64Encoder SHA-256 of the canonical Body of THIS hop's message
 *   DstDomId    — string-encoded destination DomId
 *   HopSig      — Base64Encoder Ed25519 signature (excluded when computing
 *                 signed bytes; see {@link CanonicalJson#canonicalizeExcluding})
 *   Nonce       — Base64Encoder 16-byte random value
 *   PrevHopHash — Base64Encoder SHA-256 of the canonical previous Hop (incl.
 *                 its HopSig); empty string at Seq 0
 *   Seq         — integer sequence number (0 at origin)
 *   SignerPrv   — hstId of the provider performing this hop
 *   SrcDomId    — string-encoded source DomId
 *   Timestamp   — wall-clock milliseconds since the Unix epoch
 */
public class Hop
{
    public final int    seq;
    public final String signerPrv;
    public final String srcDomId;
    public final String dstDomId;
    public final String bodyHash;
    public final String prevHopHash;
    public final long   timestamp;
    public final String nonce;
    public final String hopSig;

    private Hop(final int    seq,
                final String signerPrv,
                final String srcDomId,
                final String dstDomId,
                final String bodyHash,
                final String prevHopHash,
                final long   timestamp,
                final String nonce,
                final String hopSig)
    {
        this.seq         = seq;
        this.signerPrv   = signerPrv;
        this.srcDomId    = srcDomId;
        this.dstDomId    = dstDomId;
        this.bodyHash    = bodyHash;
        this.prevHopHash = prevHopHash;
        this.timestamp   = timestamp;
        this.nonce       = nonce;
        this.hopSig      = hopSig;
    }

    // -------------------------------------------------------------------------
    // Factory — build + sign
    // -------------------------------------------------------------------------

    /**
     * Builds and signs a new hop at {@code seq}.
     *
     * @param seq         sequence number (0 at origin)
     * @param signerPrv   hstId of the provider performing the send
     * @param srcDomId    source DomId string
     * @param dstDomId    destination DomId string
     * @param bodyMap     message Body map; SHA-256(canonical(body)) is stored
     * @param prevHopHash SHA-256 of the previous canonical hop, or "" at seq 0
     */
    public static Hop sign(final int    seq,
                           final String signerPrv,
                           final String srcDomId,
                           final String dstDomId,
                           final com.domatar.util.JsonMap bodyMap,
                           final String prevHopHash)
    {
        final byte[] bodyBytes  = CanonicalJson.canonicalize(bodyMap);
        final String bodyHashB64 = Base64Encoder.encode(KeyOps.sha256(bodyBytes));

        final byte[] nonceBytes = new byte[16];
        new SecureRandom().nextBytes(nonceBytes);
        final String nonce = Base64Encoder.encode(nonceBytes);

        final long timestamp = System.currentTimeMillis();

        final Hop unsigned = new Hop(seq, signerPrv, srcDomId, dstDomId,
                                     bodyHashB64, prevHopHash != null ? prevHopHash : "",
                                     timestamp, nonce, null);

        final byte[] canonical = CanonicalJson.canonicalizeExcluding(unsigned.toMap(), "HopSig");
        final byte[] sigBytes  = ProviderKeyStore.sign(canonical);
        final String hopSig    = Base64Encoder.encode(sigBytes);

        return new Hop(seq, signerPrv, srcDomId, dstDomId,
                       bodyHashB64, prevHopHash != null ? prevHopHash : "",
                       timestamp, nonce, hopSig);
    }

    // -------------------------------------------------------------------------
    // Hash of this hop (for use as the next hop's PrevHopHash)
    // -------------------------------------------------------------------------

    /**
     * Returns Base64Encoder(SHA-256(canonical(this hop including HopSig))),
     * as required by the next hop's {@code PrevHopHash}.
     */
    public String canonicalHash()
    {
        final byte[] canonical = CanonicalJson.canonicalize(toMap());
        return Base64Encoder.encode(KeyOps.sha256(canonical));
    }

    // -------------------------------------------------------------------------
    // Serialization
    // -------------------------------------------------------------------------

    /** Returns this hop as a JSON-compatible map (includes HopSig). */
    public JsonMap toMap()
    {
        final JsonMap m = new JsonHashMap();
        m.put("BodyHash",    bodyHash);
        m.put("DstDomId",    dstDomId);
        m.put("HopSig",      hopSig);
        m.put("Nonce",       nonce);
        m.put("PrevHopHash", prevHopHash);
        m.put("Seq",         seq);
        m.put("SignerPrv",   signerPrv);
        m.put("SrcDomId",    srcDomId);
        m.put("Timestamp",   timestamp);
        return m;
    }

    /** Constructs from a pre-parsed JSON map (e.g. a Sec.Path element). */
    public static Hop fromMap(final JsonMap m)
    {
        if (m == null)
            return null;

        final Object seqObj = m.get("Seq");
        int seq = 0;
        if (seqObj instanceof Number)
            seq = ((Number) seqObj).intValue();
        else if (seqObj instanceof String)
        {
            try { seq = Integer.parseInt((String) seqObj); }
            catch (final NumberFormatException ignored) {}
        }

        final Object tsObj = m.get("Timestamp");
        long timestamp = 0L;
        if (tsObj instanceof Number)
            timestamp = ((Number) tsObj).longValue();
        else if (tsObj instanceof String)
        {
            try { timestamp = Long.parseLong((String) tsObj); }
            catch (final NumberFormatException ignored) {}
        }

        return new Hop(
            seq,
            m.getString("SignerPrv"),
            m.getString("SrcDomId"),
            m.getString("DstDomId"),
            m.getString("BodyHash"),
            m.getString("PrevHopHash"),
            timestamp,
            m.getString("Nonce"),
            m.getString("HopSig"));
    }
}
