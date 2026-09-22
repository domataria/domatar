package com.quippin.objimpl;

import java.util.List;

import com.domatar.core.Auth;
import com.domatar.core.Context;
import com.domatar.db.LnkDb;
import com.domatar.db.ObjDb;
import com.domatar.util.IdGen;
import com.domatar.util.JsonArrayList;
import com.domatar.util.JsonList;
import com.domatar.util.JsonMap;
import com.domatar.util.JsonMsg;
import com.domatar.util.Lnk;
import com.domatar.util.Obj;
import com.domatar.util.ObjAttrs;
import com.domatar.util.ObjImpl;
import com.domatar.util.DomId;
import com.domatar.util.DomatarException;
import com.domatar.util.DomatarMsgClient;

public class QuipsImpl extends ObjImpl
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

    if ("GetQuips".equals(opr))
      getQuips(opr, inMsg, outMsg, msgClient);
    else if ("AddQuip".equals(opr))
      addQuip(opr, inMsg, outMsg, msgClient);
    else if ("BanUser".equals(opr))
      banUser(opr, inMsg, outMsg, msgClient);
    else
      return super.handleMsg(msg, obj, contextPath, contextRealPath, msgClient);

    return outMsg.toString();
  }

  // Verified: viewing or posting in someone's quips container requires a
  // logged-in caller on this prv.
  @Override
  public boolean hasRights(final JsonMsg inMsg, final Obj obj, final DomatarMsgClient msgClient)
      throws DomatarException
  {
    return Auth.isVerified(inMsg);
  }

  private void getQuips(final String opr, final JsonMsg inMsg, final JsonMsg outMsg,
                        final DomatarMsgClient msgClient) throws DomatarException
  {
    Log.add("Quips", inMsg, msgClient);

    final ObjAttrs attrs = inMsg.getAttrs();
    final DomId domId = inMsg.getDstId();
    final Context context = inMsg.getContext();
    final String name = attrs.getAttr("UsrName");
    final String usrId = attrs.getAttr("UsrId");
    final List<Obj> objList = ObjDb.getObjPrefix(domId.hstId, "quippin", domId.actId, "quip", null, 50);
    final JsonList quipAttrList = new JsonArrayList(objList.size());

    for (final Obj obj : objList)
    {
      try
      {
        final String quipId = IdGen.getTimeFromIdBase10(obj.domId.objId);
        final String sentimentNum = obj.attrs.getAttr("SentimentNum");
        final String domIdStr = obj.domId.toString();
        final String text = obj.attrs.getAttr("Text");
        final String reQuipId = obj.attrs.getAttr("ReQuipId");
        final String parentId = obj.attrs.getAttr("ParentId");

        final JsonMap map = Quip.getAttrs(name,
                                          usrId,
                                          domIdStr,
                                          text,
                                          sentimentNum,
                                          reQuipId,
                                          parentId,
                                          quipId,
                                          inMsg.getContext(),
                                          msgClient);

        if (map != null)
        {
          final String quipSuffix = IdGen.getIdSuffix(obj.domId.objId);
          final String sentimentObjId = IdGen.createId("sentiment", quipSuffix + "-" + context.actId);
          final DomId likeId = new DomId(obj.domId.hstId,
                                         obj.domId.appId,
                                         context.actId,
                                         sentimentObjId);
          final Obj likeObj = ObjDb.getObj(likeId);
          map.put("Liked", (likeObj != null) ? "True" : "False");
          quipAttrList.add(map);
        }
      }
      catch (Exception e)
      {
        System.out.println(e.toString());
      }
    }

    final ObjAttrs outAttrs = new ObjAttrs();
    outAttrs.addAttr("Quips", quipAttrList);
    outMsg.addResponseBody(opr, outAttrs);
  }

  private void addQuip(final String opr, final JsonMsg inMsg, final JsonMsg outMsg,
                       final DomatarMsgClient msgClient) throws DomatarException
  {
    final DomId dstDomId = inMsg.getDstId();
    final String objId = IdGen.getId("quip");
    final DomId quipId = new DomId(dstDomId.hstId, "quippin", dstDomId.actId, objId);
    final ObjAttrs attrs = new ObjAttrs();
    final String text = inMsg.getAttrs().getAttr("Text");

    if (text != null)
      attrs.addAttr("Text", text);

    final String parentIdStr = inMsg.getAttrs().getAttr("ParentId");

    if (parentIdStr != null)
      attrs.addAttr("ParentId", parentIdStr);

    final String reQuipId = inMsg.getAttrs().getAttr("ReQuipId");

    if (reQuipId != null)
      attrs.addAttr("ReQuipId", reQuipId);

    attrs.addAttr("SentimentNum", "0");

    final String objName;
    final String objDesc;

    if (text != null)
    {
      objName = text.length() <= 40  ? text : text.substring(0, 40);
      objDesc = text.length() <= 100 ? text : text.substring(0, 100);
    }
    else
    {
      // Fetch the original quip text so the Navigator shows meaningful labels.
      String originalText = null;
      try
      {
        final Obj originalQuip = new Obj(new DomId(reQuipId), msgClient);
        if (originalQuip.attrs != null)
          originalText = originalQuip.attrs.getAttr("Text");
      }
      catch (Exception ignored) {}

      if (originalText != null && !originalText.isEmpty())
      {
        objName = "Requip: " + (originalText.length() <= 38  ? originalText : originalText.substring(0, 38));
        objDesc = "Requip: " + (originalText.length() <= 98 ? originalText : originalText.substring(0, 98));
      }
      else
      {
        objName = "Requip";
        objDesc = "Requip";
      }
    }

    final Obj quipObj = new Obj(quipId, "quippin", "quip", objName, objDesc, attrs);
    ObjDb.addObj(quipObj);

    // Navigator lnk: quips container -> this quip row.
    LnkDb.addLnk(new Lnk(dstDomId, quipId,
                          "quippin", "quip",
                          objName, objDesc,
                          "quippin", "quip",
                          null, System.currentTimeMillis()));

    // Navigator lnk: this requip -> original quip (cross-host link so the
    // Navigator can navigate directly to the original quip from the repost).
    if (reQuipId != null)
    {
      final DomId originalDomId = new DomId(reQuipId);
      LnkDb.addLnk(new Lnk(quipId, originalDomId,
                            "quippin", "quip",
                            objName, objDesc,
                            "quippin", "requip",
                            null, 1L));
    }

    if (parentIdStr != null)
    {
      final DomId parentId = new DomId(parentIdStr);
      final JsonMsg addQuipResponseMsg = new JsonMsg();
      final ObjAttrs responseAttrs = new ObjAttrs();
      responseAttrs.addAttr("QuipId", quipId.toString());
      responseAttrs.addAttr("ObjName", objName);
      responseAttrs.addAttr("ObjDesc", objDesc);
      addQuipResponseMsg.addRequestBody("AddQuipChild", responseAttrs);
      msgClient.send(parentId, addQuipResponseMsg);
    }

    outMsg.addResponseBody(opr, null);
  }

  // Ban a user's actId from appearing in the caller's quips namespace.
  // The actId is derived from the quip's DomId that the caller passed in.
  private void banUser(final String opr, final JsonMsg inMsg, final JsonMsg outMsg,
                       final DomatarMsgClient msgClient) throws DomatarException
  {
    final DomId dstDomId = inMsg.getDstId(); // caller's quips container
    final String actIdToBan = inMsg.getAttrs().getAttr("ActId");

    if (actIdToBan == null)
    {
      outMsg.addError(opr, "Missing ActId");
      return;
    }

    final JsonMsg banMsg = new JsonMsg();
    final ObjAttrs banAttrs = new ObjAttrs();
    banAttrs.addAttr("ActId", actIdToBan);
    banMsg.addClsId("quippin", "bans");
    banMsg.addRequestBody("Ban", banAttrs);

    final DomId bansId = new DomId(dstDomId.hstId, "quippin", dstDomId.actId, "bans");
    msgClient.send(bansId, banMsg);

    outMsg.addResponseBody(opr, null);
  }
}
