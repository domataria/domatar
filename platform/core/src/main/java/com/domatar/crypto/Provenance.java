/*
 * Copyright (c) 2024 Domatar
 */

package com.domatar.crypto;

import com.domatar.core.Trust;
import com.domatar.util.DomId;
import com.domatar.util.DomatarException;
import com.domatar.util.JsonMap;

/**
 * The chain plus per-operation credentials (Spec-Security PART 8.2).
 * Platform-only: never a field of Context or JsonMsg, never handed to a
 * handler. Delegation and Binding sit here once, not on every hop.
 */
public final class Provenance
{
    private final Path       path;
    private final Delegation delegation;
    private final Binding    binding;

    private Provenance(final Path path, final Delegation delegation,
                       final Binding binding)
    {
        this.path       = path != null ? path : Path.empty();
        this.delegation = delegation;
        this.binding    = binding;
    }

    public static Provenance empty()
    {
        return new Provenance(Path.empty(), null, null);
    }

    public static Provenance of(final Path path, final Delegation d,
                                final Binding b)
    {
        return new Provenance(path, d, b);
    }

    /** The Path object is itself safe to hand out (wrapped, immutable). */
    public Path path()
    {
        return path;
    }

    public String contextId()
    {
        return path.contextId();
    }

    public int depth()
    {
        return path.depth();
    }

    public DomId[] domIdPath() throws DomatarException
    {
        return path.domIdPath();
    }

    public boolean isEmpty()
    {
        return path.isEmpty();
    }

    public boolean hasCredentials()
    {
        return delegation != null && binding != null;
    }

    public Delegation delegation()
    {
        return delegation;
    }

    public Binding binding()
    {
        return binding;
    }

    /**
     * New Provenance with the extended Path and the SAME delegation and
     * binding (they are per-operation and sit here once — Spec PART 8.2).
     */
    public Provenance append(final DomId src, final DomId dst,
                             final byte[] canonicalBody, final String signerPrv)
        throws DomatarException
    {
        return new Provenance(path.append(src, dst, canonicalBody, signerPrv),
                              delegation, binding);
    }

    public String hop0ActId()
    {
        return path.isEmpty() ? null : path.hop0().actId;
    }

    /**
     * Q2 then Q1 (Spec PART 7.3 / 8.9). There is no second nonce: replaying
     * the account assertion is replaying hop 0.
     *
     * {@code localBindingVersion} is the stored Binding.version when this
     * node hosts the account, or null. Typed as Long because the shipped
     * Binding.version is a long (millis), not a small int.
     */
    public Verdict verify(final JsonMap body, final ProviderKeyResolver keys,
                          final int fpVersion, final Long localBindingVersion)
    {
        final Verdict q2 = path.verify(body, keys);
        if (q2.trust != Trust.PATH)
            return q2;

        final Hop h0 = path.hop0();
        if (h0.actId == null)
            return q2;

        if (binding == null || !binding.verify(fpVersion))
            return Verdict.none("binding");

        if (delegation == null)
            return Verdict.none("no delegation");

        if (!h0.actId.equals(binding.actId)
            || !delegation.actId.equals(binding.actId))
            return Verdict.none("actId mismatch");

        if (!delegation.ownPubKeyB64.equals(binding.ownPubKeyB64))
            return Verdict.none("ownPubKey mismatch");

        if (!delegation.prvId.equals(h0.signerPrv))
            return Verdict.none("delegation names " + delegation.prvId
                + ", hop 0 signed by " + h0.signerPrv);

        final long skewMs = 5L * 60L * 1000L;
        if (System.currentTimeMillis() > delegation.notAfter + skewMs)
            return Verdict.none("delegation expired");

        if (!delegation.verify(binding, fpVersion))
            return Verdict.none("delegSig");

        if (localBindingVersion != null
            && binding.version < localBindingVersion.longValue())
            return Verdict.none("superseded binding");

        return Verdict.account(h0.actId, path.contextId());
    }

    /** Empty or hop 0 with no HopSig — an HTTP unsigned root. */
    public boolean isUnsignedHttp()
    {
        return path.isEmpty() || path.hop0().hopSig == null;
    }
}
