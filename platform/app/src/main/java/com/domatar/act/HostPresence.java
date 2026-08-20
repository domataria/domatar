/*
 * Copyright (c) 2024 Domatar
 */

package com.domatar.act;

/**
 * Same-package facade for marketplace / HostProvision callers. Implementation
 * lives in {@link com.domatar.install.HostPresence} (domatar-core) so login
 * AccountsWui can call {@code hasPortableAppHosts} without depending on
 * domatar-app.
 */
public final class HostPresence
{
  private HostPresence() {}

  public static boolean hasPeer(final String actId, final String targetPrvId)
  {
    return com.domatar.install.HostPresence.hasPeer(actId, targetPrvId);
  }

  public static boolean hasPortableAppHosts(final String actId, final String prvId)
  {
    return com.domatar.install.HostPresence.hasPortableAppHosts(actId, prvId);
  }
}
