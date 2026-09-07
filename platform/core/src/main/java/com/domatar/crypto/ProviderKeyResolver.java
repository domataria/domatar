/*
 * Copyright (c) 2024 Domatar
 */

package com.domatar.crypto;

import java.security.PublicKey;

/**
 * Looks up a provider's operational public key by prvId (KD13).
 * Returns null for unknown. The production implementation is
 * {@code com.domatar.install.DirectoryKeyResolver}; crypto must not
 * import install.
 */
@FunctionalInterface
public interface ProviderKeyResolver
{
    PublicKey resolve(String prvId);
}
