package com.domatar.db;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import com.domatar.util.IdGen;
import com.domatar.util.Obj;
import com.domatar.util.ObjAttrs;
import com.domatar.util.DomId;
import com.domatar.util.DomatarException;

public class ObjDb
{
  // Column length limits matching the obj table DDL.
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

  public static void addObj(Obj obj) throws DomatarException
  {
    DbConnection conn = null;
    PreparedStatement pstmt = null;

    try
    {
      conn = new DbConnection(ObjDb.class, "addObj");
      pstmt = conn.prepareStatement("insert into obj values(?,?,?,?,?,?,?,?, CAST(? AS JSON))");

      pstmt.setString(1, obj.domId.hstId);
      pstmt.setString(2, obj.domId.appId);
      pstmt.setString(3, obj.domId.actId);
      pstmt.setString(4, obj.domId.objId);
      pstmt.setString(5, obj.clsAppId);
      pstmt.setString(6, obj.clsId);
      pstmt.setString(7, trunc(obj.objName, MAX_OBJ_NAME));
      pstmt.setString(8, trunc(obj.objDesc, MAX_OBJ_DESC));
      pstmt.setString(9, obj.attrs.toString());
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
   * Creates the object only if no row with the same DomId already exists.
   * Every install routine uses this to keep installs idempotent.
   */
  public static void addObjIfMissing(DomId domId, String clsAppId, String clsId,
                                     String objName, String objDesc) throws DomatarException
  {
    if (getObj(domId) == null)
      addObj(new Obj(domId, clsAppId, clsId, objName, objDesc, new ObjAttrs()));
  }

  /** Variant that accepts pre-built attrs (e.g. objects with non-empty initial state). */
  public static void addObjIfMissing(DomId domId, String clsAppId, String clsId,
                                     String objName, String objDesc, ObjAttrs attrs) throws DomatarException
  {
    if (getObj(domId) == null)
      addObj(new Obj(domId, clsAppId, clsId, objName, objDesc, attrs));
  }

  public static Obj getObj(DomId domId) throws DomatarException
  {
    DbConnection conn = null;
    Statement stmt = null;
    ResultSet rset = null;

    String queryStr = null;

    Obj obj = null;

    try
    {
        queryStr = "select ClsAppId, ClsId, ObjName, ObjDesc, CAST(Attrs AS CHAR CHARACTER SET utf8mb4) " +
                   "from obj where " +
                   "HstId='" + domId.hstId + "' and " +
                   "AppId='" + domId.appId + "' and " +
                   "ActId='" + domId.actId + "' and " +
                   "ObjId='" + domId.objId + "'";

        conn = new DbConnection(ObjDb.class, "getObj");
        stmt = conn.createStatement();
        rset = stmt.executeQuery(queryStr);

        if (rset.next())
        {
          final String clsAppId = rset.getString(1);
          final String clsId    = rset.getString(2);
          final String objName  = rset.getString(3);
          final String objDesc  = rset.getString(4);
          final String attrs    = rset.getString(5);

          obj = new Obj(domId, clsAppId, clsId, objName, objDesc, new ObjAttrs(attrs));
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

    return obj;
  }

  public static List<Obj> getObjRange(DomId fromDomId, String toObjId, String where, int max) throws DomatarException
  {
    DbConnection conn = null;
    Statement stmt = null;
    ResultSet rset = null;

    String queryStr = null;

    ArrayList<Obj> objList = new ArrayList<Obj>();

    try
    {
      String hstId = fromDomId.hstId;
      String appId = fromDomId.appId;
      String actId = fromDomId.actId;
      String fromObjId = fromDomId.objId;

      String whereStr;

      if (where != null && where.length() > 0)
        whereStr = "and " + where;
      else
        whereStr = "";

      queryStr = "select ObjId, ClsAppId, ClsId, ObjName, ObjDesc, CAST(Attrs AS CHAR CHARACTER SET utf8mb4) " +
                 "from obj where " +
                 "HstId='" + hstId + "' and " +
                 "AppId='" + appId + "' and " +
                 "ActId='" + actId + "' and " +
                 "ObjId<='" + fromObjId + "' and " +
                 "ObjId>'" + toObjId + "' " + whereStr +
                 "order by ObjId desc";

      conn = new DbConnection(ObjDb.class, "getObj");
      stmt = conn.createStatement();
      rset = stmt.executeQuery(queryStr);

      for (int i = 0; i < max && rset.next(); i++)
      {
        final String objId    = rset.getString(1);
        final String clsAppId = rset.getString(2);
        final String clsId    = rset.getString(3);
        final String objName  = rset.getString(4);
        final String objDesc  = rset.getString(5);
        final String attrs    = rset.getString(6);

        final DomId domId = new DomId(hstId, appId, actId, objId);
        final Obj obj = new Obj(domId, clsAppId, clsId, objName, objDesc, new ObjAttrs(attrs));
        objList.add(obj);
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

    return objList;
  }

  public static List<Obj> getObjPrefix(DomId domId, String objPrefix, String where, int max) throws DomatarException
  {
    return getObjPrefix(domId.hstId, domId.appId, domId.actId, objPrefix, where, max);
  }

  public static List<Obj> getObjPrefix(String hstId, String appId, String actId, String objPrefix, String where, int max) throws DomatarException
  {
    DbConnection conn = null;
    Statement stmt = null;
    ResultSet rset = null;

    String queryStr = null;

    ArrayList<Obj> objList = new ArrayList<Obj>();

    String likePrefix = objPrefix + "-%";

    try
    {
      String whereStr;

      if (where != null && where.length() > 0)
        whereStr = "and " + where;
      else
        whereStr = "";

      queryStr = "select ObjId, ClsAppId, ClsId, ObjName, ObjDesc, CAST(Attrs AS CHAR CHARACTER SET utf8mb4) " +
                 "from obj where " +
                 "HstId='" + hstId + "' and " +
                 "AppId='" + appId + "' and " +
                 "ActId='" + actId + "' and " +
                 "ObjId like '" + likePrefix + "' " + whereStr +
                 "order by ObjId desc";

      conn = new DbConnection(ObjDb.class, "getObj");
      stmt = conn.createStatement();
      rset = stmt.executeQuery(queryStr);

      for (int i = 0; i < max && rset.next(); i++)
      {
        final String objId    = rset.getString(1);
        final String clsAppId = rset.getString(2);
        final String clsId    = rset.getString(3);
        final String objName  = rset.getString(4);
        final String objDesc  = rset.getString(5);
        final String attrs    = rset.getString(6);

        final DomId domId = new DomId(hstId, appId, actId, objId);
        final Obj obj = new Obj(domId, clsAppId, clsId, objName, objDesc, new ObjAttrs(attrs));
        objList.add(obj);
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

    return objList;
  }

  public static void incrementObjAttr(DomId domId, String attrKey, long increment, long min) throws DomatarException
  {
    DbConnection conn = null;
    Statement stmt = null;
    ResultSet rset = null;

    String queryStr = null;
    String sqlStr = null;

    try
    {
        queryStr = "select CAST(Attrs AS CHAR CHARACTER SET utf8mb4) " +
                   "from obj where " +
                   "HstId='" + domId.hstId + "' and " +
                   "AppId='" + domId.appId + "' and " +
                   "ActId='" + domId.actId + "' and " +
                   "ObjId='" + domId.objId + "'";

        conn = new DbConnection(ObjDb.class, "getObj");
        stmt = conn.createStatement();
        rset = stmt.executeQuery(queryStr);

        if (rset.next())
        {
          final String attrsStr = rset.getString(1);

          ObjAttrs attrs = new ObjAttrs(attrsStr);

          String attrStr = attrs.getAttr(attrKey);

          long attr = (attrStr == null) ? 0 : Long.parseLong(attrStr);

          long newAttr = attr + increment;

          if (newAttr < min)
            newAttr = min;

          String newAttrStr = Long.toString(newAttr);

          attrs.addAttr(attrKey, newAttrStr);

          sqlStr = "update obj set " +
                   "Attrs= CAST('" + attrs.toSqlString() + "' AS JSON) " +
                   "where HstId='" + domId.hstId + "' and AppId ='" + domId.appId +
                   "' and ActId = '" + domId.actId + "' and ObjId = '" + domId.objId + "'";

          stmt.executeUpdate(sqlStr);
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
  }

  /**
   * Correctly updates ObjName, ObjDesc, and Attrs for an existing obj row.
   */
  public static void modifyObj(Obj obj) throws DomatarException
  {
    DbConnection conn = null;
    Statement stmt = null;

    String safeName = trunc(obj.objName != null ? obj.objName : "", MAX_OBJ_NAME).replace("'", "''");
    String safeDesc = trunc(obj.objDesc != null ? obj.objDesc : "", MAX_OBJ_DESC).replace("'", "''");

    String sqlStr = "UPDATE obj SET " +
                    "ObjName='" + safeName + "', " +
                    "ObjDesc='" + safeDesc + "', " +
                    "Attrs=CAST('" + obj.attrs.toSqlString() + "' AS JSON) " +
                    "WHERE HstId='" + obj.domId.hstId + "'" +
                    " AND AppId='" + obj.domId.appId + "'" +
                    " AND ActId='" + obj.domId.actId + "'" +
                    " AND ObjId='" + obj.domId.objId + "'";

    try
    {
      conn = new DbConnection(ObjDb.class, "modifyObj");
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
   * Returns every object in this provider's database whose class is
   * (domatar, cls) — i.e. every class-descriptor row across all sub-hosts.
   * Used by ServiceMigration.migrateAll() to locate inline descriptors for
   * splitting into service + slim class (Spec-Service.txt T1.8).
   */
  public static List<Obj> listClsDescriptors() throws DomatarException
  {
    DbConnection conn  = null;
    Statement    stmt  = null;
    ResultSet    rset  = null;

    ArrayList<Obj> result = new ArrayList<Obj>();

    try
    {
      conn = new DbConnection(ObjDb.class, "listClsDescriptors");
      stmt = conn.createStatement();
      rset = stmt.executeQuery(
          "SELECT HstId, AppId, ActId, ObjId, ObjName, ObjDesc, " +
          "CAST(Attrs AS CHAR CHARACTER SET utf8mb4) " +
          "FROM obj " +
          "WHERE ClsAppId='domatar' AND ClsId='cls'");

      while (rset.next())
      {
        String hstId   = rset.getString(1);
        String appId   = rset.getString(2);
        String actId   = rset.getString(3);
        String objId   = rset.getString(4);
        String objName = rset.getString(5);
        String objDesc = rset.getString(6);
        String attrs   = rset.getString(7);

        DomId domId = new DomId(hstId, appId, actId, objId);
        result.add(new Obj(domId, "domatar", "cls", objName, objDesc,
                           new ObjAttrs(attrs)));
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

    return result;
  }

  /**
   * Delete every obj row on {@code hstId} (used by Login-Multiple cutover
   * to retire {@code login-&lt;actId&gt;} hosts).
   */
  public static void deleteByHstId(final String hstId) throws DomatarException
  {
    if (hstId == null || hstId.isEmpty())
      throw new DomatarException("deleteByHstId: missing hstId");

    DbConnection conn = null;
    Statement stmt = null;

    try
    {
      conn = new DbConnection(ObjDb.class, "deleteByHstId");
      stmt = conn.createStatement();
      stmt.execute("delete from obj where HstId='" + hstId + "'");
    }
    catch (SQLException e)
    {
      throw new DomatarException("deleteByHstId: " + e.toString());
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

  public static void deleteObj(DomId domId) throws DomatarException
  {
    DbConnection conn = null;
    Statement stmt = null;

    String sqlStr = "delete from obj" +
                    " where HstId='" + domId.hstId + "'" +
                    " and AppId='" + domId.appId + "'" +
                    " and ActId='" + domId.actId + "'" +
                    " and ObjId='" + domId.objId + "'";

    try
    {
      conn = new DbConnection(ObjDb.class, "deleteObj");
      stmt = conn.createStatement();
      stmt.execute(sqlStr);
    }
    catch (SQLException e)
    {
      throw new DomatarException("deleteObj: " + e.toString());
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
   * Changes the class (ClsAppId, ClsId) of an existing object in-place.
   * Used by install routines that need to re-class an object after creation,
   * e.g. migrating app-spreadsheet from (domatar, app) to (spreadsheet, app).
   * Does nothing if the object does not exist.
   */
  public static void reclassObj(DomId domId, String newClsAppId, String newClsId)
      throws DomatarException
  {
    DbConnection conn = null;
    Statement stmt = null;

    String sqlStr = "UPDATE obj SET " +
                    "ClsAppId='" + newClsAppId + "', " +
                    "ClsId='"    + newClsId    + "' " +
                    "WHERE HstId='" + domId.hstId + "'" +
                    " AND AppId='"  + domId.appId + "'" +
                    " AND ActId='"  + domId.actId + "'" +
                    " AND ObjId='"  + domId.objId + "'";

    try
    {
      conn = new DbConnection(ObjDb.class, "reclassObj");
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
   * Delete every obj whose AppId matches and whose ObjId is in the closed range
   * [prefix + "-", olderThanId]. The prefix bound is REQUIRED: ObjIds in this
   * table look like "<prefix>-<suffix>" (see IdGen.createId), and a raw
   * "ObjId <= olderThanId" predicate would match every prefix that happens to
   * sort lexicographically before the purge target - e.g. purging old "log-"
   * rows would also delete every "act-", "ban-", "follow-", and "like-" row,
   * because 'a','b','f','l' all sort before 'l'+'o'+'g'. Scoping the lower
   * bound to prefix + "-" keeps the purge inside one ObjId family.
   */
  public static void deleteObjRange(String appId, String prefix, String from, String to) throws DomatarException
  {
    DbConnection conn = null;
    Statement stmt = null;

    String fromId = IdGen.createId(prefix, from);
    String toId = IdGen.createId(prefix, to);

    String queryStr = "delete from obj where " +
                      "AppId='" + appId + "' and " +
                      "ObjId>='" + fromId + "' and " +
                      "ObjId<='" + toId + "'";

    try
    {
      conn = new DbConnection(ObjDb.class, "deleteOldObjs");
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