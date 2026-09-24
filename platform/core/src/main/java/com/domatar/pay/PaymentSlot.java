package com.domatar.pay;

import com.domatar.util.Json;
import com.domatar.util.JsonHashMap;
import com.domatar.util.JsonMap;

/**
 * Slot {@code payment} JSON on {@code op_dst}. Platform owns the shape.
 */
public final class PaymentSlot
{
  public static final String SLOT = "payment";

  public static final String STATUS_DRAWN = "drawn";

  public static final String STATUS_REBATED = "rebated";

  public static final String UNIT = "credit";

  private PaymentSlot() {}

  public static JsonMap drawn(final long amount, final String payee, final String payer)
  {
    final JsonHashMap out = new JsonHashMap();
    out.put("status", STATUS_DRAWN);
    out.put("amount", Long.valueOf(amount));
    out.put("unit", UNIT);
    out.put("payee", payee);
    out.put("payer", payer);
    return out;
  }

  public static JsonMap rebated(final JsonMap prior)
  {
    final JsonHashMap out = new JsonHashMap();
    if (prior != null)
    {
      final Object amount = prior.get("amount");
      if (amount != null)
        out.put("amount", amount);
      final Object unit = prior.get("unit");
      if (unit != null)
        out.put("unit", unit);
      final Object payee = prior.get("payee");
      if (payee != null)
        out.put("payee", payee);
      final Object payer = prior.get("payer");
      if (payer != null)
        out.put("payer", payer);
    }
    out.put("status", STATUS_REBATED);
    return out;
  }

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

  /** Missing or non-numeric amount is -1. */
  public static long amount(final JsonMap slot)
  {
    if (slot == null)
      return -1L;
    final Object raw = slot.get("amount");
    if (!(raw instanceof Number))
      return -1L;
    return ((Number) raw).longValue();
  }

  public static String payee(final JsonMap slot)
  {
    return slot == null ? null : slot.getString("payee");
  }

  public static String payer(final JsonMap slot)
  {
    return slot == null ? null : slot.getString("payer");
  }
}
