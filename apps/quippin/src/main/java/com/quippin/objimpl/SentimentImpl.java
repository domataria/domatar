package com.quippin.objimpl;

import com.domatar.core.Auth;
import com.domatar.util.Act;
import com.domatar.util.IdGen;
import com.domatar.util.JsonMsg;
import com.domatar.util.Obj;
import com.domatar.util.ObjAttrs;
import com.domatar.util.ObjImpl;
import com.domatar.util.DomatarException;
import com.domatar.util.DomatarMsgClient;

public class SentimentImpl extends ObjImpl
{
  @Override
  public String handleMsg(final String msg, final Obj obj, final String contextPath,
                          final String contextRealPath, final DomatarMsgClient msgClient)
      throws DomatarException
  {
    final JsonMsg inMsg = new JsonMsg(msg);
    final String opr = inMsg.getOperation();

    if ("Compensate".equals(opr))
      return super.handleMsg(msg, obj, contextPath, contextRealPath, msgClient);

    final JsonMsg outMsg = new JsonMsg();

    if (!hasRights(inMsg, obj, msgClient))
      return notAuthorized(inMsg);

    if (obj == null || obj.domId == null)
    {
      outMsg.addError(opr, "Obj not found");
      return outMsg.toString();
    }

    if ("GetSentiment".equals(opr))
      getSentiment(opr, inMsg, outMsg, obj, msgClient);
    else
      return super.handleMsg(msg, obj, contextPath, contextRealPath, msgClient);

    return outMsg.toString();
  }

  // Verified: sentiment lookups are read-only but display sensitive enough
  // to gate behind login (matches today's framework gate).
  @Override
  public boolean hasRights(final JsonMsg inMsg, final Obj obj, final DomatarMsgClient msgClient)
      throws DomatarException
  {
    return Auth.isVerified(msgClient);
  }

  private void getSentiment(final String opr, final JsonMsg inMsg, final JsonMsg outMsg,
                            final Obj obj, final DomatarMsgClient msgClient) throws DomatarException
  {
    final Act act = Act.getLocalPrvAct(obj.domId, msgClient);
    final String name = act.usrName;
    final String handle = act.getUsrHandle();
    final ObjAttrs outAttrs = new ObjAttrs();
    final String quipId = IdGen.getTimeFromIdBase10(obj.domId.objId);
    final String domId = obj.domId.toString();
    final String sentiment = obj.attrs.getAttr("Sentiment");
    outAttrs.addAttr("Name", name);
    outAttrs.addAttr("Handle", handle);
    outAttrs.addAttr("QuipId", quipId);
    outAttrs.addAttr("DomId", domId);
    outAttrs.addAttr("Sentiment", sentiment);
    outMsg.addResponseBody(opr, outAttrs);
  }
}
