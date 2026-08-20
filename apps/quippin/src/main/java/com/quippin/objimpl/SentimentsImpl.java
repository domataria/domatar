package com.quippin.objimpl;

import com.domatar.core.Auth;
import com.domatar.db.LnkDb;
import com.domatar.util.Base64Encoder;
import com.domatar.util.JsonMsg;
import com.domatar.util.Lnk;
import com.domatar.util.Obj;
import com.domatar.util.ObjImpl;
import com.domatar.util.DomId;
import com.domatar.util.DomatarException;
import com.domatar.util.DomatarMsgClient;

public class SentimentsImpl extends ObjImpl
{
  @Override
  public String handleMsg(final String msg, final Obj obj, final String contextPath,
                          final String contextRealPath, final DomatarMsgClient msgClient)
      throws DomatarException
  {
    final JsonMsg inMsg = new JsonMsg(msg);
    final String opr = inMsg.getOperation();
    final JsonMsg outMsg = new JsonMsg();

    if (!hasRights(inMsg, obj, msgClient))
      return notAuthorized(inMsg);

    if ("LnkSentiment".equals(opr))
      lnkSentiment(opr, inMsg, outMsg, msgClient);
    else
      return super.handleMsg(msg, obj, contextPath, contextRealPath, msgClient);

    return outMsg.toString();
  }

  // Verified: writing into a user's sentiments index is a logged-in op.
  @Override
  public boolean hasRights(final JsonMsg inMsg, final Obj obj, final DomatarMsgClient msgClient)
      throws DomatarException
  {
    return Auth.isVerified(inMsg);
  }

  private void lnkSentiment(final String opr, final JsonMsg inMsg, final JsonMsg outMsg,
                             final DomatarMsgClient msgClient) throws DomatarException
  {
    final DomId dstDomId = inMsg.getDstId();
    final String sentimentIdStr = inMsg.getAttrs().getAttr("SentimentId");

    if (sentimentIdStr != null)
    {
      final DomId sentimentId = new DomId(sentimentIdStr);
      final String time      = inMsg.getAttrs().getAttr("Time");
      final String sentiment = inMsg.getAttrs().getAttr("Sentiment");
      final String objName   = inMsg.getAttrs().getAttr("ObjName");
      final String objDesc   = inMsg.getAttrs().getAttr("ObjDesc");

      LnkDb.deleteLnks(dstDomId, sentimentId, "quippin", "sentiment", null, null);

      if (sentiment != null && !sentiment.equals("None"))
      {
        final long longTime = Base64Encoder.decodeToLong(time);
        final Lnk lnk = new Lnk(dstDomId, sentimentId,
                                 "quippin", "sentiment", objName, objDesc,
                                 "quippin", "sentiment", sentiment, longTime);
        LnkDb.addLnk(lnk);
      }
    }

    outMsg.addResponseBody(opr, null);
  }
}
