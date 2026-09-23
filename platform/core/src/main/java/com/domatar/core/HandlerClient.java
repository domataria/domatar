/*
 * Copyright (c) 2024 Domatar
 */

package com.domatar.core;

import java.util.List;

import com.domatar.crypto.Provenance;
import com.domatar.log.OpMsg;
import com.domatar.util.DomId;
import com.domatar.util.DomatarException;
import com.domatar.util.DomatarMsgClient;
import com.domatar.util.JsonMsg;

/**
 * The only client a handler is given. Final, so an app cannot subclass
 * it, and the constructor is package-private, so an app cannot build
 * one. {@link Auth} trusts this class and nothing else that implements
 * {@link DomatarMsgClient}.
 */
public final class HandlerClient implements DomatarMsgClient
{
  private final HttpClient inner;

  HandlerClient(final HttpClient inner)
  {
    this.inner = inner;
  }

  HttpClient inner()
  {
    return inner;
  }

  public boolean holdsAccount()
  {
    final Context ctx = inner.inboundContext();
    return ctx != null && ctx.isVerified();
  }

  /** ActId when this client holds {@link Trust#ACCOUNT}; otherwise null. */
  public String accountActId()
  {
    final Context ctx = inner.inboundContext();
    if (ctx == null || !ctx.isVerified())
      return null;
    return ctx.actId;
  }

  @Override
  public String contextId()
  {
    final Context ctx = inner.inboundContext();
    return ctx == null ? null : ctx.contextId;
  }

  public Context stampedContext()
  {
    return inner.inboundContext();
  }

  public String callerDomId() throws DomatarException
  {
    final Provenance prov = inner.inboundProvenance();
    if (prov == null || prov.isEmpty())
      return getSrcId().toString();
    return prov.path().last().srcDomId;
  }

  public String contextPath()
  {
    return inner.handlerContextPath();
  }

  public String contextRealPath()
  {
    return inner.handlerContextRealPath();
  }

  public void snapshotPriors(final String contextId, final String dstDomId,
                             final String msgName) throws DomatarException
  {
    inner.snapshotPriors(contextId, dstDomId, msgName);
  }

  public void setAdmitFirst(final boolean first)
  {
    inner.thisAdmitIsFirst = first;
  }

  public boolean admitWasFirst()
  {
    return inner.thisAdmitIsFirst;
  }

  @Override
  public String send(final DomId domId, final String document) throws DomatarException
  {
    return inner.send(domId, document);
  }

  @Override
  public JsonMsg send(final DomId domId, final JsonMsg jsonMsg) throws DomatarException
  {
    return inner.send(domId, jsonMsg);
  }

  @Override
  public DomId getSrcId()
  {
    return inner.getSrcId();
  }

  @Override
  public DomatarMsgClient withToken(final String token) throws DomatarException
  {
    return new HandlerClient((HttpClient) inner.withToken(token));
  }

  @Override
  public DomId[] domIdPath() throws DomatarException
  {
    return inner.domIdPath();
  }

  @Override
  public int priorVisitCount() throws DomatarException
  {
    return inner.priorVisitCount();
  }

  @Override
  public int priorVisitCount(final String contextId) throws DomatarException
  {
    return inner.priorVisitCount(contextId);
  }

  @Override
  public boolean alreadyEntered() throws DomatarException
  {
    return inner.alreadyEntered();
  }

  @Override
  public boolean alreadyEntered(final String contextId) throws DomatarException
  {
    return inner.alreadyEntered(contextId);
  }

  @Override
  public int priorVisitCountAny() throws DomatarException
  {
    return inner.priorVisitCountAny();
  }

  @Override
  public int priorVisitCountAny(final String contextId) throws DomatarException
  {
    return inner.priorVisitCountAny(contextId);
  }

  @Override
  public List<OpMsg> outMsgs() throws DomatarException
  {
    return inner.outMsgs();
  }

  @Override
  public List<OpMsg> outMsgs(final String contextId) throws DomatarException
  {
    return inner.outMsgs(contextId);
  }

  @Override
  public void attach(final String slot, final Object json, final long attachExpiresAt)
      throws DomatarException
  {
    inner.attach(slot, json, attachExpiresAt);
  }

  @Override
  public void attach(final String contextId, final String slot, final Object json,
                     final long attachExpiresAt) throws DomatarException
  {
    inner.attach(contextId, slot, json, attachExpiresAt);
  }

  @Override
  public void attach(final String contextId, final String msgName, final String slot,
                     final Object json, final long attachExpiresAt) throws DomatarException
  {
    inner.attach(contextId, msgName, slot, json, attachExpiresAt);
  }

  @Override
  public Object attachment(final String slot) throws DomatarException
  {
    return inner.attachment(slot);
  }

  @Override
  public Object attachment(final String contextId, final String slot) throws DomatarException
  {
    return inner.attachment(contextId, slot);
  }

  @Override
  public Object attachment(final String contextId, final String msgName, final String slot)
      throws DomatarException
  {
    return inner.attachment(contextId, msgName, slot);
  }
}
