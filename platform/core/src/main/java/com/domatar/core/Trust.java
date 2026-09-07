/*
 * Copyright (c) 2024 Domatar
 */

package com.domatar.core;

/**
 * Stamped provenance level (Spec-Security PART 7.5). Lives in {@code core},
 * not {@code crypto}: it is the one crypto-derived value a hasRights
 * implementor names, so app JARs never import {@code com.domatar.crypto}.
 */
public enum Trust
{
  NONE,
  PATH,
  ACCOUNT
}
