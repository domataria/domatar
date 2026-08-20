/*
 * Copyright (c) 2024 Domatar
 */

package com.domatar.core;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Global in-memory cache of resolved class-definition documents (Spec-Service.txt PART 11).
 *
 * Key: "clsAppId.clsId" → the JSON string that GetCls returns under "Attrs".
 * Global keying is correct because services are immutable and class descriptors
 * are user-independent; the resolved interface is the same for every account.
 *
 * All public methods are thread-safe via a synchronized backing map.
 * Invalidation points:
 *   - ClsInstall.upsertClsObj: invalidate(clsAppId, clsId)
 *   - SrvInstall.addSrvObj:    invalidateAll()
 *   - AppLoader.contextInitialized: invalidateAll() on every redeploy
 */
public class ClsMap
{
  private static final Map<String, String> map =
      Collections.synchronizedMap(new HashMap<>());

  public static String get(final String clsAppId, final String clsId)
  {
    return map.get(key(clsAppId, clsId));
  }

  public static void put(final String clsAppId, final String clsId, final String resolvedJson)
  {
    map.put(key(clsAppId, clsId), resolvedJson);
  }

  public static void invalidate(final String clsAppId, final String clsId)
  {
    map.remove(key(clsAppId, clsId));
  }

  public static void invalidateAll()
  {
    map.clear();
  }

  private static String key(final String clsAppId, final String clsId)
  {
    return clsAppId + "." + clsId;
  }
}
