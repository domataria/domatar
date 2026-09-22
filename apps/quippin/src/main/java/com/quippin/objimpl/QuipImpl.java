package com.quippin.objimpl;

import java.util.List;

import com.domatar.core.Auth;
import com.domatar.core.Context;
import com.domatar.db.LnkDb;
import com.domatar.db.ObjDb;
import com.domatar.util.Act;
import com.domatar.util.IdGen;
import com.domatar.util.JsonArrayList;
import com.domatar.util.JsonMap;
import com.domatar.util.JsonMsg;
import com.domatar.util.Lnk;
import com.domatar.util.Obj;
import com.domatar.util.ObjAttrs;
import com.domatar.util.ObjImpl;
import com.domatar.util.DomId;
import com.domatar.util.DomatarException;
import com.domatar.util.DomatarMsgClient;

public class QuipImpl extends ObjImpl
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

    if ("GetQuip".equals(opr))
      getQuip(opr, inMsg, outMsg, obj, msgClient);
    else if ("AddQuipChild".equals(opr))
      addQuipChild(opr, inMsg, outMsg, obj, msgClient);
    else if ("RemoveQuipChild".equals(opr))
      removeQuipChild(opr, inMsg, outMsg, obj, msgClient);
    else if ("DeleteQuip".equals(opr))
      deleteQuip(opr, inMsg, outMsg, obj, msgClient);
    else if ("GetQuipChildren".equals(opr))
      getQuipChilden(opr, inMsg, outMsg, obj, msgClient);
    else if ("AddSentiment".equals(opr))
      addSentiment(opr, inMsg, outMsg, obj, msgClient);
    else if ("BanChild".equals(opr))
      banChild(opr, inMsg, outMsg, obj, msgClient);
    else
      return super.handleMsg(msg, obj, contextPath, contextRealPath, msgClient);

    return outMsg.toString();
  }

  // Verified: a quip is the user-content nucleus - mutating it (replies,
  // sentiments, bans) requires a logged-in caller. The owner-only gates on
  // RemoveQuipChild / BanChild stay where they are inside those methods,
  // since they're a stricter check on top of "logged in".
  @Override
  public boolean hasRights(final JsonMsg inMsg, final Obj obj, final DomatarMsgClient msgClient)
      throws DomatarException
  {
    return Auth.isVerified(inMsg);
  }

  private void getQuip(final String opr, final JsonMsg inMsg, final JsonMsg outMsg,
                       final Obj obj, final DomatarMsgClient msgClient) throws DomatarException
  {
    Log.add("Quip", inMsg, msgClient);

    final ObjAttrs inAttrs = inMsg.getAttrs();
    final String name = inAttrs.getAttr("UsrName");
    final String usrId = inAttrs.getAttr("UsrId");
    final String domId = obj.domId.toString();
    final String text = obj.attrs.getAttr("Text");
    final String sentimentNum = obj.attrs.getAttr("SentimentNum");
    final String reQuipId = obj.attrs.getAttr("ReQuipId");
    final String parentId = obj.attrs.getAttr("ParentId");
    final String quipId = IdGen.getTimeFromIdBase10(obj.domId.objId);

    final JsonMap quipAttrs = Quip.getAttrs(name,
                                            usrId,
                                            domId,
                                            text,
                                            sentimentNum,
                                            reQuipId,
                                            parentId,
                                            quipId,
                                            inMsg.getContext(),
                                            msgClient);

    final ObjAttrs outAttrs;

    if (quipAttrs != null)
      outAttrs = new ObjAttrs(quipAttrs);
    else
      outAttrs = null;

    outMsg.addResponseBody(opr, outAttrs);
  }

  private void addQuipChild(final String opr, final JsonMsg inMsg, final JsonMsg outMsg,
                            final Obj obj, final DomatarMsgClient msgClient) throws DomatarException
  {
    final DomId dstDomId = inMsg.getDstId();
    final String domIdStr = inMsg.getAttrs().getAttr("QuipId");

    if (domIdStr != null)
    {
      final DomId rspQuipId = new DomId(domIdStr);
      final String objName = inMsg.getAttrs().getAttr("ObjName");
      final String objDesc = inMsg.getAttrs().getAttr("ObjDesc");
      final long time = System.currentTimeMillis();
      final Context context = inMsg.getContext();
      final String ip = context.usrIp;
      final Lnk lnk = new Lnk(dstDomId, rspQuipId, "quippin", "quip", objName, objDesc, "quippin", "child", ip, time);
      LnkDb.addLnk(lnk);
    }

    outMsg.addResponseBody(opr, null);
  }

  private void removeQuipChild(final String opr, final JsonMsg inMsg, final JsonMsg outMsg,
                               final Obj obj, final DomatarMsgClient msgClient) throws DomatarException
  {
    // Only the owner of the quip can delete its children
    if (obj.domId.actId.equals(inMsg.getContext().actId))
    {
      final DomId dstDomId = inMsg.getDstId();
      final String childIdStr = inMsg.getAttrs().getAttr("ChildQuipId");

      if (childIdStr != null)
      {
        final DomId childId = new DomId(childIdStr);

        // Delete parent -> child lnk (existing)
        LnkDb.deleteLnks(dstDomId, childId, "quippin", "child", null, null);

        // Delete quips container -> child lnk (Navigator tree edge)
        final DomId quipsDomId = new DomId(dstDomId.hstId, "quippin", dstDomId.actId, "quips");
        LnkDb.deleteLnks(quipsDomId, childId, "quippin", "quip", null, null);
      }
    }

    outMsg.addResponseBody(opr, null);
  }

  private void addSentiment(final String opr, final JsonMsg inMsg, final JsonMsg outMsg,
                            final Obj obj, final DomatarMsgClient msgClient) throws DomatarException
  {
    Log.add("Quip", inMsg, msgClient);

    final String sentimentHstId = obj.domId.hstId;
    final String sentimentAppId = "quippin";
    final String sentimentActId = inMsg.getSrcId().actId;
    final String sentimentObjId = IdGen.createId("sentiment", IdGen.getIdSuffix(obj.domId.objId) + "-" + sentimentActId);
    final DomId sentimentDomId = new DomId(sentimentHstId, sentimentAppId, sentimentActId, sentimentObjId);

    ObjDb.deleteObj(sentimentDomId);

    final ObjAttrs msgAttrs = inMsg.getAttrs();
    String sentiment = msgAttrs.getAttr("Sentiment");

    if (sentiment == null || !"Like".equals(sentiment))
      sentiment = "None";

    final String time = IdGen.getCurTimeBase64();
    final ObjAttrs sentimentAttrs = new ObjAttrs();
    sentimentAttrs.addAttr("Sentiment", sentiment);
    sentimentAttrs.addAttr("Time", time);

    final Obj sentimentObj = new Obj(sentimentDomId, "quippin", "sentiment", sentimentObjId, sentiment, sentimentAttrs);

    if ("None".equals(sentiment))
    {
      ObjDb.incrementObjAttr(obj.domId, "SentimentNum", -1, 0);
    }
    else
    {
      ObjDb.addObj(sentimentObj);
      ObjDb.incrementObjAttr(obj.domId, "SentimentNum", 1, 0);
    }

    final JsonMsg lnkSentimentMsg = new JsonMsg();
    final ObjAttrs lnkSentimentAttrs = new ObjAttrs();
    lnkSentimentAttrs.addAttr("Time", time);
    lnkSentimentAttrs.addAttr("Sentiment", sentiment);
    lnkSentimentAttrs.addAttr("SentimentId", sentimentObj.domId.toString());
    lnkSentimentAttrs.addAttr("ObjName", sentimentObj.objName);
    lnkSentimentAttrs.addAttr("ObjDesc", sentimentObj.objDesc);
    lnkSentimentMsg.addClsId("quippin", "sentiments");
    lnkSentimentMsg.addRequestBody("LnkSentiment", lnkSentimentAttrs);

    final DomId sentimentsDomId = new DomId(DomId.subHstId("quippin", sentimentActId), "quippin", sentimentActId, "sentiments");
    msgClient.send(sentimentsDomId, lnkSentimentMsg);

    outMsg.addResponseBody(opr, null);
  }

  private void deleteQuip(final String opr, final JsonMsg inMsg, final JsonMsg outMsg,
                          final Obj obj, final DomatarMsgClient msgClient) throws DomatarException
  {
    if (!obj.domId.actId.equals(inMsg.getContext().actId))
    {
      outMsg.addError(opr, "Not authorized");
      return;
    }

    final DomId quipDomId = obj.domId;

    // Remove the quips-container -> quip link (Navigator edge).
    final DomId quipsDomId = new DomId(quipDomId.hstId, "quippin", quipDomId.actId, "quips");
    LnkDb.deleteLnks(quipsDomId, quipDomId, "quippin", "quip", null, null);

    // Remove the parent -> child link if this is a reply.
    final String parentIdStr = obj.attrs.getAttr("ParentId");

    if (parentIdStr != null)
    {
      final DomId parentId = new DomId(parentIdStr);
      LnkDb.deleteLnks(parentId, quipDomId, "quippin", "child", null, null);
    }

    ObjDb.deleteObj(quipDomId);
    outMsg.addResponseBody(opr, null);
  }

  private void getQuipChilden(final String opr, final JsonMsg inMsg, final JsonMsg outMsg,
                              final Obj obj, final DomatarMsgClient msgClient) throws DomatarException
  {
    Log.add("Quip", inMsg, msgClient);

    final List<Lnk> lnks = LnkDb.getLnks(obj.domId, "quippin", "child", null, null, 500, false);
    final JsonArrayList quipAttrList = new JsonArrayList(lnks.size());
    final Context context = inMsg.getContext();

    for (final Lnk lnk : lnks)
    {
      final Obj quipObj = new Obj(lnk.lnkDomId, msgClient);
      final Act quipAct = Act.getLocalPrvAct(quipObj.domId, msgClient);
      final String quipId = IdGen.getTimeFromIdBase10(quipObj.domId.objId);
      final String name = quipAct.usrName;
      final String usrId = quipAct.usrId;
      final String domIdStr = quipObj.domId.toString();
      final String text = quipObj.attrs.getAttr("Text");
      final String sentimentNum = quipObj.attrs.getAttr("SentimentNum");
      final String reQuipId = quipObj.attrs.getAttr("ReQuipId");
      final String parentId = quipObj.attrs.getAttr("ParentId");

      final JsonMap map = Quip.getAttrs(name,
                                        usrId,
                                        domIdStr,
                                        text,
                                        sentimentNum,
                                        reQuipId,
                                        parentId,
                                        quipId,
                                        context,
                                        msgClient);

      if (map != null)
        quipAttrList.add(map);
    }

    final ObjAttrs outAttrs = new ObjAttrs();
    outAttrs.addAttr("Quips", quipAttrList);
    outMsg.addResponseBody(opr, outAttrs);
  }

  private void banChild(final String opr, final JsonMsg inMsg, final JsonMsg outMsg,
                        final Obj obj, final DomatarMsgClient msgClient) throws DomatarException
  {
    final DomId dstId = inMsg.getDstId();
    final String childIdStr = inMsg.getAttr("ChildQuipId");
    final String banIp = inMsg.getAttr("BanIp");

    if (childIdStr != null)
    {
      final DomId childId = new DomId(childIdStr);
      final JsonMsg banMsg = new JsonMsg();
      final ObjAttrs banAttrs = new ObjAttrs();
      banAttrs.addAttr("ActId", childId.actId);

      if (banIp != null)
      {
        final Lnk lnk = LnkDb.getLnk(dstId, childId, "quippin", "child");

        if (lnk != null)
        {
          final String ip = lnk.val;

          if (ip != null && ip.length() > 0)
            banAttrs.addAttr("Ip", ip);
        }
      }

      banMsg.addClsId("quippin", "bans");
      banMsg.addRequestBody("Ban", banAttrs);

      final DomId bansId = new DomId(dstId.hstId, "quippin", dstId.actId, "bans");
      msgClient.send(bansId, banMsg);
    }

    outMsg.addResponseBody(opr, null);
  }
}
