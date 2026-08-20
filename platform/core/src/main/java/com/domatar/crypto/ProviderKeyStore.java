/*
 * Copyright (c) 2024 Domatar
 */

package com.domatar.crypto;

import com.domatar.core.DomatarConfig;
import com.domatar.util.Base64Encoder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.logging.Logger;

/**
 * Loads and holds THIS provider's operational Ed25519 key pair, per
 * Spec-Security.txt PART 5.2.  One key pair per provider (not per account).
 *
 * The operational key pair is used to sign outbound origin messages on behalf
 * of all accounts resident on this provider; verifiers obtain the public key
 * via the signed hst directory record (Phase 3+).
 *
 * Persistence:
 *   The 32-byte private key seed is stored as a Base64Encoder-encoded string in
 *   a file named {@code provider.opkey} next to {@code provider.config.txt}
 *   (or at the path given by {@code DOMATAR_PROVIDER_KEY_PATH} /
 *   {@code ProviderKeyPath}).  On first call {@link #getOrCreate()} generates a
 *   fresh random key and writes this file.
 *
 * This class is NOT wired into the live message path in Phase 1; it is
 * testable stand-alone.
 */
public class ProviderKeyStore
{
    private static final Logger LOG = Logger.getLogger(ProviderKeyStore.class.getName());

    private static volatile KeyPair cached;

    private ProviderKeyStore() {}

    /**
     * Returns the provider operational key pair, loading or generating it as needed.
     * Thread-safe (double-checked locking).
     */
    public static KeyPair getOrCreate()
    {
        if (cached != null)
            return cached;

        synchronized (ProviderKeyStore.class)
        {
            if (cached != null)
                return cached;

            cached = loadOrGenerate();
        }

        return cached;
    }

    /**
     * Returns the raw 32-byte public key of this provider's operational key pair.
     */
    public static byte[] publicKeyBytes()
    {
        return KeyOps.publicKeyBytes(getOrCreate().getPublic());
    }

    /**
     * Signs {@code message} with this provider's operational private key.
     *
     * @return 64-byte Ed25519 signature
     */
    public static byte[] sign(final byte[] message)
    {
        return KeyOps.sign(getOrCreate().getPrivate(), message);
    }

    /**
     * Clears the in-memory cached key pair (for testing only).
     */
    static void resetForTesting()
    {
        synchronized (ProviderKeyStore.class)
        {
            cached = null;
        }
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private static KeyPair loadOrGenerate()
    {
        final String keyPath = resolveKeyPath();

        if (keyPath != null)
        {
            final Path path = Paths.get(keyPath);

            if (Files.exists(path))
            {
                final KeyPair loaded = tryLoad(path);

                if (loaded != null)
                    return loaded;
            }

            LOG.info("Provider operational key not found at " + path + " — generating new key");
            final KeyPair fresh = KeyOps.generateKeyPair();
            persist(path, fresh);
            return fresh;
        }

        LOG.warning("DOMATAR_PROVIDER_KEY_PATH / ProviderKeyPath not configured — "
            + "generating an ephemeral operational key (NOT persisted). "
            + "Set ProviderKeyPath in provider.config.txt for production use.");
        return KeyOps.generateKeyPair();
    }

    private static KeyPair tryLoad(final Path path)
    {
        try
        {
            final String encoded = Files.readString(path, StandardCharsets.UTF_8).trim();
            final byte[] privBytes = Base64Encoder.decode(encoded);

            if (privBytes.length != 32)
            {
                LOG.warning("provider.opkey at " + path
                    + " does not decode to 32 bytes — ignoring and regenerating");
                return null;
            }

            final PrivateKey priv    = KeyOps.privateKeyFromBytes(privBytes);
            final AccountKeys keys   = AccountKeys.fromPrivKey(privBytes);
            final PublicKey   pub    = KeyOps.publicKeyFromBytes(keys.rootPubKey);
            return new java.security.KeyPair(pub, priv);
        }
        catch (final IOException e)
        {
            LOG.warning("Could not read provider.opkey at " + path + ": " + e.getMessage());
            return null;
        }
    }

    private static void persist(final Path path, final KeyPair kp)
    {
        try
        {
            final byte[] privBytes = KeyOps.privateKeyBytes(kp.getPrivate());
            final String encoded   = Base64Encoder.encode(privBytes) + System.lineSeparator();
            Files.createDirectories(path.getParent());
            Files.writeString(path, encoded,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            LOG.info("Provider operational key written to " + path);
        }
        catch (final IOException e)
        {
            LOG.warning("Could not persist provider.opkey to " + path
                + " — key is ephemeral this session: " + e.getMessage());
        }
    }

    private static String resolveKeyPath()
    {
        return DomatarConfig.getProviderKeyPath();
    }
}
