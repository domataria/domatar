/*
 * Copyright (c) 2024 Domatar
 */

package com.canton.ledger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.canton.ledger.TemplateDesc.ChoiceDesc;
import com.domatar.util.DomatarException;

public class MockCantonTest
{
  @Test
  public void persistAndReloadsFromJsonFile() throws Exception
  {
    final File tmp = Files.createTempFile("canton-mock", ".json").toFile();

    assertTrue(tmp.delete());
    final MockCanton a = new MockCanton(tmp);
    final SubmitResult created = a.submitCreate("Bank", IouTemplates.TEMPLATE_ID, iou("Bank", "Alice"));
    final String cid = created.created.get(0).contractId;
    final MockCanton b = new MockCanton(tmp);
    final List<Contract> alice = b.queryAcs("Alice");

    assertEquals(1, alice.size());
    assertEquals(cid, alice.get(0).contractId);
    final SubmitResult second = b.submitCreate("Bank", IouTemplates.TEMPLATE_ID, iou("Bank", "Alice"));

    assertEquals("cid-2", second.created.get(0).contractId);
  }

  @Test
  public void queryAcsHidesNonStakeholders() throws DomatarException
  {
    final MockCanton mock = new MockCanton(null);

    mock.submitCreate("Bank", IouTemplates.TEMPLATE_ID, iou("Bank", "Alice"));
    assertEquals(1, mock.queryAcs("Alice").size());
    assertEquals(1, mock.queryAcs("Bank").size());
    assertEquals(0, mock.queryAcs("Bob").size());
  }

  @Test
  public void createAsNonSignatoryFails() throws DomatarException
  {
    final MockCanton mock = new MockCanton(null);

    assertThrows(DomatarException.class,
        () -> mock.submitCreate("Alice", IouTemplates.TEMPLATE_ID, iou("Bank", "Alice")));
  }

  @Test
  public void transferAsOwnerArchivesAndCreatesSuccessor() throws DomatarException
  {
    final MockCanton mock = new MockCanton(null);
    final SubmitResult created = mock.submitCreate("Bank", IouTemplates.TEMPLATE_ID, iou("Bank", "Alice"));
    final String oldId = created.created.get(0).contractId;
    final SubmitResult xfer = mock.submitExercise("Alice", oldId, "Transfer",
        Collections.singletonMap("NewOwner", "Bob"));

    assertEquals(1, xfer.archived.size());
    assertEquals(oldId, xfer.archived.get(0));
    assertEquals(1, xfer.created.size());
    assertEquals("Bob", xfer.created.get(0).payload.get("Owner"));
    assertEquals(0, mock.queryAcs("Alice").size());
    assertEquals(1, mock.queryAcs("Bank").size());
    assertEquals(1, mock.queryAcs("Bob").size());
    assertEquals("Bob", mock.queryAcs("Bank").get(0).payload.get("Owner"));
    // Phase 7: Open/GetObj on a real handle must pick up this Owner
    // without a container Sync (HandleSync.refreshOrDrop).
  }

  @Test
  public void transferAsIssuerFails() throws DomatarException
  {
    final MockCanton mock = new MockCanton(null);
    final SubmitResult created = mock.submitCreate("Bank", IouTemplates.TEMPLATE_ID, iou("Bank", "Alice"));
    final String cid = created.created.get(0).contractId;

    assertThrows(DomatarException.class,
        () -> mock.submitExercise("Bank", cid, "Transfer",
            Collections.singletonMap("NewOwner", "Bob")));
    assertEquals(1, mock.queryAcs("Bank").size());
    assertEquals(cid, mock.queryAcs("Bank").get(0).contractId);
  }

  @Test
  public void settleAsIssuerArchives() throws DomatarException
  {
    final MockCanton mock = new MockCanton(null);
    final SubmitResult created = mock.submitCreate("Bank", IouTemplates.TEMPLATE_ID, iou("Bank", "Alice"));

    mock.submitExercise("Bank", created.created.get(0).contractId, "Settle", Collections.emptyMap());
    assertEquals(0, mock.queryAcs("Bank").size());
    assertEquals(0, mock.queryAcs("Alice").size());
  }

  @Test
  public void settleAsOwnerFails() throws DomatarException
  {
    final MockCanton mock = new MockCanton(null);
    final SubmitResult created = mock.submitCreate("Bank", IouTemplates.TEMPLATE_ID, iou("Bank", "Alice"));
    final String cid = created.created.get(0).contractId;

    assertThrows(DomatarException.class,
        () -> mock.submitExercise("Alice", cid, "Settle", Collections.emptyMap()));
    assertEquals(1, mock.queryAcs("Bank").size());
  }

  @Test
  public void listTemplatesIsIou() throws DomatarException
  {
    final MockCanton mock = new MockCanton(null);
    final List<String> ids = mock.listTemplates();
    final TemplateDesc iou = mock.getTemplate("Iou");
    final List<String> choiceNames = new ArrayList<>();

    assertEquals(Collections.singletonList("Iou"), ids);
    for (final ChoiceDesc ch : iou.choices)
      choiceNames.add(ch.name);
    assertTrue(choiceNames.contains("Transfer"));
    assertTrue(choiceNames.contains("Settle"));
    assertTrue(choiceNames.contains("Archive"));
  }

  @Test
  public void patchPayloadKeepsContractIdAndMergesExistingKeys() throws Exception
  {
    final File tmp = Files.createTempFile("canton-mock-patch", ".json").toFile();

    assertTrue(tmp.delete());
    final MockCanton mock = new MockCanton(tmp);
    final String cid = mock.submitCreate("Bank", IouTemplates.TEMPLATE_ID,
        iou("Bank", "Alice")).created.get(0).contractId;
    final Map<String, String> fields = new LinkedHashMap<>();

    fields.put("Amount", "250");
    fields.put("NotAField", "ignored");
    final Contract patched = mock.patchPayload(cid, fields);

    assertEquals(cid, patched.contractId);
    assertEquals("250", patched.payload.get("Amount"));
    assertEquals("USD", patched.payload.get("Currency"));
    assertEquals("Alice", patched.payload.get("Owner"));

    final MockCanton reloaded = new MockCanton(tmp);

    assertEquals("250", reloaded.getContract(cid).payload.get("Amount"));
    assertEquals(cid, reloaded.getContract(cid).contractId);
  }

  @Test
  public void patchPayloadUnknownContractFails() throws DomatarException
  {
    final MockCanton mock = new MockCanton(null);

    assertThrows(DomatarException.class,
        () -> mock.patchPayload("cid-missing", Collections.singletonMap("Amount", "1")));
  }

  @Test
  public void mockOrNullReturnsMockCanton()
  {
    assertTrue(CantonClients.mockOrNull() instanceof MockCanton);
  }

  private static Map<String, String> iou(final String issuer, final String owner)
  {
    final Map<String, String> payload = new LinkedHashMap<>();

    payload.put("Issuer", issuer);
    payload.put("Owner", owner);
    payload.put("Amount", "100");
    payload.put("Currency", "USD");
    return payload;
  }
}
