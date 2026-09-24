package com.domatar.log;

import com.domatar.util.DomatarException;

/**
 * A listener refused the first-admit transaction. The visit rolls back.
 */
public final class OpLogDeny extends DomatarException
{
  private static final long serialVersionUID = 1L;

  public OpLogDeny()
  {
    super("admit denied");
  }
}
