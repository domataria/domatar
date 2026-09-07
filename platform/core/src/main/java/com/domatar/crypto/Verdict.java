/*
 * Copyright (c) 2024 Domatar
 */

package com.domatar.crypto;

import com.domatar.core.Trust;

/**
 * Outcome of path / credential verification, and the only route from
 * wire bytes to a trusted actId (Spec-Security PART 7.5 / 8.9 / 16).
 *
 * {@code reason} is for logs ONLY — it must never be parsed for policy.
 * {@code actId} is non-null iff {@code trust == ACCOUNT}.
 */
public final class Verdict
{
    public final Trust  trust;
    public final String actId;
    public final String contextId;
    public final String reason;

    private Verdict(final Trust trust, final String actId,
                    final String contextId, final String reason)
    {
        this.trust     = trust;
        this.actId     = actId;
        this.contextId = contextId;
        this.reason    = reason;
    }

    public static Verdict none(final String reason)
    {
        return new Verdict(Trust.NONE, null, null, reason);
    }

    public static Verdict path(final String contextId)
    {
        return new Verdict(Trust.PATH, null, contextId, null);
    }

    public static Verdict account(final String actId, final String contextId)
    {
        return new Verdict(Trust.ACCOUNT, actId, contextId, null);
    }
}
