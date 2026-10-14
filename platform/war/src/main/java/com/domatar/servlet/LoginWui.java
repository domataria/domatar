/*
 * Copyright (c) 2024 Domatar
 */

package com.domatar.servlet;

import java.io.IOException;

import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import com.domatar.db.DbConnection;

/**
 * Stub. {@link #doAction} is empty and nothing maps this class.
 * Browser login is {@code ActWui}. {@link #init} records a JDBC URL
 * if a container constructs this servlet.
 */
public class LoginWui extends HttpServlet
{
  private static final long serialVersionUID = 1L;
  private static String dbConnection = null;

  @Override
  public void init(final ServletConfig config) throws ServletException
  {
    super.init(config);

    final ServletContext servletContext = getServletContext();

    dbConnection = servletContext.getInitParameter("DbConnection");

    DbConnection.setConnectStr(dbConnection);
  }

  @Override
  protected void doGet(final HttpServletRequest req, final HttpServletResponse res)
      throws ServletException, IOException
  {
    doAction(req, res);
  }

  @Override
  public void doPost(final HttpServletRequest req, final HttpServletResponse res)
      throws ServletException, IOException
  {
    doAction(req, res);
  }

  public void doAction(final HttpServletRequest req, final HttpServletResponse res)
      throws ServletException, IOException
  {
  }
}
