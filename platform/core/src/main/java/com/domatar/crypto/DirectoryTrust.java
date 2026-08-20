/*
 * Copyright (c) 2024 Domatar
 */

package com.domatar.crypto;

import com.domatar.core.DomatarConfig;
import com.domatar.util.Base64Encoder;
import com.domatar.util.JsonHashMap;
import com.domatar.util.JsonMap;

import java.security.PublicKey;
import java.util.logging.Logger;

/**
 * Holds the DIRECTORY ROOT public key — the single pinned trust anchor for
 * provider identity, per Spec-Security.txt PART 5.3.
 *
 * The directory root key signs each hst table record (Phase 3+), allowing any
 * Domatar node to verify that a host record came from the authoritative global
 * directory and was not tampered with in transit.
 *
 * Configuration:
 *   DOMATAR_DIRECTORY_ROOT_PUBKEY / DirectoryRootPubKey — Base64Encoder-encoded
 *     32-byte Ed25519 public key of the directory root.  Required on every node
 *     that verifies hst records (Phase 3+).
 *
 *   DOMATAR_DIRECTORY_ROOT_PRIVKEY / DirectoryRootPrivKey — Base64Encoder-encoded
 *     32-byte Ed25519 private key seed.  Set ONLY on the node that IS the global
 *     directory server.  Used in Phase 3 to sign hst records on write.
 *
 * This class is NOT wired into the live message path in Phase 1; it is
 * testable stand-alone.
 */
public class DirectoryTrust
{
    private static final Logger LOG = Logger.getLogger(DirectoryTrust.class.getName());

    private static volatile PublicKey  cachedRootPubKey;
    private static volatile byte[]     cachedRootPrivBytes;

    private DirectoryTrust() {}

    /**
     * Returns the directory root {@link PublicKey}, loaded from config.
     *
     * @throws IllegalStateException if {@code DirectoryRootPubKey} is not configured
     */
    public static PublicKey rootPublicKey()
    {
        if (cachedRootPubKey != null)
            return cachedRootPubKey;

        synchronized (DirectoryTrust.class)
        {
            if (cachedRootPubKey != null)
                return cachedRootPubKey;

            final String encoded = DomatarConfig.getDirectoryRootPubKey();

            if (encoded == null || encoded.isEmpty())
                throw new IllegalStateException(
                    "DirectoryRootPubKey is not configured "
                    + "(set DOMATAR_DIRECTORY_ROOT_PUBKEY or DirectoryRootPubKey "
                    + "in provider.config.txt)");

            final byte[] rawBytes = Base64Encoder.decode(encoded);

            if (rawBytes.length != 32)
                throw new IllegalStateException(
                    "DirectoryRootPubKey must decode to 32 bytes, got " + rawBytes.length);

            cachedRootPubKey = KeyOps.publicKeyFromBytes(rawBytes);
        }

        return cachedRootPubKey;
    }

    /**
     * Returns {@code true} iff {@code sigBytes} is a valid Ed25519 signature of
     * {@code canonicalRowBytes} under the directory root public key.
     *
     * @param canonicalRowBytes the canonical JSON bytes of the hst record
     *                          (produced by {@link CanonicalJson})
     * @param sigBytes          64-byte Ed25519 signature
     */
    public static boolean verifyHstRecord(final byte[] canonicalRowBytes, final byte[] sigBytes)
    {
        return KeyOps.verify(rootPublicKey(), canonicalRowBytes, sigBytes);
    }

    /**
     * Returns the raw 32-byte private key seed of the directory root key, or
     * {@code null} if this node is not the directory server.
     *
     * Used in Phase 3 by the directory server to sign hst records on write.
     */
    public static byte[] rootPrivateKeyBytes()
    {
        if (cachedRootPrivBytes != null)
            return cachedRootPrivBytes;

        synchronized (DirectoryTrust.class)
        {
            if (cachedRootPrivBytes != null)
                return cachedRootPrivBytes;

            final String encoded = DomatarConfig.getDirectoryRootPrivKey();

            if (encoded == null || encoded.isEmpty())
                return null;

            final byte[] rawBytes = Base64Encoder.decode(encoded);

            if (rawBytes.length != 32)
                throw new IllegalStateException(
                    "DirectoryRootPrivKey must decode to 32 bytes, got " + rawBytes.length);

            cachedRootPrivBytes = rawBytes;
        }

        return cachedRootPrivBytes;
    }

    /**
     * Builds the canonical byte string for an hst record.
     *
     * The canonical form is {@code CanonicalJson({HstId,Domain,PrvId,Version,PubKey})}
     * (per Spec-Security.txt PART 10.1).  Null fields are included as JSON {@code null}.
     *
     * @param hstId    host identifier
     * @param domain   domain name or host:port
     * @param prvId    providing-host identifier
     * @param version  monotonic version number
     * @param pubKey   Base64Encoder-encoded provider operational public key, or null
     * @return UTF-8 canonical JSON bytes
     */
    public static byte[] canonicalHstRecord(final String hstId, final String domain,
                                            final String prvId, final long version,
                                            final String pubKey)
    {
        final JsonMap row = new JsonHashMap();
        row.put("HstId",   hstId);
        row.put("Domain",  domain);
        row.put("PrvId",   prvId);
        row.put("Version", version);
        row.put("PubKey",  pubKey);
        return CanonicalJson.canonicalize(row);
    }

    /**
     * Signs the canonical hst record with the directory root private key and
     * returns the Base64Encoder-encoded 64-byte signature, or {@code null} if
     * this node is NOT the directory server (root private key not configured).
     *
     * @param hstId   host identifier
     * @param domain  domain name
     * @param prvId   providing-host identifier
     * @param version monotonic version
     * @param pubKey  provider operational public key (Base64Encoder), or null
     * @return Base64Encoder signature string, or null
     */
    public static String signRecord(final String hstId, final String domain,
                                    final String prvId, final long version,
                                    final String pubKey)
    {
        final byte[] privBytes = rootPrivateKeyBytes();

        if (privBytes == null)
            return null; // not the directory server

        final byte[] canonical = canonicalHstRecord(hstId, domain, prvId, version, pubKey);
        final byte[] sig       = KeyOps.sign(KeyOps.privateKeyFromBytes(privBytes), canonical);

        return Base64Encoder.encode(sig);
    }

    /**
     * Clears the in-memory cached keys (for testing only).
     */
    static void resetForTesting()
    {
        synchronized (DirectoryTrust.class)
        {
            cachedRootPubKey    = null;
            cachedRootPrivBytes = null;
        }
    }
}
