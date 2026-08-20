/*
 * Copyright (c) 2024 Domatar
 */

package com.domatar.install;

import com.domatar.app.App;
import com.domatar.app.AppRegistry;
import com.domatar.util.DomId;

/**
 * Resolves per-user app sub-host ids for InstallUser (Spec-AppStore PART 9).
 *
 * <p>login / desktop / navigator / appstore keep provider-qualified replicas.
 * Ordinary apps use the portable {@code <appId>-<actId>} form unless the app's
 * {@link AppInstall#userHostIdExtension} returns a valid extra segment.
 */
public final class UserHostIds
{
  private UserHostIds() {}

  /**
   * Resolve the HstId for a per-user install of {@code appId}.
   * Asks {@link AppRegistry} for an optional extension when the app is loaded.
   */
  public static String resolve(final String appId,
                               final String actId,
                               final String usrId,
                               final String prvId)
  {
    if (appId == null || actId == null)
      return null;

    if (isReplicaShellApp(appId))
      return DomId.subHstId(appId, actId, prvId);

    String ext = null;

    try
    {
      final App app = AppRegistry.get(appId);

      if (app != null && app.installInstance != null)
        ext = app.installInstance.userHostIdExtension(actId, usrId, prvId);
    }
    catch (final RuntimeException ignored)
    {
      ext = null;
    }

    return resolveWithExtension(appId, actId, prvId, ext);
  }

  /**
   * Pure resolve used by unit tests: applies {@code ext} without AppRegistry.
   * Invalid extensions (null, blank, or containing {@link DomId#HOST_SEP}) are
   * ignored so the portable {@code <appId>-<actId>} form is used.
   */
  public static String resolveWithExtension(final String appId,
                                            final String actId,
                                            final String prvId,
                                            final String ext)
  {
    if (appId == null || actId == null)
      return null;

    if (isReplicaShellApp(appId))
      return DomId.subHstId(appId, actId, prvId);

    String use = ext;

    if (use != null)
    {
      use = use.trim();

      if (use.isEmpty() || use.indexOf(DomId.HOST_SEP) >= 0)
        use = null;
    }

    if (use == null)
      return DomId.subHstId(appId, actId);

    return DomId.subHstId(appId, actId, use);
  }

  /** Stock shell apps use {@code <appId>-<actId>-<prvId>} on each login home. */
  public static boolean isReplicaShellApp(final String appId)
  {
    return "desktop".equals(appId) || "navigator".equals(appId)
        || "login".equals(appId) || "appstore".equals(appId);
  }
}
