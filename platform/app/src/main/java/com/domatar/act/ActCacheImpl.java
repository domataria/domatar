package com.domatar.act;

import com.domatar.core.Auth;
import com.domatar.core.LoginRemote;
import com.domatar.db.ObjDb;
import com.domatar.util.Act;
import com.domatar.util.JsonMsg;
import com.domatar.util.Obj;
import com.domatar.util.ObjAttrs;
import com.domatar.util.ObjImpl;
import com.domatar.util.DomId;
import com.domatar.util.DomatarException;
import com.domatar.util.DomatarMsgClient;

public class ActCacheImpl extends ObjImpl
{
  @Override
  public String handleMsg(final String msg,
                          final Obj obj,
                          final String contextPath,
                          final String contextRealPath,
                          final DomatarMsgClient msgClient)
      throws DomatarException
  {
    final JsonMsg inMsg  = new JsonMsg(msg);
    final JsonMsg outMsg = new JsonMsg();
    final String  opr    = inMsg.getOperation();

    if ("Compensate".equals(opr))
      return super.handleMsg(msg, obj, contextPath, contextRealPath, msgClient);


    if (!hasRights(inMsg, obj, msgClient))
      return notAuthorized(inMsg);

    if (!"GetAct".equals(opr))
      return super.handleMsg(msg, obj, contextPath, contextRealPath, msgClient);

    final DomId dstId = inMsg.getDstId();
    final DomId actCacheId = new DomId(dstId.hstId, "act", dstId.actId, "actCache");
    final Obj actCacheObj = ObjDb.getObj(actCacheId);

    final String usrId;
    final String usrName;

    if (actCacheObj == null)
    {
      final Act act = LoginRemote.getAct(actCacheId.actId, null, msgClient);

      usrId   = act.usrId;
      usrName = act.usrName;

      final ObjAttrs attrs = new ObjAttrs();
      attrs.addAttr("UsrId",   usrId);
      attrs.addAttr("UsrName", usrName);

      final Obj newCacheObj = new Obj(actCacheId, "act", "actCache", "ActCache", "ActCache",
                                      attrs);
      ObjDb.addObj(newCacheObj);
    }
    else
    {
      usrId   = actCacheObj.attrs.getAttr("UsrId");
      usrName = actCacheObj.attrs.getAttr("UsrName");
    }

    final ObjAttrs outAttrs = new ObjAttrs();
    outAttrs.addAttr("UsrId",   usrId);
    outAttrs.addAttr("UsrName", usrName);
    outMsg.addResponseBody(opr, outAttrs);

    return outMsg.toString();
  }

  // Verified: an actCache lookup is a logged-in display step.
  @Override
  public boolean hasRights(final JsonMsg inMsg, final Obj obj,
                           final DomatarMsgClient msgClient)
      throws DomatarException
  {
    return Auth.isVerified(msgClient);
  }
}
