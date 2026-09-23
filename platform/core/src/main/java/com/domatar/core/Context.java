package com.domatar.core;

import com.domatar.util.JsonMap;

/**
 * Per-request identity envelope. Carries who the caller is plus a
 * three-valued {@link Trust} stamped by a local trust boundary
 * (DomatarServlet for browser entry, Msg.doAction for cross-prv inbound)
 * from its own Verdict. {@code trust}, {@code actId}, and {@code contextId}
 * are never taken from the wire.
 *
 * There is ONE lineage: the signed Path. The old split — unsigned
 * Context.domIdPath versus the signed path, read from different places —
 * is gone (Spec PART 8.1). The object list is the derived projection
 * Path.domIdPath(), read through the client.
 *
 * Verification is identity, not authorization. It happens once per request
 * at the trust boundary; in-process local handler chains inherit the stamped
 * Trust. Cross-prv crossings always re-evaluate — the receiving prv only
 * believes its own checks.
 */
public class Context
{
  public final String actId;
  public final String usrId;
  public final String usrName;
  public final String usrIp;
  public final String token;
  public final Trust trust;
  public final String contextId;
  public final JsonMap httpHeaders;

  Context(String actId,
          String usrId,
          String usrName,
          String usrIp,
          String token,
          Trust trust,
          String contextId,
          JsonMap httpHeaders)
  {
    this.actId = actId;
    this.usrId = usrId;
    this.usrName = usrName;
    this.usrIp = usrIp;
    this.token = token;
    this.trust = trust != null ? trust : Trust.NONE;
    this.contextId = contextId;
    this.httpHeaders = httpHeaders;
  }

  /**
   * Convenience for callers that do not stamp a verdict — Trust.NONE,
   * contextId null. Trust boundaries use the full constructor.
   */
  public Context(String actId,
                 String usrId,
                 String usrName,
                 String usrIp,
                 String token,
                 JsonMap httpHeaders)
  {
    this(actId, usrId, usrName, usrIp, token, Trust.NONE, null, httpHeaders);
  }

  public boolean isVerified()
  {
    return trust == Trust.ACCOUNT;
  }
}
