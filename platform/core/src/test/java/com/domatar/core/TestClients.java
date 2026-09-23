/*
 * Copyright (c) 2024 Domatar
 */

package com.domatar.core;

import com.domatar.crypto.Provenance;
import com.domatar.util.DomId;

/**
 * Test-only factory. Not in the production jar. Production code cannot
 * stamp Trust.ACCOUNT onto a client it builds itself.
 */
public final class TestClients
{
  private TestClients() {}

  public static Context account(final String actId, final String usrId,
                                final String name, final String ip,
                                final String token, final String contextId)
  {
    return new Context(actId, usrId, name, ip, token, Trust.ACCOUNT, contextId, null);
  }

  public static HandlerClient inbound(final DomId dst, final String domain,
                                      final Context ctx, final String path,
                                      final String realPath, final Provenance prov)
  {
    return new HandlerClient(HttpClient.inbound(dst, domain, ctx, path, realPath, prov));
  }
}
