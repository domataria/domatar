/*
 * Copyright (c) 2024 Domatar
 */

package com.login.objimpl;

import java.util.List;

import com.domatar.core.Auth;
import com.domatar.core.Context;
import com.domatar.db.ActDb;
import com.domatar.util.Act;
import com.domatar.util.JsonArrayList;
import com.domatar.util.JsonHashMap;
import com.domatar.util.JsonList;
import com.domatar.util.JsonMap;
import com.domatar.util.JsonMsg;
import com.domatar.util.Obj;
import com.domatar.util.ObjAttrs;
import com.domatar.util.ObjImpl;
import com.domatar.util.DomatarException;
import com.domatar.util.DomatarMsgClient;

/**
 * Handler for class (login, accounts).
 *
 * Lives at:
 *   login~&lt;prvActId&gt; / login / &lt;prvActId&gt; / accounts
 *
 * Provider-layer object only — not created for regular user accounts.
 * Spec-DomatarApp.txt PART 6.2.2.
 *
 * Operations:
 *   GetObj  - inherited from ObjImpl
 *   Open    - returns all accounts registered on this provider (read-only v1)
 */
public class LoginAccountsImpl extends ObjImpl
{
  @Override
  public String handleMsg(final String msg, final Obj obj, final String contextPath,
                          final String contextRealPath, final DomatarMsgClient msgClient)
      throws DomatarException
  {
    final JsonMsg inMsg  = new JsonMsg(msg);
    final String  opr    = inMsg.getOperation();

    if ("Compensate".equals(opr))
      return super.handleMsg(msg, obj, contextPath, contextRealPath, msgClient);

    final JsonMsg outMsg = new JsonMsg();

    if (!hasRights(inMsg, obj, msgClient))
      return notAuthorized(inMsg);

    if ("GetLnks".equals(opr))
      open(opr, inMsg, outMsg, obj);
    else
      return super.handleMsg(msg, obj, contextPath, contextRealPath, msgClient);

    return outMsg.toString();
  }

  /** Owner-match: only the provider account may read the full account list. */
  @Override
  public boolean hasRights(final JsonMsg inMsg, final Obj obj,
                           final DomatarMsgClient msgClient)
      throws DomatarException
  {
    if (!Auth.isVerified(msgClient))
      return false;

    final String actId = Auth.actId(msgClient);
    return actId != null && obj != null && obj.domId != null
        && actId.equals(obj.domId.actId);
  }

  /**
   * Open: reads the local act table and returns one entry per account.
   * Read-only in v1; administrative operations are PART 12 TODO.
   */
  private void open(final String opr, final JsonMsg inMsg, final JsonMsg outMsg,
                    final Obj obj)
      throws DomatarException
  {
    final List<Act> acts = ActDb.getAllActs();
    final JsonList  list = new JsonArrayList();

    for (final Act a : acts)
    {
      final JsonMap entry = new JsonHashMap();
      entry.put("ActId",   a.actId);
      entry.put("UsrId",   a.usrId);
      entry.put("UsrName", a.usrName);
      list.add(entry);
    }

    final ObjAttrs outAttrs = new ObjAttrs();
    outAttrs.addAttr("Children", list);
    outMsg.addResponseBody(opr, outAttrs);
  }
}
