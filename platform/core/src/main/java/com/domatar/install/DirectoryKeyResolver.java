/*
 * Copyright (c) 2024 Domatar
 */

package com.domatar.install;

import java.security.PublicKey;

import com.domatar.crypto.KeyOps;
import com.domatar.crypto.ProviderKeyResolver;
import com.domatar.db.HstDb;
import com.domatar.util.Base64Encoder;
import com.domatar.util.Hst;

/**
 * Directory-backed {@link ProviderKeyResolver} (KD13). Cache-first via
 * {@link HstDb}; on a missing pubKey, fetches from the global directory
 * and caches. Returns null on any failure.
 */
public final class DirectoryKeyResolver implements ProviderKeyResolver
{
  public static final DirectoryKeyResolver INSTANCE = new DirectoryKeyResolver();

  private DirectoryKeyResolver() {}

  @Override
  public PublicKey resolve(final String prvId)
  {
    try
    {
      Hst hst = HstDb.getHst(prvId);

      if (hst == null || hst.pubKey == null)
        hst = DirectoryLookup.fetchAndCache(prvId);

      return decode(hst);
    }
    catch (final Exception e)
    {
      return null;
    }
  }

  /**
   * Forces a directory fetch (the one retry-after-rotation case
   * Msg used to do inline).
   */
  public PublicKey refresh(final String prvId)
  {
    try
    {
      return decode(DirectoryLookup.fetchAndCache(prvId));
    }
    catch (final Exception e)
    {
      return null;
    }
  }

  private static PublicKey decode(final Hst hst)
  {
    if (hst == null || hst.pubKey == null)
      return null;

    try
    {
      return KeyOps.publicKeyFromBytes(Base64Encoder.decode(hst.pubKey));
    }
    catch (final Exception e)
    {
      return null;
    }
  }
}
