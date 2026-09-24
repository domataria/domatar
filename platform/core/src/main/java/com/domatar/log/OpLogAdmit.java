package com.domatar.log;

import com.domatar.core.HandlerClient;
import com.domatar.crypto.Provenance;
import com.domatar.util.JsonMsg;
import com.domatar.util.Obj;
import com.domatar.util.Rights;

/**
 * One admit, passed to {@link OpLogListener}. {@code rights} stays null
 * when a listener authorized the call itself (Compensate). It is set
 * before {@code beforeAdmit}.
 */
public final class OpLogAdmit
{
  public final HandlerClient client;
  public final JsonMsg inMsg;
  public final Obj obj;
  public final Provenance prov;
  public Rights rights;
  public boolean firstAdmit;

  public OpLogAdmit(final HandlerClient client, final JsonMsg inMsg,
      final Obj obj, final Provenance prov)
  {
    this.client = client;
    this.inMsg = inMsg;
    this.obj = obj;
    this.prov = prov;
  }
}
