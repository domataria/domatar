package com.domatar.saga;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.sql.DriverManager;
import java.sql.SQLException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.domatar.db.DbConnection;
import com.domatar.db.OpLogDb;
import com.domatar.install.OpLogInstall;
import com.domatar.log.OpDst;
import com.domatar.util.JsonMap;
import com.domatar.util.JsonMsg;
import com.domatar.util.ObjAttrs;

public class CompensateAdmitTest
{
  private static final long TTL = 60_000L;
  private static final String[] URLS = {
      System.getenv("DOMATAR_DB_URL"),
      System.getProperty("DOMATAR_DB_URL"),
      "jdbc:mysql://127.0.0.1:9307/domatar?user=root&password=domatar",
      "jdbc:mysql://127.0.0.1:3306/domatar?user=root&password=domatar"
  };

  private static boolean dbReady;

  private String hst;
  private String ctx;
  private String dst;
  private String caller;
  private long now;

  @BeforeAll
  public static void connectOrSkip() throws Exception
  {
    String ok = null;
    for (final String url : URLS)
    {
      if (url == null || url.isBlank())
        continue;
      try
      {
        DriverManager.getConnection(url + "&serverTimezone=UTC").close();
        ok = url;
        break;
      }
      catch (final SQLException ignored)
      {
      }
    }

    dbReady = ok != null;
    if (dbReady)
    {
      DbConnection.setConnectStr(ok);
      OpLogInstall.ensureTables();
    }
  }

  @BeforeEach
  public void ids()
  {
    final String n = Long.toHexString(System.nanoTime());
    hst = "saga-t-" + n;
    ctx = "ctx-" + n;
    dst = hst + ".app.act.obj";
    caller = hst + ".app.act.parent";
    now = System.currentTimeMillis();
  }

  @AfterEach
  public void cleanup() throws Exception
  {
    if (!dbReady)
      return;
    OpLogDb.gcHost(hst, now + TTL + 1);
  }

  @Test
  public void deny_origMsgNameCompensate()
  {
    assertFalse(Compensate.admit("Compensate", null, null, "actA", caller));
    assertFalse(Compensate.admit("", null, null, "actA", caller));
    assertFalse(Compensate.admit(null, null, null, "actA", caller));
  }

  @Test
  public void admit_noVisit()
  {
    assertTrue(Compensate.admit("Ping", null, null, "actA", caller));
  }

  @Test
  public void deny_actIdMismatch() throws Exception
  {
    final OpDst visit = seedVisit("actA");
    assertFalse(Compensate.admit("Ping", visit, null, "actB", caller));
  }

  @Test
  public void deny_callerDomIdMismatch_whenNotDone() throws Exception
  {
    final OpDst visit = seedVisit("actA");
    final JsonMap slot = SagaSlot.applied("Credit", null);
    assertFalse(Compensate.admit("Ping", visit, slot, "actA",
        hst + ".app.act.other"));
    assertFalse(Compensate.admit("Ping", visit, null, "actA", null));
  }

  @Test
  public void admit_callerDomIdMatch_whenNotDone() throws Exception
  {
    final OpDst visit = seedVisit("actA");
    assertTrue(Compensate.admit("Ping", visit, null, "actA", caller));
    assertTrue(Compensate.admit("Ping", visit,
        SagaSlot.applied("Credit", null), "actA", caller));
  }

  @Test
  public void admit_alreadyCompensated_anyCaller_sameActId() throws Exception
  {
    final OpDst visit = seedVisit("actA");
    final JsonMap slot = SagaSlot.withStatus(
        SagaSlot.applied("Credit", null), SagaSlot.STATUS_COMPENSATED);
    assertTrue(Compensate.admit("Ping", visit, slot, "actA",
        hst + ".app.act.skip"));
  }

  @Test
  public void deny_alreadyCompensated_wrongActId() throws Exception
  {
    final OpDst visit = seedVisit("actA");
    final JsonMap slot = SagaSlot.withStatus(
        SagaSlot.applied("Credit", null), SagaSlot.STATUS_COMPENSATED);
    assertFalse(Compensate.admit("Ping", visit, slot, "actB", caller));
  }

  @Test
  public void withStatus_keepsEffect()
  {
    final JsonMap applied = SagaSlot.applied("Credit", "{\"Amount\":\"1\"}");
    final JsonMap next = SagaSlot.withStatus(applied, SagaSlot.STATUS_COMPENSATING);
    assertEquals("Credit", next.getString("compensates"));
    assertEquals("{\"Amount\":\"1\"}", next.get("effect"));
    assertEquals(SagaSlot.STATUS_COMPENSATING, SagaSlot.status(next));
    assertNull(SagaSlot.parse(null));
    assertNull(SagaSlot.parse(""));
  }

  @Test
  public void result_toMap_fromMsg() throws Exception
  {
    final CompensateResult child = CompensateResult.noop("c", "d2", "Ping",
        "already");
    final CompensateResult parent = CompensateResult.failed("c", "d1", "Debit",
        "child");
    parent.children.add(child);

    final JsonMsg msg = new JsonMsg();
    final ObjAttrs attrs = new ObjAttrs(parent.toMap());
    msg.addResponseBody("Compensate", attrs);

    final CompensateResult parsed = CompensateResult.fromMsg(msg);
    assertEquals("failed", parsed.outcome);
    assertEquals("child", parsed.reason);
    assertEquals(1, parsed.children.size());
    assertEquals("noop", parsed.children.get(0).outcome);
  }

  private OpDst seedVisit(final String actId) throws Exception
  {
    assumeTrue(dbReady, "MySQL unreachable; skip visit-backed admit test");
    assertTrue(OpLogDb.upsertVisit(hst, ctx, dst, "Ping", actId, caller,
        "ACCOUNT", now, TTL));
    final OpDst visit = OpLogDb.getVisit(hst, ctx, dst, "Ping");
    assertEquals(actId, visit.actId);
    assertEquals(caller, visit.callerDomId);
    return visit;
  }
}
