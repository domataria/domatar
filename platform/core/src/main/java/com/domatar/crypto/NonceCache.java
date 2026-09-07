/*
 * Copyright (c) 2024 Domatar
 */

package com.domatar.crypto;

import com.domatar.core.DomatarConfig;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Sliding-window replay-prevention cache (Spec-Security.txt PART 7.3 step e).
 *
 * Stores {@code (signerPrvId, nonce)} pairs; each entry expires after
 * {@code DOMATAR_MSG_SKEW_MS} milliseconds (default 120 s).  Any message whose
 * nonce has been seen within the window is rejected.
 *
 * This is an in-memory implementation — restarts reset the window.  That is
 * acceptable for Phase 1: the timestamp check already reduces the attack window
 * to ±skew, so the nonce cache only needs to cover replays within a single
 * server lifetime. A persistent nonce store can be added in a later phase if
 * needed.
 *
 * Thread-safe via synchronization on the cache map.
 */
public class NonceCache
{
    private static final NonceCache INSTANCE = new NonceCache();

    /** Returns the process-wide singleton. */
    public static NonceCache getInstance()
    {
        return INSTANCE;
    }

    // LRU-ish map: insertion order, oldest entries pruned when TTL expires.
    private final LinkedHashMap<String, Long> seen = new LinkedHashMap<>();

    private NonceCache() {}

    /**
     * Records a {@code (signerPrvId, nonce)} pair.
     *
     * @param signerPrvId the hstId of the provider that signed the message
     * @param nonce       the nonce from the hop
     * @return {@code true} if the nonce was already seen within the window
     *         (replay detected), {@code false} if it is new (accept)
     */
    public boolean reject(final String signerPrvId, final String nonce)
    {
        final String key       = signerPrvId + "\u0000" + nonce;
        final long   now       = System.currentTimeMillis();
        final long   windowMs  = DomatarConfig.getMsgSkewMs();
        final long   expiresAt = now + windowMs;

        synchronized (seen)
        {
            purgeExpired(now, windowMs);

            if (seen.containsKey(key))
                return true; // replay

            seen.put(key, expiresAt);
            return false;
        }
    }

    private void purgeExpired(final long now, final long windowMs)
    {
        final Iterator<Map.Entry<String, Long>> it = seen.entrySet().iterator();

        while (it.hasNext())
        {
            final Map.Entry<String, Long> entry = it.next();

            if (entry.getValue() < now)
                it.remove();
            else
                break; // entries are in insertion order; once we hit a live one, done
        }
    }
}
