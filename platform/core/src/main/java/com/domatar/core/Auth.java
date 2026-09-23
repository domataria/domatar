/*
 * Copyright (c) 2024 Domatar
 */

package com.domatar.core;

import com.domatar.util.DomatarException;
import com.domatar.util.DomatarMsgClient;

/**
 * Authorization helpers for use by ObjImpl subclasses' hasRights() overrides.
 *
 * Verification ("are you who you say you are") is identity, established
 * exactly once per request at the trust boundary - DomatarServlet for
 * browser entry, Msg.doAction for cross-prv inbound. Both run
 * LoginRemote.verifyLogin once and stamp Trust.ACCOUNT on the
 * dispatch context. Subsequent in-process handler calls inherit the
 * stamped Trust.
 *
 * Authorization ("may you do this on this object") is per-object policy,
 * expressed in each ObjImpl subclass's hasRights(). Callers that only
 * need the simplest "must be a verified caller" rule express it as
 *
 *     return Auth.isVerified(msgClient);
 *
 * which is true only for a platform {@link HandlerClient} stamped
 * Trust.ACCOUNT. A hand-built DomatarMsgClient or a Verified flag on
 * the message does not count. Richer policies (owner-match,
 * follower-status, ban-checking, ...) belong in the handler itself
 * or in additional helpers here.
 *
 * NOTE on cross-prv: a prv only verifies tokens that exist in its OWN
 * act table. A user logged into prv1 has no act row on prv2 and so
 * arrives at prv2 with Trust.NONE unless the {@code Sec=} envelope
 * verifies. That matches the pre-refactor framework gate, which was also
 * local-only. Cross-prv writes need cross-prv account replication or
 * signed inter-server auth to work; both are TODOs in the spec.
 */
public final class Auth
{
  private Auth() {}

  /**
   * True when the dispatcher handed this handler a platform client
   * stamped Trust.ACCOUNT.
   */
  public static boolean isVerified(final DomatarMsgClient client)
  {
    return client instanceof HandlerClient && ((HandlerClient) client).holdsAccount();
  }

  /**
   * ActId of a platform client stamped Trust.ACCOUNT. Null for any
   * other client, including an app's own implementation of the interface.
   */
  public static String actId(final DomatarMsgClient client)
  {
    if (!(client instanceof HandlerClient))
      return null;

    return ((HandlerClient) client).accountActId();
  }

  /**
   * True when dispatch built this client. Unverified platform clients
   * are included. An app implementation of {@link DomatarMsgClient} is not.
   */
  public static boolean isPlatformClient(final DomatarMsgClient client)
  {
    return client instanceof HandlerClient;
  }

  /**
   * Caller DomId stamped on a platform client. Null for any other client.
   */
  public static String callerDomId(final DomatarMsgClient client)
      throws DomatarException
  {
    if (!(client instanceof HandlerClient))
      return null;

    return ((HandlerClient) client).callerDomId();
  }

  /**
   * Context stamped on a platform client. Null for any other client.
   */
  public static Context stampedContext(final DomatarMsgClient client)
  {
    if (!(client instanceof HandlerClient))
      return null;

    return ((HandlerClient) client).stampedContext();
  }

  /** Servlet context path of a platform client, or null. */
  public static String contextPath(final DomatarMsgClient client)
  {
    if (!(client instanceof HandlerClient))
      return null;

    return ((HandlerClient) client).contextPath();
  }

  /** Exploded-WAR path of a platform client, or null. */
  public static String contextRealPath(final DomatarMsgClient client)
  {
    if (!(client instanceof HandlerClient))
      return null;

    return ((HandlerClient) client).contextRealPath();
  }
}
