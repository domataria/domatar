package com.domatar.saga;

import java.util.ArrayList;
import java.util.List;

import com.domatar.util.DomatarException;
import com.domatar.util.JsonArrayList;
import com.domatar.util.JsonList;
import com.domatar.util.JsonHashMap;
import com.domatar.util.JsonMap;
import com.domatar.util.JsonMsg;

/**
 * Tree the initiator holds after Compensate (Spec-Saga PART 6.1).
 */
public final class CompensateResult
{
  public static final String OUTCOME_COMPENSATED = "compensated";
  public static final String OUTCOME_NOOP = "noop";
  public static final String OUTCOME_DENIED = "denied";
  public static final String OUTCOME_FAILED = "failed";
  public static final String OUTCOME_IRREVERSIBLE = "irreversible";

  public String origContextId;
  public String dstDomId;
  public String origMsgName;
  public String outcome;
  public String reason;
  public List<CompensateResult> children;

  public CompensateResult(final String origContextId, final String dstDomId,
      final String origMsgName, final String outcome, final String reason,
      final List<CompensateResult> children)
  {
    this.origContextId = origContextId;
    this.dstDomId = dstDomId;
    this.origMsgName = origMsgName;
    this.outcome = outcome;
    this.reason = reason;
    this.children = children != null ? children : new ArrayList<>();
  }

  public static CompensateResult noop(final String ctx, final String dst,
      final String msg, final String reason)
  {
    return new CompensateResult(ctx, dst, msg, OUTCOME_NOOP, reason,
        new ArrayList<>());
  }

  public static CompensateResult denied(final String ctx, final String dst,
      final String msg, final String reason)
  {
    return new CompensateResult(ctx, dst, msg, OUTCOME_DENIED, reason,
        new ArrayList<>());
  }

  public static CompensateResult failed(final String ctx, final String dst,
      final String msg, final String reason)
  {
    return new CompensateResult(ctx, dst, msg, OUTCOME_FAILED, reason,
        new ArrayList<>());
  }

  public static CompensateResult irreversible(final String ctx, final String dst,
      final String msg, final String reason)
  {
    return new CompensateResult(ctx, dst, msg, OUTCOME_IRREVERSIBLE, reason,
        new ArrayList<>());
  }

  public JsonMap toMap()
  {
    final JsonHashMap map = new JsonHashMap();
    map.put("OrigContextId", origContextId);
    map.put("DstDomId", dstDomId);
    map.put("OrigMsgName", origMsgName);
    map.put("Outcome", outcome);
    map.put("Reason", reason != null ? reason : "");
    final JsonArrayList kids = new JsonArrayList();
    for (final CompensateResult child : children)
      kids.add(child.toMap());
    map.put("Children", kids);
    return map;
  }

  public static CompensateResult fromMsg(final JsonMsg msg)
      throws DomatarException
  {
    if (msg == null)
      return failed(null, null, null, "no msg");

    final JsonMap attrs = msg.getAttrs().toMap();
    final List<CompensateResult> kids = new ArrayList<>();
    final JsonList rawKids = attrs.getList("Children");
    if (rawKids != null)
    {
      for (final Object o : rawKids)
      {
        if (o instanceof JsonMap)
          kids.add(fromMap((JsonMap) o));
      }
    }
    return new CompensateResult(
        attrs.getString("OrigContextId"),
        attrs.getString("DstDomId"),
        attrs.getString("OrigMsgName"),
        attrs.getString("Outcome"),
        attrs.getString("Reason"),
        kids);
  }

  private static CompensateResult fromMap(final JsonMap map)
      throws DomatarException
  {
    final List<CompensateResult> kids = new ArrayList<>();
    final JsonList rawKids = map.getList("Children");
    if (rawKids != null)
    {
      for (final Object o : rawKids)
      {
        if (o instanceof JsonMap)
          kids.add(fromMap((JsonMap) o));
      }
    }
    return new CompensateResult(
        map.getString("OrigContextId"),
        map.getString("DstDomId"),
        map.getString("OrigMsgName"),
        map.getString("Outcome"),
        map.getString("Reason"),
        kids);
  }
}
