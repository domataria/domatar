/*
 * Copyright (c) 2024 Domatar
 */

package com.aiagent.agent;

import java.util.Map;

import com.domatar.util.Json;
import com.domatar.util.JsonArrayList;
import com.domatar.util.JsonHashMap;
import com.domatar.util.JsonList;
import com.domatar.util.JsonMap;

/**
 * LLM-facing projection of a tool result. The WUI keeps the full
 * ToolResult on the msg row; only the completion prompt is slimmed so
 * Groq Dev-tier TPM limits (70–80k on the small models) are not blown
 * by Party fingerprints and duplicate Weight* fields.
 */
final class ToolResultCompact
{
  private static final String[] CONTRACT_KEEP = {
      "Name", "Symbol", "Amount", "Nav", "AsOf", "Status",
      "Yield", "Rate", "Par", "Start", "End", "Currency",
      "ContractId", "TemplateId", "Constituents"
  };

  private static final int MAX_LIST = 40;

  private ToolResultCompact() {}

  static String forLlm(final String raw)
  {
    if (raw == null || raw.isEmpty())
      return "";
    try
    {
      return Json.toJson(slim(Json.parse(raw)));
    }
    catch (final Exception e)
    {
      return raw;
    }
  }

  static Object slim(final Object raw)
  {
    if (raw instanceof JsonMap)
    {
      final JsonMap map = (JsonMap) raw;
      if (map.get("Contracts") instanceof JsonList)
        return slimContracts(map);
      return slimMap(map);
    }
    if (raw instanceof JsonList)
      return slimList((JsonList) raw);
    if (raw instanceof String)
      return shortParty((String) raw);
    return raw;
  }

  private static JsonMap slimContracts(final JsonMap src)
  {
    final JsonList in = src.getList("Contracts");
    final JsonList out = new JsonArrayList();
    final int n = in.size();
    final int cap = Math.min(n, MAX_LIST);

    for (int i = 0; i < cap; i++)
    {
      final Object row = in.get(i);
      if (row instanceof JsonMap)
        out.add(slimContractRow((JsonMap) row));
    }

    final JsonMap result = new JsonHashMap();
    result.put("Count", Integer.toString(n));
    result.put("Contracts", out);
    if (n > cap)
      result.put("Omitted", Integer.toString(n - cap));
    return result;
  }

  private static JsonMap slimContractRow(final JsonMap src)
  {
    final JsonMap out = new JsonHashMap();
    for (final String key : CONTRACT_KEEP)
    {
      final Object v = src.get(key);
      if (v == null)
        continue;
      if (v instanceof String)
      {
        final String s = (String) v;
        if (s.isEmpty())
          continue;
        out.put(key, "TemplateId".equals(key) ? shortTemplate(s) : s);
      }
      else
        out.put(key, slim(v));
    }
    final Object owner = src.get("Owner");
    if (owner instanceof String && !((String) owner).isEmpty())
      out.put("Owner", shortParty((String) owner));
    return out;
  }

  private static JsonMap slimMap(final JsonMap src)
  {
    final JsonMap out = new JsonHashMap();
    for (final Map.Entry<String, Object> e : src.entrySet())
    {
      final String key = e.getKey();
      if (dropKey(key))
        continue;
      out.put(key, slim(e.getValue()));
    }
    return out;
  }

  private static JsonList slimList(final JsonList src)
  {
    final JsonList out = new JsonArrayList();
    final int cap = Math.min(src.size(), MAX_LIST);
    for (int i = 0; i < cap; i++)
      out.add(slim(src.get(i)));
    return out;
  }

  private static boolean dropKey(final String key)
  {
    return "PackageId".equals(key)
        || "ContractDomId".equals(key)
        || "Signatories".equals(key)
        || "Observers".equals(key)
        || "InstrumentAdmin".equals(key)
        || (key != null && key.startsWith("Weight") && key.length() > 6);
  }

  static String shortParty(final String s)
  {
    if (s == null)
      return "";
    final int sep = s.indexOf("::");
    if (sep > 0 && s.length() - sep > 8)
      return s.substring(0, sep);
    return s;
  }

  static String shortTemplate(final String s)
  {
    if (s == null)
      return "";
    final int colon = s.lastIndexOf(':');
    if (colon >= 0 && colon < s.length() - 1)
      return s.substring(colon + 1);
    return s;
  }
}
