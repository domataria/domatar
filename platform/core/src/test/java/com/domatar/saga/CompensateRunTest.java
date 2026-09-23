package com.domatar.saga;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.domatar.core.Context;
import com.domatar.core.HandlerClient;
import com.domatar.core.TestClients;
import com.domatar.crypto.CanonicalJson;
import com.domatar.crypto.Path;
import com.domatar.crypto.Provenance;
import com.domatar.db.DbConnection;
import com.domatar.db.OpLogDb;
import com.domatar.install.OpLogInstall;
import com.domatar.log.OpLog;
import com.domatar.util.DomId;
import com.domatar.util.DomatarException;
import com.domatar.util.JsonHashMap;
import com.domatar.util.JsonMap;
import com.domatar.util.JsonMsg;
import com.domatar.util.Obj;
import com.domatar.util.ObjAttrs;

public class CompensateRunTest
{
  private static final long TTL = 60_000L;
  private static final String[] URLS = {
      System.getenv("DOMATAR_DB_URL"),
      System.getProperty("DOMATAR_DB_URL"),
      "jdbc:mysql://127.0.0.1:9307/domatar?user=root&password=domatar",
      "jdbc:mysql://127.0.0.1:3306/domatar?user=root&password=domatar"
  };

  private static boolean dbReady;
  private static String jdbcUrl;

  private String hst;
  private DomId dst;
  private String caller;
  private Context ctx;
  private Provenance prov;
  private HandlerClient client;
  private Obj obj;
  private SagaProbeImpl probe;
  private final List<String> sentNames = new ArrayList<>();
  private final List<String> sentDsts = new ArrayList<>();
  private final List<CompensateResult> canned = new ArrayList<>();

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
      jdbcUrl = ok;
      DbConnection.setConnectStr(ok);
      OpLogInstall.ensureTables();
    }
  }

  @BeforeEach
  public void ids() throws Exception
  {
    Compensate.resetTestHooks();
    SagaProbeImpl.reset();
    sentNames.clear();
    sentDsts.clear();
    canned.clear();

    final String n = Long.toHexString(System.nanoTime());
    hst = "saga-r-" + n;
    final DomId ui = new DomId(hst, "ui", "act", "uiObj");
    dst = new DomId(hst, "probe", "actA", "objA");
    caller = hst + ".probe.actA.parent";
    final Path path = Path.root(ui, dst, bodyBytes("Ping"), "actA", "testprv");
    prov = Provenance.of(path, null, null);
    ctx = TestClients.account("actA", "u", "n", "127.0.0.1", "tok", path.contextId());
    client = inbound();
    obj = new Obj(dst, "probe", "probe", "Probe", "Probe", new ObjAttrs());
    probe = new SagaProbeImpl();

    Compensate.compensatesLookup = name -> "Ping".equals(name) ? "UndoPing" : null;
    Compensate.childSender = (c, childDst, msg) -> {
      sentNames.add(msg.getAttr("OrigMsgName"));
      sentDsts.add(childDst.toString());
      final int i = sentNames.size() - 1;
      final CompensateResult r = i < canned.size()
          ? canned.get(i)
          : CompensateResult.noop(ctx.contextId, childDst.toString(),
              msg.getAttr("OrigMsgName"), "already");
      final JsonMsg ack = new JsonMsg();
      ack.addResponseBody("Compensate", new ObjAttrs(r.toMap()));
      return ack;
    };
  }

  @AfterEach
  public void cleanup() throws Exception
  {
    Compensate.resetTestHooks();
    SagaProbeImpl.reset();
    OpLog.skipVisit(false);
    if (dbReady && hst != null)
    {
      exec("DELETE FROM op_msg WHERE HstId=?", hst);
      exec("DELETE FROM op_dst WHERE HstId=?", hst);
    }
  }

  @Test
  public void secondCompensate_isNoop_slotCompensated() throws Exception
  {
    assumeDb();
    seedPingVisit();

    final CompensateResult first = runCompensate();
    assertEquals(CompensateResult.OUTCOME_COMPENSATED, first.outcome);
    assertEquals(1, SagaProbeImpl.APPLY.get());

    final CompensateResult second = runCompensate();
    assertEquals(CompensateResult.OUTCOME_NOOP, second.outcome);
    assertEquals("already", second.reason);
    assertEquals(1, SagaProbeImpl.APPLY.get());
    assertEquals(SagaSlot.STATUS_COMPENSATED, pingStatus());
  }

  @Test
  public void retriedCompensate_newNonce_doesNotApplyTwice() throws Exception
  {
    assumeDb();
    seedPingVisit();
    markCompensating();

    probe.handleMsg(undoPing().toString(), obj, "/domatar", "", client);
    probe.handleMsg(undoPing().toString(), obj, "/domatar", "", client);
    assertEquals(1, SagaProbeImpl.APPLY.get());

    final CompensateResult first = runCompensate();
    assertEquals(CompensateResult.OUTCOME_COMPENSATED, first.outcome);
    assertEquals(1, SagaProbeImpl.APPLY.get());

    final CompensateResult second = runCompensate();
    assertEquals(CompensateResult.OUTCOME_NOOP, second.outcome);
    assertEquals(1, SagaProbeImpl.APPLY.get());
  }

  @Test
  public void attachUsesOrigMsgName_notCompensate() throws Exception
  {
    assumeDb();
    seedPingVisit();
    OpLogDb.upsertVisit(hst, ctx.contextId, dst.toString(), "Compensate",
        "actA", caller, "ACCOUNT", OpLog.nowMs(), TTL);
    OpLogDb.attach(hst, ctx.contextId, dst.toString(), "Compensate",
        SagaSlot.SLOT, "{\"status\":\"applied\",\"decoy\":true}",
        OpLog.nowMs() + TTL);

    final CompensateResult r = runCompensate();
    assertEquals(CompensateResult.OUTCOME_COMPENSATED, r.outcome);
    assertEquals(SagaSlot.STATUS_COMPENSATED, pingStatus());

    final JsonMap decoy = SagaSlot.parse(
        client.attachment(ctx.contextId, "Compensate", SagaSlot.SLOT));
    assertNotNull(decoy);
    assertEquals(SagaSlot.STATUS_APPLIED, SagaSlot.status(decoy));
    assertEquals(Boolean.TRUE, decoy.get("decoy"));
  }

  @Test
  public void spareCompensate_noVisit_isNoop() throws Exception
  {
    assumeDb();
    final CompensateResult r = runCompensate();
    assertEquals(CompensateResult.OUTCOME_NOOP, r.outcome);
    assertEquals("no visit", r.reason);
    assertEquals(0, SagaProbeImpl.APPLY.get());
  }

  @Test
  public void reverseSeq_A_B_A_sendsChildCompensateInReverse() throws Exception
  {
    assumeDb();
    seedPingVisit();
    final String a1 = hst + ".probe.actA.childA1";
    final String b = hst + ".probe.actA.childB";
    final String a2 = hst + ".probe.actA.childA2";
    final long t = OpLog.nowMs();
    OpLogDb.insertMsg(hst, ctx.contextId, dst.toString(), a1, "A", t);
    OpLogDb.insertMsg(hst, ctx.contextId, dst.toString(), b, "B", t + 1);
    OpLogDb.insertMsg(hst, ctx.contextId, dst.toString(), a2, "A", t + 2);

    final CompensateResult r = runCompensate();
    assertEquals(CompensateResult.OUTCOME_COMPENSATED, r.outcome);
    assertEquals(List.of("A", "B", "A"), sentNames);
    assertEquals(List.of(a2, b, a1), sentDsts);
  }

  @Test
  public void skipOutMsgNamedCompensate() throws Exception
  {
    assumeDb();
    seedPingVisit();
    final String pongDst = hst + ".probe.actA.pong";
    final String skipDst = hst + ".probe.actA.skip";
    final String pingDst = hst + ".probe.actA.ping";
    final long t = OpLog.nowMs();
    OpLogDb.insertMsg(hst, ctx.contextId, dst.toString(), pingDst, "Ping", t);
    OpLogDb.insertMsg(hst, ctx.contextId, dst.toString(), skipDst,
        "Compensate", t + 1);
    OpLogDb.insertMsg(hst, ctx.contextId, dst.toString(), pongDst, "Pong", t + 2);

    runCompensate();
    assertEquals(List.of("Pong", "Ping"), sentNames);
    assertEquals(List.of(pongDst, pingDst), sentDsts);
  }

  @Test
  public void childFailed_parentOutcomeFailed_siblingsStayCompensated()
      throws Exception
  {
    assumeDb();
    seedPingVisit();
    final String sib = hst + ".probe.actA.sib";
    final String fail = hst + ".probe.actA.fail";
    final long t = OpLog.nowMs();
    OpLogDb.insertMsg(hst, ctx.contextId, dst.toString(), sib, "Sib", t);
    OpLogDb.insertMsg(hst, ctx.contextId, dst.toString(), fail, "Fail", t + 1);

    canned.add(new CompensateResult(ctx.contextId, fail, "Fail",
        CompensateResult.OUTCOME_COMPENSATED, "", new ArrayList<>()));
    canned.add(CompensateResult.failed(ctx.contextId, sib, "Sib", "boom"));

    final CompensateResult r = runCompensate();
    assertEquals(CompensateResult.OUTCOME_FAILED, r.outcome);
    assertEquals("child", r.reason);
    assertEquals(2, r.children.size());
    assertEquals(CompensateResult.OUTCOME_COMPENSATED, r.children.get(0).outcome);
    assertEquals(CompensateResult.OUTCOME_FAILED, r.children.get(1).outcome);
    assertEquals(SagaSlot.STATUS_FAILED, pingStatus());
    assertEquals(0, SagaProbeImpl.APPLY.get());
  }

  @Test
  public void irreversibleGetX_childrenCascaded_localIrreversible()
      throws Exception
  {
    assumeDb();
    Compensate.compensatesLookup = name -> null;
    seedVisit("GetX");
    final String child = hst + ".probe.actA.child";
    OpLogDb.insertMsg(hst, ctx.contextId, dst.toString(), child, "Child",
        OpLog.nowMs());

    final CompensateResult r = runCompensate("GetX");
    assertEquals(CompensateResult.OUTCOME_IRREVERSIBLE, r.outcome);
    assertEquals(List.of("Child"), sentNames);
    assertEquals(0, SagaProbeImpl.APPLY.get());
    assertEquals(SagaSlot.STATUS_IRREVERSIBLE,
        SagaSlot.status(SagaSlot.parse(
            client.attachment(ctx.contextId, "GetX", SagaSlot.SLOT))));
  }

  @Test
  public void skipVisit_apply_doesNotInsertOpDstOrOpMsg() throws Exception
  {
    assumeDb();
    seedPingVisit();
    final int visitsBefore = OpLogDb.priorVisitCount(hst, ctx.contextId,
        dst.toString(), "UndoPing");
    final int edgesBefore = client.outMsgs(ctx.contextId).size();

    final CompensateResult r = runCompensate();
    assertEquals(CompensateResult.OUTCOME_COMPENSATED, r.outcome);
    assertEquals(Boolean.TRUE, SagaProbeImpl.skipVisitDuringApply);
    assertFalseSkipCleared();
    assertEquals(visitsBefore, OpLogDb.priorVisitCount(hst, ctx.contextId,
        dst.toString(), "UndoPing"));
    assertEquals(0, OpLogDb.priorVisitCount(hst, ctx.contextId, dst.toString(),
        "UndoPing"));
    assertEquals(edgesBefore, client.outMsgs(ctx.contextId).size());
  }

  private void assertFalseSkipCleared()
  {
    assertEquals(false, OpLog.isSkipVisit());
  }

  private void assumeDb()
  {
    assumeTrue(dbReady, "MySQL unreachable; skip CompensateRunTest");
  }

  private CompensateResult runCompensate() throws Exception
  {
    return runCompensate("Ping");
  }

  private CompensateResult runCompensate(final String origMsgName)
      throws Exception
  {
    final String ret = Compensate.run(probe, compensateMsg(origMsgName), obj,
        client);
    return CompensateResult.fromMsg(new JsonMsg(ret));
  }

  private JsonMsg compensateMsg(final String origMsgName) throws DomatarException
  {
    final JsonMsg m = new JsonMsg();
    m.addRequestHead(dst, dst, ctx);
    final ObjAttrs attrs = new ObjAttrs();
    attrs.addAttr("OrigContextId", ctx.contextId);
    attrs.addAttr("OrigMsgName", origMsgName);
    m.addRequestBody("Compensate", attrs);
    return m;
  }

  private JsonMsg undoPing() throws DomatarException
  {
    final JsonMsg m = new JsonMsg();
    m.addRequestHead(dst, dst, ctx);
    final ObjAttrs attrs = new ObjAttrs();
    attrs.addAttr("OrigContextId", ctx.contextId);
    m.addRequestBody("UndoPing", attrs);
    return m;
  }

  private void seedPingVisit() throws Exception
  {
    seedVisit("Ping");
    final JsonHashMap effect = new JsonHashMap();
    effect.put("OrigContextId", ctx.contextId);
    client.attach(ctx.contextId, "Ping", SagaSlot.SLOT,
        SagaSlot.applied("UndoPing", effect), OpLog.nowMs() + TTL);
  }

  private void seedVisit(final String msgName) throws Exception
  {
    client.snapshotPriors(ctx.contextId, dst.toString(), msgName);
    final JsonMsg visitMsg = new JsonMsg();
    visitMsg.addRequestHead(dst, dst, ctx);
    visitMsg.addRequestBody(msgName, new ObjAttrs());
    assertTrue(OpLog.admitIfNeeded(client, visitMsg, obj, prov));
  }

  private void markCompensating() throws Exception
  {
    final JsonMap slot = SagaSlot.parse(
        client.attachment(ctx.contextId, "Ping", SagaSlot.SLOT));
    client.attach(ctx.contextId, "Ping", SagaSlot.SLOT,
        SagaSlot.withStatus(slot, SagaSlot.STATUS_COMPENSATING),
        OpLog.nowMs() + TTL);
  }

  private String pingStatus() throws Exception
  {
    return SagaSlot.status(SagaSlot.parse(
        client.attachment(ctx.contextId, "Ping", SagaSlot.SLOT)));
  }

  private HandlerClient inbound()
  {
    return TestClients.inbound(dst, "localhost", ctx, "/domatar", "", prov);
  }

  private static byte[] bodyBytes(final String op) throws DomatarException
  {
    final JsonMap body = new JsonHashMap();
    body.put("Operation", op);
    return CanonicalJson.canonicalize(body);
  }

  private static void exec(final String sql, final String hstId) throws Exception
  {
    try (Connection c = DriverManager.getConnection(jdbcUrl + "&serverTimezone=UTC");
         PreparedStatement pstmt = c.prepareStatement(sql))
    {
      pstmt.setString(1, hstId);
      pstmt.executeUpdate();
    }
  }
}
