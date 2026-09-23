package com.domatar.util;

import java.util.List;

import com.domatar.log.OpMsg;

/**
 * The client a handler receives is a capability that can only EXTEND
 * the chain it was given. It does not expose root, rootAs, or forward.
 */
public interface DomatarMsgClient
{
  public String send (DomId domId, String document) throws DomatarException;

  public JsonMsg send (DomId domId, JsonMsg jsonMsg) throws DomatarException;

  public DomId getSrcId();

  /**
   * Lineage id stamped for this delivery. Null when this client
   * holds no platform context.
   */
  public String contextId();

  /**
   * Same chain and identity; only the session token changes.
   */
  public DomatarMsgClient withToken(String token) throws DomatarException;

  /**
   * Spec PART 8.5 projection: UI then every destination, a fresh
   * {@code DomId[]} per call, derived from the held path.
   */
  public DomId[] domIdPath() throws DomatarException;

  public int priorVisitCount() throws DomatarException;

  public int priorVisitCount(String contextId) throws DomatarException;

  public boolean alreadyEntered() throws DomatarException;

  public boolean alreadyEntered(String contextId) throws DomatarException;

  public int priorVisitCountAny() throws DomatarException;

  public int priorVisitCountAny(String contextId) throws DomatarException;

  public List<OpMsg> outMsgs() throws DomatarException;

  public List<OpMsg> outMsgs(String contextId) throws DomatarException;

  public void attach(String slot, Object json, long attachExpiresAt)
      throws DomatarException;

  public void attach(String contextId, String slot, Object json,
      long attachExpiresAt) throws DomatarException;

  public void attach(String contextId, String msgName, String slot,
      Object json, long attachExpiresAt) throws DomatarException;

  public Object attachment(String slot) throws DomatarException;

  public Object attachment(String contextId, String slot) throws DomatarException;

  public Object attachment(String contextId, String msgName, String slot)
      throws DomatarException;
}
