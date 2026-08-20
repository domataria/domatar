/*
 * Copyright (c) 2024 Domatar
 */

package com.domatar.crypto;

import com.domatar.core.DomatarConfig;
import com.domatar.util.Base64Encoder;
import com.domatar.util.DomatarException;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.NamedParameterSpec;
import java.util.Arrays;

/**
 * Value holder and factory for an account's root identity.
 *
 * The actId is a self-certifying fingerprint derived from the account's
 * Ed25519 root public key. Algorithm version is selected by
 * {@link #deriveId(byte[], int)} (Spec-ActId-Versioning.txt PART 4).
 * Version 1 (the only algorithm today):
 *
 *   actId = Base64Encoder.encode( SHA-256(rootPubKey)[0..24) )
 *
 * 24 bytes / 3 bytes per group × 4 chars per group = 32 URL-safe characters,
 * no padding.  The fingerprint alphabet is [0-9 A-Z _ a-z ~]; it contains no
 * '.' or '@' so fingerprint actIds are unambiguously distinct from the legacy
 * "name@appId" form.
 *
 * WARNING: the security of this scheme depends entirely on the quality of the
 * CSPRNG used to generate the Ed25519 key pair (see Spec-Security.txt PART 3.4).
 * Always use the JDK default SecureRandom via {@link #generate()}.
 */
public class AccountKeys
{
    /** Fingerprint algorithm v1: SHA-256[0..24) → Base64Encoder → 32 chars. */
    public static final int FP_VERSION_V1 = 1;

    /** Legacy non-fingerprint actId (name@app); never minted new. */
    public static final int FP_VERSION_NONE = 0;

    /** Default minting version until a deliberate v2 rollout (KD5). */
    private static final int FP_DEFAULT_VERSION = 1;

    /** Raw 32-byte Ed25519 private key seed. */
    public final byte[] rootPrivKey;

    /** Raw 32-byte Ed25519 public key. */
    public final byte[] rootPubKey;

    /** Fingerprint actId derived from rootPubKey. */
    public final String actId;

    private AccountKeys(final byte[] rootPrivKey, final byte[] rootPubKey, final String actId)
    {
        this.rootPrivKey = Arrays.copyOf(rootPrivKey, rootPrivKey.length);
        this.rootPubKey  = Arrays.copyOf(rootPubKey,  rootPubKey.length);
        this.actId       = actId;
    }

    /**
     * Version new accounts are minted under. Reads
     * {@link DomatarConfig#getFpDefaultVersion()} when available; falls back
     * to {@link #FP_DEFAULT_VERSION} if config cannot load (KD5).
     */
    public static int defaultVersion()
    {
        try
        {
            return DomatarConfig.getFpDefaultVersion();
        }
        catch (final Throwable ignored)
        {
            return FP_DEFAULT_VERSION;
        }
    }

    /** All fingerprint versions this build can derive/validate. */
    public static int[] registeredVersions()
    {
        return new int[] { FP_VERSION_V1 };
    }

    /**
     * Derive an id (actId/ownId) from a 32-byte Ed25519 public key under the
     * given fingerprint version. Sole home of the v1 SHA-256/24/Base64 math.
     */
    public static String deriveId(final byte[] pubKey, final int version)
        throws DomatarException
    {
        if (pubKey == null || pubKey.length != 32)
            throw new DomatarException("public key must be 32 bytes");

        if (version == FP_VERSION_V1)
        {
            final byte[] digest  = KeyOps.sha256(pubKey);
            final byte[] first24 = Arrays.copyOfRange(digest, 0, 24);
            return Base64Encoder.encode(first24);
        }

        throw new DomatarException("Unsupported FpVersion " + version);
    }

    /** True iff pubKey fingerprints to id under version. Never throws. */
    public static boolean fingerprintsTo(final byte[] pubKey,
                                         final String id,
                                         final int version)
    {
        if (pubKey == null || id == null)
            return false;

        try
        {
            return id.equals(deriveId(pubKey, version));
        }
        catch (final DomatarException e)
        {
            return false;
        }
    }

    /**
     * Version-tolerant self-cert for self-authenticating records (KD2):
     * true iff pubKey fingerprints to id under ANY registered version.
     */
    public static boolean fingerprintsToAny(final byte[] pubKey, final String id)
    {
        for (final int v : registeredVersions())
            if (fingerprintsTo(pubKey, id, v))
                return true;

        return false;
    }

    /**
     * Generates a new random account identity under {@link #defaultVersion()}.
     */
    public static AccountKeys generate()
    {
        final KeyPair kp        = KeyOps.generateKeyPair();
        final byte[]  privBytes = KeyOps.privateKeyBytes(kp.getPrivate());
        final byte[]  pubBytes  = KeyOps.publicKeyBytes(kp.getPublic());

        try
        {
            final String id = deriveId(pubBytes, defaultVersion());
            return new AccountKeys(privBytes, pubBytes, id);
        }
        catch (final DomatarException e)
        {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    /**
     * Reconstructs a v1 identity from a stored 32-byte private key seed
     * (KD4: existing sealed keys are all v1).
     */
    public static AccountKeys fromPrivKey(final byte[] rootPrivKey)
    {
        return fromPrivKey(rootPrivKey, FP_VERSION_V1);
    }

    /**
     * Reconstructs the full identity from a stored 32-byte private key seed
     * under the given fingerprint version.
     *
     * The public key is re-derived by re-generating the Ed25519 key pair from the
     * same seed, using a deterministic {@link SecureRandom} that replays the seed
     * bytes exactly as the JDK's Ed25519 key-pair generator consumes them
     * (one {@code nextBytes(byte[32])} call during {@code generateKeyPair()}).
     * A runtime sanity check verifies that the private key round-trips correctly;
     * it will fail fast if a future JDK changes the consumption pattern.
     *
     * @param rootPrivKey 32-byte Ed25519 private key seed
     * @param version     fingerprint algorithm version for the derived actId
     */
    public static AccountKeys fromPrivKey(final byte[] rootPrivKey, final int version)
    {
        if (rootPrivKey.length != 32)
            throw new IllegalArgumentException("Ed25519 private key seed must be 32 bytes");

        try
        {
            // Supply the known seed to the JDK keygen via a deterministic SecureRandom.
            // The JDK Ed25519 KeyPairGenerator calls nextBytes(byte[32]) exactly once to
            // obtain the private key seed, then derives the public key from it internally.
            final byte[] seedCopy = Arrays.copyOf(rootPrivKey, 32);

            final SecureRandom deterministicRandom = new SecureRandom()
            {
                @Override
                public void nextBytes(final byte[] bytes)
                {
                    final int len = Math.min(bytes.length, seedCopy.length);
                    System.arraycopy(seedCopy, 0, bytes, 0, len);
                    // zero remaining bytes if requested length > 32 (should not happen)
                    if (bytes.length > len)
                        Arrays.fill(bytes, len, bytes.length, (byte) 0);
                }
            };

            final KeyPairGenerator kpg = KeyPairGenerator.getInstance("Ed25519");
            kpg.initialize(NamedParameterSpec.ED25519, deterministicRandom);

            final KeyPair kp        = kpg.generateKeyPair();
            final byte[]  privBytes = KeyOps.privateKeyBytes(kp.getPrivate());
            final byte[]  pubBytes  = KeyOps.publicKeyBytes(kp.getPublic());

            if (!Arrays.equals(privBytes, rootPrivKey))
                throw new IllegalStateException(
                    "Ed25519 private key seed did not round-trip — JDK provider behaviour changed");

            final String id = deriveId(pubBytes, version);
            return new AccountKeys(privBytes, pubBytes, id);
        }
        catch (final DomatarException e)
        {
            throw new IllegalArgumentException(e.getMessage(), e);
        }
        catch (final NoSuchAlgorithmException e)
        {
            throw new IllegalStateException("Ed25519 not available (requires JDK 15+)", e);
        }
        catch (final java.security.InvalidAlgorithmParameterException e)
        {
            throw new IllegalStateException("Failed to initialise Ed25519 KeyPairGenerator", e);
        }
    }

    /**
     * Computes the v1 fingerprint actId from the raw 32-byte public key.
     * Delegates to {@link #deriveId(byte[], int)} with {@link #FP_VERSION_V1}.
     */
    public static String deriveActId(final byte[] rootPubKey)
    {
        try
        {
            return deriveId(rootPubKey, FP_VERSION_V1);
        }
        catch (final DomatarException e)
        {
            throw new IllegalArgumentException(e.getMessage(), e);
        }
    }

    /**
     * Same fingerprint as {@link #deriveActId(byte[])}; named for ownId call
     * sites (Spec-OwnIds.txt PART 2.1).
     */
    public static String deriveOwnId(final byte[] ownPubKey)
    {
        return deriveActId(ownPubKey);
    }

    /**
     * Returns a defensive copy of the raw private key seed, suitable for passing
     * to {@link MasterKey#seal(byte[])}.
     */
    public byte[] privateKeyCopy()
    {
        return Arrays.copyOf(rootPrivKey, rootPrivKey.length);
    }
}
