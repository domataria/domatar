package com.domatar.core;

import com.domatar.util.JsonMap;
import com.domatar.util.DomId;

/**
 * Per-request identity envelope. Carries who the caller claims to be plus a
 * "verified" flag that is true iff some upstream trust boundary
 * (DomatarServlet for browser entry, Msg.doAction for cross-prv inbound)
 * has matched (usrId, token) against THIS prv's act table.
 *
 * Verification is identity, not authorization. It happens once per request
 * at the trust boundary; in-process local handler chains inherit the flag
 * unchanged. Cross-prv crossings always reset it - the receiving prv has
 * to re-establish trust against its own act table.
 */
public class Context
{
  public final String actId;
  public final String usrId;
  public final String usrName;
  public final String usrIp;
  public final String token;
  public final boolean verified;
  public final JsonMap httpHeaders;
  public final DomId [] domIdPath;

  public Context(String actId,
                 String usrId,
                 String usrName,
                 String usrIp,
                 String token,
                 boolean verified,
                 JsonMap httpHeaders,
                 DomId [] domIdPath)
  {
    this.actId = actId;
    this.usrId = usrId;
    this.usrName = usrName;
    this.usrIp = usrIp;
    this.token = token;
    this.verified = verified;
    this.httpHeaders = httpHeaders;
    this.domIdPath = domIdPath;
  }

  /**
   * Convenience for callers that pre-date the verified flag - assumes
   * unverified. Trust boundaries should use the full constructor and pass
   * verified=true after they have run verifyLogin successfully.
   */
  public Context(String actId,
                 String usrId,
                 String usrName,
                 String usrIp,
                 String token,
                 JsonMap httpHeaders,
                 DomId [] domIdPath)
  {
    this(actId, usrId, usrName, usrIp, token, false, httpHeaders, domIdPath);
  }
}
