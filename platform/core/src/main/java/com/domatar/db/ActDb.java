/*
 * Copyright (c) 2024 Domatar
 */

package com.domatar.db;

import com.domatar.crypto.AccountKeys;
import com.domatar.util.Act;
import com.domatar.util.Base64Encoder;
import com.domatar.util.DomatarException;

import java.security.MessageDigest;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class ActDb
{
  /**
   * Token plus the one-shot lineage permit for the actId just inserted.
   */
  public static final class NewAct
  {
    public final String token;
    public final LineagePermit permit;

    private NewAct(final String token, final LineagePermit permit)
    {
      this.token = token;
      this.permit = permit;
    }
  }

  private static final Random random = new Random();

  /**
   * Creates a new account row. The {@code ownPrvKeySealed} parameter holds
   * the AES-256-GCM sealed Ed25519 OWNERSHIP private key produced by
   * {@code MasterKey.seal()} (Spec-OwnIds.txt PART 5.2; formerly the account
   * root key in Spec-Security.txt PART 5.4). Pass {@code null} for callers
   * that have not yet adopted keypair minting; the column is nullable so
   * those rows still load correctly. The genesis private key is never stored
   * here (Spec-OwnIds.txt PART 7).
   */
  public static NewAct addAct(String hostId, String domain, String prvId,
                              String actId, String usrId, String usrName,
                              String pwd, String ip,
                              String ownPrvKeySealed) throws DomatarException
  {
    DbConnection conn = null;
    PreparedStatement pstmt = null;

    String token = getToken();
    long time = System.currentTimeMillis();

    try
    {
      conn = new DbConnection(ObjDb.class, "addAct");
      pstmt = conn.prepareStatement(
          "insert into act (ActId,UsrId,UsrName,Pwd,Ip1,Token1,Time1,Encryption,FpVersion,OwnPrvKey)" +
          " values (?,?,?,?,?,?,?,1,?,?)");

      pstmt.setString(1, actId);
      pstmt.setString(2, usrId);
      pstmt.setString(3, usrName);
      pstmt.setString(4, encrypt(pwd, 1));
      pstmt.setString(5, ip);
      pstmt.setString(6, token);
      pstmt.setLong(7, time);
      pstmt.setInt(8, AccountKeys.defaultVersion());
      pstmt.setString(9, ownPrvKeySealed);
      pstmt.executeUpdate();

      // Idempotent hst row creation: with multi-app linking
      // (Spec-Login.txt PART 5) the per-user sub-host may already
      // exist from an earlier signup on the same actId, or - when
      // this central host is the Login app - from this same addAct's
      // ensureLoginSubHst preflight (Spec-LoginApp.txt PART 6). Skip
      // the insert in that case rather than throwing on the duplicate
      // PK so the rest of addAct can complete.
      if (HstDb.getHst(hostId) == null)
        HstDb.addHst(hostId, domain, prvId, 1L, System.currentTimeMillis(), conn);
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

    return new NewAct(token, new LineagePermit(actId));
  }

  /** Back-compat overload: creates an act row without a sealed ownership key (OwnPrvKey = NULL). */
  public static NewAct addAct(String hostId, String domain, String prvId,
                               String actId, String usrId, String usrName,
                               String pwd, String ip) throws DomatarException
  {
    return addAct(hostId, domain, prvId, actId, usrId, usrName, pwd, ip, null);
  }

  /** Returns the sealed ownership private key for an account, or null if not yet set. */
  public static String getOwnPrvKey(String actId) throws DomatarException
  {
    DbConnection conn = null;
    PreparedStatement pstmt = null;
    ResultSet rset = null;

    try
    {
      conn  = new DbConnection(ActDb.class, "getOwnPrvKey");
      pstmt = conn.prepareStatement("select OwnPrvKey from act where ActId=?");
      pstmt.setString(1, actId);
      rset  = pstmt.executeQuery();

      return rset.next() ? rset.getString(1) : null;
    }
    catch (SQLException e)
    {
      throw new DomatarException(e);
    }
    finally
    {
      try
      {
        if (rset  != null) rset.close();
        if (pstmt != null) pstmt.close();
        if (conn  != null) conn.close();
      }
      catch (SQLException e) { throw new DomatarException(e); }
    }
  }

  /** Writes (or overwrites) the sealed ownership private key for an account. */
  public static void setOwnPrvKey(String actId, String sealed) throws DomatarException
  {
    DbConnection conn = null;
    PreparedStatement pstmt = null;

    try
    {
      conn  = new DbConnection(ActDb.class, "setOwnPrvKey");
      pstmt = conn.prepareStatement("update act set OwnPrvKey=? where ActId=?");
      pstmt.setString(1, sealed);
      pstmt.setString(2, actId);
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
        if (pstmt != null) pstmt.close();
        if (conn  != null) conn.close();
      }
      catch (SQLException e) { throw new DomatarException(e); }
    }
  }

  public static List<Act> getAllActs() throws DomatarException
  {
    DbConnection conn = null;
    Statement    stmt = null;
    ResultSet    rset = null;

    List<Act> acts = new ArrayList<Act>();

    try
    {
      conn = new DbConnection(ObjDb.class, "getAllActs");
      stmt = conn.createStatement();
      rset = stmt.executeQuery("select ActId, UsrId, UsrName from act order by ActId");

      while (rset.next())
      {
        final String actId   = rset.getString("ActId");
        final String usrId   = rset.getString("UsrId");
        final String usrName = rset.getString("UsrName");
        acts.add(new Act(actId, usrId, usrName, null));
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

    return acts;
  }

  public static Act getAct(String actId) throws DomatarException
  {
    DbConnection conn = null;
    Statement stmt = null;
    ResultSet rset = null;

    String queryStr = null;

    Act act = null;

    try
    {
      queryStr = "select UsrId, UsrName " +
                 "from act where " +
                 "ActId='" + actId + "'";

      conn = new DbConnection(ObjDb.class, "getObj");
      stmt = conn.createStatement();
      rset = stmt.executeQuery(queryStr);

      if (rset.next())
      {
        final String usrId   = rset.getString(1);
        final String usrName = rset.getString(2);

        act = new Act(actId, usrId, usrName, null);
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

    return act;
  }

  public static Act getActByUsrId(String usrId) throws DomatarException
  {
    DbConnection conn = null;
    Statement stmt = null;
    ResultSet rset = null;

    String queryStr = null;

    Act act = null;

    try
    {
      queryStr = "select ActId, UsrName " +
                 "from act where " +
                 "UsrId='" + usrId + "'";

      conn = new DbConnection(ObjDb.class, "getObj");
      stmt = conn.createStatement();
      rset = stmt.executeQuery(queryStr);

      if (rset.next())
      {
        final String actId   = rset.getString(1);
        final String usrName = rset.getString(2);

        act = new Act(actId, usrId, usrName, null);
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

    return act;
  }

  public static Act login(String usrId, String ip, String pwd) throws DomatarException
  {
    DbConnection conn = null;
    Statement stmt = null;
    ResultSet rset = null;

    String queryStr = null;

    Act act = null;

    try
    {
      queryStr = "select ActId, UsrName, Pwd, Ip1, Token1, Time1, Ip2, Token2, Time3, Ip3, Token3, Time3, Encryption " +
                 "from act where " +
                 "UsrId='" + usrId + "'";

      conn = new DbConnection(ObjDb.class, "getObj");
      stmt = conn.createStatement();
      rset = stmt.executeQuery(queryStr);

      if (rset.next())
      {
        final String actId   = rset.getString(1);
        final String usrName = rset.getString(2);
        final String dbpwd   = rset.getString(3);
        final String ip1     = rset.getString(4);
        final String token1  = rset.getString(5);
        final long time1     = rset.getLong(6);
        final String ip2     = rset.getString(7);
        final String token2  = rset.getString(8);
        final long time2     = rset.getLong(9);
        final String ip3     = rset.getString(10);
        final String token3  = rset.getString(11);
        final long time3     = rset.getLong(12);
        final int encryption = rset.getInt(13);

        if (dbpwd != null && pwd != null && dbpwd.equals(encrypt(pwd, encryption)))
        {
          String token;

          // It shouldn't happen that you login twice from the same ip, but we check just in case
          if (ip1 != null && ip1.equals(ip))
            token = token1;
          else if (ip2 != null && ip2.equals(ip))
            token = token2;
          else if (ip3 != null && ip3.equals(ip))
            token = token3;
          else
          {
            String ind;

            if (token1 == null)
              ind = "1";
            else if (token2 == null)
              ind = "2";
            else if (token3 == null)
              ind = "3";
            else if (time1 < time2 && time1 < time3) // login1 is oldest
              ind = "1";
            else if (time2 < time1 && time2 < time3) // login2 is oldest
              ind = "2";
            else                                     // login3 is oldest
              ind = "3";

            token = getToken();
            String time = Long.toString(System.currentTimeMillis());

            String sqlStr = "update act set ip" + ind + "='" + ip + "', " +
                "token" + ind + "='" + token + "', " +
                "time" + ind + "=" + time + " " +
                "where UsrId='" + usrId + "'";

            stmt.executeUpdate(sqlStr);
          }

          act = new Act(actId, usrId, usrName, token);
        }
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

    return act;
  }

  /**
   * Patch UsrId / UsrName on an existing act row, identified by
   * (UsrId, ActId). Either of newUsrId or newUsrName may be null to
   * leave that column untouched. Returns true iff a row was actually
   * updated.
   *
   * Keying on UsrId (the row's stable identifier - one act row per
   * &lt;localname&gt;@&lt;appId&gt;) lets a single physical database
   * carry multiple rows for the same ActId in the local simulation
   * where central hosts share a MySQL. ActId is included as an
   * authorization sanity check: even if two central hosts shared a
   * UsrId namespace by accident, an UpdateAct from a verified caller
   * for actId=X would not touch a row whose ActId is Y.
   *
   * Caller is responsible for authorization (verified+owner-match)
   * and uniqueness checks on newUsrId before calling this.
   */
  public static boolean updateAct(String actId, String usrId, String newUsrId, String newUsrName) throws DomatarException
  {
    if (newUsrId == null && newUsrName == null)
      return false;

    DbConnection conn = null;
    PreparedStatement pstmt = null;

    try
    {
      StringBuilder sets = new StringBuilder();

      if (newUsrId != null)
        sets.append("UsrId=?");

      if (newUsrName != null)
      {
        if (sets.length() > 0)
          sets.append(", ");

        sets.append("UsrName=?");
      }

      String sql = "update act set " + sets + " where UsrId=? and ActId=?";

      conn = new DbConnection(ActDb.class, "updateAct");
      pstmt = conn.prepareStatement(sql);

      int idx = 1;

      if (newUsrId != null)
        pstmt.setString(idx++, newUsrId);

      if (newUsrName != null)
        pstmt.setString(idx++, newUsrName);

      pstmt.setString(idx++, usrId);
      pstmt.setString(idx,   actId);

      int rows = pstmt.executeUpdate();

      return rows > 0;
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
   * Verify oldPwd against the row's stored hash and, on match, replace
   * the hash with the encryption of newPwd. Returns true iff the
   * password was actually changed; returns false if the old password
   * did not match (NOT an exception - "wrong password" is a normal
   * answer to "change my password").
   */
  public static boolean changePwd(String actId, String usrId, String oldPwd, String newPwd) throws DomatarException
  {
    DbConnection conn = null;
    PreparedStatement pstmt = null;
    ResultSet rset = null;

    try
    {
      conn = new DbConnection(ActDb.class, "changePwd");

      pstmt = conn.prepareStatement("select Pwd, Encryption from act where UsrId=? and ActId=?");
      pstmt.setString(1, usrId);
      pstmt.setString(2, actId);
      rset = pstmt.executeQuery();

      if (!rset.next())
        return false;

      String dbpwd = rset.getString(1);
      int    enc   = rset.getInt(2);

      if (dbpwd == null || !dbpwd.equals(encrypt(oldPwd, enc)))
        return false;

      rset.close();
      pstmt.close();

      pstmt = conn.prepareStatement("update act set Pwd=? where UsrId=? and ActId=?");
      pstmt.setString(1, encrypt(newPwd, enc));
      pstmt.setString(2, usrId);
      pstmt.setString(3, actId);

      int rows = pstmt.executeUpdate();

      return rows > 0;
    }
    catch (SQLException e)
    {
      throw new DomatarException(e);
    }
    finally
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
      catch (SQLException e)
      {
        throw new DomatarException(e);
      }
    }
  }

  /**
   * Delete an act row. Caller is responsible for the "at least one
   * other linked login remains" check (via the Login app's directory
   * on login~&lt;actId&gt;) before calling this - ActDb is a thin
   * persistence layer and does not know about cross-app linkage.
   */
  public static boolean deleteAct(String actId, String usrId) throws DomatarException
  {
    DbConnection conn = null;
    PreparedStatement pstmt = null;

    try
    {
      conn = new DbConnection(ActDb.class, "deleteAct");
      pstmt = conn.prepareStatement("delete from act where UsrId=? and ActId=?");
      pstmt.setString(1, usrId);
      pstmt.setString(2, actId);

      int rows = pstmt.executeUpdate();

      return rows > 0;
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

  public static boolean logout(String usrId, String ip) throws DomatarException
  {
    DbConnection conn = null;
    Statement stmt = null;
    ResultSet rset = null;

    String queryStr = null;

    boolean loggedOut = false;

    try
    {
      queryStr = "select Ip1, Ip2, Ip3 " +
                 "from act where " +
                 "UsrId='" + usrId;

      conn = new DbConnection(ObjDb.class, "getObj");
      stmt = conn.createStatement();
      rset = stmt.executeQuery(queryStr);

      if (rset.next())
      {
        final String ip1 = rset.getString(1);
        final String ip2 = rset.getString(2);
        final String ip3 = rset.getString(3);

        String ind = null;

        if (ip1 != null && ip1.equals(ip))
          ind = "1";
        else if (ip2 != null && ip2.equals(ip))
          ind = "2";
        else if (ip3 != null && ip3.equals(ip))
          ind = "3";

        if (ind != null)
        {
          String sqlStr = "update act set ip" + ind + " = NULL, " +
                          "token" + ind + " = NULL, " +
                          "time" + ind + " = NULL " +
                          "where UsrId='" + usrId + "'";

          stmt.executeUpdate(sqlStr);

          loggedOut = true;
        }
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

    return loggedOut;
  }

  public static Act verifyLogin(String actId, String usrId, String ip, String token) throws DomatarException
  {
    DbConnection conn = null;
    Statement stmt = null;
    ResultSet rset = null;

    String queryStr = null;

    boolean loggedIn = false;

    Act act = null;

    try
    {
      String where = (actId == null ? "UsrId='" + usrId + "'" : "ActId='" + actId + "'");

      queryStr = "select ActId, UsrId, UsrName, Ip1, Token1, Ip2, Token2, Ip3, Token3 " +
                 "from act where " + where;

      conn = new DbConnection(ObjDb.class, "getObj");
      stmt = conn.createStatement();
      rset = stmt.executeQuery(queryStr);

      if (rset.next())
      {
        final String actIdDb = rset.getString(1);
        final String usrIdDb = rset.getString(2);
        final String usrName = rset.getString(3);
        final String ip1     = rset.getString(4);
        final String token1  = rset.getString(5);
        final String ip2     = rset.getString(6);
        final String token2  = rset.getString(7);
        final String ip3     = rset.getString(8);
        final String token3  = rset.getString(9);

/*
        if (token1 != null && ip1 != null && token1.equals(token) && ip1.equals(ip))
          loggedIn = true;
        else if (token2 != null && ip2 != null && token2.equals(token) && ip2.equals(ip))
          loggedIn = true;
        else if (token3 != null && ip3 != null && token3.equals(token) && ip3.equals(ip))
          loggedIn = true
*/

        if (token1 != null && token1.equals(token))
          loggedIn = true;
        else if (token2 != null && token2.equals(token))
          loggedIn = true;
        else if (token3 != null && token3.equals(token))
          loggedIn = true;

        if (loggedIn)
          act = new Act(actIdDb, usrIdDb, usrName, token);
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

    return act;
  }

  private static String encrypt(String password, int encryption) throws DomatarException
  {
    try
    {
      if (encryption == 1)
      {
        MessageDigest sha = MessageDigest.getInstance("SHA-1");

        byte [] bytes = password.getBytes("UTF8");

        sha.update(bytes);

        return Base64Encoder.encode(sha.digest());
      }
      else
        throw new DomatarException("Unsupported Encryption");
    }
    catch (Exception e)
    {
      throw new DomatarException(e);
    }
  }

  // -------------------------------------------------------------------------
  // Delegation (Phase 4+)
  // -------------------------------------------------------------------------

  /**
   * Value holder for the three delegation columns read by
   * {@link #getDelegationRow(String)}.
   */
  public static final class DelegationRow
  {
    public final String delegation;
    public final String delegSig;
    public final long   delegNotAfter;

    public DelegationRow(final String delegation, final String delegSig, final long delegNotAfter)
    {
      this.delegation    = delegation;
      this.delegSig      = delegSig;
      this.delegNotAfter = delegNotAfter;
    }
  }

  /**
   * Stored fingerprint version for {@code actId}; returns 1 when the row is
   * missing or FpVersion is NULL (KD3 / Spec-ActId-Versioning.txt PART 3.2).
   */
  public static int getFpVersion(final String actId) throws DomatarException
  {
    DbConnection      conn  = null;
    PreparedStatement pstmt = null;
    ResultSet         rset  = null;

    try
    {
      conn  = new DbConnection(ActDb.class, "getFpVersion");
      pstmt = conn.prepareStatement("select FpVersion from act where ActId=?");
      pstmt.setString(1, actId);
      rset  = pstmt.executeQuery();

      if (!rset.next())
        return 1;

      final int version = rset.getInt("FpVersion");

      if (rset.wasNull())
        return 1;

      return version;
    }
    catch (final SQLException e)
    {
      throw new DomatarException(e);
    }
    finally
    {
      try
      {
        if (rset  != null) rset.close();
        if (pstmt != null) pstmt.close();
        if (conn  != null) conn.close();
      }
      catch (final SQLException e) { throw new DomatarException(e); }
    }
  }

  /**
   * Returns the stored delegation columns for {@code actId}, or {@code null}
   * if the row is missing or the Delegation column is NULL.
   */
  public static DelegationRow getDelegationRow(final String actId) throws DomatarException
  {
    DbConnection      conn  = null;
    PreparedStatement pstmt = null;
    ResultSet         rset  = null;

    try
    {
      conn  = new DbConnection(ActDb.class, "getDelegationRow");
      pstmt = conn.prepareStatement(
          "select Delegation, DelegSig, DelegNotAfter from act where ActId=?");
      pstmt.setString(1, actId);
      rset  = pstmt.executeQuery();

      if (!rset.next())
        return null;

      final String delegation    = rset.getString("Delegation");
      final String delegSig      = rset.getString("DelegSig");
      final long   delegNotAfter = rset.getLong("DelegNotAfter");

      return (delegation == null) ? null
                                  : new DelegationRow(delegation, delegSig, delegNotAfter);
    }
    catch (final SQLException e)
    {
      throw new DomatarException(e);
    }
    finally
    {
      try
      {
        if (rset  != null) rset.close();
        if (pstmt != null) pstmt.close();
        if (conn  != null) conn.close();
      }
      catch (final SQLException e) { throw new DomatarException(e); }
    }
  }

  /**
   * Stores (or replaces) the active delegation for {@code actId}.
   *
   * @param actId        account fingerprint
   * @param delegation   JSON string of the delegation object
   * @param delegSig     Base64Encoder signature
   * @param delegNotAfter expiry timestamp (ms since epoch) for fast checks
   */
  public static void setDelegation(final String actId,
                                   final String delegation,
                                   final String delegSig,
                                   final long   delegNotAfter) throws DomatarException
  {
    DbConnection      conn  = null;
    PreparedStatement pstmt = null;

    try
    {
      conn  = new DbConnection(ActDb.class, "setDelegation");
      pstmt = conn.prepareStatement(
          "update act set Delegation=?, DelegSig=?, DelegNotAfter=? where ActId=?");
      pstmt.setString(1, delegation);
      pstmt.setString(2, delegSig);
      pstmt.setLong  (3, delegNotAfter);
      pstmt.setString(4, actId);
      pstmt.executeUpdate();
    }
    catch (final SQLException e)
    {
      throw new DomatarException(e);
    }
    finally
    {
      try
      {
        if (pstmt != null) pstmt.close();
        if (conn  != null) conn.close();
      }
      catch (final SQLException e) { throw new DomatarException(e); }
    }
  }

  // -------------------------------------------------------------------------
  // Binding (OwnIds Phase 2+)
  // -------------------------------------------------------------------------

  /**
   * Value holder for the binding columns read by {@link #getBindingRow(String)}.
   */
  public static final class BindingRow
  {
    public final String genesisPubKey;
    public final String ownPubKey;
    public final String bindingSig;
    public final long   version;
    public final long   notBefore;

    public BindingRow(final String genesisPubKey,
                      final String ownPubKey,
                      final String bindingSig,
                      final long   version,
                      final long   notBefore)
    {
      this.genesisPubKey = genesisPubKey;
      this.ownPubKey     = ownPubKey;
      this.bindingSig    = bindingSig;
      this.version       = version;
      this.notBefore     = notBefore;
    }
  }

  /**
   * Returns the stored binding columns for {@code actId}, or {@code null}
   * if the row is missing or GenesisPubKey is NULL (not yet migrated).
   */
  public static BindingRow getBindingRow(final String actId) throws DomatarException
  {
    DbConnection      conn  = null;
    PreparedStatement pstmt = null;
    ResultSet         rset  = null;

    try
    {
      conn  = new DbConnection(ActDb.class, "getBindingRow");
      pstmt = conn.prepareStatement(
          "select GenesisPubKey, OwnPubKey, BindingVersion, BindingNotBefore, BindingSig"
          + " from act where ActId=?");
      pstmt.setString(1, actId);
      rset  = pstmt.executeQuery();

      if (!rset.next())
        return null;

      final String genesisPubKey = rset.getString("GenesisPubKey");
      if (genesisPubKey == null)
        return null;

      return new BindingRow(
          genesisPubKey,
          rset.getString("OwnPubKey"),
          rset.getString("BindingSig"),
          rset.getLong("BindingVersion"),
          rset.getLong("BindingNotBefore"));
    }
    catch (final SQLException e)
    {
      throw new DomatarException(e);
    }
    finally
    {
      try
      {
        if (rset  != null) rset.close();
        if (pstmt != null) pstmt.close();
        if (conn  != null) conn.close();
      }
      catch (final SQLException e) { throw new DomatarException(e); }
    }
  }

  /**
   * Stores (or replaces) the actId->ownId binding for {@code actId}.
   */
  public static void setBinding(final String actId,
                                final String genesisPubKey,
                                final String ownPubKey,
                                final long   version,
                                final long   notBefore,
                                final String bindingSig) throws DomatarException
  {
    DbConnection      conn  = null;
    PreparedStatement pstmt = null;

    try
    {
      conn  = new DbConnection(ActDb.class, "setBinding");
      pstmt = conn.prepareStatement(
          "update act set GenesisPubKey=?, OwnPubKey=?, BindingVersion=?,"
          + " BindingNotBefore=?, BindingSig=? where ActId=?");
      pstmt.setString(1, genesisPubKey);
      pstmt.setString(2, ownPubKey);
      pstmt.setLong  (3, version);
      pstmt.setLong  (4, notBefore);
      pstmt.setString(5, bindingSig);
      pstmt.setString(6, actId);
      pstmt.executeUpdate();
    }
    catch (final SQLException e)
    {
      throw new DomatarException(e);
    }
    finally
    {
      try
      {
        if (pstmt != null) pstmt.close();
        if (conn  != null) conn.close();
      }
      catch (final SQLException e) { throw new DomatarException(e); }
    }
  }

  /**
   * Returns a reconstructed {@link com.domatar.crypto.Binding} for
   * {@code actId}, or {@code null} if no binding is stored.
   */
  public static com.domatar.crypto.Binding getBinding(final String actId)
      throws DomatarException
  {
    final BindingRow row = getBindingRow(actId);
    if (row == null)
      return null;

    return com.domatar.crypto.Binding.fromStored(
        actId, row.genesisPubKey, row.ownPubKey,
        row.version, row.notBefore, row.bindingSig);
  }

  private static String getToken ()
  {
    StringBuffer buf = new StringBuffer(10);

    for (int i = 0; i < 64; i++)
    {
      int rand = random.nextInt(64);

      String s = Base64Encoder.encode(rand);

      buf.append(s);
    }

    return buf.toString();
  }
}
