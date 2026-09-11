package com.domatar.db;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import java.util.regex.Pattern;

import com.domatar.log.OpMsg;
import com.domatar.util.DomatarException;
import com.domatar.util.Json;
import com.domatar.util.JsonMap;

/**
 * SQL for {@code op_dst} / {@code op_msg}. Dispatch does not call this yet.
 */
public final class OpLogDb
{
  private static final Logger LOG = Logger.getLogger(OpLogDb.class.getName());

  private static final Pattern SLOT =
      Pattern.compile("^[A-Za-z][A-Za-z0-9_]*$");

  private static final int MSG_SEQ_RETRIES = 64;

  private static final String OP_DST_DDL =
      "CREATE TABLE IF NOT EXISTS `op_dst` ("
      + "`HstId` varchar(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,"
      + "`ContextId` varchar(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,"
      + "`DstDomId` varchar(400) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,"
      + "`MsgName` varchar(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,"
      + "`ActId` varchar(100) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL,"
      + "`CallerDomId` varchar(400) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,"
      + "`Trust` varchar(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,"
      + "`VisitCount` int NOT NULL,"
      + "`FirstSeenAt` bigint NOT NULL,"
      + "`LastSeenAt` bigint NOT NULL,"
      + "`VisitExpiresAt` bigint NOT NULL,"
      + "`Attachment` json DEFAULT NULL,"
      + "`AttachExpiresAt` bigint DEFAULT NULL,"
      + "PRIMARY KEY (`HstId`,`ContextId`,`DstDomId`,`MsgName`),"
      + "KEY `op_dst_ctx` (`HstId`,`ContextId`)"
      + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin";

  private static final String OP_MSG_DDL =
      "CREATE TABLE IF NOT EXISTS `op_msg` ("
      + "`HstId` varchar(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,"
      + "`ContextId` varchar(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,"
      + "`SrcDomId` varchar(400) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,"
      + "`Seq` int NOT NULL,"
      + "`DstDomId` varchar(400) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,"
      + "`OutMsgName` varchar(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,"
      + "`SentAt` bigint NOT NULL,"
      + "PRIMARY KEY (`HstId`,`ContextId`,`SrcDomId`,`Seq`),"
      + "KEY `op_msg_ctx` (`HstId`,`ContextId`)"
      + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin";

  private static final String UPSERT_VISIT =
      "INSERT INTO op_dst ("
      + "HstId, ContextId, DstDomId, MsgName, ActId, CallerDomId, Trust,"
      + "VisitCount, FirstSeenAt, LastSeenAt, VisitExpiresAt,"
      + "Attachment, AttachExpiresAt) "
      + "VALUES (?,?,?,?,?,?,?,1,?,?,?,NULL,NULL) AS new "
      + "ON DUPLICATE KEY UPDATE "
      + "VisitCount = op_dst.VisitCount + 1,"
      + "LastSeenAt = (@oplog_first := 0) + new.LastSeenAt,"
      + "VisitExpiresAt = new.VisitExpiresAt";

  private OpLogDb() {}

  public static void ensureTables() throws DomatarException
  {
    DbConnection conn = null;
    Statement stmt = null;

    try
    {
      conn = new DbConnection(OpLogDb.class, "ensureTables");
      stmt = conn.createStatement();
      stmt.execute(OP_DST_DDL);
      stmt.execute(OP_MSG_DDL);
    }
    catch (final SQLException e)
    {
      throw new DomatarException(e);
    }
    finally
    {
      close(null, stmt, null, conn);
    }
  }

  /**
   * Insert VisitCount=1 or increment. Returns true iff this statement
   * inserted. Connector/J CLIENT_FOUND_ROWS makes executeUpdate return 1
   * for both insert and update, so firstAdmit is {@code @oplog_first}
   * (1 on INSERT, 0 in the ON DUPLICATE assignment).
   */
  public static boolean upsertVisit(
      final String hstId, final String contextId, final String dstDomId,
      final String msgName, final String actId, final String callerDomId,
      final String trust, final long nowMs, final long visitTtlMs)
      throws DomatarException
  {
    DbConnection conn = null;
    Statement stmt = null;
    PreparedStatement pstmt = null;
    ResultSet rset = null;

    try
    {
      conn = new DbConnection(OpLogDb.class, "upsertVisit");
      stmt = conn.createStatement();
      stmt.execute("SET @oplog_first = 1");

      pstmt = conn.prepareStatement(UPSERT_VISIT);
      pstmt.setString(1, hstId);
      pstmt.setString(2, contextId);
      pstmt.setString(3, dstDomId);
      pstmt.setString(4, msgName);
      if (actId == null)
        pstmt.setNull(5, Types.VARCHAR);
      else
        pstmt.setString(5, actId);
      pstmt.setString(6, callerDomId);
      pstmt.setString(7, trust);
      pstmt.setLong(8, nowMs);
      pstmt.setLong(9, nowMs);
      pstmt.setLong(10, nowMs + visitTtlMs);
      pstmt.executeUpdate();

      rset = stmt.executeQuery("SELECT @oplog_first");
      rset.next();
      return rset.getLong(1) == 1;
    }
    catch (final SQLException e)
    {
      throw new DomatarException(e);
    }
    finally
    {
      close(rset, pstmt, stmt, conn);
    }
  }

  public static void insertMsg(
      final String hstId, final String contextId, final String srcDomId,
      final String dstDomId, final String outMsgName, final long sentAt)
      throws DomatarException
  {
    SQLException last = null;

    for (int attempt = 0; attempt < MSG_SEQ_RETRIES; attempt++)
    {
      DbConnection conn = null;
      PreparedStatement sel = null;
      PreparedStatement ins = null;
      ResultSet rset = null;

      try
      {
        conn = new DbConnection(OpLogDb.class, "insertMsg");
        sel = conn.prepareStatement(
            "SELECT COALESCE(MAX(Seq),0)+1 FROM op_msg"
            + " WHERE HstId=? AND ContextId=? AND SrcDomId=?");
        sel.setString(1, hstId);
        sel.setString(2, contextId);
        sel.setString(3, srcDomId);
        rset = sel.executeQuery();
        rset.next();
        final int seq = rset.getInt(1);
        rset.close();
        rset = null;

        ins = conn.prepareStatement(
            "INSERT INTO op_msg"
            + " (HstId,ContextId,SrcDomId,Seq,DstDomId,OutMsgName,SentAt)"
            + " VALUES (?,?,?,?,?,?,?)");
        ins.setString(1, hstId);
        ins.setString(2, contextId);
        ins.setString(3, srcDomId);
        ins.setInt(4, seq);
        ins.setString(5, dstDomId);
        ins.setString(6, outMsgName);
        ins.setLong(7, sentAt);
        ins.executeUpdate();
        return;
      }
      catch (final SQLException e)
      {
        if (isPkCollision(e))
        {
          last = e;
        }
        else
        {
          throw new DomatarException(e);
        }
      }
      finally
      {
        close(rset, ins, sel, conn);
      }
    }

    throw new DomatarException("op_msg seq retries exhausted", last);
  }

  public static int priorVisitCount(
      final String hstId, final String contextId, final String dstDomId,
      final String msgName) throws DomatarException
  {
    DbConnection conn = null;
    PreparedStatement pstmt = null;
    ResultSet rset = null;

    try
    {
      conn = new DbConnection(OpLogDb.class, "priorVisitCount");
      pstmt = conn.prepareStatement(
          "SELECT VisitCount FROM op_dst"
          + " WHERE HstId=? AND ContextId=? AND DstDomId=? AND MsgName=?");
      pstmt.setString(1, hstId);
      pstmt.setString(2, contextId);
      pstmt.setString(3, dstDomId);
      pstmt.setString(4, msgName);
      rset = pstmt.executeQuery();
      if (!rset.next())
        return 0;
      return rset.getInt(1);
    }
    catch (final SQLException e)
    {
      throw new DomatarException(e);
    }
    finally
    {
      close(rset, pstmt, null, conn);
    }
  }

  public static int priorVisitCountAny(
      final String hstId, final String contextId, final String dstDomId)
      throws DomatarException
  {
    DbConnection conn = null;
    PreparedStatement pstmt = null;
    ResultSet rset = null;

    try
    {
      conn = new DbConnection(OpLogDb.class, "priorVisitCountAny");
      pstmt = conn.prepareStatement(
          "SELECT COALESCE(SUM(VisitCount),0) FROM op_dst"
          + " WHERE HstId=? AND ContextId=? AND DstDomId=?");
      pstmt.setString(1, hstId);
      pstmt.setString(2, contextId);
      pstmt.setString(3, dstDomId);
      rset = pstmt.executeQuery();
      rset.next();
      return rset.getInt(1);
    }
    catch (final SQLException e)
    {
      throw new DomatarException(e);
    }
    finally
    {
      close(rset, pstmt, null, conn);
    }
  }

  public static List<OpMsg> listOutMsgs(
      final String hstId, final String contextId, final String srcDomId)
      throws DomatarException
  {
    DbConnection conn = null;
    PreparedStatement pstmt = null;
    ResultSet rset = null;
    final List<OpMsg> out = new ArrayList<>();

    try
    {
      conn = new DbConnection(OpLogDb.class, "listOutMsgs");
      pstmt = conn.prepareStatement(
          "SELECT HstId,ContextId,SrcDomId,DstDomId,OutMsgName,Seq,SentAt"
          + " FROM op_msg WHERE HstId=? AND ContextId=? AND SrcDomId=?"
          + " ORDER BY Seq ASC");
      pstmt.setString(1, hstId);
      pstmt.setString(2, contextId);
      pstmt.setString(3, srcDomId);
      rset = pstmt.executeQuery();
      while (rset.next())
      {
        out.add(new OpMsg(
            rset.getString(1), rset.getString(2), rset.getString(3),
            rset.getString(4), rset.getString(5), rset.getInt(6),
            rset.getLong(7)));
      }
      return out;
    }
    catch (final SQLException e)
    {
      throw new DomatarException(e);
    }
    finally
    {
      close(rset, pstmt, null, conn);
    }
  }

  public static void attach(
      final String hstId, final String contextId, final String dstDomId,
      final String msgName, final String slot, final String bodyJsonOrNull,
      final long slotExpiresAt) throws DomatarException
  {
    if (slot == null || !SLOT.matcher(slot).matches())
      throw new DomatarException("invalid attachment slot: " + slot);

    if (!visitExists(hstId, contextId, dstDomId, msgName))
    {
      LOG.warning("OpLogDb.attach: no visit row for " + hstId + " "
          + contextId + " " + dstDomId + " " + msgName);
      return;
    }

    final String path = "$." + slot;

    DbConnection conn = null;
    PreparedStatement pstmt = null;

    try
    {
      conn = new DbConnection(OpLogDb.class, "attach");

      if (bodyJsonOrNull == null)
      {
        pstmt = conn.prepareStatement(
            "UPDATE op_dst SET Attachment = JSON_REMOVE(Attachment, ?)"
            + " WHERE HstId=? AND ContextId=? AND DstDomId=? AND MsgName=?");
        pstmt.setString(1, path);
        pstmt.setString(2, hstId);
        pstmt.setString(3, contextId);
        pstmt.setString(4, dstDomId);
        pstmt.setString(5, msgName);
        pstmt.executeUpdate();
      }
      else
      {
        pstmt = conn.prepareStatement(
            "UPDATE op_dst SET Attachment = JSON_SET("
            + "COALESCE(Attachment, CAST('{}' AS JSON)), ?,"
            + "JSON_OBJECT('ExpiresAt', ?, 'Body', CAST(? AS JSON)))"
            + " WHERE HstId=? AND ContextId=? AND DstDomId=? AND MsgName=?");
        pstmt.setString(1, path);
        pstmt.setLong(2, slotExpiresAt);
        pstmt.setString(3, bodyJsonOrNull);
        pstmt.setString(4, hstId);
        pstmt.setString(5, contextId);
        pstmt.setString(6, dstDomId);
        pstmt.setString(7, msgName);
        pstmt.executeUpdate();
      }
    }
    catch (final SQLException e)
    {
      throw new DomatarException("attach JSON failed", e);
    }
    finally
    {
      close(null, pstmt, null, conn);
    }

    recomputeAttachExpiresAt(hstId, contextId, dstDomId, msgName);
  }

  public static String attachmentBody(
      final String hstId, final String contextId, final String dstDomId,
      final String msgName, final String slot) throws DomatarException
  {
    if (slot == null || !SLOT.matcher(slot).matches())
      throw new DomatarException("invalid attachment slot: " + slot);

    DbConnection conn = null;
    PreparedStatement pstmt = null;
    ResultSet rset = null;

    try
    {
      conn = new DbConnection(OpLogDb.class, "attachmentBody");
      pstmt = conn.prepareStatement(
          "SELECT JSON_UNQUOTE(JSON_EXTRACT(Attachment,"
          + " CONCAT('$.', ?, '.Body'))) FROM op_dst"
          + " WHERE HstId=? AND ContextId=? AND DstDomId=? AND MsgName=?");
      pstmt.setString(1, slot);
      pstmt.setString(2, hstId);
      pstmt.setString(3, contextId);
      pstmt.setString(4, dstDomId);
      pstmt.setString(5, msgName);
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
      close(rset, pstmt, null, conn);
    }
  }

  /**
   * Host-local GC. Deletes expired visits, then orphan {@code op_msg}
   * rows whose ContextId is gone on this HstId.
   */
  public static int gcHost(final String hstId, final long nowMs)
      throws DomatarException
  {
    DbConnection conn = null;
    PreparedStatement delDst = null;
    PreparedStatement delMsg = null;

    try
    {
      conn = new DbConnection(OpLogDb.class, "gcHost");
      delDst = conn.prepareStatement(
          "DELETE FROM op_dst WHERE HstId=? AND ? > VisitExpiresAt"
          + " AND (AttachExpiresAt IS NULL OR ? > AttachExpiresAt)");
      delDst.setString(1, hstId);
      delDst.setLong(2, nowMs);
      delDst.setLong(3, nowMs);
      final int dstN = delDst.executeUpdate();

      delMsg = conn.prepareStatement(
          "DELETE FROM op_msg WHERE HstId=? AND ContextId NOT IN"
          + " (SELECT ContextId FROM op_dst WHERE HstId=?)");
      delMsg.setString(1, hstId);
      delMsg.setString(2, hstId);
      final int msgN = delMsg.executeUpdate();
      return dstN + msgN;
    }
    catch (final SQLException e)
    {
      throw new DomatarException(e);
    }
    finally
    {
      close(null, delMsg, delDst, conn);
    }
  }

  private static boolean visitExists(
      final String hstId, final String contextId, final String dstDomId,
      final String msgName) throws DomatarException
  {
    return priorVisitCount(hstId, contextId, dstDomId, msgName) > 0;
  }

  private static void recomputeAttachExpiresAt(
      final String hstId, final String contextId, final String dstDomId,
      final String msgName) throws DomatarException
  {
    DbConnection conn = null;
    PreparedStatement sel = null;
    PreparedStatement upd = null;
    ResultSet rset = null;

    try
    {
      conn = new DbConnection(OpLogDb.class, "recomputeAttachExpiresAt");
      sel = conn.prepareStatement(
          "SELECT CAST(Attachment AS CHAR CHARACTER SET utf8mb4) FROM op_dst"
          + " WHERE HstId=? AND ContextId=? AND DstDomId=? AND MsgName=?");
      sel.setString(1, hstId);
      sel.setString(2, contextId);
      sel.setString(3, dstDomId);
      sel.setString(4, msgName);
      rset = sel.executeQuery();
      if (!rset.next())
        return;

      final String json = rset.getString(1);
      rset.close();
      rset = null;

      Long maxExp = null;
      boolean empty = json == null || json.isEmpty() || "null".equals(json);

      if (!empty)
      {
        final JsonMap map = Json.parseMap(json);
        if (map == null || map.isEmpty())
        {
          empty = true;
        }
        else
        {
          long max = Long.MIN_VALUE;
          boolean any = false;
          for (final Map.Entry<String, Object> e : map.entrySet())
          {
            if (!(e.getValue() instanceof JsonMap))
              continue;
            final Number n = ((JsonMap) e.getValue()).getNumber("ExpiresAt");
            if (n == null)
              continue;
            any = true;
            final long v = n.longValue();
            if (v > max)
              max = v;
          }
          if (any)
            maxExp = max;
          else
            empty = true;
        }
      }

      upd = conn.prepareStatement(
          "UPDATE op_dst SET Attachment = CASE WHEN ? THEN NULL ELSE Attachment END,"
          + " AttachExpiresAt=? "
          + " WHERE HstId=? AND ContextId=? AND DstDomId=? AND MsgName=?");
      upd.setBoolean(1, empty);
      if (maxExp == null)
        upd.setNull(2, Types.BIGINT);
      else
        upd.setLong(2, maxExp);
      upd.setString(3, hstId);
      upd.setString(4, contextId);
      upd.setString(5, dstDomId);
      upd.setString(6, msgName);
      upd.executeUpdate();
    }
    catch (final SQLException e)
    {
      throw new DomatarException(e);
    }
    finally
    {
      close(rset, upd, sel, conn);
    }
  }

  private static boolean isPkCollision(final SQLException e)
  {
    return e.getErrorCode() == 1062 || "23000".equals(e.getSQLState());
  }

  private static void close(
      final ResultSet rset, final Statement a, final Statement b,
      final DbConnection conn) throws DomatarException
  {
    try
    {
      if (rset != null)
        rset.close();
      if (a != null)
        a.close();
      if (b != null)
        b.close();
      if (conn != null)
        conn.close();
    }
    catch (final SQLException e)
    {
      throw new DomatarException(e);
    }
  }
}
