/*
 * Copyright (c) 2024 Domatar
 */

package com.domatar.act;

import com.domatar.app.AppRegistry;
import com.domatar.core.DomatarConfig;
import com.domatar.db.HstDb;
import com.domatar.install.UserSubstrateIds;
import com.domatar.util.DomatarException;
import com.domatar.util.Hst;

/**
 * Identity {@code AppId} rules for Create account / {@code AddAct}.
 *
 * <p>A new account's usrId is {@code <handle>@<appId>}. The suffix must be a
 * loaded <em>content</em> app this provider offers. Stock shells
 * ({@code login}, {@code desktop}, {@code navigator}, {@code appstore}) and
 * the platform app ({@code domatar}) are not valid identity apps. There is
 * no default AppId — missing or invalid values fail the request.
 */
public final class SignupAppId
{
  public static final String PLATFORM_APP_ID = "domatar";

  private SignupAppId() {}

  public static boolean isShellAppId(final String appId)
  {
    if (appId == null || appId.isEmpty())
      return false;

    for (final String roleId : UserSubstrateIds.ROLE_IDS)
    {
      if (roleId.equals(appId))
        return true;
    }
    return false;
  }

  public static boolean isForbiddenIdentityAppId(final String appId)
  {
    return isShellAppId(appId) || PLATFORM_APP_ID.equals(appId);
  }

  /**
   * Create-account check on this node: required, not a shell/platform id,
   * loaded in {@link AppRegistry}, and listed in this provider's offered
   * hosts when that list is set.
   *
   * @return error text, or {@code null} if the appId is acceptable
   */
  public static String rejectSignup(final String appId)
  {
    if (appId == null || appId.isBlank())
      return "AppId is required";

    final String id = appId.trim();

    if (isForbiddenIdentityAppId(id))
      return "AppId \"" + id + "\" is not a valid identity app "
          + "(shell and platform ids are not allowed)";

    if (AppRegistry.get(id) == null)
      return "Unknown AppId \"" + id + "\"";

    if (!isOfferedOnThisProvider(id))
      return "AppId \"" + id + "\" is not offered on this provider";

    return null;
  }

  /**
   * {@code AddAct} on an app central host: the destination appId must be a
   * loaded content app. Offered-hosts is an {@code ActWui} / signup-edge
   * concern so link-mode to a remote app still works.
   *
   * @return error text, or {@code null} if the appId is acceptable
   */
  public static String rejectIdentity(final String appId)
  {
    if (appId == null || appId.isBlank())
      return "AppId is required";

    final String id = appId.trim();

    if (isForbiddenIdentityAppId(id))
      return "AppId \"" + id + "\" is not a valid identity app "
          + "(shell and platform ids are not allowed)";

    if (AppRegistry.get(id) == null)
      return "Unknown AppId \"" + id + "\"";

    return null;
  }

  /**
   * True when this identity app's central host is owned by the local
   * provider ({@code hst.PrvId}). Env {@code DOMATAR_OFFERED_HOSTS} is
   * consulted first when set; otherwise the directory {@code hst} row.
   * Missing env must not mean "allow every loaded app".
   */
  static boolean isOfferedOnThisProvider(final String appId)
  {
    final String csv = DomatarConfig.getOfferedHosts();

    if (csv != null && !csv.isBlank())
    {
      for (final String raw : csv.split(","))
      {
        if (appId.equals(raw.trim()))
          return true;
      }
      return false;
    }

    try
    {
      final Hst hst = HstDb.getHst(appId);
      if (hst == null || hst.prvId == null || hst.prvId.isEmpty())
        return false;
      final String localPrv = DomatarConfig.getPrvId();
      return hst.prvId.equals(localPrv);
    }
    catch (final DomatarException e)
    {
      return false;
    }
  }
}
