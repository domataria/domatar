/*
 * Copyright (c) 2024 Domatar
 */

package com.hecto.objimpl;

import com.domatar.install.HomeHostInstall;
import com.domatar.util.DomId;
import com.domatar.util.DomatarException;

/**
 * Desk and booking addresses. The desk is owned by the Hecto home
 * user, so a priced call pays that account.
 */
public final class HectoIds
{
  public static final long COST = 25L;
  public static final long SEED = 100L;
  public static final String HOME = "hecto";
  public static final String DESK = "desk";
  public static final String BOOKING = "booking";

  private HectoIds()
  {
  }

  public static DomId desk() throws DomatarException
  {
    final String actId = HomeHostInstall.actId(HOME);

    if (actId == null || actId.isEmpty())
      return null;
    return new DomId(HOME, HOME, actId, DESK);
  }

  public static DomId subHost(final String actId, final String objId)
      throws DomatarException
  {
    return new DomId(DomId.subHstId(HOME, actId), HOME, actId, objId);
  }

  public static DomId booking(final String actId) throws DomatarException
  {
    return subHost(actId, BOOKING);
  }
}
