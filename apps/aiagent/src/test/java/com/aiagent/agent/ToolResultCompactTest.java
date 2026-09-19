/*
 * Copyright (c) 2024 Domatar
 */

package com.aiagent.agent;

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
}
