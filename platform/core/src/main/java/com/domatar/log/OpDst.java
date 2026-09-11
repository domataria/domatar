package com.domatar.log;

/**
 * One {@code op_dst} visit row. Immutable.
 */
public final class OpDst
{
  public final String hstId;
  public final String contextId;
  public final String dstDomId;
  public final String msgName;
  public final String actId;
  public final String callerDomId;
  public final String trust;
  public final int visitCount;
  public final long firstSeenAt;
  public final long lastSeenAt;
  public final long visitExpiresAt;
  public final String attachmentJson;
  public final Long attachExpiresAt;

  public OpDst(final String hstId, final String contextId, final String dstDomId,
               final String msgName, final String actId, final String callerDomId,
               final String trust, final int visitCount, final long firstSeenAt,
               final long lastSeenAt, final long visitExpiresAt,
               final String attachmentJson, final Long attachExpiresAt)
  {
    this.hstId = hstId;
    this.contextId = contextId;
    this.dstDomId = dstDomId;
    this.msgName = msgName;
    this.actId = actId;
    this.callerDomId = callerDomId;
    this.trust = trust;
    this.visitCount = visitCount;
    this.firstSeenAt = firstSeenAt;
    this.lastSeenAt = lastSeenAt;
    this.visitExpiresAt = visitExpiresAt;
    this.attachmentJson = attachmentJson;
    this.attachExpiresAt = attachExpiresAt;
  }
}
