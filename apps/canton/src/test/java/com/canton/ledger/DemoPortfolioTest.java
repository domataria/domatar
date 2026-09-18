/*
 * Copyright (c) 2024 Domatar
 */

package com.canton.ledger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import com.domatar.util.DomatarException;

public class DemoPortfolioTest
{
  private static final Pattern PARTY =
      Pattern.compile("[A-Za-z0-9_-]{1,185}::1220[a-f0-9]{64}");

  @Test
  public void tenContractsVisibleToDavid() throws DomatarException
  {
    final List<Contract> all = DemoPortfolio.contracts();
    final Set<String> symbols = new HashSet<>();

    assertEquals(10, all.size());
    for (final Contract c : all)
    {
      assertTrue(c.contractId.length() <= 100, c.contractId);
      assertNotNull(DemoPortfolio.template(c.templateId), c.templateId);
      assertTrue(c.signatories.contains(DemoPortfolio.DAVID)
          || c.observers.contains(DemoPortfolio.DAVID), c.contractId);
      if (c.payload.get("Symbol") != null)
        symbols.add(c.payload.get("Symbol"));
    }
    assertTrue(symbols.contains("CC"));
    assertTrue(symbols.contains("HECTX"));
    assertTrue(symbols.contains("HXAI"));
    assertTrue(symbols.contains("HXSPX"));
    assertTrue(symbols.contains("HECTO"));
    assertTrue(symbols.contains("USYC"));
    assertTrue(symbols.contains("SBC"));
  }

  @Test
  public void partyIdsMatchCantonHintForm()
  {
    assertTrue(PARTY.matcher(DemoPortfolio.DAVID).matches());
    assertTrue(PARTY.matcher(DemoPortfolio.DSO).matches());
    assertTrue(PARTY.matcher(DemoPortfolio.HECTO_REG).matches());
    assertTrue(PARTY.matcher(DemoPortfolio.HASHNOTE).matches());
    assertTrue(PARTY.matcher(DemoPortfolio.BRALE).matches());
    assertTrue(PARTY.matcher(DemoPortfolio.BROADRIDGE).matches());
    assertTrue(PARTY.matcher(DemoPortfolio.DEALER).matches());
  }

  @Test
  public void storeJsonReloads() throws Exception
  {
    final Path file = Path.of("target", "canton-mock-demo.json");
    Files.createDirectories(file.getParent());
    Files.writeString(file, DemoPortfolio.storeJson());
    final MockCanton mock = new MockCanton(file.toFile());
    final List<Contract> acs = mock.queryAcs(DemoPortfolio.DAVID);

    assertEquals(10, acs.size());
    assertEquals(0, mock.queryAcs("Alice").size());
  }
}
