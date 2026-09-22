package com.domatar.saga;

import com.domatar.util.Json;
import com.domatar.util.JsonHashMap;
import com.domatar.util.JsonMap;

/**
 * Slot {@code saga} JSON on {@code op_dst}. Platform owns the shape;
 * Body keys besides status / compensates / effect are preserved.
 */
public final class SagaSlot
{
  public static final String SLOT = "saga";

  public static final String STATUS_APPLIED = "applied";
  public static final String STATUS_COMPENSATING = "compensating";
  public static final String STATUS_COMPENSATED = "compensated";
  public static final String STATUS_IRREVERSIBLE = "irreversible";
  public static final String STATUS_FAILED = "failed";

  private SagaSlot() {}

  public static JsonMap parse(final Object attachment)
  {
    if (attachment == null)
      return null;

    if (attachment instanceof JsonMap)
    {
      final JsonMap map = (JsonMap) attachment;
      return map.isEmpty() ? null : map;
    }

    if (attachment instanceof String)
    {
      final String s = ((String) attachment).trim();
      if (s.isEmpty())
        return null;
      try
      {
        final JsonMap map = Json.parseMap(s);
        return map == null || map.isEmpty() ? null : map;
      }
      catch (final Exception e)
      {
        return null;
      }
    }

    return null;
  }

  public static String status(final JsonMap slot)
  {
    if (slot == null)
      return null;
    final String s = slot.getString("status");
    return s == null || s.isEmpty() ? null : s;
  }

  public static JsonMap applied(final String compensates, final Object effect)
  {
    final JsonHashMap out = new JsonHashMap();
    out.put("status", STATUS_APPLIED);
    if (compensates != null && !compensates.isEmpty())
      out.put("compensates", compensates);
    if (effect != null)
      out.put("effect", effect);
    return out;
  }

  public static JsonMap withStatus(final JsonMap prior, final String status)
  {
    final JsonHashMap out = new JsonHashMap();
    if (prior != null)
    {
      for (final String key : prior.keySet())
        out.put(key, prior.get(key));
    }
    out.put("status", status);
    return out;
  }

  public static boolean isDone(final String status)
  {
    return STATUS_COMPENSATED.equals(status)
        || STATUS_IRREVERSIBLE.equals(status);
  }
}
