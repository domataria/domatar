/*
 * Copyright (c) 2024 Domatar
 */

package com.domatar.crypto;

import com.domatar.core.DomatarConfig;
import com.domatar.util.Base64Encoder;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;
import java.util.logging.Logger;

/**
 * Encrypt-at-rest wrapper for private key bytes stored in the database,
 * per Spec-Security.txt PART 5.4.
 *
 * Sealed format:
 *   "enc:" prefix  — AES-256-GCM.
 *                    The value after the prefix is Base64Encoder.encode(iv || ciphertext)
 *                    where iv = 12 random bytes and ciphertext includes the 16-byte
 *                    GCM authentication tag appended by the JCA Cipher.
 *
 *   "raw:" prefix  — pass-through (no encryption).  Used only in development
 *                    environments where DOMATAR_MASTER_KEY / MasterKey is absent.
 *                    The value after the prefix is Base64Encoder.encode(plaintext).
 *                    A WARNING is logged every time seal() operates in this mode.
 *
 * The master key is configured via:
 *   Environment var:  DOMATAR_MASTER_KEY
 *   provider.config.txt key: MasterKey
 * Value: Base64Encoder-encoded 32-byte (256-bit) AES key.
 *
 * IMPORTANT: running in pass-through mode stores private keys UNENCRYPTED (only
 * Base64-encoded) in the database.  This is intentional for local development and
 * MUST NOT be used in production.  Check your deployment's DomatarConfig to
 * ensure MasterKey is set before launching a production instance.
 */
public class MasterKey
{
    private static final Logger LOG = Logger.getLogger(MasterKey.class.getName());

    private static final String ALGORITHM   = "AES/GCM/NoPadding";
    private static final int    IV_BYTES    = 12;
    private static final int    TAG_BITS    = 128;
    private static final String ENC_PREFIX  = "enc:";
    private static final String RAW_PREFIX  = "raw:";

    private MasterKey() {}

    /**
     * Encrypts (seals) the given plaintext bytes using the provider master key.
     *
     * @param plaintextKey the raw key bytes to protect (e.g. a 32-byte Ed25519 seed)
     * @return a string suitable for storing in a database column
     * @throws IllegalStateException if the JCA cipher is unavailable
     */
    public static String seal(final byte[] plaintextKey)
    {
        final byte[] masterKeyBytes = loadMasterKey();

        if (masterKeyBytes == null)
        {
            LOG.warning("DOMATAR_MASTER_KEY is not set — storing private key UNENCRYPTED. "
                + "This is only safe in a development environment.");
            return RAW_PREFIX + Base64Encoder.encode(plaintextKey);
        }

        try
        {
            final byte[]          iv        = new byte[IV_BYTES];
            new SecureRandom().nextBytes(iv);

            final Cipher          cipher    = Cipher.getInstance(ALGORITHM);
            final SecretKeySpec   keySpec   = new SecretKeySpec(masterKeyBytes, "AES");
            final GCMParameterSpec paramSpec = new GCMParameterSpec(TAG_BITS, iv);

            cipher.init(Cipher.ENCRYPT_MODE, keySpec, paramSpec);
            final byte[] ciphertext = cipher.doFinal(plaintextKey);

            final byte[] payload = new byte[IV_BYTES + ciphertext.length];
            System.arraycopy(iv,         0, payload, 0,        IV_BYTES);
            System.arraycopy(ciphertext, 0, payload, IV_BYTES, ciphertext.length);

            return ENC_PREFIX + Base64Encoder.encode(payload);
        }
        catch (final Exception e)
        {
            throw new IllegalStateException("MasterKey.seal failed", e);
        }
    }

    /**
     * Decrypts (opens) a sealed value produced by {@link #seal(byte[])}.
     *
     * @param sealedBase64 the value as stored in the database
     * @return the original plaintext bytes
     * @throws IllegalArgumentException if the format is unrecognised
     * @throws IllegalStateException if decryption fails (wrong key, corrupt data)
     */
    public static byte[] open(final String sealedBase64)
    {
        if (sealedBase64 == null || sealedBase64.isEmpty())
            throw new IllegalArgumentException("sealedBase64 must not be null or empty");

        if (sealedBase64.startsWith(RAW_PREFIX))
            return Base64Encoder.decode(sealedBase64.substring(RAW_PREFIX.length()));

        if (!sealedBase64.startsWith(ENC_PREFIX))
            throw new IllegalArgumentException(
                "Unrecognised sealed format (expected 'enc:' or 'raw:' prefix)");

        final byte[] masterKeyBytes = loadMasterKey();

        if (masterKeyBytes == null)
            throw new IllegalStateException(
                "Cannot open encrypted sealed value: DOMATAR_MASTER_KEY is not set");

        try
        {
            final byte[] payload    = Base64Encoder.decode(sealedBase64.substring(ENC_PREFIX.length()));
            final byte[] iv         = new byte[IV_BYTES];
            System.arraycopy(payload, 0, iv, 0, IV_BYTES);
            final byte[] ciphertext = new byte[payload.length - IV_BYTES];
            System.arraycopy(payload, IV_BYTES, ciphertext, 0, ciphertext.length);

            final Cipher         cipher    = Cipher.getInstance(ALGORITHM);
            final SecretKeySpec  keySpec   = new SecretKeySpec(masterKeyBytes, "AES");
            final GCMParameterSpec paramSpec = new GCMParameterSpec(TAG_BITS, iv);

            cipher.init(Cipher.DECRYPT_MODE, keySpec, paramSpec);
            return cipher.doFinal(ciphertext);
        }
        catch (final Exception e)
        {
            throw new IllegalStateException("MasterKey.open failed", e);
        }
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    /**
     * Returns the 32-byte AES master key from config, or {@code null} if absent.
     * The config value is a Base64Encoder-encoded 32-byte key.
     */
    private static byte[] loadMasterKey()
    {
        final String encoded = DomatarConfig.getMasterKey();

        if (encoded == null || encoded.isEmpty())
            return null;

        final byte[] decoded = Base64Encoder.decode(encoded);

        if (decoded.length != 32)
            throw new IllegalStateException(
                "MasterKey config value must decode to exactly 32 bytes (AES-256), got "
                    + decoded.length);

        return decoded;
    }
}
