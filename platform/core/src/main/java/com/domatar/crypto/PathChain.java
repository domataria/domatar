/*
 * Copyright (c) 2024 Domatar
 */

package com.domatar.crypto;

import com.domatar.core.DomatarConfig;
import com.domatar.util.Base64Encoder;
import com.domatar.util.JsonMap;
import com.domatar.util.DomId;

import java.security.PublicKey;
import java.util.List;
import java.util.function.Function;

/**
 * Q2 path-provenance utilities: append and verify the signed hop chain
 * (Spec-Security.txt PART 8.2 / 8.3).
 *
 * <h2>Append</h2>
 * Every time a provider performs a send that extends the causal path, call
 * {@link #append} to build and sign the next hop.  The method computes
 * {@code PrevHopHash} automatically from the last existing hop.
 *
 * <h2>Verify</h2>
 * A receiver that needs to trust the entire route calls
 * {@link #verify(List, OriginBlock, JsonMap, Function)}.  The five checks
 * mirror Spec-Security.txt PART 8.3 (a–e).
 */
public class PathChain
{
    private PathChain() {}

    // -------------------------------------------------------------------------
    // Append
    // -------------------------------------------------------------------------

    /**
     * Builds and signs a new Hop for an outbound send, appending it to the
     * chain conceptually.  The caller is responsible for putting the returned
     * hop into the {@code Head.Sec.Path} list (via
     * {@link com.domatar.util.JsonMsg#appendHopToSec}).
     *
     * @param existingPath the current hop list (may be empty for hop 0)
     * @param src          source DomId of this send
     * @param dst          destination DomId of this send
     * @param body         message Body map; SHA-256 of its canonical JSON is stored
     * @param signerPrv    hstId of this provider
     * @return the newly signed Hop
     */
    public static Hop append(final List<Hop> existingPath,
                             final DomId     src,
                             final DomId     dst,
                             final JsonMap   body,
                             final String    signerPrv)
    {
        final int seq;
        final String prevHopHash;

        if (existingPath == null || existingPath.isEmpty())
        {
            seq         = 0;
            prevHopHash = "";
        }
        else
        {
            seq         = existingPath.size();
            prevHopHash = existingPath.get(existingPath.size() - 1).canonicalHash();
        }

        return Hop.sign(seq, signerPrv, src.toString(), dst.toString(), body, prevHopHash);
    }

    // -------------------------------------------------------------------------
    // Verify
    // -------------------------------------------------------------------------

    /**
     * Verifies a complete hop chain (Spec-Security.txt PART 8.3 a–e).
     *
     * @param path           the full hop list (must not be null or empty)
     * @param q1Origin       the Q1 Origin block from the same message Sec envelope;
     *                       used for check (a). May be null — in which case
     *                       check (a) is skipped.
     * @param finalBody      the Body map of the message as received (used to
     *                       verify the final hop's BodyHash, check d portion)
     * @param pubKeyResolver takes a {@code signerPrv} hstId and returns that
     *                       provider's operational {@link PublicKey}, or
     *                       {@code null} if unknown
     * @return {@code true} iff all applicable checks pass
     */
    public static boolean verify(final List<Hop>              path,
                                 final OriginBlock            q1Origin,
                                 final JsonMap                finalBody,
                                 final Function<String, PublicKey> pubKeyResolver)
    {
        if (path == null || path.isEmpty())
            return false;

        // (a) Hop[0] is the origin — its SrcDomId and BodyHash must agree with Q1.
        if (q1Origin != null)
        {
            final Hop first = path.get(0);

            if (!q1Origin.srcDomId.equals(first.srcDomId))
                return false;

            if (!q1Origin.bodyHash.equals(first.bodyHash))
                return false;
        }

        // (b) and (c): walk every hop.
        for (int i = 0; i < path.size(); i++)
        {
            final Hop hop = path.get(i);

            // (b) PrevHopHash chain is unbroken.
            if (i == 0)
            {
                // Seq 0 must have empty prevHopHash.
                if (hop.prevHopHash == null || !hop.prevHopHash.isEmpty())
                    return false;
            }
            else
            {
                final String expectedPrev = path.get(i - 1).canonicalHash();

                if (!expectedPrev.equals(hop.prevHopHash))
                    return false;
            }

            // Seq field must match position.
            if (hop.seq != i)
                return false;

            // (c) HopSig verifies under the signer's public key.
            if (hop.signerPrv == null || hop.hopSig == null)
                return false;

            final PublicKey pubKey = pubKeyResolver.apply(hop.signerPrv);

            if (pubKey == null)
                return false; // unknown signer — reject

            final byte[] canonical = CanonicalJson.canonicalizeExcluding(hop.toMap(), "HopSig");
            final byte[] sigBytes;

            try { sigBytes = Base64Encoder.decode(hop.hopSig); }
            catch (final RuntimeException e) { return false; }

            if (!KeyOps.verify(pubKey, canonical, sigBytes))
                return false;
        }

        // (d) Path is contiguous: each hop's DstDomId must equal the next hop's SrcDomId.
        for (int i = 0; i < path.size() - 1; i++)
        {
            if (!path.get(i).dstDomId.equals(path.get(i + 1).srcDomId))
                return false;
        }

        // (e) Replay / expiry checks on the FINAL hop (mirrors Q1 final-hop rules).
        final Hop last     = path.get(path.size() - 1);
        final long skewMs  = DomatarConfig.getMsgSkewMs();
        final long now     = System.currentTimeMillis();

        if (Math.abs(now - last.timestamp) > skewMs)
            return false;

        if (NonceCache.getInstance().reject("path:" + last.signerPrv, last.nonce))
            return false; // path-nonce replay

        return true;
    }
}
