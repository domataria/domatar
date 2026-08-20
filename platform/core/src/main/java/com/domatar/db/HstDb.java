package com.domatar.db;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import com.domatar.util.Hst;
import com.domatar.util.DomatarException;

public class HstDb
{
  // Centralised select clause: keeps the column order in sync between getHst,
  // getHsts, and the row-mapper.
  private static final String SELECT_COLS =
      "Domain, PrvId, Version, FetchedAt, PubKey, RecordSig";

  // -------------------------------------------------------------------------
  // addHst overloads
  // -------------------------------------------------------------------------

  public static void addHst(final String hstId, final String domain, final String prvId) throws DomatarException
  {
    addHst(hstId, domain, prvId, 1L, System.currentTimeMillis(), null, null);
  }

  public static void addHst(final String hstId, final String domain, final String prvId,
                             final long version, final long fetchedAt)
      throws DomatarException
  {
    addHst(hstId, domain, prvId, version, fetchedAt, null, null);
  }

  public static void addHst(final String hstId, final String domain, final String prvId,
                             final long version, final long fetchedAt,
                             final String pubKey, final String recordSig)
      throws DomatarException
  {
    DbConnection conn = null;

    try
    {
      conn = new DbConnection(HstDb.class, "addHst");
      addHst(hstId, domain, prvId, version, fetchedAt, pubKey, recordSig, conn);
    }
    catch (SQLException e)
    {
      throw new DomatarException(e);
    }
    finally
    {
      try
      {
        if (conn != null)
          conn.close();
      }
      catch (SQLException e)
      {
        throw new DomatarException(e);
      }
    }
  }

  static void addHst(final String hstId,
                     final String domain,
                     final String prvId,
                     final long   version,
                     final long   fetchedAt,
                     final DbConnection conn) throws DomatarException
  {
    addHst(hstId, domain, prvId, version, fetchedAt, null, null, conn);
  }

  static void addHst(final String hstId,
                     final String domain,
                     final String prvId,
                     final long   version,
                     final long   fetchedAt,
                     final String pubKey,
                     final String recordSig,
                     final DbConnection conn) throws DomatarException
  {
    PreparedStatement pstmt = null;

    try
    {
      pstmt = conn.prepareStatement(
          "insert into hst (HstId, Domain, PrvId, Version, FetchedAt, PubKey, RecordSig)"
          + " values(?,?,?,?,?,?,?)");

      pstmt.setString(1, hstId);
      pstmt.setString(2, domain);
      pstmt.setString(3, prvId);
      pstmt.setLong  (4, version);
      pstmt.setLong  (5, fetchedAt);
      pstmt.setString(6, pubKey);
      pstmt.setString(7, recordSig);
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
      }
      catch (SQLException e)
      {
        throw new DomatarException(e);
      }
    }
  }

  public static Hst getHst(String hstId) throws DomatarException
  {
    DbConnection conn = null;
    Statement stmt = null;
    ResultSet rset = null;

    Hst hst = null;
    String queryStr = null;

    try
    {
        queryStr = "select " + SELECT_COLS + " " +
                   "from hst where " +
                   "HstId='" + hstId + "'";

        conn = new DbConnection(HstDb.class, "getHst");
        stmt = conn.createStatement();
        rset = stmt.executeQuery(queryStr);

        if (rset.next())
          hst = readRow(hstId, rset);
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

    return hst;
  }

  public static List<Hst> getAllHsts() throws DomatarException
  {
    DbConnection conn = null;
    Statement    stmt = null;
    ResultSet    rset = null;

    List<Hst> hsts = new ArrayList<Hst>();

    try
    {
      conn = new DbConnection(HstDb.class, "getAllHsts");
      stmt = conn.createStatement();
      rset = stmt.executeQuery(
          "select HstId, " + SELECT_COLS + " from hst order by HstId");

      while (rset.next())
      {
        String hstId = rset.getString("HstId");
        hsts.add(readRow(hstId, rset));
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

    return hsts;
  }

  public static Hst[] getHsts(String[] hstIds) throws DomatarException
  {
    DbConnection conn = null;
    Statement stmt = null;
    ResultSet rset = null;

    ArrayList<Hst> hstList = new ArrayList<Hst>();

    String queryStr = null;

    try
    {
        conn = new DbConnection(HstDb.class, "getHsts");

        for (String hstId : hstIds)
        {
          queryStr = "select " + SELECT_COLS + " " +
                     "from hst where " +
                     "HstId='" + hstId + "'";

          stmt = conn.createStatement();
          rset = stmt.executeQuery(queryStr);

          if (rset.next())
            hstList.add(readRow(hstId, rset));
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

    Hst[] hsts = new Hst[hstList.size()];

    return hstList.toArray(hsts);
  }

  public static void deleteHst(String hstId) throws DomatarException
  {
    DbConnection conn = null;
    Statement    stmt = null;

    try
    {
      conn = new DbConnection(HstDb.class, "deleteHst");
      stmt = conn.createStatement();
      stmt.executeUpdate("delete from hst where HstId='" + hstId + "'");
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

  // -------------------------------------------------------------------------
  // modifyHst overloads
  // -------------------------------------------------------------------------

  public static void modifyHst(String hstId, String domain, String prvId) throws DomatarException
  {
    modifyHst(hstId, domain, prvId, 0L, System.currentTimeMillis(), null, null);
  }

  public static void modifyHst(String hstId, String domain, String prvId,
                               long version, long fetchedAt) throws DomatarException
  {
    modifyHst(hstId, domain, prvId, version, fetchedAt, null, null);
  }

  public static void modifyHst(String hstId, String domain, String prvId,
                               long version, long fetchedAt,
                               String pubKey, String recordSig) throws DomatarException
  {
    DbConnection conn = null;
    Statement stmt = null;

    StringBuilder setStr = new StringBuilder();

    if (domain != null)
      append(setStr, "Domain='" + domain + "'");

    if (prvId != null)
      append(setStr, "PrvId='" + prvId + "'");

    if (version > 0)
      append(setStr, "Version=" + version);

    append(setStr, "FetchedAt=" + fetchedAt);

    if (pubKey != null)
      append(setStr, "PubKey='" + pubKey + "'");

    if (recordSig != null)
      append(setStr, "RecordSig='" + recordSig + "'");

    String sqlStr = "update hst set " + setStr + " where HstId='" + hstId + "'";

    try
    {
      conn = new DbConnection(HstDb.class, "modifyHst");
      stmt = conn.createStatement();
      stmt.executeUpdate(sqlStr);
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
   * Updates only the {@code PubKey} and {@code RecordSig} columns for the given
   * host.  Used by the directory node after signing a record (Phase 3+).
   */
  public static void updateHstKeys(final String hstId,
                                   final String pubKey,
                                   final String recordSig) throws DomatarException
  {
    DbConnection conn = null;
    Statement    stmt = null;

    StringBuilder setStr = new StringBuilder();

    if (pubKey    != null) append(setStr, "PubKey='"    + pubKey    + "'");
    if (recordSig != null) append(setStr, "RecordSig='" + recordSig + "'");

    if (setStr.length() == 0)
      return; // nothing to set

    final String sqlStr = "update hst set " + setStr + " where HstId='" + hstId + "'";

    try
    {
      conn = new DbConnection(HstDb.class, "updateHstKeys");
      stmt = conn.createStatement();
      stmt.executeUpdate(sqlStr);
    }
    catch (Exception e)
    {
      throw new DomatarException(e);
    }
    finally
    {
      try
      {
        if (stmt != null) stmt.close();
        if (conn != null) conn.close();
      }
      catch (Exception e)
      {
        throw new DomatarException(e);
      }
    }
  }

  // -------------------------------------------------------------------------
  // updateHst overloads (upsert: add if missing, modify if present)
  // -------------------------------------------------------------------------

  public static void updateHst(String hstId, String domain, String prvId) throws DomatarException
  {
    updateHst(hstId, domain, prvId, 0L, System.currentTimeMillis(), null, null);
  }

  public static void updateHst(String hstId, String domain, String prvId,
                               long version, long fetchedAt) throws DomatarException
  {
    updateHst(hstId, domain, prvId, version, fetchedAt, null, null);
  }

  public static void updateHst(String hstId, String domain, String prvId,
                               long version, long fetchedAt,
                               String pubKey, String recordSig) throws DomatarException
  {
    Hst hst = getHst(hstId);

    if (hst == null)
      addHst(hstId, domain, prvId, version > 0 ? version : 1L, fetchedAt, pubKey, recordSig);
    else
      modifyHst(hstId, domain, prvId, version, fetchedAt, pubKey, recordSig);
  }

  private static Hst readRow(String hstId, ResultSet rset) throws SQLException
  {
    String domain    = rset.getString("Domain");
    String prvId     = rset.getString("PrvId");
    long   version   = rset.getLong("Version");      // 0 if NULL
    long   fetchedAt = rset.getLong("FetchedAt");    // 0 if NULL
    String pubKey    = rset.getString("PubKey");     // NULL before Phase 3
    String recordSig = rset.getString("RecordSig");  // NULL before Phase 3

    return new Hst(hstId, domain, prvId, version, fetchedAt, pubKey, recordSig);
  }

  private static void append(StringBuilder sb, String clause)
  {
    if (sb.length() > 0)
      sb.append(", ");

    sb.append(clause);
  }
}
