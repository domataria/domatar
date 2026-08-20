package com.domatar.db;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Random;

import com.domatar.util.Base64Encoder;
import com.domatar.util.DomatarException;

public class TestDb
{
  private static final Random random = new Random();
  
  public static void main(String[] args) throws DomatarException
  {
    addTest();
    
    System.out.println("Done");
  }

  public static void addTest() throws DomatarException
  {
    DbConnection.setConnectStr("jdbc:mysql://localhost/domatar?user=root&password=domatar");
    
    DbConnection conn = null;
    PreparedStatement pstmt = null;
  
    try
    {
      conn = new DbConnection(HstDb.class, "addTest");
      pstmt = conn.prepareStatement("insert into test values(?)");
      
      for (int i = 0; i < 100000; i++)
      {
        String token = getToken();
    
        pstmt.setString(1, token);
        pstmt.executeUpdate();
        
        if (i % 10 == 0)
          System.out.println(i);
      }
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
