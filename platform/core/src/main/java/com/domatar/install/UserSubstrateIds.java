/*
 * Copyright (c) 2024 Domatar
 */

package com.domatar.install;

import com.domatar.util.DomId;
import com.domatar.util.DomatarException;

/**
 * Ids for the per-login-provider user domatar substrate host
 * {@code domatar-&lt;actId&gt;-&lt;prvId&gt;} (Spec-Mandatory-App-Rewrite.txt;
 * Update-Mandatory-App-Rewrite.txt Phase 1 / KD1–KD3).
 */
public final class UserSubstrateIds
{
  public static final String[] ROLE_IDS =
      { "login", "desktop", "navigator", "appstore" };

  private UserSubstrateIds() {}

  /**
   * Intended qualified host id. Create with {@link DomId#subHstId(String,String,String)}
   * before {@link DomId#localSubHstId} can resolve it.
   */
  public static String hstId(final String actId, final String prvId)
  {
    return DomId.subHstId("domatar", actId, prvId);
  }

  public static DomId appDomatar(final String actId, final String prvId)
      throws DomatarException
  {
    return new DomId(hstId(actId, prvId), "domatar", actId, "app-domatar");
  }

  public static DomId userApps(final String actId, final String prvId)
      throws DomatarException
  {
    return new DomId(hstId(actId, prvId), "domatar", actId, "userApps");
  }

  public static DomId shells(final String actId, final String prvId)
      throws DomatarException
  {
    return new DomId(hstId(actId, prvId), "domatar", actId, "shells");
  }

  public static DomId membership(final String actId, final String prvId)
      throws DomatarException
  {
    return new DomId(hstId(actId, prvId), "domatar", actId, "membership");
  }

  public static DomId binding(final String actId, final String prvId)
      throws DomatarException
  {
    return new DomId(hstId(actId, prvId), "domatar", actId, "binding");
  }

  public static DomId userAppRow(final String actId, final String prvId,
                                 final String appId) throws DomatarException
  {
    return new DomId(hstId(actId, prvId), "domatar", actId, "app-" + appId);
  }

  public static DomId shellRow(final String actId, final String prvId,
                               final String loginPrvId, final String roleId)
      throws DomatarException
  {
    return new DomId(hstId(actId, prvId), "domatar", actId,
        "shell-" + loginPrvId + "-" + roleId);
  }
}
