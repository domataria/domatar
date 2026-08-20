/*
 * Copyright (c) 2024 Domatar
 */

package com.domatar.crypto;

import java.security.InvalidKeyException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.SignatureException;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;

/**
 * Low-level Ed25519 and SHA-256 helpers over the JDK.
 *
 * All JCA interaction is confined to this class; no other class in the crypto
 * package touches java.security directly.
 *
 * Ed25519 is available from JDK 15. This project targets Java 17 (parent pom).
 *
 * Key encoding conventions:
 *   publicKeyBytes  — raw 32-byte compressed point (stripped from the 44-byte
 *                     X.509 SubjectPublicKeyInfo DER envelope).
 *   privateKeyBytes — raw 32-byte seed (stripped from the 48-byte PKCS#8 DER
 *                     envelope).
 */
public class KeyOps
{
    // DER headers for Ed25519 X.509 (public) and PKCS#8 (private) encodings.
    // These are fixed for the Ed25519 OID 1.3.101.112.
    private static final byte[] PUB_KEY_HEADER = {
        0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65,
        0x70, 0x03, 0x21, 0x00
    };
    private static final byte[] PRIV_KEY_HEADER = {
        0x30, 0x2e, 0x02, 0x01, 0x00, 0x30, 0x05, 0x06,
        0x03, 0x2b, 0x65, 0x70, 0x04, 0x22, 0x04, 0x20
    };

    private KeyOps() {}

    /**
     * Generates a fresh Ed25519 key pair using the platform default SecureRandom.
     */
    public static KeyPair generateKeyPair()
    {
        try
        {
            final KeyPairGenerator kpg = KeyPairGenerator.getInstance("Ed25519");
            return kpg.generateKeyPair();
        }
        catch (final NoSuchAlgorithmException e)
        {
            throw new IllegalStateException("Ed25519 not available (requires JDK 15+)", e);
        }
    }

    /**
     * Extracts the raw 32-byte compressed point from a PublicKey.
     */
    public static byte[] publicKeyBytes(final PublicKey key)
    {
        final byte[] encoded = key.getEncoded();

        if (encoded.length != PUB_KEY_HEADER.length + 32)
            throw new IllegalArgumentException(
                    "Unexpected Ed25519 public key encoding length: " + encoded.length);

        return Arrays.copyOfRange(encoded, PUB_KEY_HEADER.length, encoded.length);
    }

    /**
     * Extracts the raw 32-byte seed from a PrivateKey.
     */
    public static byte[] privateKeyBytes(final PrivateKey key)
    {
        final byte[] encoded = key.getEncoded();

        if (encoded.length != PRIV_KEY_HEADER.length + 32)
            throw new IllegalArgumentException(
                    "Unexpected Ed25519 private key encoding length: " + encoded.length);

        return Arrays.copyOfRange(encoded, PRIV_KEY_HEADER.length, encoded.length);
    }

    /**
     * Reconstructs a PublicKey from a raw 32-byte compressed point.
     */
    public static PublicKey publicKeyFromBytes(final byte[] rawBytes)
    {
        if (rawBytes.length != 32)
            throw new IllegalArgumentException("Ed25519 public key must be 32 bytes");

        final byte[] encoded = new byte[PUB_KEY_HEADER.length + 32];
        System.arraycopy(PUB_KEY_HEADER, 0, encoded, 0, PUB_KEY_HEADER.length);
        System.arraycopy(rawBytes, 0, encoded, PUB_KEY_HEADER.length, 32);

        try
        {
            return KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(encoded));
        }
        catch (final NoSuchAlgorithmException | InvalidKeySpecException e)
        {
            throw new IllegalStateException("Failed to reconstruct Ed25519 public key", e);
        }
    }

    /**
     * Reconstructs a PrivateKey from a raw 32-byte seed.
     */
    public static PrivateKey privateKeyFromBytes(final byte[] rawBytes)
    {
        if (rawBytes.length != 32)
            throw new IllegalArgumentException("Ed25519 private key seed must be 32 bytes");

        final byte[] encoded = new byte[PRIV_KEY_HEADER.length + 32];
        System.arraycopy(PRIV_KEY_HEADER, 0, encoded, 0, PRIV_KEY_HEADER.length);
        System.arraycopy(rawBytes, 0, encoded, PRIV_KEY_HEADER.length, 32);

        try
        {
            return KeyFactory.getInstance("Ed25519").generatePrivate(new PKCS8EncodedKeySpec(encoded));
        }
        catch (final NoSuchAlgorithmException | InvalidKeySpecException e)
        {
            throw new IllegalStateException("Failed to reconstruct Ed25519 private key", e);
        }
    }

    /**
     * Signs {@code message} with {@code key} and returns the 64-byte signature.
     */
    public static byte[] sign(final PrivateKey key, final byte[] message)
    {
        try
        {
            final Signature sig = Signature.getInstance("Ed25519");
            sig.initSign(key);
            sig.update(message);
            return sig.sign();
        }
        catch (final NoSuchAlgorithmException | InvalidKeyException | SignatureException e)
        {
            throw new IllegalStateException("Ed25519 sign failed", e);
        }
    }

    /**
     * Returns {@code true} iff {@code sig} is a valid Ed25519 signature of
     * {@code message} under {@code key}.
     */
    public static boolean verify(final PublicKey key, final byte[] message, final byte[] sig)
    {
        try
        {
            final Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(key);
            verifier.update(message);
            return verifier.verify(sig);
        }
        catch (final NoSuchAlgorithmException | InvalidKeyException e)
        {
            throw new IllegalStateException("Ed25519 verify failed", e);
        }
        catch (final SignatureException e)
        {
            return false;
        }
    }

    /**
     * Returns the 32-byte SHA-256 hash of {@code input}.
     */
    public static byte[] sha256(final byte[] input)
    {
        try
        {
            return MessageDigest.getInstance("SHA-256").digest(input);
        }
        catch (final NoSuchAlgorithmException e)
        {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
