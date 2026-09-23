/*
 * Copyright (c) 2024 Domatar
 */

package com.domatar.db;

/**
 * One-shot right to mint hop 0 as an account this process just inserted.
 * The constructor is package-private. Only {@link ActDb#addAct} creates one.
 * {@link #consume} succeeds once, and only for that actId.
 */
public final class LineagePermit
{
  private final String actId;
  private boolean used;

  LineagePermit(final String actId)
  {
    this.actId = actId;
  }

  public synchronized boolean consume(final String requestedActId)
  {
    if (used || actId == null || !actId.equals(requestedActId))
      return false;

    used = true;
    return true;
  }
}
