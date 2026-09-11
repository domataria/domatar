package com.domatar.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.domatar.install.OpLogInstall;
import com.domatar.log.OpMsg;
import com.domatar.util.DomatarException;

public class OpLogDbTest
{
  private static final long TTL = 60_000L;
  private static final String[] URLS = {
      System.getenv("DOMATAR_DB_URL"),
      System.getProperty("DOMATAR_DB_URL"),
      "jdbc:mysql://127.0.0.1:9307/domatar?user=root&password=domatar",
      "jdbc:mysql://127.0.0.1:3306/domatar?user=root&password=domatar"
  };

  private String hst;
  private String ctx;
  private String dst;
  private String src;
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

    assumeTrue(ok != null,
        "DbConnection unset / MySQL unreachable; skip OpLogDbTest");
    DbConnection.setConnectStr(ok);
    OpLogInstall.ensureTables();
  }

  @BeforeEach
  public void ids()
  {
    final String n = Long.toHexString(System.nanoTime());
    hst = "oplog-t-" + n;
    ctx = "ctx-" + n;
    dst = hst + ".app.act.obj";
    src = hst + ".app.act.ui";
    now = System.currentTimeMillis();
  }

  @AfterEach
  public void cleanup() throws Exception
  {
    exec("DELETE FROM op_msg WHERE HstId=?", hst);
    exec("DELETE FROM op_dst WHERE HstId=?", hst);
  }

  @Test
  public void insertThenDuplicate_returnsFirstAdmitThenFalse() throws Exception
  {
    assertTrue(OpLogDb.upsertVisit(hst, ctx, dst, "Ping", "actA", src,
        "ACCOUNT", now, TTL));
    assertFalse(OpLogDb.upsertVisit(hst, ctx, dst, "Ping", "actA", src,
        "ACCOUNT", now + 1, TTL));
    assertEquals(2, OpLogDb.priorVisitCount(hst, ctx, dst, "Ping"));
  }

  @Test
  public void secondAdmit_doesNotChangeActIdOrCallerDomId() throws Exception
  {
    assertTrue(OpLogDb.upsertVisit(hst, ctx, dst, "Ping", "actA", src,
        "ACCOUNT", now, TTL));
    assertFalse(OpLogDb.upsertVisit(hst, ctx, dst, "Ping", "actB",
        hst + ".other.act.ui", "PATH", now + 1, TTL));
    assertEquals("actA", scalar("SELECT ActId FROM op_dst WHERE HstId=? AND ContextId=?",
        hst, ctx));
    assertEquals(src, scalar("SELECT CallerDomId FROM op_dst WHERE HstId=? AND ContextId=?",
        hst, ctx));
    assertEquals("ACCOUNT", scalar("SELECT Trust FROM op_dst WHERE HstId=? AND ContextId=?",
        hst, ctx));
  }

  @Test
  public void priorVisitCount_zeroThenOne() throws Exception
  {
    assertEquals(0, OpLogDb.priorVisitCount(hst, ctx, dst, "Ping"));
    assertEquals(0, OpLogDb.priorVisitCountAny(hst, ctx, dst));
    OpLogDb.upsertVisit(hst, ctx, dst, "Ping", "actA", src, "ACCOUNT", now, TTL);
    assertEquals(1, OpLogDb.priorVisitCount(hst, ctx, dst, "Ping"));
    assertEquals(1, OpLogDb.priorVisitCountAny(hst, ctx, dst));
  }

  @Test
  public void twoContextIds_doNotCollide() throws Exception
  {
    final String ctx2 = ctx + "-b";
    OpLogDb.upsertVisit(hst, ctx, dst, "Ping", "actA", src, "ACCOUNT", now, TTL);
    OpLogDb.upsertVisit(hst, ctx2, dst, "Ping", "actA", src, "ACCOUNT", now, TTL);
    assertEquals(1, OpLogDb.priorVisitCount(hst, ctx, dst, "Ping"));
    assertEquals(1, OpLogDb.priorVisitCount(hst, ctx2, dst, "Ping"));
    assertEquals(1, OpLogDb.priorVisitCountAny(hst, ctx, dst));
    assertEquals(1, OpLogDb.priorVisitCountAny(hst, ctx2, dst));
  }

  @Test
  public void insertMsg_twoSendsSameDest_seq1Then2() throws Exception
  {
    OpLogDb.insertMsg(hst, ctx, src, dst, "Ping", now);
    OpLogDb.insertMsg(hst, ctx, src, dst, "Ping", now + 1);
    final List<OpMsg> msgs = OpLogDb.listOutMsgs(hst, ctx, src);
    assertEquals(2, msgs.size());
    assertEquals(1, msgs.get(0).seq);
    assertEquals(2, msgs.get(1).seq);
    assertEquals("Ping", msgs.get(0).outMsgName);
    assertEquals(dst, msgs.get(1).dstDomId);
  }

  @Test
  public void insertMsg_pkCollisionRetries() throws Exception
  {
    final int n = 16;
    final ExecutorService pool = Executors.newFixedThreadPool(n);
    final CountDownLatch start = new CountDownLatch(1);
    final CountDownLatch done = new CountDownLatch(n);
    final AtomicInteger errors = new AtomicInteger();

    for (int i = 0; i < n; i++)
    {
      pool.submit(() ->
      {
        try
        {
          start.await();
          OpLogDb.insertMsg(hst, ctx, src, dst, "Ping", now);
        }
        catch (final Exception e)
        {
          errors.incrementAndGet();
        }
        finally
        {
          done.countDown();
        }
      });
    }

    start.countDown();
    assertTrue(done.await(30, TimeUnit.SECONDS));
    pool.shutdownNow();
    assertEquals(0, errors.get());

    final List<OpMsg> msgs = OpLogDb.listOutMsgs(hst, ctx, src);
    assertEquals(n, msgs.size());
    final Set<Integer> seqs = new HashSet<>();
    for (final OpMsg m : msgs)
      seqs.add(m.seq);
    assertEquals(n, seqs.size());
  }

  @Test
  public void attach_missingRow_doesNotInsert() throws Exception
  {
    OpLogDb.attach(hst, ctx, dst, "Ping", "saga", "{\"ok\":true}", now + TTL);
    assertEquals("0", scalar("SELECT COUNT(*) FROM op_dst WHERE HstId=?", hst));
    assertNull(OpLogDb.attachmentBody(hst, ctx, dst, "Ping", "saga"));
  }

  @Test
  public void attach_jsonSet_doesNotClobberOtherSlot() throws Exception
  {
    OpLogDb.upsertVisit(hst, ctx, dst, "Ping", "actA", src, "ACCOUNT", now, TTL);
    OpLogDb.attach(hst, ctx, dst, "Ping", "saga", "{\"s\":1}", now + 1000);
    OpLogDb.attach(hst, ctx, dst, "Ping", "payment", "{\"p\":2}", now + 2000);
    assertTrue(OpLogDb.attachmentBody(hst, ctx, dst, "Ping", "saga").contains("1"));
    assertTrue(OpLogDb.attachmentBody(hst, ctx, dst, "Ping", "payment").contains("2"));
  }

  @Test
  public void attach_null_deletesOnlyThatSlot() throws Exception
  {
    OpLogDb.upsertVisit(hst, ctx, dst, "Ping", "actA", src, "ACCOUNT", now, TTL);
    OpLogDb.attach(hst, ctx, dst, "Ping", "saga", "{\"s\":1}", now + 1000);
    OpLogDb.attach(hst, ctx, dst, "Ping", "payment", "{\"p\":2}", now + 2000);
    OpLogDb.attach(hst, ctx, dst, "Ping", "saga", null, 0);
    assertNull(OpLogDb.attachmentBody(hst, ctx, dst, "Ping", "saga"));
    assertTrue(OpLogDb.attachmentBody(hst, ctx, dst, "Ping", "payment").contains("2"));
  }

  @Test
  public void attachExpiresAt_isMaxOfRemaining() throws Exception
  {
    OpLogDb.upsertVisit(hst, ctx, dst, "Ping", "actA", src, "ACCOUNT", now, TTL);
    OpLogDb.attach(hst, ctx, dst, "Ping", "saga", "{\"s\":1}", 1000);
    OpLogDb.attach(hst, ctx, dst, "Ping", "payment", "{\"p\":2}", 5000);
    assertEquals("5000", scalar(
        "SELECT AttachExpiresAt FROM op_dst WHERE HstId=? AND ContextId=?",
        hst, ctx));
    OpLogDb.attach(hst, ctx, dst, "Ping", "payment", null, 0);
    assertEquals("1000", scalar(
        "SELECT AttachExpiresAt FROM op_dst WHERE HstId=? AND ContextId=?",
        hst, ctx));
    OpLogDb.attach(hst, ctx, dst, "Ping", "saga", null, 0);
    assertEquals(null, scalar(
        "SELECT AttachExpiresAt FROM op_dst WHERE HstId=? AND ContextId=?",
        hst, ctx));
    assertEquals(null, scalar(
        "SELECT CAST(Attachment AS CHAR) FROM op_dst WHERE HstId=? AND ContextId=?",
        hst, ctx));
  }

  @Test
  public void gcHost_respectsAttachExpiresAt() throws Exception
  {
    OpLogDb.upsertVisit(hst, ctx, dst, "Ping", "actA", src, "ACCOUNT", now, TTL);
    OpLogDb.attach(hst, ctx, dst, "Ping", "saga", "{\"s\":1}", now + 86_400_000L);
    exec("UPDATE op_dst SET VisitExpiresAt=? WHERE HstId=?", now - 1, hst);

    assertEquals(0, OpLogDb.gcHost(hst, now));
    assertEquals("1", scalar("SELECT COUNT(*) FROM op_dst WHERE HstId=?", hst));

    exec("UPDATE op_dst SET AttachExpiresAt=? WHERE HstId=?", now - 1, hst);
    assertTrue(OpLogDb.gcHost(hst, now) >= 1);
    assertEquals("0", scalar("SELECT COUNT(*) FROM op_dst WHERE HstId=?", hst));
  }

  @Test
  public void gcHost_deletesOpMsgWhenVisitGone() throws Exception
  {
    OpLogDb.upsertVisit(hst, ctx, dst, "Ping", "actA", src, "ACCOUNT", now, TTL);
    OpLogDb.insertMsg(hst, ctx, src, dst, "Ping", now);
    exec("UPDATE op_dst SET VisitExpiresAt=? WHERE HstId=?", now - 1, hst);

    assertTrue(OpLogDb.gcHost(hst, now) >= 2);
    assertEquals("0", scalar("SELECT COUNT(*) FROM op_dst WHERE HstId=?", hst));
    assertEquals("0", scalar("SELECT COUNT(*) FROM op_msg WHERE HstId=?", hst));
  }

  @Test
  public void gcHost_leavesOtherHst() throws Exception
  {
    final String hst2 = hst + "-other";
    try
    {
      OpLogDb.upsertVisit(hst, ctx, dst, "Ping", "actA", src, "ACCOUNT", now, TTL);
      OpLogDb.upsertVisit(hst2, ctx, hst2 + ".app.act.obj", "Ping", "actA",
          src, "ACCOUNT", now, TTL);
      OpLogDb.insertMsg(hst, ctx, src, dst, "Ping", now);
      OpLogDb.insertMsg(hst2, ctx, src, hst2 + ".app.act.obj", "Ping", now);
      exec("UPDATE op_dst SET VisitExpiresAt=? WHERE HstId IN (?,?)",
          now - 1, hst, hst2);

      OpLogDb.gcHost(hst, now);
      assertEquals("0", scalar("SELECT COUNT(*) FROM op_dst WHERE HstId=?", hst));
      assertEquals("0", scalar("SELECT COUNT(*) FROM op_msg WHERE HstId=?", hst));
      assertEquals("1", scalar("SELECT COUNT(*) FROM op_dst WHERE HstId=?", hst2));
      assertEquals("1", scalar("SELECT COUNT(*) FROM op_msg WHERE HstId=?", hst2));
    }
    finally
    {
      exec("DELETE FROM op_msg WHERE HstId=?", hst2);
      exec("DELETE FROM op_dst WHERE HstId=?", hst2);
    }
  }

  @Test
  public void gcHost_doesNotDeleteOtherHstId() throws Exception
  {
    final String hst2 = hst + "-b";
    try
    {
      OpLogDb.upsertVisit(hst, ctx, dst, "Ping", "actA", src, "ACCOUNT", now, TTL);
      OpLogDb.upsertVisit(hst2, ctx, hst2 + ".app.act.obj", "Ping", "actA",
          src, "ACCOUNT", now, TTL);
      OpLogDb.insertMsg(hst, ctx, src, dst, "Ping", now);
      OpLogDb.insertMsg(hst2, ctx, src, hst2 + ".app.act.obj", "Ping", now);
      exec("UPDATE op_dst SET VisitExpiresAt=? WHERE HstId=?", now - 1, hst);
      exec("UPDATE op_dst SET VisitExpiresAt=? WHERE HstId=?", now - 1, hst2);

      OpLogDb.gcHost(hst, now);
      assertEquals("0", scalar("SELECT COUNT(*) FROM op_dst WHERE HstId=?", hst));
      assertEquals("0", scalar("SELECT COUNT(*) FROM op_msg WHERE HstId=?", hst));
      assertEquals("1", scalar("SELECT COUNT(*) FROM op_dst WHERE HstId=?", hst2));
      assertEquals("1", scalar("SELECT COUNT(*) FROM op_msg WHERE HstId=?", hst2));
    }
    finally
    {
      exec("DELETE FROM op_msg WHERE HstId=?", hst2);
      exec("DELETE FROM op_dst WHERE HstId=?", hst2);
    }
  }

  @Test
  public void hstId_mustMatchParsedHost() throws Exception
  {
    // OpLogDb writes the HstId it is given. admitIfNeeded parses DstDomId
    // in Phase 2; a mismatch here is a caller bug.
    final String dstOther = "other-hst.app.act.obj";
    OpLogDb.upsertVisit(hst, ctx, dstOther, "Ping", "actA", src, "ACCOUNT",
        now, TTL);
    assertEquals(hst, scalar(
        "SELECT HstId FROM op_dst WHERE ContextId=? AND DstDomId=?",
        ctx, dstOther));
    assertNotEquals("other-hst", hst);
  }

  private static String scalar(final String sql, final Object... args)
      throws DomatarException
  {
    DbConnection conn = null;
    PreparedStatement pstmt = null;
    ResultSet rset = null;

    try
    {
      conn = new DbConnection(OpLogDbTest.class, "scalar");
      pstmt = conn.prepareStatement(sql);
      bind(pstmt, args);
      rset = pstmt.executeQuery();
      if (!rset.next())
        return null;
      return rset.getString(1);
    }
    catch (final SQLException e)
    {
      throw new DomatarException(e);
    }
    finally
    {
      close(rset, pstmt, conn);
    }
  }

  private static void exec(final String sql, final Object... args)
      throws DomatarException
  {
    DbConnection conn = null;
    PreparedStatement pstmt = null;

    try
    {
      conn = new DbConnection(OpLogDbTest.class, "exec");
      pstmt = conn.prepareStatement(sql);
      bind(pstmt, args);
      pstmt.executeUpdate();
    }
    catch (final SQLException e)
    {
      throw new DomatarException(e);
    }
    finally
    {
      close(null, pstmt, conn);
    }
  }

  private static void bind(final PreparedStatement pstmt, final Object[] args)
      throws SQLException
  {
    for (int i = 0; i < args.length; i++)
    {
      final Object a = args[i];
      if (a instanceof Long)
        pstmt.setLong(i + 1, (Long) a);
      else
        pstmt.setString(i + 1, (String) a);
    }
  }

  private static void close(
      final ResultSet rset, final PreparedStatement pstmt,
      final DbConnection conn) throws DomatarException
  {
    try
    {
      if (rset != null)
        rset.close();
      if (pstmt != null)
        pstmt.close();
      if (conn != null)
        conn.close();
    }
    catch (final SQLException e)
    {
      throw new DomatarException(e);
    }
  }
}
