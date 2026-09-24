/*
 * Copyright (c) 2024 Domatar
 */

package com.domatar.install;

import java.util.logging.Logger;

import com.domatar.db.PayDb;
import com.domatar.util.DomatarException;

/**
 * Idempotent CREATE TABLE IF NOT EXISTS for {@code pay_bal}
 * on volumes that already ran schema.sql before that table existed.
 */
public final class PaymentInstall
{
  private static final Logger LOG = Logger.getLogger(PaymentInstall.class.getName());

  private PaymentInstall() {}

  public static void ensureTable() throws DomatarException
  {
    PayDb.ensureTable();
    LOG.info("PaymentInstall: pay_bal present");
  }
}
