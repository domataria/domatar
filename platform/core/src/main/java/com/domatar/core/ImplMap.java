package com.domatar.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.domatar.util.ObjImpl;
import com.domatar.util.DomatarInterface;

/**
 * Registry mapping (clsAppId, clsId) pairs to their handler instances.
 *
 * In Option D a single WAR hosts all apps.  AppLoader populates this map
 * during ServletContext initialisation by reading each app's manifest.
 * Unregistered classes fall back to ObjImpl, which handles the standard
 * GetObj / Open operations.
 */
public class ImplMap
{
  private static final HashMap<String, DomatarInterface> implMap     = new HashMap<>();
  private static final DomatarInterface                  defaultImpl = new ObjImpl();

  /**
   * Registers a handler for the given (clsAppId, clsId) pair.
   * A second call for the same key overwrites the previous entry.
   */
  public static synchronized void register(final String clsAppId,
                                           final String clsId,
                                           final DomatarInterface impl)
  {
    implMap.put(key(clsAppId, clsId), impl);
  }

  /**
   * Returns the handler for (clsAppId, clsId), or ObjImpl if none registered.
   */
  public static synchronized DomatarInterface get(final String clsAppId, final String clsId)
  {
    final DomatarInterface impl = implMap.get(key(clsAppId, clsId));
    return (impl != null) ? impl : defaultImpl;
  }

  /**
   * Returns sorted "&lt;clsAppId&gt;.&lt;clsId&gt; -> &lt;handler-class-name&gt;" lines.
   * Used for startup logging and tests.
   */
  public static synchronized List<String> describe()
  {
    final List<String> lines = new ArrayList<>();

    for (final Map.Entry<String, DomatarInterface> e : implMap.entrySet())
      lines.add(e.getKey() + " -> " + e.getValue().getClass().getName());

    Collections.sort(lines);
    return lines;
  }

  /** Removes all registrations.  For use in tests only. */
  public static synchronized void clear()
  {
    implMap.clear();
  }

  private static String key(final String clsAppId, final String clsId)
  {
    return clsAppId + "." + clsId;
  }
}
