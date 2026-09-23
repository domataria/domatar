package com.domatar.log;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
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
import com.domatar.util.DomId;
import com.domatar.util.DomatarException;
import com.domatar.util.JsonHashMap;
import com.domatar.util.JsonMap;
import com.domatar.util.JsonMsg;
import com.domatar.util.ObjAttrs;

public class OpLogClientTest
{
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
  private Context ctx;
  private Provenance prov;
  private HandlerClient client;
  private JsonMsg ping;

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
    final String n = Long.toHexString(System.nanoTime());
    hst = "oplog-c-" + n;
    final DomId ui = new DomId(hst, "ui", "act", "uiObj");
    dst = new DomId(hst, "app", "act", "objA");
    final Path path = Path.root(ui, dst, bodyBytes("Ping"), "actA", "testprv");
    prov = Provenance.of(path, null, null);
    ctx = TestClients.account("actA", "u", "n", "127.0.0.1", "tok", path.contextId());
    client = inbound();
    ping = msg("Ping");
  }

  @AfterEach
  public void cleanup() throws Exception
  {
    OpLog.skipVisit(false);
    OpLog.skipVisit(false);
    if (dbReady && hst != null)
    {
      exec("DELETE FROM op_msg WHERE HstId=?", hst);
      exec("DELETE FROM op_dst WHERE HstId=?", hst);
    }
  }

  @Test
  public void alreadyEntered_falseThenTrue_acrossTwoAdmits() throws Exception
  {
    assumeTrue(dbReady, "DbConnection unset / MySQL unreachable; skip OpLogClientTest");

    assertFalse(client.alreadyEntered());
    client.snapshotPriors(ctx.contextId, dst.toString(), "Ping");
    assertFalse(client.alreadyEntered());
    assertEquals(0, client.priorVisitCount());
    assertTrue(OpLog.admitIfNeeded(client, ping, null, prov));
    assertFalse(client.alreadyEntered());
    assertEquals(0, client.priorVisitCount());
    assertEquals(1, OpLogDb.priorVisitCount(hst, ctx.contextId, dst.toString(),
        "Ping"));

    final HandlerClient second = inbound();
    second.snapshotPriors(ctx.contextId, dst.toString(), "Ping");
    assertTrue(second.alreadyEntered());
    assertEquals(1, second.priorVisitCount());
    assertFalse(OpLog.admitIfNeeded(second, ping, null, prov));
    assertTrue(second.alreadyEntered());
    assertEquals(1, second.priorVisitCount());
    assertEquals(2, OpLogDb.priorVisitCount(hst, ctx.contextId, dst.toString(),
        "Ping"));
  }

  @Test
  public void attach_contextIdMsgName_writesOrigVisit_notCompensate()
      throws Exception
  {
    assumeTrue(dbReady, "DbConnection unset / MySQL unreachable; skip OpLogClientTest");

    client.snapshotPriors(ctx.contextId, dst.toString(), "Ping");
    OpLog.admitIfNeeded(client, ping, null, prov);

    final HandlerClient compensate = inbound();
    compensate.snapshotPriors(ctx.contextId, dst.toString(), "Compensate");

    final JsonMap saga = new JsonHashMap();
    saga.put("undo", "Ping");
    final long exp = System.currentTimeMillis() + 3_600_000L;
    compensate.attach("saga", saga, exp);
    assertNull(compensate.attachment("saga"));

    compensate.attach(ctx.contextId, "Ping", "saga", saga, exp);
    assertNull(compensate.attachment("saga"));
    final Object body = compensate.attachment(ctx.contextId, "Ping", "saga");
    assertInstanceOf(JsonMap.class, body);
    assertEquals("Ping", ((JsonMap) body).getString("undo"));
    assertInstanceOf(JsonMap.class, client.attachment("saga"));
  }

  @Test
  public void attach_rejectsByteArray() throws Exception
  {
    assumeTrue(dbReady, "DbConnection unset / MySQL unreachable; skip OpLogClientTest");

    client.snapshotPriors(ctx.contextId, dst.toString(), "Ping");
    OpLog.admitIfNeeded(client, ping, null, prov);
    client.attach("saga", new byte[] { 1, 2 }, System.currentTimeMillis() + 1000);
    assertNull(client.attachment("saga"));
  }

  @Test
  public void attach_null_deletesSlot() throws Exception
  {
    assumeTrue(dbReady, "DbConnection unset / MySQL unreachable; skip OpLogClientTest");

    client.snapshotPriors(ctx.contextId, dst.toString(), "Ping");
    OpLog.admitIfNeeded(client, ping, null, prov);
    final JsonMap saga = new JsonHashMap();
    saga.put("s", Integer.valueOf(1));
    client.attach("saga", saga, System.currentTimeMillis() + 1000);
    assertInstanceOf(JsonMap.class, client.attachment("saga"));
    client.attach("saga", null, 0);
    assertNull(client.attachment("saga"));
  }

  @Test
  public void outMsgs_orderSeqAsc() throws Exception
  {
    assumeTrue(dbReady, "DbConnection unset / MySQL unreachable; skip OpLogClientTest");

    client.snapshotPriors(ctx.contextId, dst.toString(), "Ping");
    OpLog.admitIfNeeded(client, ping, null, prov);
    final long t = System.currentTimeMillis();
    OpLogDb.insertMsg(hst, ctx.contextId, dst.toString(), dst.toString(),
        "Ping", t);
    OpLogDb.insertMsg(hst, ctx.contextId, dst.toString(), dst.toString(),
        "Pong", t + 1);
    final List<OpMsg> edges = client.outMsgs();
    assertEquals(2, edges.size());
    assertEquals(1, edges.get(0).seq);
    assertEquals(2, edges.get(1).seq);
    assertEquals("Ping", edges.get(0).outMsgName);
    assertEquals("Pong", edges.get(1).outMsgName);
  }

  private HandlerClient inbound()
  {
    return TestClients.inbound(dst, "localhost", ctx, "/domatar", "", prov);
  }

  private JsonMsg msg(final String op) throws DomatarException
  {
    final JsonMsg m = new JsonMsg();
    m.addRequestHead(dst, dst, ctx);
    m.addClsId("desktop", "desktop");
    m.addRequestBody(op, new ObjAttrs());
    return m;
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
