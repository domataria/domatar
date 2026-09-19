/*
 * Copyright (c) 2024 Domatar
 */

package com.canton.objimpl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.domatar.core.Context;
import com.domatar.core.Trust;
import com.domatar.util.DomId;
import com.domatar.util.JsonMsg;

public class CantonAuthTest
{
  private static final String ACT = "A9hXcT6IM~Dl1ypTT0GAft2na2LNOpCC";

  @Test
  public void verifiedCallerMatchesDestWhenObjIsNull() throws Exception
  {
    final JsonMsg msg = request(ACT, ACT, Trust.ACCOUNT);

    assertTrue(CantonAuth.isVerifiedOwner(msg, null));
    assertEquals(ACT, CantonAuth.destActId(msg, null));
  }

  @Test
  public void rejectsDifferentAct() throws Exception
  {
    final JsonMsg msg = request(ACT, "otherActId______________________",
        Trust.ACCOUNT);

    assertFalse(CantonAuth.isVerifiedOwner(msg, null));
  }

  @Test
  public void rejectsUnverified() throws Exception
  {
    final JsonMsg msg = request(ACT, ACT, Trust.NONE);

    assertFalse(CantonAuth.isVerifiedOwner(msg, null));
  }

  private static JsonMsg request(final String callerAct, final String destAct,
                                 final Trust trust) throws Exception
  {
    final Context ctx = new Context(callerAct, "davidb@quippin", "David",
        "127.0.0.1", "tok", trust, "ctx1", null);
    final DomId src = new DomId("h1", "aiagent", callerAct, "agent-loop");
    final DomId dst = new DomId("canton-" + destAct, "canton", destAct, "app-canton");
    final JsonMsg msg = new JsonMsg();
    msg.addRequestHead(src, dst, ctx);
    msg.setContext(ctx);
    return msg;
  }
}
