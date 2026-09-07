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
 * (Spec-Security PART 8.3 / 8.4). Immutable; this IS the wire shape.
 *
 * Wire fields (canonical-sorted for signing; absent fields omitted,
 * never encoded as null):
 *   ActId       — index 0 only, when an account is asserted
 *   BodyHash    — Base64Encoder SHA-256 of this hop's canonical Body
 *   ContextId   — minted at root, identical on every hop of one action
 *   DstDomId    — string-encoded destination (the current object)
 *   HopSig      — Base64Encoder Ed25519; omitted on an unsigned root
 *   Nonce       — Base64Encoder 16-byte random value
 *   PrevHopHash — SHA-256 of the previous hop including HopSig; "" at 0
 *   SignerPrv   — prvId whose operational key signed this hop
 *   SrcDomId    — string-encoded source (hop 0 = servlet-minted UI id)
 *   Timestamp   — wall-clock milliseconds since the Unix epoch
 *
 * Invariants: hop[i].SrcDomId == hop[i-1].DstDomId; ActId at index 0
 * only. There is no Seq: position is pinned transitively by PrevHopHash
 * (empty at index 0; each later hop covers the previous including its
 * signature).
 */
public final class Hop
{
    public final String contextId;
    public final String actId;
    public final String signerPrv;
    public final String srcDomId;
    public final String dstDomId;
    public final String bodyHash;
    public final String prevHopHash;
    public final long   timestamp;
    public final String nonce;
    public final String hopSig;

    Hop(final String contextId,
        final String actId,
        final String signerPrv,
        final String srcDomId,
        final String dstDomId,
        final String bodyHash,
        final String prevHopHash,
        final long   timestamp,
        final String nonce,
        final String hopSig)
    {
        this.contextId   = contextId;
        this.actId       = actId;
        this.signerPrv   = signerPrv;
        this.srcDomId    = srcDomId;
        this.dstDomId    = dstDomId;
        this.bodyHash    = bodyHash;
        this.prevHopHash = prevHopHash != null ? prevHopHash : "";
        this.timestamp   = timestamp;
        this.nonce       = nonce;
        this.hopSig      = hopSig;
    }

    /**
     * Builds and signs a hop. Signing failure propagates; this never
     * returns an unsigned hop. The hop retains nothing of the body
     * (Spec PART 8.3).
     *
     * @param prevHopHash SHA-256 of the previous canonical hop, or "" at index 0
     */
    public static Hop sign(final String contextId,
                           final String actId,
                           final String signerPrv,
                           final String srcDomId,
                           final String dstDomId,
                           final byte[] canonicalBody,
                           final String prevHopHash)
    {
        final Hop unsigned = unsigned(contextId, actId, signerPrv, srcDomId,
                                      dstDomId, canonicalBody, prevHopHash);

        final byte[] canonical = CanonicalJson.canonicalizeExcluding(unsigned.toMap(), "HopSig");
        final byte[] sigBytes  = ProviderKeyStore.sign(canonical);
        final String hopSig    = Base64Encoder.encode(sigBytes);

        return new Hop(unsigned.contextId, unsigned.actId, unsigned.signerPrv,
                       unsigned.srcDomId, unsigned.dstDomId, unsigned.bodyHash,
                       unsigned.prevHopHash, unsigned.timestamp, unsigned.nonce,
                       hopSig);
    }

    /**
     * Pre-bootstrap root of Spec PART 8.10: identical bytes to {@link #sign},
     * hopSig null. Can never pass Path.verify check (b). In-process only —
     * an HTTP message with an unsigned root is rejected.
     */
    public static Hop unsigned(final String contextId,
                               final String actId,
                               final String signerPrv,
                               final String srcDomId,
                               final String dstDomId,
                               final byte[] canonicalBody,
                               final String prevHopHash)
    {
        final String bodyHashB64 = Base64Encoder.encode(KeyOps.sha256(canonicalBody));

        final byte[] nonceBytes = new byte[16];
        new SecureRandom().nextBytes(nonceBytes);
        final String nonce = Base64Encoder.encode(nonceBytes);

        return new Hop(contextId, actId, signerPrv, srcDomId, dstDomId,
                       bodyHashB64, prevHopHash != null ? prevHopHash : "",
                       System.currentTimeMillis(), nonce, null);
    }

    /**
     * Returns Base64Encoder(SHA-256(canonical(this hop including HopSig))),
     * as required by the next hop's {@code PrevHopHash}.
     */
    public String canonicalHash()
    {
        final byte[] canonical = CanonicalJson.canonicalize(toMap());
        return Base64Encoder.encode(KeyOps.sha256(canonical));
    }

    /**
     * The current object is the destination of the hop that arrived here.
     */
    public String getDomId()
    {
        return dstDomId;
    }

    /** Returns this hop as a JSON-compatible map (includes HopSig when present). */
    public JsonMap toMap()
    {
        final JsonMap m = new JsonHashMap();

        if (actId != null)
            m.put("ActId", actId);

        m.put("BodyHash",    bodyHash);
        m.put("ContextId",   contextId);
        m.put("DstDomId",    dstDomId);

        if (hopSig != null)
            m.put("HopSig", hopSig);

        m.put("Nonce",       nonce);
        m.put("PrevHopHash", prevHopHash);
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
            m.getString("ContextId"),
            m.getString("ActId"),
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
