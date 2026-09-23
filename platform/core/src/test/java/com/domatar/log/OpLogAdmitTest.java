package com.domatar.log;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

public class OpLogAdmitTest
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
  private DomId ui;
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
    hst = "oplog-a-" + n;
    ui = new DomId(hst, "ui", "act", "uiObj");
    dst = new DomId(hst, "app", "act", "objA");
    final byte[] body = bodyBytes("Ping");
    final Path path = Path.root(ui, dst, body, "actA", "testprv");
    prov = Provenance.of(path, null, null);
    ctx = TestClients.account("actA", "u", "n", "127.0.0.1", "tok", path.contextId());
    client = TestClients.inbound(dst, "localhost", ctx, "/domatar", "", prov);
    ping = msg("desktop", "desktop", "Ping", dst);
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
  public void skipVisit_doesNotInsert() throws Exception
  {
    assumeTrue(dbReady, "DbConnection unset / MySQL unreachable; skip OpLogAdmitTest");

    OpLog.skipVisit(true);
    try
    {
      assertFalse(OpLog.admitIfNeeded(client, ping, null, prov));
      assertEquals(0, OpLogDb.priorVisitCount(hst, ctx.contextId, dst.toString(),
          "Ping"));
    }
    finally
    {
      OpLog.skipVisit(false);
    }

    assertTrue(OpLog.admitIfNeeded(client, ping, null, prov));
    assertEquals(1, OpLogDb.priorVisitCount(hst, ctx.contextId, dst.toString(),
        "Ping"));
  }

  @Test
  public void directoryCls_doesNotInsert() throws Exception
  {
    final JsonMsg dir = msg("hst", "hsts", "GetHst", dst);
    assertTrue(OpLog.isDirectory(dir));
    assertFalse(OpLog.isDirectory(ping));

    if (!dbReady)
      return;

    assertFalse(OpLog.admitIfNeeded(client, dir, null, prov));
    assertEquals(0, OpLogDb.priorVisitCount(hst, ctx.contextId, dst.toString(),
        "GetHst"));
  }

  @Test
  public void insertMsg_survivesCalleeDeny() throws Exception
  {
    assumeTrue(dbReady, "DbConnection unset / MySQL unreachable; skip OpLogAdmitTest");

    final String prevHst = System.getProperty("domatar.hstid");
    System.setProperty("domatar.hstid", hst);
    try
    {
      final DomId missing = new DomId(hst, "app", "act", "missing");
      final JsonMsg bare = new JsonMsg();
      bare.addRequestBody("Ping", new ObjAttrs());
      client.send(missing, bare);
      final List<OpMsg> edges = OpLogDb.listOutMsgs(hst, ctx.contextId, dst.toString());
      assertEquals(1, edges.size());
      assertEquals("Ping", edges.get(0).outMsgName);
      assertEquals(missing.toString(), edges.get(0).dstDomId);
      assertEquals(0, OpLogDb.priorVisitCount(hst, ctx.contextId,
          missing.toString(), "Ping"));
    }
    finally
    {
      if (prevHst == null)
        System.clearProperty("domatar.hstid");
      else
        System.setProperty("domatar.hstid", prevHst);
    }
  }

  @Test
  public void twoSends_sameDest_twoRows() throws Exception
  {
    assumeTrue(dbReady, "DbConnection unset / MySQL unreachable; skip OpLogAdmitTest");

    final String prevHst = System.getProperty("domatar.hstid");
    System.setProperty("domatar.hstid", hst);
    try
    {
      client.send(dst, ping);
      client.send(dst, ping);
      final List<OpMsg> edges = OpLogDb.listOutMsgs(hst, ctx.contextId, dst.toString());
      assertEquals(2, edges.size());
      assertEquals(1, edges.get(0).seq);
      assertEquals(2, edges.get(1).seq);
      assertEquals(dst.toString(), edges.get(0).dstDomId);
      assertEquals(dst.toString(), edges.get(1).dstDomId);
    }
    finally
    {
      if (prevHst == null)
        System.clearProperty("domatar.hstid");
      else
        System.setProperty("domatar.hstid", prevHst);
    }
  }

  private static JsonMsg msg(final String clsAppId, final String clsId,
      final String op, final DomId dstId) throws DomatarException
  {
    final JsonMsg m = new JsonMsg();
    m.addRequestHead(dstId, dstId, TestClients.account("actA", "u", "n", "127.0.0.1",
        "tok", "ctx"));
    m.addClsId(clsAppId, clsId);
    m.addRequestBody(op, new ObjAttrs());
    return m;
  }

  private static byte[] bodyBytes(final String op) throws DomatarException
  {
    final JsonMap body = new JsonHashMap();
    body.put("Operation", op);
    return CanonicalJson.canonicalize(body);
  }

  private static void exec(final String sql, final String hstId)
      throws Exception
  {
    try (Connection c = DriverManager.getConnection(jdbcUrl + "&serverTimezone=UTC");
         PreparedStatement pstmt = c.prepareStatement(sql))
    {
      pstmt.setString(1, hstId);
      pstmt.executeUpdate();
    }
  }
}
