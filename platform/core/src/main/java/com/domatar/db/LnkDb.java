package com.domatar.db;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import com.domatar.util.IdGen;
import com.domatar.util.Lnk;
import com.domatar.util.DomId;
import com.domatar.util.DomatarException;

public class LnkDb
{
  // Column length limits that match the lnk (and obj) table DDL.
  private static final int MAX_OBJ_NAME = 40;
  private static final int MAX_OBJ_DESC = 100;

  /** Truncate s to maxLen characters; returns null if s is null. */
  private static String trunc(final String s, final int maxLen)
  {
    if (s == null)
      return null;

    if (s.length() <= maxLen)
      return s;

    return s.substring(0, maxLen);
  }

  public static void addLnk(Lnk lnk) throws DomatarException
  {
    DbConnection conn = null;
    PreparedStatement pstmt = null;

    try
    {
      conn = new DbConnection(LnkDb.class, "addLnk");
      pstmt = conn.prepareStatement("insert into lnk values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)");

      pstmt.setString(1, lnk.domId.hstId);
      pstmt.setString(2, lnk.domId.appId);
      pstmt.setString(3, lnk.domId.actId);
      pstmt.setString(4, lnk.domId.objId);
      pstmt.setString(5, lnk.lnkDomId.hstId);
      pstmt.setString(6, lnk.lnkDomId.appId);
      pstmt.setString(7, lnk.lnkDomId.actId);
      pstmt.setString(8, lnk.lnkDomId.objId);
      pstmt.setString(9, lnk.lnkClsAppId);
      pstmt.setString(10, lnk.lnkClsId);
      pstmt.setString(11, trunc(lnk.lnkObjName, MAX_OBJ_NAME));
      pstmt.setString(12, trunc(lnk.lnkObjDesc, MAX_OBJ_DESC));
      pstmt.setString(13, lnk.tagAppId);
      pstmt.setString(14, lnk.tag);
      pstmt.setString(15, lnk.val != null ? lnk.val : "");
      pstmt.setLong(16, lnk.seqNum);
      pstmt.executeUpdate();
    }
    catch (SQLException e)
    {
      throw new DomatarException(e);
    }
    finally
    {
      try
      {
        if (pstmt != null)
          pstmt.close();

        if (conn != null)
          conn.close();
      }
      catch (SQLException e)
      {
        throw new DomatarException(e);
      }
    }
  }

  /**
   * Rewrite LnkClsAppId / LnkClsId on an existing edge. No-op when the
   * row is missing. Used so install can repair {@code (domatar, app)}
   * root links to {@code (<appId>, app)} without deleting the lnk.
   */
  public static void reclassLnk(final DomId domId,
                                final DomId lnkId,
                                final String tagAppId,
                                final String tag,
                                final String newClsAppId,
                                final String newClsId) throws DomatarException
  {
    DbConnection conn = null;
    PreparedStatement pstmt = null;

    try
    {
      conn = new DbConnection(LnkDb.class, "reclassLnk");
      pstmt = conn.prepareStatement(
          "update lnk set LnkClsAppId=?, LnkClsId=? where "
              + "HstId=? and AppId=? and ActId=? and ObjId=? and "
              + "LnkHstId=? and LnkAppId=? and LnkActId=? and LnkObjId=? and "
              + "TagAppId=? and Tag=?");

      pstmt.setString(1, newClsAppId);
      pstmt.setString(2, newClsId);
      pstmt.setString(3, domId.hstId);
      pstmt.setString(4, domId.appId);
      pstmt.setString(5, domId.actId);
      pstmt.setString(6, domId.objId);
      pstmt.setString(7, lnkId.hstId);
      pstmt.setString(8, lnkId.appId);
      pstmt.setString(9, lnkId.actId);
      pstmt.setString(10, lnkId.objId);
      pstmt.setString(11, tagAppId);
      pstmt.setString(12, tag);
      pstmt.executeUpdate();
    }
    catch (SQLException e)
    {
      throw new DomatarException(e);
    }
    finally
    {
      try
      {
        if (pstmt != null)
          pstmt.close();

        if (conn != null)
          conn.close();
      }
      catch (SQLException e)
      {
        throw new DomatarException(e);
      }
    }
  }

  public static Lnk getLnk(DomId domId,
                           DomId lnkId,
                           String tagAppId,
                           String tag) throws DomatarException
  {
    DbConnection conn = null;
    Statement stmt = null;
    ResultSet rset = null;

    String queryStr = null;

    Lnk lnk;

    try
    {
      queryStr = "select LnkClsAppId, LnkClsId, LnkObjName, LnkObjDesc, Val, SeqNum " +
                 "from lnk where " +
                 "HstId='" + domId.hstId + "' and " +
                 "AppId='" + domId.appId + "' and " +
                 "ActId='" + domId.actId + "' and " +
                 "ObjId='" + domId.objId + "' and " +
                 "LnkHstId='" + lnkId.hstId + "' and " +
                 "LnkAppId='" + lnkId.appId + "' and " +
                 "LnkActId='" + lnkId.actId + "' and " +
                 "LnkObjId='" + lnkId.objId + "' and " +
                 "TagAppId='" + tagAppId + "' and " +
                 "Tag='" + tag + "' ";

      conn = new DbConnection(LnkDb.class, "getLnks");
      stmt = conn.createStatement();
      rset = stmt.executeQuery(queryStr);

      if (rset.next())
      {
        final String lnkClsAppId = rset.getString(1);
        final String lnkClsId = rset.getString(2);
        final String objName  = rset.getString(3);
        final String objDesc  = rset.getString(4);
        final String val      = rset.getString(5);
        final long seqNum     = rset.getLong(6);

        lnk = new Lnk(domId, lnkId, lnkClsAppId, lnkClsId, objName, objDesc, tagAppId, tag, val, seqNum);
      }
      else
        lnk = null;
    }
    catch (Exception e)
    {
      throw new DomatarException(e);
    }
    finally
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
      catch (Exception e)
      {
        throw new DomatarException(e);
      }
    }

    return lnk;
  }

  public static List<Lnk> getLnks(DomId domId,
                                  String tagAppId,
                                  String tag,
                                  String valExpression,
                                  String seqNumExpression,
                                  int maxResults,
                                  boolean desc) throws DomatarException
  {
    return getLnks(domId, tagAppId, tag, valExpression, seqNumExpression,
                   maxResults, desc, Long.MIN_VALUE);
  }

  /**
   * Pagination-aware variant. When startSeqNum != Long.MIN_VALUE only the
   * links at or beyond that cursor are returned in SeqNum order (>= for an
   * ascending page, <= when desc is set), so a caller can walk the children
   * of a large object one page at a time by re-issuing the query with the
   * previous page's NextSeqNum cursor.
   */
  public static List<Lnk> getLnks(DomId domId,
                                  String tagAppId,
                                  String tag,
                                  String valExpression,
                                  String seqNumExpression,
                                  int maxResults,
                                  boolean desc,
                                  long startSeqNum) throws DomatarException
  {
    DbConnection conn = null;
    Statement stmt = null;
    ResultSet rset = null;

    String queryStr = null;

    ArrayList<Lnk> list = new ArrayList<Lnk>(maxResults);

    try
    {
      // Build WHERE clause conditionally so that null filters don't
      // leave a dangling "and" keyword before "order by".
      StringBuilder where = new StringBuilder();
      where.append("HstId='"  + domId.hstId + "' and ");
      where.append("AppId='"  + domId.appId + "' and ");
      where.append("ActId='"  + domId.actId + "' and ");
      where.append("ObjId='"  + domId.objId + "'");

      if (tagAppId != null)
        where.append(" and TagAppId='" + tagAppId + "'");

      if (tag != null)
        where.append(" and Tag='" + tag + "'");

      if (valExpression != null)
        where.append(" and '" + valExpression + "'");

      if (seqNumExpression != null)
        where.append(" and '" + seqNumExpression + "'");

      // Paging cursor: SeqNum is the canonical sibling ordering, so an
      // ascending page starts at SeqNum >= startSeqNum (descending pages
      // walk downward with <=). startSeqNum is a long, so concatenation is
      // injection-safe.
      if (startSeqNum != Long.MIN_VALUE)
        where.append(" and SeqNum " + (desc ? "<=" : ">=") + " " + startSeqNum);

      queryStr = "select LnkHstId, LnkAppId, LnkActId, LnkObjId, LnkClsAppId, LnkClsId, LnkObjName, LnkObjDesc, Val, SeqNum " +
                 "from lnk where " + where +
                 " order by SeqNum" +
                 (desc ? " desc" : "");

      conn = new DbConnection(LnkDb.class, "getLnks");
      stmt = conn.createStatement();
      rset = stmt.executeQuery(queryStr);

      for (int i = 0; i < maxResults && rset.next(); i++)
      {
        String lnkHstId    = rset.getString(1);
        String lnkAppId    = rset.getString(2);
        String lnkActId    = rset.getString(3);
        String lnkObjId    = rset.getString(4);
        String lnkClsAppId = rset.getString(5);
        String lnkClsId    = rset.getString(6);
        String objName     = rset.getString(7);
        String objDesc     = rset.getString(8);
        String val         = rset.getString(9);
        long seqNum        = rset.getLong(10);

        DomId lnkDomId = new DomId(lnkHstId, lnkAppId, lnkActId, lnkObjId);

        list.add(new Lnk(domId, lnkDomId, lnkClsAppId, lnkClsId, objName, objDesc, tagAppId, tag, val, seqNum));
      }
    }
    catch (Exception e)
    {
      throw new DomatarException(e);
    }
    finally
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
      catch (Exception e)
      {
        throw new DomatarException(e);
      }
    }

    return list;
  }

  public static long getLnkNum(DomId domId,
                               String tagAppId,
                               String tag,
                               String valExpression,
                               String seqNumExpression) throws DomatarException
  {
    DbConnection conn = null;
    Statement stmt = null;
    ResultSet rset = null;

    String queryStr = null;

    long lnkNum;

    try
    {
      queryStr = "select count(*) " +
                 "from lnk where " +
                 "HstId='" + domId.hstId + "' and " +
                 "AppId='" + domId.appId + "' and " +
                 "ActId='" + domId.actId + "' and " +
                 "ObjId='" + domId.objId + "' and " +
                 "TagAppId='" + tagAppId + "' and " +
                 "Tag='" + tag + "' " +
                 (valExpression == null ? "" : " and '" + valExpression + "' ") +
                 (seqNumExpression == null ? "" : " and '" + seqNumExpression + "' ");

      conn = new DbConnection(LnkDb.class, "getLnks");
      stmt = conn.createStatement();
      rset = stmt.executeQuery(queryStr);

      if (rset.next())
        lnkNum = rset.getLong(1);
      else
        lnkNum = 0;
    }
    catch (Exception e)
    {
      throw new DomatarException(e);
    }
    finally
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
      catch (Exception e)
      {
        throw new DomatarException(e);
      }
    }

    return lnkNum;
  }



  public static void modifyLnksSeqNum(Lnk[] lnks) throws DomatarException
  {
    for (int i = 0; i < lnks.length; i++)
      modifyLnkSeqNum(lnks[i]);
  }

  public static void modifyLnkSeqNum(Lnk lnk) throws DomatarException
  {
    DbConnection conn = null;
    PreparedStatement pstmt = null;

    try
    {
      conn = new DbConnection(LnkDb.class, "modifyLnkSeqNum");
      pstmt = conn.prepareStatement("update lnk set SeqNum = ? " +
                                    "where HstId = ? and AppId = ? and ActId = ? and ObjId = ? and " +
                                    "LnkHstId = ? and LnkAppId = ? and LnkActId = ? and LnkObjId = ? and " +
                                    "TagAppId = ? and Tag = ?");

      pstmt.setLong(1, lnk.seqNum);
      pstmt.setString(2, lnk.domId.hstId);
      pstmt.setString(3, lnk.domId.appId);
      pstmt.setString(4, lnk.domId.actId);
      pstmt.setString(5, lnk.domId.objId);
      pstmt.setString(6, lnk.lnkDomId.hstId);
      pstmt.setString(7, lnk.lnkDomId.appId);
      pstmt.setString(8, lnk.lnkDomId.actId);
      pstmt.setString(9, lnk.lnkDomId.objId);
      pstmt.setString(10, lnk.tagAppId);
      pstmt.setString(11, lnk.tag);
      pstmt.executeUpdate();
    }
    catch (Exception e)
    {
      throw new DomatarException(e);
    }
    finally
    {
      try
      {
        if (pstmt != null)
          pstmt.close();

        if (conn != null)
          conn.close();
      }
      catch (Exception e)
      {
        throw new DomatarException(e);
      }
    }
  }

  public static void deleteLnks(DomId domId,
                                DomId lnkDomId,
                                String tagAppId,
                                String tag,
                                String valExpression,
                                String seqNumExpression) throws DomatarException
  {
    DbConnection conn = null;
    Statement stmt = null;
    ResultSet rset = null;

    String queryStr = null;

    try
    {
      StringBuilder where = new StringBuilder();
      where.append("HstId='"  + domId.hstId + "' and ");
      where.append("AppId='"  + domId.appId + "' and ");
      where.append("ActId='"  + domId.actId + "' and ");
      where.append("ObjId='"  + domId.objId + "'");

      if (tagAppId != null)
        where.append(" and TagAppId='" + tagAppId + "'");

      if (tag != null)
        where.append(" and Tag='" + tag + "'");

      if (valExpression != null)
        where.append(" and '" + valExpression + "'");

      if (seqNumExpression != null)
        where.append(" and '" + seqNumExpression + "'");

      String lnkStr;

      if (lnkDomId != null)
      {
        lnkStr =
        " and " +
        "LnkHstId='" + lnkDomId.hstId + "' and " +
        "LnkAppId='" + lnkDomId.appId + "' and " +
        "LnkActId='" + lnkDomId.actId + "' and " +
        "LnkObjId='" + lnkDomId.objId + "'";
      }
      else
        lnkStr = "";

      queryStr = "delete from lnk where " + where + lnkStr;

      conn = new DbConnection(LnkDb.class, "deleteLnks");
      stmt = conn.createStatement();
      stmt.execute(queryStr);
    }
    catch (Exception e)
    {
      throw new DomatarException(e);
    }
    finally
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
      catch (Exception e)
      {
        throw new DomatarException(e);
      }
    }
  }

  /**
   * Delete every lnk that originates on {@code hstId} OR points at an obj
   * on {@code hstId} (Login-Multiple cutover of {@code login-&lt;actId&gt;}).
   */
  public static void deleteByHstId(final String hstId) throws DomatarException
  {
    if (hstId == null || hstId.isEmpty())
      throw new DomatarException("deleteByHstId: missing hstId");

    DbConnection conn = null;
    Statement stmt = null;

    try
    {
      conn = new DbConnection(LnkDb.class, "deleteByHstId");
      stmt = conn.createStatement();
      stmt.execute("delete from lnk where HstId='" + hstId
          + "' or LnkHstId='" + hstId + "'");
    }
    catch (Exception e)
    {
      throw new DomatarException(e);
    }
    finally
    {
      try
      {
        if (stmt != null)
          stmt.close();
        if (conn != null)
          conn.close();
      }
      catch (Exception e)
      {
        throw new DomatarException(e);
      }
    }
  }

  /**
   * Delete every lnk whose target LnkAppId matches and whose LnkObjId is in
   * the closed range [prefix + "-", olderThanId]. This is the parallel to
   * ObjDb.deleteObjRange for the lnk table: after purging old obj rows the
   * corresponding lnk rows must also be removed or the Navigator will show
   * "Obj not found" for every orphaned link.
   *
   * The lower-bound prefix guard is required for the same reason as in
   * deleteObjRange: without it a purge of "log-" rows would accidentally
   * delete every link whose LnkObjId sorts before the upper bound.
   */
  public static void deleteLnkRange(String lnkAppId, String prefix, String from, String to) throws DomatarException
  {
    DbConnection conn = null;
    Statement stmt = null;

    String fromId = IdGen.createId(prefix, from);
    String toId   = IdGen.createId(prefix, to);

    String queryStr = "delete from lnk where " +
                      "LnkAppId='" + lnkAppId + "' and " +
                      "LnkObjId>='" + fromId + "' and " +
                      "LnkObjId<='" + toId + "'";

    try
    {
      conn = new DbConnection(LnkDb.class, "deleteLnkRange");
      stmt = conn.createStatement();
      stmt.execute(queryStr);
    }
    catch (Exception e)
    {
      throw new DomatarException(e);
    }
    finally
    {
      try
      {
        if (stmt != null)
          stmt.close();

        if (conn != null)
          conn.close();
      }
      catch (Exception e)
      {
        throw new DomatarException(e);
      }
    }
  }
}
