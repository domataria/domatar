package com.domatar.util;

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
   * Same chain and identity; only the session token changes.
   */
  public DomatarMsgClient withToken(String token) throws DomatarException;

  /**
   * Spec PART 8.5 projection: UI then every destination, a fresh
   * {@code DomId[]} per call, derived from the held path.
   */
  public DomId[] domIdPath() throws DomatarException;
}
