/*
 * Copyright (c) 2024 Domatar
 */

package com.quippin.objimpl;

import com.domatar.core.Context;
import com.domatar.db.ObjDb;
import com.domatar.util.Act;
import com.domatar.util.IdGen;
import com.domatar.util.JsonHashMap;
import com.domatar.util.JsonMap;
import com.domatar.util.Obj;
import com.domatar.util.DomatarException;
import com.domatar.util.DomId;
import com.domatar.util.DomatarMsgClient;

public class Quip
{
  static JsonMap getAttrs(final String name,
                          final String usrId,
                          final String domIdStr,
                          final String text,
                          String sentimentNum,
                          final String reQuipId,
                          final String parentId,
                          final String quipId,
                          final Context context,
                          final DomatarMsgClient msgClient) throws DomatarException
  {
    final DomId domId = new DomId(domIdStr);

    if (isBanned(domId, context))
      return null;

    final JsonMap attrMap = new JsonHashMap();

    final Obj reQuipObj;

    if (reQuipId != null)
    {
      final DomId reQuipDomId = new DomId(reQuipId);
      reQuipObj = new Obj(reQuipDomId, msgClient);
    }
    else
      reQuipObj = null;

    if (reQuipObj == null)
    {
      if (sentimentNum == null)
        sentimentNum = "0";

      attrMap.put("Type", "Quip");
      attrMap.put("Name", name);
      attrMap.put("Handle", DomId.getUsrHandle(usrId));
      attrMap.put("Host", DomId.getUsrHst(usrId));
      attrMap.put("QuipId", quipId);
      attrMap.put("DomId", domIdStr);
      attrMap.put("ParentId", parentId);
      attrMap.put("Text", text);
      attrMap.put("SentimentNum", sentimentNum);
      attrMap.put("Liked", getLiked(domId, context));
    }
    else
    {
      final Act act = Act.getLocalPrvAct(reQuipObj.domId, msgClient);

      final String reQuipName = act.usrName;
      final String reQuipHandle = DomId.getUsrHandle(act.usrId);
      final String reQuipHost = DomId.getUsrHst(act.usrId);
      final String reQuipQuipId = IdGen.getTimeFromIdBase10(reQuipObj.domId.objId);
      final String reQuipDomId = reQuipObj.domId.toString();
      String reQuipText = reQuipObj.attrs != null ? reQuipObj.attrs.getAttr("Text") : null;
      if (reQuipText == null)
        reQuipText = "";

      final DomId reQuipdomId = new DomId(reQuipDomId);

      if (text == null)
      {
        String reQuipSentimentNum = reQuipObj.attrs.getAttr("SentimentNum");

        if (reQuipSentimentNum == null)
          reQuipSentimentNum = "0";

        attrMap.put("Type", "ReQuip");
        // Requip author (who reposted) — use the requip's own identity so it
        // is not deduplicated against the original quip in the news feed.
        attrMap.put("Name", name);
        attrMap.put("Handle", DomId.getUsrHandle(usrId));
        attrMap.put("Host", DomId.getUsrHst(usrId));
        attrMap.put("QuipId", quipId);    // requip's own timestamp → sorts at requip time
        attrMap.put("DomId", domIdStr);   // requip's own DomId    → unique in feed
        // Original quip content and attribution
        attrMap.put("OrigName", reQuipName);
        attrMap.put("OrigHandle", reQuipHandle);
        attrMap.put("OrigHost", reQuipHost);
        attrMap.put("OrigQuipId", reQuipQuipId);
        attrMap.put("OrigDomId", reQuipDomId);
        attrMap.put("Text", reQuipText);
        attrMap.put("SentimentNum", reQuipSentimentNum);
        attrMap.put("Liked", getLiked(reQuipdomId, context));
      }
      else
      {
        if (sentimentNum == null)
          sentimentNum = "0";

        attrMap.put("Type", "QuoteQuip");
        attrMap.put("Name", name);
        attrMap.put("Handle", DomId.getUsrHandle(usrId));
        attrMap.put("Host", DomId.getUsrHst(usrId));
        attrMap.put("QuipId", quipId);
        attrMap.put("DomId", domIdStr);
        attrMap.put("Text", text);
        attrMap.put("SentimentNum", sentimentNum);
        attrMap.put("QuoteName", reQuipName);
        attrMap.put("QuoteHandle", reQuipHandle);
        attrMap.put("QuoteHost", reQuipHost);
        attrMap.put("QuoteQuipId", reQuipQuipId);
        attrMap.put("QuoteDomId", reQuipDomId);
        attrMap.put("QuoteText", reQuipText);
        attrMap.put("Liked", getLiked(reQuipdomId, context));
      }
    }

    return attrMap;
  }

  private static String getLiked(final DomId domId, final Context context) throws DomatarException
  {
    // No identified caller, no per-caller "liked" state to look up.
    if (context == null || context.actId == null)
      return "False";

    final String quipSuffix = IdGen.getIdSuffix(domId.objId);
    final String sentimentObjId = IdGen.createId("sentiment",
                                                  quipSuffix + "-" + context.actId);

    final DomId likeId = new DomId(domId.hstId,
                                   domId.appId,
                                   context.actId,
                                   sentimentObjId);

    final Obj likeObj = ObjDb.getObj(likeId);

    return (likeObj != null) ? "True" : "False";
  }

  private static boolean isBanned(final DomId domId, final Context context) throws DomatarException
  {
    // An unidentified caller (e.g. a cross-prv read where the caller's
    // local session does not exist on this prv) cannot match a per-act
    // ban record, so skip that check rather than NPE in IdGen.createId.
    final String actId = context == null ? null : context.actId;

    if (actId != null)
    {
      final String banActObjId = IdGen.createId("banAct", actId, '_');
      final DomId banActDomId = new DomId(domId.hstId, domId.appId, domId.actId, banActObjId);
      final Obj banActObj = ObjDb.getObj(banActDomId);
      if (banActObj != null)
        return true;
    }

    final String ip = context == null ? null : context.usrIp;

    if (ip != null)
    {
      final String banIpObjId = IdGen.createId("banIp", ip, '_');
      final DomId banIpDomId = new DomId(domId.hstId, domId.appId, domId.actId, banIpObjId);
      final Obj banIpObj = ObjDb.getObj(banIpDomId);
      if (banIpObj != null)
        return true;
    }

    return false;
  }
}
