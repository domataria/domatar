/*
 * Copyright (c) 2024 Domatar
 */

package com.login.install;

import com.domatar.util.DomatarException;
import com.domatar.util.DomatarMsgClient;

/**
 * Login-module entry point for the membership backfill migrator
 * (Update-Login-Multiple.txt Task 4.1). Delegates to
 * {@link com.domatar.install.MembershipMigration} so SetupServlet (core)
 * can call the same logic without a core→login dependency.
 */
public final class MembershipMigration
{
  private MembershipMigration() {}

  public static String migrate() throws DomatarException
  {
    return com.domatar.install.MembershipMigration.migrate();
  }

  public static String migrate(final DomatarMsgClient msgClient) throws DomatarException
  {
    return com.domatar.install.MembershipMigration.migrate(msgClient);
  }
}
