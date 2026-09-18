/*
 * Copyright (c) 2024 Domatar
 */

package com.canton.objimpl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.canton.ledger.Contract;
import com.canton.ledger.IouTemplates;
import com.domatar.util.ObjAttrs;

public class HandleSyncAttrsTest
{
  @Test
  public void attrsFromCopiesPayloadAndStakeholders() throws Exception
  {
    final Map<String, String> payload = new LinkedHashMap<>();
    payload.put("Issuer", "Bank");
    payload.put("Owner", "Alice");
    payload.put("Amount", "100");
    payload.put("Currency", "USD");

    final Contract c = new Contract("cid-1", IouTemplates.TEMPLATE_ID, payload,
        Arrays.asList("Bank"), Arrays.asList("Alice"));
    final ObjAttrs attrs = HandleSync.attrsFrom(c);

    assertEquals("cid-1", attrs.getAttr("ContractId"));
    assertEquals("Iou", attrs.getAttr("TemplateId"));
    assertEquals("Bank", attrs.getAttr("Issuer"));
    assertEquals("Alice", attrs.getAttr("Owner"));
    assertEquals("100", attrs.getAttr("Amount"));
    assertTrue(attrs.getAttr("Signatories").contains("Bank"));
    assertTrue(attrs.getAttr("Observers").contains("Alice"));
  }
}
