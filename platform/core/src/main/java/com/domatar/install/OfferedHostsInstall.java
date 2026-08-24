/*
 * Copyright (c) 2024 Domatar
 */

package com.domatar.install;

import com.domatar.core.DomatarConfig;
import com.domatar.db.HstDb;
import com.domatar.util.DomatarException;

/**
 * Registers this node's offered home-host names in {@code hst}
 * ({@code DOMATAR_OFFERED_HOSTS} / {@code OfferedHosts}) and ensures
 * each offered app's home user {@code <appId>@<appId>}
 * ([Domatar](docs/spec/Domatar.md) PART 4.1.1).
 *
 * Login and signup address {@code usrId@appId} by looking up host
 * {@code appId} (e.g. {@code quippin}). Without those rows the wire
 * returns "Hst not found".
 */
public final class OfferedHostsInstall
{
  private OfferedHostsInstall() {}

  public static void ensure() throws DomatarException
  {
    final String csv = DomatarConfig.getOfferedHosts();

    if (csv == null || csv.isBlank())
      return;

    final String domain = DomatarConfig.getDomain();
    final String prvId  = DomatarConfig.getPrvId();

    if (domain == null || domain.isEmpty())
      return;

    for (final String raw : csv.split(","))
    {
      final String hstId = raw.trim();

      if (hstId.isEmpty())
        continue;

      HstDb.updateHst(hstId, domain, prvId);

      try
      {
        HomeHostInstall.ensureHomeUser(hstId, domain, prvId);
      }
      catch (final DomatarException e)
      {
        System.out.println("WARN: HomeHostInstall.ensureHomeUser failed for "
            + hstId + ": " + e);
      }
    }
  }
}
