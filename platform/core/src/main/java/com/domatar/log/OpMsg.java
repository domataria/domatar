package com.domatar.log;

/**
 * One {@code op_msg} out-edge row. Immutable.
 */
public final class OpMsg
{
  public final String hstId;
  public final String contextId;
  public final String srcDomId;
  public final String dstDomId;
  public final String outMsgName;
  public final int seq;
  public final long sentAt;

  public OpMsg(final String hstId, final String contextId, final String srcDomId,
               final String dstDomId, final String outMsgName, final int seq,
               final long sentAt)
  {
    this.hstId = hstId;
    this.contextId = contextId;
    this.srcDomId = srcDomId;
    this.dstDomId = dstDomId;
    this.outMsgName = outMsgName;
    this.seq = seq;
    this.sentAt = sentAt;
  }
}
