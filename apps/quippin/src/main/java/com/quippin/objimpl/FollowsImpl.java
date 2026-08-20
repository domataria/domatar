/*
 * Copyright (c) 2024 Domatar
 */

package com.quippin.objimpl;

import java.util.ArrayList;
import java.util.List;

import com.domatar.core.Auth;
import com.domatar.core.LoginRemote;
import com.domatar.db.HstDb;
import com.domatar.db.LnkDb;
import com.domatar.db.ObjDb;
import com.domatar.util.Act;
import com.domatar.util.Hst;
import com.domatar.util.IdGen;
import com.domatar.util.JsonArrayList;
import com.domatar.util.JsonHashMap;
import com.domatar.util.JsonList;
import com.domatar.util.JsonMsg;
import com.domatar.util.Lnk;
import com.domatar.util.Obj;
import com.domatar.util.ObjAttrs;
import com.domatar.util.ObjImpl;
import com.domatar.util.DomId;
import com.domatar.util.DomatarException;
import com.domatar.util.DomatarMsgClient;

public class FollowsImpl extends ObjImpl
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

    if ("Follow".equals(opr))
      follow(opr, inMsg, outMsg);
    else if ("Unfollow".equals(opr))
      unFollow(opr, inMsg, outMsg);
    else if ("IsFollowed".equals(opr))
      isFollowed(opr, inMsg, outMsg);
    else if ("GetFollows".equals(opr))
      getFollows(opr, inMsg, outMsg, msgClient);
    else
      return super.handleMsg(msg, obj, contextPath, contextRealPath, msgClient);

    return outMsg.toString();
  }

  // Verified: managing or reading a user's follows requires a logged-in
  // caller on this prv. (Owner-match - "is the caller the follow-list's
  // owner?" - is a future refinement; for now any verified user can hit
  // any local follows container.)
  @Override
  public boolean hasRights(final JsonMsg inMsg, final Obj obj, final DomatarMsgClient msgClient)
      throws DomatarException
  {
    return Auth.isVerified(inMsg);
  }

  private void follow(final String opr, final JsonMsg inMsg, final JsonMsg outMsg) throws DomatarException
  {
    final DomId dstDomId = inMsg.getDstId();
    final String followActId = inMsg.getAttrs().getAttr("FollowActId");
    final String followUsrId = inMsg.getAttrs().getAttr("FollowUsrId");
    final String followUsrName = inMsg.getAttrs().getAttr("FollowUsrName");
    final String objId = IdGen.createId("follow", followActId);
    final DomId domId = new DomId(dstDomId.hstId, "quippin", dstDomId.actId, objId);
    final ObjAttrs objAttrs = new ObjAttrs();
    objAttrs.addAttr("TimeFollowed", IdGen.getCurTimeBase64());
    final Obj newObj = new Obj(domId, "quippin", "follow", objId, followUsrName + " " + followUsrId, objAttrs);
    ObjDb.addObj(newObj);

    // Lnk (1): follows container -> this follow row (parent-child edge).
    final DomId followsDomId = new DomId(dstDomId.hstId, "quippin", dstDomId.actId, "follows");
    LnkDb.addLnk(new Lnk(followsDomId, domId,
                          "quippin", "follow",
                          objId, followUsrName + " " + followUsrId,
                          "quippin", "follow",
                          followUsrId, System.currentTimeMillis()));

    // Lnk (2): follow row -> followee's quips (cross-app drill-through).
    // Lives on the follower's prv; the followee's prv is reached cross-prv on click.
    final DomId followeeQuipsId = new DomId(DomId.subHstId("quippin", followActId), "quippin", followActId, "quips");
    LnkDb.addLnk(new Lnk(domId, followeeQuipsId,
                          "quippin", "quips",
                          followUsrName, followUsrId,
                          "quippin", "followee",
                          followUsrId, 0));

    final ObjAttrs msgAttrs = new ObjAttrs();
    msgAttrs.addAttr("Follow", "True");
    outMsg.addResponseBody(opr, msgAttrs);
  }

  private void unFollow(final String opr, final JsonMsg inMsg, final JsonMsg outMsg) throws DomatarException
  {
    final DomId dstDomId = inMsg.getDstId();
    final String followActId = inMsg.getAttrs().getAttr("FollowActId");
    final String objId = IdGen.createId("follow", followActId);
    final DomId domId = new DomId(dstDomId.hstId, "quippin", dstDomId.actId, objId);
    ObjDb.deleteObj(domId);

    // Delete lnk (1): follows container -> this follow row.
    final DomId followsDomId = new DomId(dstDomId.hstId, "quippin", dstDomId.actId, "follows");
    LnkDb.deleteLnks(followsDomId, domId, "quippin", "follow", null, null);

    // Delete lnk (2): follow row -> followee's quips.
    final DomId followeeQuipsId = new DomId(DomId.subHstId("quippin", followActId), "quippin", followActId, "quips");
    LnkDb.deleteLnks(domId, followeeQuipsId, "quippin", "followee", null, null);

    final ObjAttrs msgAttrs = new ObjAttrs();
    msgAttrs.addAttr("Follow", "False");
    outMsg.addResponseBody(opr, msgAttrs);
  }

  private void isFollowed(final String opr, final JsonMsg inMsg, final JsonMsg outMsg) throws DomatarException
  {
    final DomId dstDomId = inMsg.getDstId();
    final String followActId = inMsg.getAttrs().getAttr("FollowActId");
    final String objId = IdGen.createId("follow", followActId);
    final DomId domId = new DomId(dstDomId.hstId, "quippin", dstDomId.actId, objId);
    final Obj obj = ObjDb.getObj(domId);
    final String follow = obj == null ? "False" : "True";
    final ObjAttrs msgAttrs = new ObjAttrs();
    msgAttrs.addAttr("Follow", follow);
    outMsg.addResponseBody(opr, msgAttrs);
  }

  private void getFollows(final String opr, final JsonMsg inMsg, final JsonMsg outMsg,
                          final DomatarMsgClient msgClient) throws DomatarException
  {
    final DomId dstDomId = inMsg.getDstId();
    final List<Obj> objs = ObjDb.getObjPrefix(dstDomId.hstId, "quippin", dstDomId.actId, "follow", null, 5000);

    // Follows list (for the Follows page display)
    final JsonList followList = new JsonArrayList(objs.size());

    // PrvIds map (for the News feed: provider -> [subhst, ...])
    final ArrayList<String> hstIdList = new ArrayList<String>();

    for (final Obj obj : objs)
    {
      final String actId = IdGen.getIdSuffix(obj.domId.objId);

      // Live lookup so we always show the current usrName and usrId.
      final Act act = LoginRemote.getAct(actId, null, msgClient);

      final String usrId   = act != null ? act.usrId   : actId;
      final String usrName = act != null ? act.usrName : actId;

      final ObjAttrs entry = new ObjAttrs();
      entry.addAttr("ActId",   actId);
      entry.addAttr("UsrId",   usrId);
      entry.addAttr("UsrName", usrName);
      followList.add(entry.toMap());

      hstIdList.add(DomId.subHstId("quippin", actId));
    }

    final Hst[] hsts = HstDb.getHsts(hstIdList.toArray(new String[0]));
    final JsonHashMap prvMap = new JsonHashMap();

    for (final Hst hst : hsts)
    {
      JsonList prvHstList = prvMap.getList(hst.prvId);
      if (prvHstList == null)
      {
        prvHstList = new JsonArrayList();
        prvMap.put(hst.prvId, prvHstList);
      }
      prvHstList.add(hst.getSubHst());
    }

    final ObjAttrs outAttrs = new ObjAttrs();
    outAttrs.addAttr("Follows", followList);
    outAttrs.addAttr("PrvIds",  prvMap);
    outMsg.addResponseBody(opr, outAttrs);
  }
}
