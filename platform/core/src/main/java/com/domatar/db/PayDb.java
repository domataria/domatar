package com.domatar.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.domatar.pay.PaymentSlot;
import com.domatar.util.DomatarException;
import com.domatar.util.Json;
import com.domatar.util.JsonMap;

/**
 * SQL for {@code pay_bal}. A priced admit draws inside one local transaction
 * with the visit upsert.
 */
public final class PayDb
{
  private static final Logger LOG = Logger.getLogger(PayDb.class.getName());
  private static final String PAY_BAL_DDL =
      "CREATE TABLE IF NOT EXISTS `pay_bal` ("
      + "`HstId` varchar(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,"
      + "`PayeeActId` varchar(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,"
      + "`PayerActId` varchar(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,"
      + "`Remaining` bigint NOT NULL,"
      + "`UpdatedAt` bigint NOT NULL,"
      + "PRIMARY KEY (`HstId`,`PayeeActId`,`PayerActId`),"
      + "CONSTRAINT `pay_bal_remaining_nonneg` CHECK (`Remaining` >= 0)"
      + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin";

  private static final String DRAW =
      "UPDATE pay_bal"
      + " SET Remaining = Remaining - ?, UpdatedAt = ?"
      + " WHERE HstId=? AND PayeeActId=? AND PayerActId=?"
      + " AND Remaining >= ?";

  private static final String CREDIT =
      "INSERT INTO pay_bal"
      + " (HstId, PayeeActId, PayerActId, Remaining, UpdatedAt)"
      + " VALUES (?,?,?,?,?) AS new"
      + " ON DUPLICATE KEY UPDATE"
      + " Remaining = pay_bal.Remaining + new.Remaining,"
      + " UpdatedAt = new.UpdatedAt";

  private static final String REMAINING =
      "SELECT Remaining FROM pay_bal"
      + " WHERE HstId=? AND PayeeActId=? AND PayerActId=?";

  private static final String PAYMENT_BODY =
      "'$." + PaymentSlot.SLOT + ".Body'";

  private static final String PAYMENT_STATUS =
      "'$." + PaymentSlot.SLOT + ".Body.status'";

  public enum Door
  {
    ADMITTED,
    DENIED
  }

  public static final class DoorResult
  {
    public final Door door;
    public final boolean firstAdmit;

    public DoorResult(final Door door, final boolean firstAdmit)
    {
      this.door = door;
      this.firstAdmit = firstAdmit;
    }
  }

  private PayDb() {}

  public static void ensureTable() throws DomatarException
  {
    DbConnection conn = null;
    Statement stmt = null;

    try
    {
      conn = new DbConnection(PayDb.class, "ensureTable");
      stmt = conn.createStatement();
      stmt.execute(PAY_BAL_DDL);
    }
    catch (final SQLException e)
    {
      throw new DomatarException(e);
    }
    finally
    {
      close(null, stmt, conn);
    }
  }

  /**
   * Decrement one balance. False when amount is not positive, the row
   * is missing, or Remaining would go below zero.
   */
  public static boolean tryDraw(
      final String hstId, final String payeeActId, final String payerActId,
      final long amount, final long nowMs) throws DomatarException
  {
    if (amount <= 0L)
      return false;

    DbConnection conn = null;

    try
    {
      conn = new DbConnection(PayDb.class, "tryDraw");
      return drawOn(conn, hstId, payeeActId, payerActId, amount, nowMs) == 1;
    }
    catch (final SQLException e)
    {
      throw new DomatarException(e);
    }
    finally
    {
      close(null, null, conn);
    }
  }

  public static int drawOn(
      final DbConnection conn,
      final String hstId, final String payeeActId, final String payerActId,
      final long amount, final long nowMs) throws DomatarException
  {
    if (amount <= 0L)
      return 0;

    PreparedStatement pstmt = null;

    try
    {
      pstmt = conn.prepareStatement(DRAW);
      pstmt.setLong(1, amount);
      pstmt.setLong(2, nowMs);
      pstmt.setString(3, hstId);
      pstmt.setString(4, payeeActId);
      pstmt.setString(5, payerActId);
      pstmt.setLong(6, amount);
      return pstmt.executeUpdate();
    }
    catch (final SQLException e)
    {
      throw new DomatarException(e);
    }
    finally
    {
      close(null, pstmt, null);
    }
  }

  /**
   * Visit upsert, optional draw, and slot attach on one connection.
   * A failed draw rolls the visit back.
   */
  public static DoorResult admitAndDraw(
      final String hstId, final String contextId, final String dstDomId,
      final String msgName, final String visitActId, final String callerDomId,
      final String trust, final long nowMs, final long visitTtlMs,
      final long amount, final String payeeActId, final String payerActId,
      final String slotBodyJson, final long slotExpiresAt)
      throws DomatarException
  {
    DbConnection conn = null;
    DomatarException pending = null;

    try
    {
      conn = new DbConnection(PayDb.class, "admitAndDraw");
      conn.setAutoCommit(false);
      conn.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);

      final boolean first = OpLogDb.upsertVisitOn(
          conn, hstId, contextId, dstDomId, msgName,
          visitActId, callerDomId, trust, nowMs, visitTtlMs);
      if (!first || amount <= 0L)
      {
        conn.commit();
        return new DoorResult(Door.ADMITTED, first);
      }

      final int rows = drawOn(
          conn, hstId, payeeActId, payerActId, amount, nowMs);
      if (rows != 1)
      {
        conn.rollback();
        return new DoorResult(Door.DENIED, false);
      }

      OpLogDb.attachOn(conn, hstId, contextId, dstDomId, msgName,
          PaymentSlot.SLOT, slotBodyJson, slotExpiresAt);
      OpLogDb.recomputeAttachExpiresAtOn(
          conn, hstId, contextId, dstDomId, msgName);
      conn.commit();
      return new DoorResult(Door.ADMITTED, true);
    }
    catch (final SQLException e)
    {
      pending = new DomatarException(e);
      rollbackQuiet(conn);
      throw pending;
    }
    catch (final DomatarException e)
    {
      pending = e;
      rollbackQuiet(conn);
      throw e;
    }
    finally
    {
      if (conn != null)
      {
        try
        {
          conn.setAutoCommit(true);
          conn.setTransactionIsolation(Connection.TRANSACTION_READ_UNCOMMITTED);
        }
        catch (final SQLException e)
        {
          LOG.log(Level.WARNING, "pay_bal connection reset failed", e);
        }
        try
        {
          close(null, null, conn);
        }
        catch (final DomatarException e)
        {
          LOG.log(Level.WARNING, "pay_bal connection close failed", e);
          if (pending == null)
            throw e;
        }
      }
    }
  }

  private static void rollbackQuiet(final DbConnection conn)
  {
    if (conn == null)
      return;
    try
    {
      conn.rollback();
    }
    catch (final SQLException e)
    {
      LOG.log(Level.WARNING, "pay_bal rollback failed", e);
    }
  }

  /**
   * WHY: rebate restores credits on the same balance. This is not a
   * withdrawal and not a Stripe top-up.
   */
  public static void credit(
      final String hstId, final String payeeActId, final String payerActId,
      final long amount, final long nowMs) throws DomatarException
  {
    if (amount <= 0L)
      return;

    DbConnection conn = null;

    try
    {
      conn = new DbConnection(PayDb.class, "credit");
      creditOn(conn, hstId, payeeActId, payerActId, amount, nowMs);
    }
    catch (final SQLException e)
    {
      throw new DomatarException(e);
    }
    finally
    {
      close(null, null, conn);
    }
  }

  static void creditOn(
      final DbConnection conn,
      final String hstId, final String payeeActId, final String payerActId,
      final long amount, final long nowMs) throws DomatarException
  {
    if (amount <= 0L || conn == null)
      return;

    PreparedStatement pstmt = null;

    try
    {
      pstmt = conn.prepareStatement(CREDIT);
      pstmt.setString(1, hstId);
      pstmt.setString(2, payeeActId);
      pstmt.setString(3, payerActId);
      pstmt.setLong(4, amount);
      pstmt.setLong(5, nowMs);
      pstmt.executeUpdate();
    }
    catch (final SQLException e)
    {
      throw new DomatarException(e);
    }
    finally
    {
      close(null, pstmt, null);
    }
  }

  /**
   * Restore slot.amount once when payment status is drawn.
   * Missing slot, a foreign unit, or a second call commits and returns false.
   */
  public static boolean rebate(
      final String hstId, final String contextId, final String dstDomId,
      final String msgName, final long nowMs) throws DomatarException
  {
    if (hstId == null || contextId == null || dstDomId == null || msgName == null)
      return false;

    final boolean[] restored = new boolean[] { false };

    try
    {
      OpLogDb.inTransaction(new OpLogDb.TxWork()
      {
        @Override
        public void run(final DbConnection conn) throws DomatarException
        {
          final JsonMap body = lockPaymentBody(conn, hstId, contextId, dstDomId, msgName);
          if (!drawnCredit(body))
            return;

          final int rows = markRebated(conn, hstId, contextId, dstDomId, msgName,
              Json.toJson(PaymentSlot.rebated(body)));
          if (rows != 1)
            throw new RebateLost();

          creditOn(conn, hstId, PaymentSlot.payee(body), PaymentSlot.payer(body),
              PaymentSlot.amount(body), nowMs);
          OpLogDb.recomputeAttachExpiresAtOn(conn, hstId, contextId, dstDomId, msgName);
          restored[0] = true;
        }
      });
    }
    catch (final RebateLost lost)
    {
      return false;
    }
    return restored[0];
  }

  private static JsonMap lockPaymentBody(
      final DbConnection conn,
      final String hstId, final String contextId, final String dstDomId,
      final String msgName) throws DomatarException
  {
    PreparedStatement pstmt = null;
    ResultSet rset = null;

    try
    {
      pstmt = conn.prepareStatement(
          "SELECT JSON_UNQUOTE(JSON_EXTRACT(Attachment, " + PAYMENT_BODY + "))"
          + " FROM op_dst WHERE HstId=? AND ContextId=? AND DstDomId=? AND MsgName=?"
          + " FOR UPDATE");
      pstmt.setString(1, hstId);
      pstmt.setString(2, contextId);
      pstmt.setString(3, dstDomId);
      pstmt.setString(4, msgName);
      rset = pstmt.executeQuery();
      if (!rset.next())
        return null;
      return PaymentSlot.parse(rset.getString(1));
    }
    catch (final SQLException e)
    {
      throw new DomatarException(e);
    }
    finally
    {
      close(rset, pstmt, null);
    }
  }

  private static int markRebated(
      final DbConnection conn,
      final String hstId, final String contextId, final String dstDomId,
      final String msgName, final String bodyJson) throws DomatarException
  {
    PreparedStatement pstmt = null;

    try
    {
      pstmt = conn.prepareStatement(
          "UPDATE op_dst SET Attachment = JSON_SET(Attachment, " + PAYMENT_BODY
          + ", CAST(? AS JSON))"
          + " WHERE HstId=? AND ContextId=? AND DstDomId=? AND MsgName=?"
          + " AND JSON_UNQUOTE(JSON_EXTRACT(Attachment, " + PAYMENT_STATUS + ")) = ?");
      pstmt.setString(1, bodyJson);
      pstmt.setString(2, hstId);
      pstmt.setString(3, contextId);
      pstmt.setString(4, dstDomId);
      pstmt.setString(5, msgName);
      pstmt.setString(6, PaymentSlot.STATUS_DRAWN);
      return pstmt.executeUpdate();
    }
    catch (final SQLException e)
    {
      throw new DomatarException(e);
    }
    finally
    {
      close(null, pstmt, null);
    }
  }

  private static boolean drawnCredit(final JsonMap body)
  {
    if (body == null)
      return false;
    if (!PaymentSlot.STATUS_DRAWN.equals(PaymentSlot.status(body)))
      return false;
    if (!PaymentSlot.UNIT.equals(body.getString("unit")))
      return false;
    if (PaymentSlot.amount(body) <= 0L)
      return false;
    final String payee = PaymentSlot.payee(body);
    final String payer = PaymentSlot.payer(body);
    return payee != null && !payee.isEmpty() && payer != null && !payer.isEmpty();
  }

  private static final class RebateLost extends DomatarException
  {
    private static final long serialVersionUID = 1L;

    private RebateLost()
    {
      super("rebate lost");
    }
  }

  /** Null when no row exists. */
  public static Long remaining(
      final String hstId, final String payeeActId, final String payerActId)
      throws DomatarException
  {
    DbConnection conn = null;
    PreparedStatement pstmt = null;
    ResultSet rset = null;

    try
    {
      conn = new DbConnection(PayDb.class, "remaining");
      pstmt = conn.prepareStatement(REMAINING);
      pstmt.setString(1, hstId);
      pstmt.setString(2, payeeActId);
      pstmt.setString(3, payerActId);
      rset = pstmt.executeQuery();
      if (!rset.next())
        return null;
      return Long.valueOf(rset.getLong(1));
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

  private static void close(
      final ResultSet rset, final Statement stmt, final DbConnection conn)
      throws DomatarException
  {
    try
    {
      if (rset != null)
        rset.close();
      if (stmt != null)
        stmt.close();
      if (conn != null)
        conn.close();
    }
    catch (final SQLException e)
    {
      throw new DomatarException(e);
    }
  }
}
