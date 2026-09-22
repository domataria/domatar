/*
 * Copyright (c) 2024 Domatar
 */

package com.domatar.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.domatar.log.OpLog;
import com.domatar.util.JsonArrayList;
import com.domatar.util.JsonHashMap;
import com.domatar.util.JsonMap;

public class ClsResolverPolicyTest
{
  @AfterEach
  public void clearClsMap()
  {
    ClsMap.invalidateAll();
  }

  @Test
  public void policyWinsOverService()
  {
    assertEquals("Read",
        ClsResolver.pickDeclared("Read", "Write", "Write"));
  }

  @Test
  public void serviceUsedWhenPolicyMissing()
  {
    assertEquals("Read",
        ClsResolver.pickDeclared(null, "Read", "Write"));
  }

  @Test
  public void defaultWhenNeitherDeclared()
  {
    assertEquals("Write",
        ClsResolver.pickDeclared(null, null, "Write"));
  }

  @Test
  public void compensates_policyWinsOverService()
  {
    assertEquals("Credit",
        ClsResolver.pickDeclared("Credit", "Refund", null));
  }

  @Test
  public void compensates_absentMeansNull()
  {
    assertNull(ClsResolver.pickDeclared(null, null, null));
  }

  @Test
  public void compensates_emptyStringMeansNull()
  {
    assertNull(ClsResolver.pickDeclared("", "", null));
    assertEquals("Credit", ClsResolver.pickDeclared("", "Credit", null));
  }

  @Test
  public void applyMsgPolicy_setsCompensatesFromPolicy()
  {
    final JsonArrayList msgs = new JsonArrayList();
    final JsonHashMap msg = new JsonHashMap();
    msg.put("Name", "Debit");
    msg.put("Srv", "money.accounts");
    msg.put("Compensates", "Refund");
    msgs.add(msg);

    final JsonHashMap clsDoc = new JsonHashMap();
    final JsonArrayList policy = new JsonArrayList();
    final JsonHashMap entry = new JsonHashMap();
    entry.put("Srv", "money.accounts");
    entry.put("Name", "Debit");
    entry.put("Compensates", "Credit");
    policy.add(entry);
    clsDoc.put("MsgPolicy", policy);

    ClsResolver.applyMsgPolicy(msgs, clsDoc);
    assertEquals("Credit", ((JsonMap) msgs.get(0)).getString("Compensates"));
  }

  @Test
  public void applyMsgPolicy_omitsCompensatesWhenAbsent()
  {
    final JsonArrayList msgs = new JsonArrayList();
    final JsonHashMap msg = new JsonHashMap();
    msg.put("Name", "GetX");
    msg.put("Srv", "app.obj");
    msgs.add(msg);

    ClsResolver.applyMsgPolicy(msgs, new JsonHashMap());
    assertNull(((JsonMap) msgs.get(0)).get("Compensates"));
    assertEquals("Write", ((JsonMap) msgs.get(0)).getString("SideEffect"));
  }

  @Test
  public void applyMsgPolicy_omitsEmptyCompensates()
  {
    final JsonArrayList msgs = new JsonArrayList();
    final JsonHashMap msg = new JsonHashMap();
    msg.put("Name", "GetX");
    msg.put("Compensates", "");
    msgs.add(msg);

    ClsResolver.applyMsgPolicy(msgs, new JsonHashMap());
    assertNull(((JsonMap) msgs.get(0)).get("Compensates"));
  }

  @Test
  public void compensates_scansMergedMsgs()
  {
    final JsonHashMap holder = new JsonHashMap();
    final JsonArrayList msgs = new JsonArrayList();
    final JsonHashMap debit = new JsonHashMap();
    debit.put("Name", "Debit");
    debit.put("Compensates", "Credit");
    msgs.add(debit);
    final JsonHashMap credit = new JsonHashMap();
    credit.put("Name", "Credit");
    msgs.add(credit);
    holder.put("Msgs", msgs);

    assertEquals("Credit", ClsResolver.compensates(holder, "Debit"));
    assertNull(ClsResolver.compensates(holder, "Credit"));
    assertNull(ClsResolver.compensates(holder, "Missing"));
    assertNull(ClsResolver.compensates(holder, ""));
  }

  @Test
  public void compensates_clsMapLookup() throws Exception
  {
    ClsMap.put("money", "accounts",
        "{\"Msgs\":[{\"Name\":\"Debit\",\"Compensates\":\"Credit\"}]}");
    assertEquals("Credit",
        ClsResolver.compensates("money", "accounts", "Debit", null));
    assertNull(ClsResolver.compensates("money", "accounts", "GetAccounts", null));
  }

  @Test
  public void compensates_unknownClassIsNull() throws Exception
  {
    assertNull(ClsResolver.compensates("nope", "nope", "Debit", null));
  }

  @Test
  public void sagaTtl_defaultAtLeastVisitTtl()
  {
    assertTrue(DomatarConfig.getSagaTtlMs() >= DomatarConfig.getVisitTtlMs());
    assertEquals(DomatarConfig.getSagaTtlMs(), OpLog.sagaTtlMs());
    assertEquals(86_400_000L, DomatarConfig.getSagaTtlMs());
  }
}
