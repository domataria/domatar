/*
 * Copyright (c) 2024 Domatar
 */

package com.domatar.core;

import com.domatar.util.Act;
import com.domatar.util.DomId;
import com.domatar.util.DomatarException;
import com.domatar.util.DomatarMsgClient;
import com.domatar.util.JsonMsg;

/**
 * A browser login that core has already checked. App code can use
 * {@link #client()} to build a message. It cannot mint a lineage for
 * a different account. {@link #root} is how the servlet sends hop 0.
 */
public final class BrowserSession
{
  private final HttpClient rootClient;
  private final HandlerClient exposed;
  private final Act act;
  private final Context context;

  BrowserSession(final HttpClient rootClient, final HandlerClient exposed,
                 final Act act, final Context context)
  {
    this.rootClient = rootClient;
    this.exposed = exposed;
    this.act = act;
    this.context = context;
  }

  public DomatarMsgClient client()
  {
    return exposed;
  }

  public Act act()
  {
    return act;
  }

  public Context context()
  {
    return context;
  }

  public DomId srcDomId()
  {
    return rootClient.getSrcId();
  }

  public JsonMsg root(final DomId dst, final JsonMsg msg) throws DomatarException
  {
    return rootClient.root(dst, msg);
  }
}
