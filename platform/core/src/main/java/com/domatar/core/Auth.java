/*
 * Copyright (c) 2024 Domatar
 */

package com.domatar.core;

import com.domatar.util.JsonMsg;
import com.domatar.util.DomatarException;

/**
 * Authorization helpers for use by ObjImpl subclasses' hasRights() overrides.
 *
 * Verification ("are you who you say you are") is identity, established
 * exactly once per request at the trust boundary - DomatarServlet for
 * browser entry, Msg.doAction for cross-prv inbound. Both run
 * LoginRemote.verifyLogin once and stamp Context.verified=true on the
 * dispatch context. Subsequent in-process handler calls inherit the flag.
 *
 * Authorization ("may you do this on this object") is per-object policy,
 * expressed in each ObjImpl subclass's hasRights(). Callers that only
 * need the simplest "must be a verified caller" rule express it as
 *
 *     return Auth.isVerified(inMsg);
 *
 * which collapses to a flag read - no DB hit, no recursion. Richer
 * policies (owner-match, follower-status, ban-checking, ...) belong in
 * the handler itself or in additional helpers here.
 *
 * NOTE on cross-prv: a prv only verifies tokens that exist in its OWN
 * act table. A user logged into prv1 has no act row on prv2 and so
 * arrives at prv2 with verified=false. That matches the pre-refactor
 * framework gate, which was also local-only. Cross-prv writes need
 * cross-prv account replication or signed inter-server auth to work;
 * both are TODOs in the spec.
 */
public final class Auth
{
  private Auth() {}

  /**
   * Returns true iff some upstream trust boundary already verified this
   * caller against THIS prv's act table on this request.
   */
  public static boolean isVerified(final JsonMsg inMsg) throws DomatarException
  {
    final Context ctx = inMsg.getContext();

    return ctx != null && ctx.verified;
  }
}
