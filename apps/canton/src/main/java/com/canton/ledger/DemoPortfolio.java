/*
 * Copyright (c) 2024 Domatar
 */

package com.canton.ledger;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.canton.ledger.TemplateDesc.ChoiceDesc;
import com.canton.ledger.TemplateDesc.FieldDesc;
import com.domatar.util.DomatarException;
import com.domatar.util.Json;
import com.domatar.util.JsonArrayList;
import com.domatar.util.JsonHashMap;
import com.domatar.util.JsonList;

/**
 * Workshop demo ACS: CIP-56-shaped holdings (Hecto indexes, CC, cash
 * equivalents) plus a locked Amulet, a pending transfer, and a DLR
 * repo. Not minted by submitCreate; loaded via the mock JSON file.
 */
public final class DemoPortfolio
{
  public static final String DAVID =
      "davidb-quippin::12204e23bebfd1ad03860e2e2ddfe61e3ce2d92eb2d2cd0a32ea029c3f6f2431b57b";
  public static final String DSO =
      party("DSO", "1d5fc5c31e7c6bed0ec9a382f88f90c8221c0860ec0a59d984ae0485b897b226");
  public static final String HECTO_REG =
      party("Hecto", "8c1a9f0e4d7b2a6c5e8f1d3b0a7c4e9f2d5b8a1c6e3f0d9b7a4c1e8f5d2b0a11");
  public static final String HASHNOTE =
      party("Hashnote", "f2c81e9a4b7d3e5c0a8f1d6b9e4c7a2f5d8b1e4a7c0f3d6b9e2a5c8f1d4b7082");
  public static final String BRALE =
      party("Brale", "a7e3c91d4b8f2e6a0c5d9b3f1e8a4c7d0b6f2e9a5c1d8b4f0e7a3c6d2b918e55");
  public static final String BROADRIDGE =
      party("Broadridge", "b8d4e0f6a2c9e5b1d7f3a8c4e0b6d2f9a5c1e7b3d0f8a4c6e2b9d57e0134aabb");
  public static final String DEALER =
      party("dealer-gs", "c9e5a1b7d3f0e8a4c2d9b6f1e7a3c0d8b4f2e9a5c1d7b3f0e6a2c84f5d90ccdd");

  public static final String AMULET = "Splice.Amulet:Amulet";
  public static final String HOLDING = "Splice.Api.Token.HoldingV1:Holding";
  public static final String LOCKED = "Splice.Amulet:LockedAmulet";
  public static final String XFER =
      "Splice.Api.Token.TransferInstructionV1:TransferInstruction";
  public static final String REPO = "Broadridge.Dlr:Repo";

  private DemoPortfolio()
  {
  }

  public static TemplateDesc template(final String templateId)
  {
    if (AMULET.equals(templateId) || HOLDING.equals(templateId))
      return holding(templateId);
    if (LOCKED.equals(templateId))
      return locked();
    if (XFER.equals(templateId))
      return transferInstruction();
    if (REPO.equals(templateId))
      return repo();
    return null;
  }

  public static List<Contract> contracts()
  {
    final List<Contract> out = new ArrayList<>();

    out.add(holding(cid("cc"), AMULET, DAVID, DSO,
        "12840.2500000000", "CC", "Amulet", "Canton Coin", DSO));
    out.add(holding(cid("hectx"), HOLDING, DAVID, HECTO_REG,
        "42.0000000000", "HECTX", "HECTX", "Hectocorn Index", HECTO_REG));
    out.add(holding(cid("hxai"), HOLDING, DAVID, HECTO_REG,
        "15.5000000000", "HXAI", "HXAI", "Hecto AI Index", HECTO_REG));
    out.add(holding(cid("hxspx"), HOLDING, DAVID, HECTO_REG,
        "8.2500000000", "HXSPX", "HXSPX", "Hecto Aerospace Index", HECTO_REG));
    out.add(holding(cid("hecto"), HOLDING, DAVID, HECTO_REG,
        "100000.0000000000", "HECTO", "HECTO", "Hecto Allocator", HECTO_REG));
    out.add(holding(cid("usyc"), HOLDING, DAVID, HASHNOTE,
        "250000.00", "USYC", "USYC", "Hashnote Short Duration Yield", HASHNOTE));
    out.add(holding(cid("sbc"), HOLDING, DAVID, BRALE,
        "50000.00", "SBC", "SBC", "Brale US Dollar", BRALE));

    final Map<String, String> locked = payload(
        "Owner", DAVID,
        "Amount", "5000.0000000000",
        "Symbol", "CC",
        "InstrumentId", "Amulet",
        "InstrumentAdmin", DSO,
        "Name", "Locked Canton Coin",
        "LockContext", "round-incentive",
        "LockExpiry", "2026-09-25T00:00:00Z");
    out.add(new Contract(cid("locked"), LOCKED, locked,
        Arrays.asList(DAVID), Arrays.asList(DSO)));

    final Map<String, String> pending = payload(
        "Sender", DSO,
        "Receiver", DAVID,
        "Owner", DSO,
        "Amount", "500.0000000000",
        "Symbol", "CC",
        "InstrumentId", "Amulet",
        "InstrumentAdmin", DSO,
        "Name", "Canton Coin transfer",
        "Status", "Pending");
    out.add(new Contract(cid("xfer"), XFER, pending,
        Arrays.asList(DSO), Arrays.asList(DAVID)));

    final Map<String, String> dlr = payload(
        "Operator", BROADRIDGE,
        "Seller", DEALER,
        "Buyer", DAVID,
        "Owner", DAVID,
        "Par", "10000000",
        "Amount", "10000000",
        "Symbol", "USD",
        "Collateral", "UST 91282CML4",
        "Rate", "0.0525",
        "Start", "2026-09-18",
        "End", "2026-09-19",
        "Name", "Overnight UST repo");
    out.add(new Contract(cid("dlr"), REPO, dlr,
        Arrays.asList(BROADRIDGE, DEALER, DAVID),
        Collections.emptyList()));
    return out;
  }

  public static String storeJson() throws DomatarException
  {
    final JsonHashMap root = new JsonHashMap();
    final JsonList rows = new JsonArrayList();

    root.put("nextId", Integer.valueOf(1));
    for (final Contract c : contracts())
    {
      final JsonHashMap row = new JsonHashMap();
      final JsonHashMap payload = new JsonHashMap();
      final JsonList signatories = new JsonArrayList();
      final JsonList observers = new JsonArrayList();

      row.put("contractId", c.contractId);
      row.put("templateId", c.templateId);
      for (final Map.Entry<String, String> e : c.payload.entrySet())
        payload.put(e.getKey(), e.getValue());
      row.put("payload", payload);
      for (final String p : c.signatories)
        signatories.add(p);
      for (final String p : c.observers)
        observers.add(p);
      row.put("signatories", signatories);
      row.put("observers", observers);
      rows.add(row);
    }
    root.put("contracts", rows);
    return Json.toJson(root);
  }

  private static Contract holding(final String id, final String templateId,
                                  final String owner, final String admin,
                                  final String amount, final String symbol,
                                  final String instrumentId, final String name,
                                  final String observer)
  {
    final Map<String, String> payload = payload(
        "Owner", owner,
        "Amount", amount,
        "Symbol", symbol,
        "InstrumentId", instrumentId,
        "InstrumentAdmin", admin,
        "Name", name);
    return new Contract(id, templateId, payload,
        Arrays.asList(owner), Arrays.asList(observer));
  }

  private static TemplateDesc holding(final String templateId)
  {
    return new TemplateDesc(templateId,
        Arrays.asList(
            new FieldDesc("Owner", "Party"),
            new FieldDesc("Amount", "Decimal"),
            new FieldDesc("Symbol", "Text"),
            new FieldDesc("InstrumentId", "Text"),
            new FieldDesc("InstrumentAdmin", "Party"),
            new FieldDesc("Name", "Text")),
        Collections.singletonList("Owner"),
        Collections.singletonList("InstrumentAdmin"),
        Arrays.asList(
            new ChoiceDesc("Transfer", Collections.singletonList("Owner"), true,
                Collections.singletonList(new FieldDesc("NewOwner", "Party"))),
            new ChoiceDesc("Archive", Collections.singletonList("Owner"), true,
                Collections.emptyList())));
  }

  private static TemplateDesc locked()
  {
    return new TemplateDesc(LOCKED,
        Arrays.asList(
            new FieldDesc("Owner", "Party"),
            new FieldDesc("Amount", "Decimal"),
            new FieldDesc("Symbol", "Text"),
            new FieldDesc("LockContext", "Text"),
            new FieldDesc("LockExpiry", "Text")),
        Collections.singletonList("Owner"),
        Collections.emptyList(),
        Collections.singletonList(
            new ChoiceDesc("Unlock", Collections.singletonList("Owner"), true,
                Collections.emptyList())));
  }

  private static TemplateDesc transferInstruction()
  {
    return new TemplateDesc(XFER,
        Arrays.asList(
            new FieldDesc("Sender", "Party"),
            new FieldDesc("Receiver", "Party"),
            new FieldDesc("Amount", "Decimal"),
            new FieldDesc("Symbol", "Text"),
            new FieldDesc("Status", "Text")),
        Collections.singletonList("Sender"),
        Collections.singletonList("Receiver"),
        Arrays.asList(
            new ChoiceDesc("Accept", Collections.singletonList("Receiver"), true,
                Collections.emptyList()),
            new ChoiceDesc("Reject", Collections.singletonList("Receiver"), true,
                Collections.emptyList())));
  }

  private static TemplateDesc repo()
  {
    return new TemplateDesc(REPO,
        Arrays.asList(
            new FieldDesc("Operator", "Party"),
            new FieldDesc("Seller", "Party"),
            new FieldDesc("Buyer", "Party"),
            new FieldDesc("Par", "Decimal"),
            new FieldDesc("Collateral", "Text"),
            new FieldDesc("Rate", "Decimal"),
            new FieldDesc("Start", "Text"),
            new FieldDesc("End", "Text")),
        Arrays.asList("Operator", "Seller", "Buyer"),
        Collections.emptyList(),
        Collections.singletonList(
            new ChoiceDesc("Close", Collections.singletonList("Buyer"), true,
                Collections.emptyList())));
  }

  private static String party(final String hint, final String hex64)
  {
    if (hint == null || hex64 == null || hex64.length() != 64)
      throw new IllegalArgumentException("Party fingerprint must be 64 hex chars");
    return hint + "::1220" + hex64;
  }

  private static String cid(final String tag)
  {
    if ("cc".equals(tag))
      return "00ce4f91a8b3d2e1c0f76a5b4d3c2e1f0a9b8c7d6e5f4a3b2c1d0e9f8a7b6c";
    if ("hectx".equals(tag))
      return "00d1a8c3e7b24f90e5c1a6b8d3f0e7a4c9b2d5f1e8a3c6b0d7f4e1a9c5b2d8";
    if ("hxai".equals(tag))
      return "00e2b9d4f8c35a01f6d2b7c9e4a1f8b5d0c3e6a2f9b4d7c1e8a5f2b0d6c3e9";
    if ("hxspx".equals(tag))
      return "00f3c0e5a9d46b12a7e3c8d0f5b2a9c6e1d4f7b3a0c5e8d2f9b6a3c1e7d4f0";
    if ("hecto".equals(tag))
      return "0014d1f6b0e57c23b8f4d9e1a6c3b0d7f2e5a8c4b1d6f9e3a0c7b4d2f8e5a1";
    if ("usyc".equals(tag))
      return "0025e2a7c1f68d34c9a5e0f2b7d4c1e8a3f6b9d5c2e7a0f4b1d8c5e3a9f6b2";
    if ("sbc".equals(tag))
      return "0036f3b8d2a79e45d0b6f1a3c8e5d2f9b4a7c0e6d3f8b1a5c2e9d6f4b0a7c3";
    if ("locked".equals(tag))
      return "0047a4c9e3b80f56e1c7a2b4d9f6e3a0c5b8d1f7e4a9c2b6d3f0e7a5c1b8d4";
    if ("xfer".equals(tag))
      return "0058b5d0f4c91a67f2d8b3c5e0a7f4b1d6c9e2a8f5b0d3c7e4a1f8b6d2c9e5";
    return "0069c6e1a5d02b78a3e9c4d6f1b8a5c2e7d0f3b9a6c1e4d8f5b2a9c7e3d0f6";
  }

  private static Map<String, String> payload(final String... kv)
  {
    final Map<String, String> map = new LinkedHashMap<>();

    for (int i = 0; i + 1 < kv.length; i += 2)
      map.put(kv[i], kv[i + 1]);
    return map;
  }
}
