package com.domatar.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.domatar.install.PaymentInstall;

public class PayDbTest
{
  private static final String[] URLS = {
      System.getenv("DOMATAR_DB_URL"),
      System.getProperty("DOMATAR_DB_URL"),
      "jdbc:mysql://127.0.0.1:9307/domatar?user=root&password=domatar",
      "jdbc:mysql://127.0.0.1:3306/domatar?user=root&password=domatar"
  };

  private static final String PAYEE = "payeeA";
  private static final String PAYER = "payerA";

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
      PaymentInstall.ensureTable();
    }
  }

  @BeforeEach
  public void ids()
  {
    hst = "paybal-" + Long.toHexString(System.nanoTime());
  }

  @AfterEach
  public void cleanup() throws Exception
  {
    if (dbReady && hst != null)
      exec("DELETE FROM pay_bal WHERE HstId=?", hst);
  }

  @Test
  public void draw_decreasesRemaining() throws Exception
  {
    assumeTrue(dbReady, "DbConnection unset / MySQL unreachable; skip PayDbTest");

    PayDb.credit(hst, PAYEE, PAYER, 10L, 1_000L);
    assertEquals(1_000L, updatedAt());

    assertTrue(PayDb.tryDraw(hst, PAYEE, PAYER, 4L, 2_000L));
    assertEquals(6L, PayDb.remaining(hst, PAYEE, PAYER).longValue());
    assertEquals(2_000L, updatedAt());
  }

  @Test
  public void draw_insufficientLeavesRow() throws Exception
  {
    assumeTrue(dbReady, "DbConnection unset / MySQL unreachable; skip PayDbTest");

    PayDb.credit(hst, PAYEE, PAYER, 3L, 1_000L);
    assertFalse(PayDb.tryDraw(hst, PAYEE, PAYER, 4L, 2_000L));
    assertEquals(3L, PayDb.remaining(hst, PAYEE, PAYER).longValue());
    assertEquals(1_000L, updatedAt());
  }

  @Test
  public void draw_missingRowIsInsufficient() throws Exception
  {
    assumeTrue(dbReady, "DbConnection unset / MySQL unreachable; skip PayDbTest");

    assertFalse(PayDb.tryDraw(hst, PAYEE, PAYER, 1L, 1_000L));
    assertNull(PayDb.remaining(hst, PAYEE, PAYER));
  }

  @Test
  public void credit_insertsThenIncrements() throws Exception
  {
    assumeTrue(dbReady, "DbConnection unset / MySQL unreachable; skip PayDbTest");

    PayDb.credit(hst, PAYEE, PAYER, 2L, 1_000L);
    PayDb.credit(hst, PAYEE, PAYER, 5L, 2_000L);
    assertEquals(7L, PayDb.remaining(hst, PAYEE, PAYER).longValue());
  }

  @Test
  public void draw_nonPositiveIsFalse() throws Exception
  {
    assumeTrue(dbReady, "DbConnection unset / MySQL unreachable; skip PayDbTest");

    assertFalse(PayDb.tryDraw(hst, PAYEE, PAYER, 0L, 1_000L));
    assertNull(PayDb.remaining(hst, PAYEE, PAYER));
  }

  private long updatedAt() throws Exception
  {
    try (Connection c = DriverManager.getConnection(jdbcUrl + "&serverTimezone=UTC");
         PreparedStatement pstmt = c.prepareStatement(
             "SELECT UpdatedAt FROM pay_bal"
             + " WHERE HstId=? AND PayeeActId=? AND PayerActId=?"))
    {
      pstmt.setString(1, hst);
      pstmt.setString(2, PAYEE);
      pstmt.setString(3, PAYER);
      try (ResultSet rset = pstmt.executeQuery())
      {
        assertTrue(rset.next());
        return rset.getLong(1);
      }
    }
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
