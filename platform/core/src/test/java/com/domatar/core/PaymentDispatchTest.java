package com.domatar.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicReference;

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

public class PaymentDispatchTest
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
    hst = "paydoor-" + n;
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
  public void booleanAdmit_noDraw_visitUpserted() throws Exception
  {
    assumeTrue(dbReady, "MySQL unreachable; skip PaymentDispatchTest");

    probe.scripted = null;
    final String reply = deliver(msg("Ping"), ctx);
    assertFalse(reply.contains("Not authorized"));
    assertEquals(1, visitCount("Ping"));
    assertNull(slot("Ping"));
    assertNull(PayDb.remaining(hst, "payeeA", "payerA"));
  }

  @Test
  public void priced_draws5_slotDrawn_remainingDecreased() throws Exception
  {
    assumeTrue(dbReady, "MySQL unreachable; skip PaymentDispatchTest");

    probe.scripted = Rights.PRICED;
    PayDb.credit(hst, "payeeA", "payerA", 20L, 1000L);
    final String reply = deliver(msg("Ping"), ctx);
    assertFalse(reply.contains("Not authorized"));
    assertEquals(Long.valueOf(15L), PayDb.remaining(hst, "payeeA", "payerA"));
    assertEquals(1, visitCount("Ping"));
    assertDrawn(slot("Ping"));
  }

  @Test
  public void secondAdmit_visitCount2_remainingUnchanged() throws Exception
  {
    assumeTrue(dbReady, "MySQL unreachable; skip PaymentDispatchTest");

    probe.scripted = Rights.PRICED;
    PayDb.credit(hst, "payeeA", "payerA", 20L, 1000L);
    deliver(msg("Ping"), ctx);
    deliver(msg("Ping"), ctx);
    assertEquals(2, visitCount("Ping"));
    assertEquals(Long.valueOf(15L), PayDb.remaining(hst, "payeeA", "payerA"));
    assertDrawn(slot("Ping"));
  }

  @Test
  public void concurrentPriced_drawsOnce() throws Exception
  {
    assumeTrue(dbReady, "MySQL unreachable; skip PaymentDispatchTest");

    probe.scripted = Rights.PRICED;
    PayDb.credit(hst, "payeeA", "payerA", 20L, 1000L);
    final JsonMsg a = msg("Ping");
    final JsonMsg b = msg("Ping");
    final AtomicReference<Throwable> err = new AtomicReference<Throwable>();
    final Thread t1 = new Thread(new Runnable()
    {
      @Override
      public void run()
      {
        try
        {
          deliver(a, ctx);
        }
        catch (final Throwable t)
        {
          err.compareAndSet(null, t);
        }
      }
    });
    final Thread t2 = new Thread(new Runnable()
    {
      @Override
      public void run()
      {
        try
        {
          deliver(b, ctx);
        }
        catch (final Throwable t)
        {
          err.compareAndSet(null, t);
        }
      }
    });
    t1.start();
    t2.start();
    t1.join(30000L);
    t2.join(30000L);
    assertFalse(t1.isAlive());
    assertFalse(t2.isAlive());
    assertNull(err.get());
    assertEquals(Long.valueOf(15L), PayDb.remaining(hst, "payeeA", "payerA"));
    assertEquals(2, visitCount("Ping"));
    assertDrawn(slot("Ping"));
  }

  @Test
  public void priced_remaining0_denies_noVisit() throws Exception
  {
    assumeTrue(dbReady, "MySQL unreachable; skip PaymentDispatchTest");

    probe.scripted = Rights.PRICED;
    final String reply = deliver(msg("Ping"), ctx);
    assertTrue(reply.contains("Not authorized"));
    assertEquals(0, visitCount("Ping"));
    assertNull(slot("Ping"));
    assertNull(PayDb.remaining(hst, "payeeA", "payerA"));
  }

  @Test
  public void priced_trustPath_denies() throws Exception
  {
    assumeTrue(dbReady, "MySQL unreachable; skip PaymentDispatchTest");

    probe.scripted = Rights.PRICED;
    final Context pathCtx = new Context("payerA", "u", "n", "127.0.0.1", "tok",
        Trust.PATH, ctx.contextId, null);
    final String reply = deliver(msg("Ping"), pathCtx);
    assertTrue(reply.contains("Not authorized"));
    assertEquals(0, visitCount("Ping"));
    assertNull(PayDb.remaining(hst, "payeeA", "payerA"));
  }

  @Test
  public void getCls_priced_noDraw() throws Exception
  {
    assumeTrue(dbReady, "MySQL unreachable; skip PaymentDispatchTest");

    probe.scripted = Rights.PRICED;
    PayDb.credit(hst, "payeeA", "payerA", 20L, 1000L);
    deliver(msg("GetCls"), ctx);
    assertEquals(Long.valueOf(20L), PayDb.remaining(hst, "payeeA", "payerA"));
    assertNull(slot("GetCls"));
  }

  @Test
  public void handlerAttachPayment_doesNotStick() throws Exception
  {
    assumeTrue(dbReady, "MySQL unreachable; skip PaymentDispatchTest");

    probe.scripted = Rights.PRICED;
    PayDb.credit(hst, "payeeA", "payerA", 20L, 1000L);
    deliver(msg("Ping"), ctx);
    client.snapshotPriors(ctx.contextId, dst.toString(), "Ping");
    final JsonHashMap forged = new JsonHashMap();
    forged.put("status", "forged");
    client.attach("payment", forged, OpLog.nowMs() + 60000L);
    assertDrawn(PaymentSlot.parse(client.attachment("payment")));
  }

  private void assertDrawn(final JsonMap slot)
  {
    assertEquals(PaymentSlot.STATUS_DRAWN, PaymentSlot.status(slot));
    assertEquals(5L, PaymentSlot.amount(slot));
    assertEquals(PaymentSlot.UNIT, slot.getString("unit"));
    assertEquals("payeeA", PaymentSlot.payee(slot));
    assertEquals("payerA", PaymentSlot.payer(slot));
  }

  private String deliver(final JsonMsg msg, final Context stamped) throws DomatarException
  {
    return HttpClient.deliverLocal(dst, msg, stamped, "localhost", "/domatar", "", prov);
  }

  private int visitCount(final String op) throws DomatarException
  {
    return OpLogDb.priorVisitCount(hst, ctx.contextId, dst.toString(), op);
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
    msgs.add(namedCost("GetCls"));
    final JsonHashMap doc = new JsonHashMap();
    doc.put("Msgs", msgs);
    return Json.toJson(doc);
  }

  private static JsonHashMap namedCost(final String name)
  {
    final JsonHashMap cost = new JsonHashMap();
    cost.put("Amount", Integer.valueOf(5));
    cost.put("Unit", "credit");
    final JsonHashMap msg = new JsonHashMap();
    msg.put("Name", name);
    msg.put("Cost", cost);
    return msg;
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
