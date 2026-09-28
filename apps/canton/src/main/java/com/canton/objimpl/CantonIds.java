/*
 * Copyright (c) 2024 Domatar
 */

package com.canton.objimpl;

import com.domatar.install.HomeHostInstall;
import com.domatar.util.DomId;
import com.domatar.util.DomatarException;

/**
 * Desk and booking addresses. The desk is owned by the Canton home
 * user, so a priced call draws from the caller's credits with that
 * account rather than with themselves.
 */
public final class CantonIds
{
  public static final long COST = 25L;
  public static final long SEED = 100L;
  public static final String HOME = "canton";
  public static final String DESK = "desk";
  public static final String BOOKING = "booking";

  private CantonIds()
  {
  }

  public static DomId desk() throws DomatarException
  {
    final String actId = HomeHostInstall.actId(HOME);

    if (actId == null || actId.isEmpty())
      return null;
    return new DomId(HOME, HOME, actId, DESK);
  }

  public static DomId booking(final String actId) throws DomatarException
  {
    return HandleSync.subHost(actId, BOOKING);
  }
}
