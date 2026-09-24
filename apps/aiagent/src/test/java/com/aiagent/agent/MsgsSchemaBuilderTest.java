/*
 * Copyright (c) 2024 Domatar
 */

package com.aiagent.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.domatar.util.JsonArrayList;
import com.domatar.util.JsonHashMap;

public class MsgsSchemaBuilderTest
{
  @Test
  public void build_appendsCostHint()
  {
    final List<MsgsSchemaBuilder.ToolEntry> tools = MsgsSchemaBuilder.build(
        doc(msg("Ping", "Ping the object", "Read", cost(5, "credit"))));

    assertEquals(1, tools.size());
    final MsgsSchemaBuilder.ToolEntry entry = tools.get(0);
    assertEquals(Long.valueOf(5L), entry.costAmount);
    assertEquals("Ping", entry.tool.name);
    assertTrue(entry.tool.description.contains("Cost: 5 credit."));
    assertEquals("Read", entry.tool.sideEffect);
  }

  @Test
  public void build_omitsHintWhenCostAbsent()
  {
    final List<MsgsSchemaBuilder.ToolEntry> tools = MsgsSchemaBuilder.build(
        doc(msg("Ping", "Ping the object", "Read", null)));

    assertEquals(1, tools.size());
    final MsgsSchemaBuilder.ToolEntry entry = tools.get(0);
    assertNull(entry.costAmount);
    assertFalse(entry.tool.description.contains("Cost:"));
  }

  @Test
  public void build_omitsForeignUnit()
  {
    final List<MsgsSchemaBuilder.ToolEntry> tools = MsgsSchemaBuilder.build(
        doc(msg("Ping", "Ping the object", "Read", cost(5, "usd"))));

    assertEquals(1, tools.size());
    final MsgsSchemaBuilder.ToolEntry entry = tools.get(0);
    assertNull(entry.costAmount);
    assertFalse(entry.tool.description.contains("Cost:"));
  }

  private static JsonHashMap doc(final JsonHashMap message)
  {
    final JsonArrayList msgs = new JsonArrayList();
    msgs.add(message);
    final JsonHashMap doc = new JsonHashMap();
    doc.put("Msgs", msgs);
    return doc;
  }

  private static JsonHashMap msg(final String name, final String description,
      final String sideEffect, final JsonHashMap cost)
  {
    final JsonHashMap msg = new JsonHashMap();
    msg.put("Name", name);
    msg.put("Description", description);
    msg.put("SideEffect", sideEffect);
    if (cost != null)
      msg.put("Cost", cost);
    return msg;
  }

  private static JsonHashMap cost(final int amount, final String unit)
  {
    final JsonHashMap cost = new JsonHashMap();
    cost.put("Amount", Integer.valueOf(amount));
    cost.put("Unit", unit);
    return cost;
  }
}
