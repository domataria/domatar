package com.domatar.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.domatar.crypto.CanonicalJson;
import com.domatar.crypto.Path;
import com.domatar.crypto.Provenance;
import com.domatar.db.DbConnection;
import com.domatar.db.OpLogDb;
import com.domatar.install.OpLogInstall;
import com.domatar.log.OpLog;
import com.domatar.util.DomId;
import com.domatar.util.DomatarException;
import com.domatar.util.DomatarMsgClient;
import com.domatar.util.JsonHashMap;
import com.domatar.util.JsonMap;
import com.domatar.util.JsonMsg;
import com.domatar.util.Obj;
import com.domatar.util.ObjAttrs;
import com.domatar.util.ObjImpl;
import com.domatar.util.Rights;

public class ReentryPolicyTest
{
  private static final String[] URLS = {
      System.getenv("DOMATAR_DB_URL"),
      System.getProperty("DOMATAR_DB_URL"),
      "jdbc:mysql://127.0.0.1:9307/domatar?user=root&password=domatar",
      "jdbc:mysql://127.0.0.1:3306/domatar?user=root&password=domatar"
  };

  private static final String PRV = "testprv";
  private static final String HST = "reentry-mem";

  private static boolean dbReady;
  private static String jdbcUrl;

  private String hst;

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
  public void host()
  {
    hst = "reentry-" + Long.toHexString(System.nanoTime());
  }

  @AfterEach
  public void cleanup() throws Exception
  {
    OpLog.skipVisit(false);
    ImplMap.clear();
    if (dbReady && hst != null)
    {
      exec("DELETE FROM op_msg WHERE HstId=?");
      exec("DELETE FROM op_dst WHERE HstId=?");
    }
  }

  @Test
  public void emptyPath_inOwnPathFalse() throws Exception
  {
    final HandlerClient client = clientAt(objA(), Provenance.empty());
    assertFalse(client.inOwnPath());
  }

  @Test
  public void rootHop_inOwnPathFalse() throws Exception
  {
    final Path path = Path.root(ui(), objA(), bodyBytes("Ping"), "actA", PRV);
    final HandlerClient client = clientAt(objA(), Provenance.of(path, null, null));
    assertFalse(client.inOwnPath());
  }

  @Test
  public void srcEqualsDest_singleHop_false() throws Exception
  {
    final Path path = Path.root(objA(), objA(), bodyBytes("Ping"), "actA", PRV);
    final HandlerClient client = clientAt(objA(), Provenance.of(path, null, null));
    assertFalse(client.inOwnPath());
  }

  @Test
  public void fanOut_bNotOnOwnPath() throws Exception
  {
    final Path path = Path.root(ui(), objA(), bodyBytes("Ping"), "actA", PRV)
        .append(objA(), objB(), bodyBytes("Pong"), PRV);
    final HandlerClient client = clientAt(objB(), Provenance.of(path, null, null));
    assertFalse(client.inOwnPath());
  }

  @Test
  public void cycle_secondA_true() throws Exception
  {
    final Path path = Path.root(ui(), objA(), bodyBytes("Ping"), "actA", PRV)
        .append(objA(), objB(), bodyBytes("Pong"), PRV)
        .append(objB(), objA(), bodyBytes("Ping"), PRV);
    final HandlerClient client = clientAt(objA(), Provenance.of(path, null, null));
    assertTrue(client.inOwnPath());
  }

  @Test
  public void directRevisit_secondHop_true() throws Exception
  {
    final Path path = Path.root(ui(), objA(), bodyBytes("Ping"), "actA", PRV)
        .append(objA(), objA(), bodyBytes("Ping"), PRV);
    final HandlerClient client = clientAt(objA(), Provenance.of(path, null, null));
    assertTrue(client.inOwnPath());
  }

  @Test
  public void cycle_defaultDeny_noSecondVisit() throws Exception
  {
    assumeTrue(dbReady, "MySQL unreachable; skip ReentryPolicyTest");

    final ReentryNodeImpl node = new ReentryNodeImpl();
    ImplMap.register("reentry", "node", node);

    final DomId ui = dom(hst, "ui", "uiObj");
    final DomId a = dom(hst, "app", "objA");
    final DomId b = dom(hst, "app", "objB");
    final Path hop1 = Path.root(ui, a, bodyBytes("Ping"), "actA", PRV);
    final Path hop2 = hop1.append(a, b, bodyBytes("Pong"), PRV);
    final Path hop3 = hop2.append(b, a, bodyBytes("Ping"), PRV);
    final String contextId = hop1.contextId();
    final Context ctx = account(contextId);

    assertFalse(clientAt(a, Provenance.of(hop1, null, null)).inOwnPath());
    final String first = deliver(a, ping(a, ctx, "node"), ctx, hop1);
    assertFalse(first.contains("Not authorized"));
    assertEquals(1, visits(contextId, a));
    // The door and handleMsg each call hasRights.
    assertEquals(2, node.hasRightsCalls);

    node.hasRightsCalls = 0;
    assertFalse(clientAt(b, Provenance.of(hop2, null, null)).inOwnPath());
    deliver(b, ping(b, ctx, "node"), ctx, hop2);
    assertEquals(1, visits(contextId, b));
    assertEquals(2, node.hasRightsCalls);

    node.hasRightsCalls = 0;
    assertTrue(clientAt(a, Provenance.of(hop3, null, null)).inOwnPath());
    final String third = deliver(a, ping(a, ctx, "node"), ctx, hop3);
    assertTrue(third.contains("Not authorized"));
    assertEquals(1, visits(contextId, a));
    assertEquals(0, node.hasRightsCalls);
  }

  @Test
  public void cycle_overrideAdmits_visitWritten() throws Exception
  {
    assumeTrue(dbReady, "MySQL unreachable; skip ReentryPolicyTest");

    ImplMap.register("reentry", "recurse", new ReentryRecurseImpl());

    final DomId ui = dom(hst, "ui", "uiObj");
    final DomId a = dom(hst, "app", "objA");
    final DomId b = dom(hst, "app", "objB");
    final Path hop1 = Path.root(ui, a, bodyBytes("Ping"), "actA", PRV);
    final Path hop2 = hop1.append(a, b, bodyBytes("Pong"), PRV);
    final Path hop3 = hop2.append(b, a, bodyBytes("Ping"), PRV);
    final Context ctx = account(hop1.contextId());

    deliver(a, ping(a, ctx, "recurse"), ctx, hop1);
    deliver(b, ping(b, ctx, "recurse"), ctx, hop2);
    final String third = deliver(a, ping(a, ctx, "recurse"), ctx, hop3);
    assertFalse(third.contains("Not authorized"));
    assertEquals(2, visits(hop1.contextId(), a));
  }

  @Test
  public void directoryEnvelope_inOwnPath_stillAdmits() throws Exception
  {
    final Path path = Path.root(ui(), objA(), bodyBytes("Ping"), "actA", PRV)
        .append(objA(), objB(), bodyBytes("Pong"), PRV)
        .append(objB(), objA(), bodyBytes("Ping"), PRV);
    final HandlerClient client = clientAt(objA(), Provenance.of(path, null, null));
    assertTrue(client.inOwnPath());

    final JsonMsg dir = new JsonMsg();
    dir.addClsId("hst", "hsts");
    dir.addRequestBody("GetHst", new ObjAttrs());
    assertEquals(Rights.ADMIT, new ObjImpl().rights(dir, null, client));

    final JsonMsg ping = new JsonMsg();
    ping.addClsId("reentry", "node");
    ping.addRequestBody("Ping", new ObjAttrs());
    assertEquals(Rights.DENY, new ObjImpl().rights(ping, null, client));
  }

  @Test
  public void r1_twoContextIds_alreadyEnteredFalse_twoRows() throws Exception
  {
    assumeTrue(dbReady, "MySQL unreachable; skip ReentryPolicyTest");

    final DomId ui = dom(hst, "ui", "uiObj");
    final DomId a = dom(hst, "app", "objA");
    final Path firstPath = Path.root(ui, a, bodyBytes("Ping"), "actA", PRV);
    final Path secondPath = Path.root(ui, a, bodyBytes("Ping"), "actA", PRV);
    final Provenance firstProv = Provenance.of(firstPath, null, null);
    final Provenance secondProv = Provenance.of(secondPath, null, null);
    final HandlerClient first = clientAt(a, firstProv);
    final HandlerClient second = clientAt(a, secondProv);

    first.snapshotPriors(firstPath.contextId(), a.toString(), "Ping");
    second.snapshotPriors(secondPath.contextId(), a.toString(), "Ping");
    OpLog.admitIfNeeded(first, ping(a, account(firstPath.contextId()), "node"),
        null, firstProv);
    OpLog.admitIfNeeded(second, ping(a, account(secondPath.contextId()), "node"),
        null, secondProv);

    assertFalse(first.alreadyEntered());
    assertFalse(second.alreadyEntered());
    assertEquals(1, OpLogDb.priorVisitCount(hst, firstPath.contextId(), a.toString(), "Ping"));
    assertEquals(1, OpLogDb.priorVisitCount(hst, secondPath.contextId(), a.toString(), "Ping"));
  }

  @Test
  public void r2_sameMsg_secondAlreadyEntered_visitCount2_notACycle() throws Exception
  {
    assumeTrue(dbReady, "MySQL unreachable; skip ReentryPolicyTest");

    final DomId ui = dom(hst, "ui", "uiObj");
    final DomId a = dom(hst, "app", "objA");
    final Path path = Path.root(ui, a, bodyBytes("Ping"), "actA", PRV);
    final Provenance prov = Provenance.of(path, null, null);
    final Context ctx = account(path.contextId());
    final JsonMsg ping = ping(a, ctx, "node");

    final HandlerClient first = clientAt(a, prov);
    assertFalse(first.alreadyEntered());
    first.snapshotPriors(path.contextId(), a.toString(), "Ping");
    assertFalse(first.alreadyEntered());
    OpLog.admitIfNeeded(first, ping, null, prov);
    assertFalse(first.alreadyEntered());
    assertEquals(0, first.priorVisitCount());
    assertEquals(1, OpLogDb.priorVisitCount(hst, path.contextId(), a.toString(), "Ping"));

    final HandlerClient second = clientAt(a, prov);
    second.snapshotPriors(path.contextId(), a.toString(), "Ping");
    assertTrue(second.alreadyEntered());
    assertEquals(1, second.priorVisitCount());
    assertFalse(second.inOwnPath());
    OpLog.admitIfNeeded(second, ping, null, prov);
    assertEquals(2, OpLogDb.priorVisitCount(hst, path.contextId(), a.toString(), "Ping"));
    assertTrue(second.alreadyEntered());
    assertEquals(1, second.priorVisitCount());
  }

  @Test
  public void r2_differentMsg_alreadyEnteredFalse_priorAnyPositive() throws Exception
  {
    assumeTrue(dbReady, "MySQL unreachable; skip ReentryPolicyTest");

    final DomId ui = dom(hst, "ui", "uiObj");
    final DomId a = dom(hst, "app", "objA");
    final Path path = Path.root(ui, a, bodyBytes("Ping"), "actA", PRV);
    final Provenance prov = Provenance.of(path, null, null);
    final Context ctx = account(path.contextId());

    final HandlerClient ping = clientAt(a, prov);
    ping.snapshotPriors(path.contextId(), a.toString(), "Ping");
    OpLog.admitIfNeeded(ping, message(a, ctx, "node", "Ping"), null, prov);

    final HandlerClient pong = clientAt(a, prov);
    pong.snapshotPriors(path.contextId(), a.toString(), "Pong");
    assertFalse(pong.alreadyEntered());
    assertEquals(0, pong.priorVisitCount());
    assertTrue(pong.priorVisitCountAny() > 0);
    assertFalse(pong.inOwnPath());
    OpLog.admitIfNeeded(pong, message(a, ctx, "node", "Pong"), null, prov);

    assertEquals(1, OpLogDb.priorVisitCount(hst, path.contextId(), a.toString(), "Ping"));
    assertEquals(1, OpLogDb.priorVisitCount(hst, path.contextId(), a.toString(), "Pong"));
  }

  @Test
  public void handleMsg_snapshotIsBeforeThisDispatch() throws Exception
  {
    assumeTrue(dbReady, "MySQL unreachable; skip ReentryPolicyTest");

    final ReentryNodeImpl node = new ReentryNodeImpl();
    ImplMap.register("reentry", "node", node);

    final DomId ui = dom(hst, "ui", "uiObj");
    final DomId a = dom(hst, "app", "objA");
    final Path path = Path.root(ui, a, bodyBytes("Ping"), "actA", PRV);
    final Context ctx = account(path.contextId());

    deliver(a, ping(a, ctx, "node"), ctx, path);
    assertFalse(node.seenEntered);
    assertEquals(0, node.seenPrior);
    assertEquals(1, visits(path.contextId(), a));

    deliver(a, ping(a, ctx, "node"), ctx, path);
    assertTrue(node.seenEntered);
    assertEquals(1, node.seenPrior);
    assertEquals(2, visits(path.contextId(), a));
  }

  @Test
  public void depthCap_sendThrowsBeforeHasRights() throws Exception
  {
    System.setProperty("domatar.max.hop.depth", "1");
    // send signs with getPrvId before Path checks depth.
    System.setProperty("domatar.prvid", PRV);
    try
    {
      final ReentryNodeImpl node = new ReentryNodeImpl();
      ImplMap.register("reentry", "node", node);

      final DomId ui = dom(hst, "ui", "uiObj");
      final DomId a = dom(hst, "app", "objA");
      final DomId b = dom(hst, "app", "objB");
      final Path path = Path.root(ui, a, bodyBytes("Ping"), "actA", PRV);
      final HandlerClient client = clientAt(a, Provenance.of(path, null, null));
      final JsonMsg ping = ping(b, account(path.contextId()), "node");

      final DomatarException ex = assertThrows(DomatarException.class,
          () -> client.send(b, ping));
      assertEquals("hop depth cap exceeded", ex.getMessage());
      assertEquals(0, node.hasRightsCalls);
    }
    finally
    {
      System.clearProperty("domatar.max.hop.depth");
      System.clearProperty("domatar.prvid");
    }
  }

  private String deliver(final DomId dst, final JsonMsg msg, final Context ctx,
      final Path path) throws DomatarException
  {
    return HttpClient.deliverLocal(dst, msg, ctx, "localhost", "/domatar", "",
        Provenance.of(path, null, null));
  }

  private JsonMsg ping(final DomId dst, final Context ctx, final String clsId)
      throws DomatarException
  {
    return message(dst, ctx, clsId, "Ping");
  }

  private JsonMsg message(final DomId dst, final Context ctx, final String clsId,
      final String op) throws DomatarException
  {
    final JsonMsg m = new JsonMsg();
    m.addRequestHead(dst, dst, ctx);
    m.addClsId("reentry", clsId);
    m.addRequestBody(op, new ObjAttrs());
    return m;
  }

  private int visits(final String contextId, final DomId dst) throws DomatarException
  {
    return OpLogDb.priorVisitCount(hst, contextId, dst.toString(), "Ping");
  }

  private static Context account(final String contextId)
  {
    return TestClients.account("actA", "u", "n", "127.0.0.1", "tok", contextId);
  }

  private static HandlerClient clientAt(final DomId dst, final Provenance prov)
      throws DomatarException
  {
    final Context ctx = TestClients.account("actA", "u", "n", "127.0.0.1", "tok",
        prov.contextId());
    return TestClients.inbound(dst, "localhost", ctx, "/domatar", "", prov);
  }

  private static DomId dom(final String host, final String app, final String obj)
      throws DomatarException
  {
    return new DomId(host, app, "act", obj);
  }

  private static DomId ui() throws DomatarException
  {
    return new DomId(HST, "ui", "act", "uiObj");
  }

  private static DomId objA() throws DomatarException
  {
    return new DomId(HST, "app", "act", "objA");
  }

  private static DomId objB() throws DomatarException
  {
    return new DomId(HST, "app", "act", "objB");
  }

  private static byte[] bodyBytes(final String op) throws DomatarException
  {
    final JsonMap body = new JsonHashMap();
    body.put("Operation", op);
    return CanonicalJson.canonicalize(body);
  }

  private void exec(final String sql) throws Exception
  {
    try (Connection c = DriverManager.getConnection(jdbcUrl + "&serverTimezone=UTC");
         PreparedStatement pstmt = c.prepareStatement(sql))
    {
      pstmt.setString(1, hst);
      pstmt.executeUpdate();
    }
  }

  static final class ReentryNodeImpl extends ObjImpl
  {
    public int hasRightsCalls;
    public boolean seenEntered;
    public int seenPrior;

    @Override
    public boolean hasRights(final JsonMsg inMsg, final Obj obj,
        final DomatarMsgClient msgClient) throws DomatarException
    {
      hasRightsCalls++;
      return true;
    }

    @Override
    public String handleMsg(final String msg, final Obj obj, final String contextPath,
        final String contextRealPath, final DomatarMsgClient msgClient)
        throws DomatarException
    {
      final JsonMsg inMsg = new JsonMsg(msg);
      if ("Compensate".equals(inMsg.getOperation()))
        return super.handleMsg(msg, obj, contextPath, contextRealPath, msgClient);

      seenEntered = msgClient.alreadyEntered();
      seenPrior = msgClient.priorVisitCount();
      return super.handleMsg(msg, obj, contextPath, contextRealPath, msgClient);
    }
  }

  static final class ReentryRecurseImpl extends ObjImpl
  {
    public int hasRightsCalls;

    @Override
    public boolean hasRights(final JsonMsg inMsg, final Obj obj,
        final DomatarMsgClient msgClient) throws DomatarException
    {
      hasRightsCalls++;
      return true;
    }

    @Override
    public Rights rights(final JsonMsg inMsg, final Obj obj,
        final DomatarMsgClient msgClient) throws DomatarException
    {
      return rightsIgnoringCycle(inMsg, obj, msgClient);
    }

    @Override
    public String handleMsg(final String msg, final Obj obj, final String contextPath,
        final String contextRealPath, final DomatarMsgClient msgClient)
        throws DomatarException
    {
      final JsonMsg inMsg = new JsonMsg(msg);
      if ("Compensate".equals(inMsg.getOperation()))
        return super.handleMsg(msg, obj, contextPath, contextRealPath, msgClient);
      return super.handleMsg(msg, obj, contextPath, contextRealPath, msgClient);
    }
  }
}
