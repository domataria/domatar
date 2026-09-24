/*
 * Copyright (c) 2024 Domatar
 */

package com.domatar.install;

import java.util.logging.Logger;

import com.domatar.db.OpLogDb;
import com.domatar.log.OpLog;
import com.domatar.pay.PaymentListener;
import com.domatar.saga.SagaListener;
import com.domatar.util.DomatarException;

/**
 * Idempotent CREATE TABLE IF NOT EXISTS for {@code op_dst} / {@code op_msg}
 * on volumes that already ran schema.sql before those tables existed.
 */
public final class OpLogInstall
{
  private static final Logger LOG = Logger.getLogger(OpLogInstall.class.getName());

  private OpLogInstall() {}

  public static void ensureTables() throws DomatarException
  {
    OpLogDb.ensureTables();
    OpLog.register(new SagaListener());
    OpLog.register(new PaymentListener());
    LOG.info("OpLogInstall: op_dst / op_msg present");
  }
}
