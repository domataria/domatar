/*
 * Copyright (c) 2024 Domatar
 */

package com.domatar.util;

/**
 * Outcome of {@link ObjImpl#rights}. {@code PRICED} is billable:
 * {@code PaymentListener} draws a list price only for that outcome.
 * {@code ADMIT} is not charged.
 */
public enum Rights
{
  DENY,
  ADMIT,
  PRICED
}
