package com.domatar.db;

import com.mysql.cj.jdbc.Driver;

import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.Statement;
import java.util.EmptyStackException;
import java.util.HashMap;
import java.util.Stack;

// import com.mysql.jdbc.Driver;

public class DbConnection
{
  static final long HOUR_OF_MILLS = 3600000;
  private static final Stack<DbConnectTime> connectionStack = new Stack<DbConnectTime>();
  private static final HashMap<DbConnection, String> connectionHash = new HashMap<DbConnection, String>();
  private static long connCount = 0;
  private static String connectStr = null;
  private DbConnectTime conn;
  private final Object creatorObj;
  private final String infoStr;

  DbConnection(Object o, String info) throws SQLException
  {
    creatorObj = o;
    infoStr = info;

    open();
  }

  private class DbConnectTime
  {
    long t;
    Connection c;
  }

  public static void setConnectStr(String str)
  {
    if (connectStr == null)
      connectStr = str;
  }

  void open() throws SQLException
  {
    if (conn == null)
    {
      try
      {
        conn = connectionStack.pop();

        if (System.currentTimeMillis() - conn.t > HOUR_OF_MILLS)
        {
          conn.c.close();
          conn = openConnection();
        }
      }
      catch (EmptyStackException e)
      {
        conn = openConnection();
      }

      connectionHash.put(this, this.creatorObj.toString() + "." + this.infoStr);
    }
  }

  boolean isOpen()
  {
    return conn != null;
  }

  private DbConnectTime openConnection() throws SQLException
  {
    Connection c = getConnection();
    DbConnectTime dbConn = new DbConnectTime();
    dbConn.c = c;
    dbConn.t = System.currentTimeMillis();
    c.setAutoCommit(true);
    c.setTransactionIsolation(Connection.TRANSACTION_READ_UNCOMMITTED);
    return dbConn;
  }

  private static synchronized Connection getConnection() throws SQLException
  {
    try
    {

      if (connCount == 0)
      {
        Driver driver = new Driver();
        DriverManager.registerDriver(driver);
      }


      connCount++;

      return DriverManager.getConnection(connectStr + "&serverTimezone=UTC&CharSet=utf8");
    }
    catch (SQLException e)
    {
      System.out.println("SQLException: " + e.getMessage());
      System.out.println("SQLState:     " + e.getSQLState());
      System.out.println("VendorError:  " + e.getErrorCode());

      int hashSize =   connectionHash.size();
      String hashStr = connectionHash.toString().replace(",", ",\n");

      System.out.println("Num Connections in Java:\n" + connCount);
      System.out.println("Num Connections in Hash:\n" + hashSize);
      System.out.println("Connections:\n" + hashStr);

      throw e;
    }
  }

  void close () throws SQLException
  {
    if (System.currentTimeMillis() - conn.t < HOUR_OF_MILLS / 2)
      connectionStack.push(conn);
    else
      conn.c.close();

    conn = null;

    connectionHash.remove(this);
  }

  @Override
  protected void finalize()
  {
    if (conn != null)
      System.out.println("Error - Connection not closed Object: " + creatorObj +
                         ", Class: " + creatorObj.getClass().getName());
  }

  Statement createStatement() throws SQLException
  {
    return conn.c.createStatement();
  }

  PreparedStatement prepareStatement(String sql) throws SQLException
  {
    return conn.c.prepareStatement(sql);
  }

  CallableStatement prepareCall(String sql) throws SQLException
  {
    return conn.c.prepareCall(sql);
  }

  String nativeSQL(String sql) throws SQLException
  {
    return conn.c.nativeSQL(sql);
  }

  void setAutoCommit(boolean autoCommit) throws SQLException
  {
    conn.c.setAutoCommit(autoCommit);
  }

  boolean getAutoCommit() throws SQLException
  {
    return conn.c.getAutoCommit();
  }

  void commit() throws SQLException
  {
    conn.c.commit();
  }

  void rollback() throws SQLException
  {
    conn.c.rollback();
  }

  boolean isClosed() throws SQLException
  {
    if (conn == null)
      return true;

    return conn.c.isClosed();
  }

  DatabaseMetaData getMetaData() throws SQLException
  {
    return conn.c.getMetaData();
  }

  void setReadOnly(boolean readOnly) throws SQLException
  {
    conn.c.setReadOnly(readOnly);
  }

  boolean isReadOnly() throws SQLException
  {
    return conn.c.isReadOnly();
  }

  void setCatalog(String catalog) throws SQLException
  {
    conn.c.setCatalog(catalog);
  }

  String getCatalog() throws SQLException
  {
    return conn.c.getCatalog();
  }

  void setTransactionIsolation(int level) throws SQLException
  {
    conn.c.setTransactionIsolation(level);
  }

  int getTransactionIsolation() throws SQLException
  {
    return conn.c.getTransactionIsolation();
  }

  SQLWarning getWarnings() throws SQLException
  {
    return conn.c.getWarnings();
  }

  void clearWarnings() throws SQLException
  {
    conn.c.clearWarnings();
  }

  Statement createStatement(int resultSetType, int resultSetConcurrency)
    throws SQLException
  {
    return conn.c.createStatement(resultSetType, resultSetConcurrency);
  }

  PreparedStatement prepareStatement(String sql,
                                     int resultSetType,
                                     int resultSetConcurrency)
    throws SQLException
  {
    return conn.c.prepareStatement(sql, resultSetType, resultSetConcurrency);
  }

  CallableStatement prepareCall(String sql,
                                int resultSetType,
                                int resultSetConcurrency)
    throws SQLException
  {
    return conn.c.prepareCall(sql, resultSetType, resultSetConcurrency);
  }

  java.util.Map<String,Class<?>> getTypeMap() throws SQLException
  {
    return conn.c.getTypeMap();
  }

  void setTypeMap(java.util.Map<String,Class<?>> map) throws SQLException
  {
    conn.c.setTypeMap(map);
  }
}
