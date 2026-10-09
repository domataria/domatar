/*
 * Copyright (c) 2024 Domatar
 */

package com.travel.objimpl;

import com.domatar.util.DomId;
import com.domatar.util.DomatarException;

/**
 * Addresses for the Travel app node, the trips container, and the
 * unlinked search cache.
 */
public final class TravelIds
{
  public static final String HOME  = "travel";
  public static final String APP   = "app-travel";
  public static final String TRIPS = "trips";
  public static final String CACHE = "cache";

  private TravelIds()
  {
  }

  public static DomId subHost(final String actId, final String objId)
      throws DomatarException
  {
    return new DomId(DomId.subHstId(HOME, actId), HOME, actId, objId);
  }
}
