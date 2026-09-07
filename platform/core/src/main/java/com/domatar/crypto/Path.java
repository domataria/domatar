/*
 * Copyright (c) 2024 Domatar
 */

package com.domatar.crypto;

import com.domatar.core.DomatarConfig;
import com.domatar.util.Base64Encoder;
import com.domatar.util.DomId;
import com.domatar.util.DomatarException;
import com.domatar.util.JsonArrayList;
import com.domatar.util.JsonList;
import com.domatar.util.JsonMap;

import java.security.PublicKey;
import java.security.SecureRandom;
import java.util.Objects;

/**
 * The wrapped hop chain (Spec-Security PART 8.1 / 8.9 / 16). Immutable;
 * the array is never published. Hands out only copies, projections, and
 * {@link #last()} (Hop itself is immutable). This is what closes the A6
 * hole a public {@code Hop[]} would open.
 */
public final class Path
{
    private final Hop[] hops;

    private Path(final Hop[] hops)
    {
        this.hops = hops != null ? hops.clone() : new Hop[0];
    }

    /** Zero hops; what a fresh root-capable client holds. */
    public static Path empty()
    {
        return new Path(new Hop[0]);
    }

    /**
     * Mints ContextId as 16 random bytes, signs hop 0 with that ContextId
     * and ActId, returns a one-hop Path.
     */
    public static Path root(final DomId src, final DomId dst,
                            final byte[] canonicalBody, final String actId,
                            final String signerPrv)
    {
        final byte[] idBytes = new byte[16];
        new SecureRandom().nextBytes(idBytes);
        final String contextId = Base64Encoder.encode(idBytes);
        final Hop hop = Hop.sign(contextId, actId, signerPrv,
                                 src.toString(), dst.toString(),
                                 canonicalBody, "");
        return new Path(new Hop[] { hop });
    }

    /**
     * In-process pre-bootstrap only (Spec PART 8.10). Never passes
     * verification: check (b) fails a null HopSig.
     */
    public static Path unsignedRoot(final DomId src, final DomId dst,
                                    final byte[] canonicalBody, final String actId,
                                    final String signerPrv)
    {
        final byte[] idBytes = new byte[16];
        new SecureRandom().nextBytes(idBytes);
        final String contextId = Base64Encoder.encode(idBytes);
        final Hop hop = Hop.unsigned(contextId, actId, signerPrv,
                                     src.toString(), dst.toString(),
                                     canonicalBody, "");
        return new Path(new Hop[] { hop });
    }

    /**
     * Copies contextId from hop 0, sets actId null, chains PrevHopHash.
     * Returns a NEW Path. Throws when this Path is empty or when the
     * resulting depth would exceed {@link DomatarConfig#getMaxHopDepth()}.
     */
    public Path append(final DomId src, final DomId dst,
                       final byte[] canonicalBody, final String signerPrv)
        throws DomatarException
    {
        if (hops.length == 0)
            throw new DomatarException("send on an empty chain");

        if (depth() + 1 > DomatarConfig.getMaxHopDepth())
            throw new DomatarException("hop depth cap exceeded");

        final Hop last = hops[hops.length - 1];
        final Hop next = Hop.sign(hops[0].contextId, null, signerPrv,
                                  src.toString(), dst.toString(),
                                  canonicalBody, last.canonicalHash());
        final Hop[] extended = new Hop[hops.length + 1];
        System.arraycopy(hops, 0, extended, 0, hops.length);
        extended[hops.length] = next;
        return new Path(extended);
    }

    public String contextId()
    {
        return hops.length == 0 ? null : hops[0].contextId;
    }

    public int depth()
    {
        return hops.length;
    }

    public boolean isEmpty()
    {
        return hops.length == 0;
    }

    public Hop last()
    {
        if (hops.length == 0)
            throw new IllegalStateException("empty path");
        return hops[hops.length - 1];
    }

    Hop hop0()
    {
        if (hops.length == 0)
            throw new IllegalStateException("empty path");
        return hops[0];
    }

    /**
     * Spec PART 8.5 projection: UI then every destination, re-parsed into
     * a fresh DomId[] per call.
     */
    public DomId[] domIdPath() throws DomatarException
    {
        if (hops.length == 0)
            return new DomId[0];

        final DomId[] out = new DomId[hops.length + 1];
        out[0] = new DomId(hops[0].srcDomId);
        out[1] = new DomId(hops[0].dstDomId);
        for (int i = 1; i < hops.length; i++)
            out[i + 1] = new DomId(hops[i].dstDomId);
        return out;
    }

    /** Same projection without re-parsing, for log-only callers. */
    public String[] domIdPathStrings()
    {
        if (hops.length == 0)
            return new String[0];

        final String[] out = new String[hops.length + 1];
        out[0] = hops[0].srcDomId;
        out[1] = hops[0].dstDomId;
        for (int i = 1; i < hops.length; i++)
            out[i + 1] = hops[i].dstDomId;
        return out;
    }

    public JsonList toWire()
    {
        final JsonList list = new JsonArrayList(hops.length);
        for (int i = 0; i < hops.length; i++)
            list.add(hops[i].toMap());
        return list;
    }

    public static Path fromWire(final JsonList list)
    {
        if (list == null || list.size() == 0)
            return empty();

        final Hop[] parsed = new Hop[list.size()];
        int n = 0;
        for (int i = 0; i < list.size(); i++)
        {
            final Object elem = list.get(i);
            if (!(elem instanceof JsonMap))
                continue;
            final Hop hop = Hop.fromMap((JsonMap) elem);
            if (hop != null)
                parsed[n++] = hop;
        }

        if (n == parsed.length)
            return new Path(parsed);

        final Hop[] tight = new Hop[n];
        System.arraycopy(parsed, 0, tight, 0, n);
        return new Path(tight);
    }

    /**
     * Q2 only (Spec PART 8.9 (a)-(i)). Never returns {@link Trust#ACCOUNT};
     * Q1 is Provenance.verify in Phase 4.
     */
    public Verdict verify(final JsonMap body, final ProviderKeyResolver keys)
    {
        if (hops.length == 0)
            return Verdict.none("no path");

        for (int i = 0; i < hops.length; i++)
        {
            if (hops[i].hopSig == null)
                return Verdict.none("unsigned hop " + i);
        }

        if (hops.length > DomatarConfig.getMaxHopDepth())
            return Verdict.none("depth cap");

        final String lineage = hops[0].contextId;
        for (int i = 1; i < hops.length; i++)
        {
            if (!Objects.equals(lineage, hops[i].contextId))
                return Verdict.none("context id mismatch at " + i);
        }

        for (int i = 1; i < hops.length; i++)
        {
            if (hops[i].actId != null)
                return Verdict.none("actId at hop " + i);
        }

        for (int i = 0; i < hops.length; i++)
        {
            if (i == 0)
            {
                if (hops[i].prevHopHash == null || !hops[i].prevHopHash.isEmpty())
                    return Verdict.none("chain broken at " + i);
            }
            else
            {
                if (!hops[i - 1].canonicalHash().equals(hops[i].prevHopHash))
                    return Verdict.none("chain broken at " + i);
            }
        }

        for (int i = 0; i < hops.length; i++)
        {
            final Hop hop = hops[i];
            final PublicKey pubKey = keys == null ? null : keys.resolve(hop.signerPrv);

            if (pubKey == null)
                return Verdict.none("unknown signer " + hop.signerPrv);

            final byte[] canonical = CanonicalJson.canonicalizeExcluding(hop.toMap(), "HopSig");
            final byte[] sigBytes;
            try
            {
                sigBytes = Base64Encoder.decode(hop.hopSig);
            }
            catch (final RuntimeException e)
            {
                return Verdict.none("bad hop sig " + i);
            }

            if (!KeyOps.verify(pubKey, canonical, sigBytes))
                return Verdict.none("bad hop sig " + i);
        }

        for (int i = 1; i < hops.length; i++)
        {
            if (!Objects.equals(hops[i - 1].dstDomId, hops[i].srcDomId))
                return Verdict.none("not contiguous at " + i);
        }

        try
        {
            final byte[] canonicalBody = CanonicalJson.canonicalize(body);
            final String expected = Base64Encoder.encode(KeyOps.sha256(canonicalBody));
            if (!expected.equals(last().bodyHash))
                return Verdict.none("body hash");
        }
        catch (final RuntimeException e)
        {
            return Verdict.none("body hash");
        }

        for (int i = 1; i < hops.length; i++)
        {
            if (hops[i].timestamp < hops[i - 1].timestamp)
                return Verdict.none("timestamp order at " + i);
        }

        if (last().timestamp - hops[0].timestamp > DomatarConfig.getMaxChainAgeMs())
            return Verdict.none("chain too old");

        final long now = System.currentTimeMillis();
        if (Math.abs(now - last().timestamp) > DomatarConfig.getMsgSkewMs())
            return Verdict.none("stale final hop");

        if (NonceCache.getInstance().reject(last().signerPrv, last().nonce))
            return Verdict.none("replay");

        return Verdict.path(contextId());
    }
}
