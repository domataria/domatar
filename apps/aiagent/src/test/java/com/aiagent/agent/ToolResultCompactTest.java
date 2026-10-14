/*
 * Copyright (c) 2024 Domatar
 */

package com.aiagent.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.domatar.util.Json;
import com.domatar.util.JsonList;
import com.domatar.util.JsonMap;

public class ToolResultCompactTest
{
  @Test
  public void contractsKeepAmountAndDropPartyFingerprints() throws Exception
  {
    final String raw =
        "{\"Contracts\":[{"
        + "\"Name\":\"Canton Coin\","
        + "\"Symbol\":\"CC\","
        + "\"Amount\":\"12840.25\","
        + "\"Owner\":\"davidb-quippin::12204e23bebfd1ad03860e2e2ddfe61e3ce2d92eb2d2cd0a32ea029c3f6f2431b57b\","
        + "\"Signatories\":\"[\\n  \\\"davidb-quippin::12204e23bebfd1ad03860e2e2ddfe61e3ce2d92eb2d2cd0a32ea029c3f6f2431b57b\\\"\\n]\","
        + "\"PackageId\":\"\","
        + "\"WeightOpenAI\":\"0.28\","
        + "\"TemplateId\":\"Splice.Amulet:Amulet\","
        + "\"ContractDomId\":\"canton-x.canton.x.00ce\""
        + "}]}";

    final String slim = ToolResultCompact.forLlm(raw);
    assertTrue(slim.length() < raw.length());
    assertFalse(slim.contains("12204e23"));
    assertFalse(slim.contains("ContractDomId"));
    assertFalse(slim.contains("WeightOpenAI"));

    final JsonMap map = Json.parseMap(slim);
    assertTrue("1".equals(map.getString("Count")));
    final JsonList list = map.getList("Contracts");
    final JsonMap row = (JsonMap) list.get(0);
    assertTrue("12840.25".equals(row.getString("Amount")));
    assertTrue("CC".equals(row.getString("Symbol")));
    assertTrue("davidb-quippin".equals(row.getString("Owner")));
    assertTrue("Amulet".equals(row.getString("TemplateId")));
  }

  @Test
  public void shortPartyKeepsHint()
  {
    assertTrue("davidb-quippin".equals(ToolResultCompact.shortParty(
        "davidb-quippin::12204e23bebfd1ad03860e2e2ddfe61e3ce2d92eb2d2cd0a32ea029c3f6f2431b57b")));
    assertTrue("plain".equals(ToolResultCompact.shortParty("plain")));
  }

  @Test
  public void fitKeepsEightFlightNames() throws Exception
  {
    final String raw = rows("Offers", 8, "F",
        "\"Airline\":\"Duffel Airways\",\"Origin\":\"LHR\","
        + "\"Destination\":\"CDG\",\"Price\":\"100.00\"");

    assertNames(ToolResultCompact.fit(raw, 16000), "Offers", 8, "F");
  }

  @Test
  public void fitKeepsTwelveHotelNames() throws Exception
  {
    final String raw = rows("Hotels", 12, "H",
        "\"Street\":\"13 Rue de Malte\",\"City\":\"Paris\",\"NightlyPrice\":\"422\"");

    assertNames(ToolResultCompact.fit(raw, 16000), "Hotels", 12, "H");
  }

  @Test
  public void fitKeepsTwelveSightNames() throws Exception
  {
    final String raw = rows("Sights", 12, "S", "");

    assertNames(ToolResultCompact.fit(raw, 16000), "Sights", 12, "S");
  }

  @Test
  public void fitDropsWholeRowsWhenOverCap() throws Exception
  {
    final String street = "STREET-" + "x".repeat(400);
    final String raw = rows("Hotels", 3, "H", "\"Street\":\"" + street + "\"");
    final String fitted = ToolResultCompact.fit(raw, ToolResultCompact.fit(raw, 16000).length() / 2);
    final JsonMap map = Json.parseMap(fitted);
    final JsonList hotels = map.getList("Hotels");

    assertTrue(hotels.size() < 3);
    for (final Object item : hotels)
    {
      final JsonMap row = (JsonMap) item;
      final String name = row.getString("Name");

      assertTrue("H0".equals(name) || "H1".equals(name) || "H2".equals(name));
      assertEquals(street, row.getString("Street"));
    }
  }

  private static String rows(final String key, final int count, final String prefix,
                             final String extra)
  {
    final StringBuilder body = new StringBuilder();

    body.append("{\"").append(key).append("\":[");
    for (int i = 0; i < count; i++)
    {
      if (i > 0)
        body.append(',');
      body.append("{\"Name\":\"").append(prefix).append(i).append('"');
      if (extra != null && !extra.isEmpty())
        body.append(',').append(extra);
      body.append('}');
    }
    body.append("]}");
    return body.toString();
  }

  private static void assertNames(final String fitted, final String key,
                                  final int count, final String prefix)
      throws Exception
  {
    final JsonMap map = Json.parseMap(fitted);
    final JsonList rows = map.getList(key);

    assertEquals(count, rows.size());
    for (int i = 0; i < count; i++)
    {
      final JsonMap row = (JsonMap) rows.get(i);

      assertEquals(prefix + i, row.getString("Name"));
    }
  }
}
