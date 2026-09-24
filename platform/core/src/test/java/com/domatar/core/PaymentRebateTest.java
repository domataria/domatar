package com.domatar.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import com.domatar.db.PayDb;
import com.domatar.install.OpLogInstall;
import com.domatar.install.PaymentInstall;
import com.domatar.log.OpLog;
import com.domatar.pay.Payment;
import com.domatar.pay.PaymentProbeImpl;
import com.domatar.pay.PaymentSlot;
import com.domatar.util.DomId;
import com.domatar.util.DomatarException;
import com.domatar.util.Json;
import com.domatar.util.JsonArrayList;
import com.domatar.util.JsonHashMap;
import com.domatar.util.JsonMap;
import com.domatar.util.JsonMsg;
import com.domatar.util.ObjAttrs;
import com.domatar.util.Rights;

public class PaymentRebateTest
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
  private PaymentProbeImpl probe;

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
      PaymentInstall.ensureTable();
    }
  }

  @BeforeEach
  public void ids() throws Exception
  {
    final String n = Long.toHexString(System.nanoTime());
    hst = "payreb-" + n;
    final DomId ui = new DomId(hst, "ui", "payerA", "uiObj");
    dst = new DomId(hst, "payprobe", "payeeA", "objA");
    final byte[] body = bodyBytes("Ping");
    final Path path = Path.root(ui, dst, body, "payerA", "testprv");
    prov = Provenance.of(path, null, null);
    ctx = TestClients.account("payerA", "u", "n", "127.0.0.1", "tok", path.contextId());
    client = TestClients.inbound(dst, "localhost", ctx, "/domatar", "", prov);
    probe = new PaymentProbeImpl();
    probe.allow = true;
    ImplMap.register("payprobe", "item", probe);
    ClsMap.put("payprobe", "item", pricedCls());
  }

  @AfterEach
  public void cleanup() throws Exception
  {
    ImplMap.clear();
    ClsMap.invalidate("payprobe", "item");
    if (dbReady && hst != null)
    {
      exec("DELETE FROM op_msg WHERE HstId=?", hst);
      exec("DELETE FROM op_dst WHERE HstId=?", hst);
      exec("DELETE FROM pay_bal WHERE HstId=?", hst);
    }
  }

  @Test
  public void rebateVisit_restoresAndMarksRebated() throws Exception
  {
    assumeTrue(dbReady, "MySQL unreachable; skip PaymentRebateTest");

    draw();
    assertEquals(Long.valueOf(15L), PayDb.remaining(hst, "payeeA", "payerA"));
    Payment.rebateVisit(ctx.contextId, "Ping", client);
    assertEquals(Long.valueOf(20L), PayDb.remaining(hst, "payeeA", "payerA"));
    assertRebated(slot("Ping"));
  }

  @Test
  public void secondRebate_doesNotRestoreTwice() throws Exception
  {
    assumeTrue(dbReady, "MySQL unreachable; skip PaymentRebateTest");

    draw();
    Payment.rebateVisit(ctx.contextId, "Ping", client);
    Payment.rebateVisit(ctx.contextId, "Ping", client);
    assertEquals(Long.valueOf(20L), PayDb.remaining(hst, "payeeA", "payerA"));
    assertRebated(slot("Ping"));
  }

  @Test
  public void missingSlot_noop() throws Exception
  {
    assumeTrue(dbReady, "MySQL unreachable; skip PaymentRebateTest");

    probe.scripted = null;
    deliver(msg("Ping"));
    PayDb.credit(hst, "payeeA", "payerA", 9L, 1000L);
    Payment.rebateVisit(ctx.contextId, "Ping", client);
    assertEquals(Long.valueOf(9L), PayDb.remaining(hst, "payeeA", "payerA"));
    assertNull(slot("Ping"));
  }

  @Test
  public void rebate_usesOrigMsgName_notCompensate() throws Exception
  {
    assumeTrue(dbReady, "MySQL unreachable; skip PaymentRebateTest");

    draw();
    Payment.rebateVisit(ctx.contextId, "Compensate", client);
    assertEquals(Long.valueOf(15L), PayDb.remaining(hst, "payeeA", "payerA"));
    assertEquals(PaymentSlot.STATUS_DRAWN, PaymentSlot.status(slot("Ping")));
    Payment.rebateVisit(ctx.contextId, "Ping", client);
    assertEquals(Long.valueOf(20L), PayDb.remaining(hst, "payeeA", "payerA"));
    assertRebated(slot("Ping"));
  }

  @Test
  public void handlerCannotForgeRebate() throws Exception
  {
    assumeTrue(dbReady, "MySQL unreachable; skip PaymentRebateTest");

    draw();
    final JsonMap forged = PaymentSlot.rebated(slot("Ping"));
    client.attach(ctx.contextId, "Ping", PaymentSlot.SLOT, forged, OpLog.nowMs() + 60000L);
    assertEquals(PaymentSlot.STATUS_DRAWN, PaymentSlot.status(slot("Ping")));
    Payment.rebateVisit(ctx.contextId, "Ping", client);
    assertEquals(Long.valueOf(20L), PayDb.remaining(hst, "payeeA", "payerA"));
    assertRebated(slot("Ping"));
  }

  private void draw() throws Exception
  {
    probe.scripted = Rights.PRICED;
    PayDb.credit(hst, "payeeA", "payerA", 20L, 1000L);
    deliver(msg("Ping"));
  }

  private void assertRebated(final JsonMap slot)
  {
    assertEquals(PaymentSlot.STATUS_REBATED, PaymentSlot.status(slot));
    assertEquals(5L, PaymentSlot.amount(slot));
    assertEquals(PaymentSlot.UNIT, slot.getString("unit"));
    assertEquals("payeeA", PaymentSlot.payee(slot));
    assertEquals("payerA", PaymentSlot.payer(slot));
  }

  private void deliver(final JsonMsg msg) throws DomatarException
  {
    HttpClient.deliverLocal(dst, msg, ctx, "localhost", "/domatar", "", prov);
  }

  private JsonMap slot(final String op) throws DomatarException
  {
    return PaymentSlot.parse(OpLogDb.attachmentBody(
        hst, ctx.contextId, dst.toString(), op, PaymentSlot.SLOT));
  }

  private JsonMsg msg(final String op) throws DomatarException
  {
    final JsonMsg m = new JsonMsg();
    m.addRequestHead(dst, dst, ctx);
    m.addClsId("payprobe", "item");
    m.addRequestBody(op, new ObjAttrs());
    return m;
  }

  private static String pricedCls() throws DomatarException
  {
    final JsonArrayList msgs = new JsonArrayList();
    msgs.add(namedCost("Ping"));
    final JsonHashMap doc = new JsonHashMap();
    doc.put("Msgs", msgs);
    return Json.toJson(doc);
  }

  private static JsonHashMap namedCost(final String name)
  {
    final JsonHashMap cost = new JsonHashMap();
    cost.put("Amount", Integer.valueOf(5));
    cost.put("Unit", "credit");
    final JsonHashMap message = new JsonHashMap();
    message.put("Name", name);
    message.put("Cost", cost);
    return message;
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
