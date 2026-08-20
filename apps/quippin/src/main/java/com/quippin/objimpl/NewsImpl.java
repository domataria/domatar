package com.quippin.objimpl;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import com.domatar.db.ActDb;
import com.domatar.db.ObjDb;
import com.domatar.util.Act;
import com.domatar.util.IdGen;
import com.domatar.util.JsonArrayList;
import com.domatar.util.JsonList;
import com.domatar.util.JsonMap;
import com.domatar.util.JsonMsg;
import com.domatar.util.Obj;
import com.domatar.util.ObjAttrs;
import com.domatar.util.ObjImpl;
import com.domatar.util.DomId;
import com.domatar.util.DomatarException;
import com.domatar.util.DomatarMsgClient;

public class NewsImpl extends ObjImpl
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

    if ("GetNews".equals(opr))
      getNews(opr, inMsg, outMsg, msgClient);
    else
      return super.handleMsg(msg, obj, contextPath, contextRealPath, msgClient);

    return outMsg.toString();
  }

  // hasRights: inherits the default (public). News-feed reads are
  // public-by-design: a logged-in user on prv1 must be able to fetch a
  // followed account's quips from prv2 without owning an act on prv2.
  // The data returned is public quips; no per-user state leaks.

  private void getNews(final String opr, final JsonMsg inMsg, final JsonMsg outMsg,
                       final DomatarMsgClient msgClient) throws DomatarException
  {
    final ObjAttrs attrs = inMsg.getAttrs();
    final JsonList actIdList = attrs.getAttrList("Acts");
    final HashMap<String, Act> actMap = new HashMap<String, Act>(actIdList.size());

    for (final Object actId : actIdList)
    {
      final Act act = ActDb.getAct((String) actId);
      if (act != null)
        actMap.put((String) actId, act);
    }

    final String fromTime = IdGen.getCurTimeBase64();
    final String toTime = IdGen.addTimeBase64(fromTime, -24 * 1000, 0, 0, 0);
    final String fromObjId = IdGen.createId("quip", fromTime);
    final String toObjId = IdGen.createId("quip", toTime);
    final ArrayList<Obj> objList = new ArrayList<Obj>();

    for (final Object actId : actIdList)
    {
      final DomId domId = new DomId(DomId.subHstId("quippin", (String) actId),
                                    "quippin",
                                    (String) actId,
                                    fromObjId);
      final List<Obj> actObjList = ObjDb.getObjRange(domId, toObjId, null, 50);
      objList.addAll(actObjList);
      Log.add("News", (String) actId, opr, null, msgClient);
    }

    objList.sort((obj1, obj2) -> -obj1.domId.objId.compareTo(obj2.domId.objId));

    final JsonList quipAttrList = new JsonArrayList(objList.size());

    for (final Obj obj : objList)
    {
      final String quipId = IdGen.getTimeFromIdBase10(obj.domId.objId);
      final String actId = obj.domId.actId;
      final Act act = actMap.get(actId);

      if (act != null)
      {
        final String name = act.usrName;
        final String usrId = act.usrId;
        final String domIdStr = obj.domId.toString();
        final String text = obj.attrs.getAttr("Text");
        final String sentimentNum = obj.attrs.getAttr("SentimentNum");
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
          quipAttrList.add(map);
      }
    }

    final ObjAttrs outAttrs = new ObjAttrs();
    outAttrs.addAttr("Quips", quipAttrList);
    outMsg.addResponseBody(opr, outAttrs);
  }
}
