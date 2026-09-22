/*
 * Copyright (c) 2024 Domatar
 */

package com.quippin.objimpl;

import java.util.List;

import com.domatar.core.Auth;
import com.domatar.core.LoginRemote;
import com.domatar.db.LnkDb;
import com.domatar.db.ObjDb;
import com.domatar.util.Act;
import com.domatar.util.IdGen;
import com.domatar.util.JsonArrayList;
import com.domatar.util.JsonHashMap;
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

/**
 * LLM-native facade for the Quippin microblog app.
 *
 * Handles class (quippin, app) — the app-quippin entry-point object.
 * Exposes the user's own quips, followed-accounts feed, follows list,
 * user directory search, and write operations.
 *
 * Operations: GetQuips(), GetFeed(), GetQuip(QuipId,ActId), GetReplies(QuipId,ActId),
 *             GetNews(ActIds), GetFollows(), IsFollowed(ActId), FindUsers(Query),
 *             GetBans(), GetLogs(), Follow(ActId), Unfollow(ActId), PostQuip(Text).
 *
 * Spec: Spec-LLM-Oriented-Msgs.txt — QUIPPIN section.
 */
public class QuippinAppImpl extends ObjImpl
{
  private static final String DIR_HST_ID = "quippin";
  private static final String DIR_APP_ID = "quippin";
  private static final String DIR_ACT_ID = "quippin@quippin";
  private static final String DIR_OBJ_ID = "directory";

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

    final DomId appDomId = inMsg.getDstId();

    if ("GetQuips".equals(opr))
      getQuips(opr, outMsg, appDomId);
    else if ("GetFeed".equals(opr))
      getFeed(opr, outMsg, appDomId, msgClient);
    else if ("GetQuip".equals(opr))
      getQuip(opr, inMsg, outMsg, appDomId);
    else if ("GetReplies".equals(opr))
      getReplies(opr, inMsg, outMsg, appDomId);
    else if ("GetNews".equals(opr))
      getNews(opr, inMsg, outMsg);
    else if ("GetFollows".equals(opr))
      getFollows(opr, outMsg, appDomId, msgClient);
    else if ("IsFollowed".equals(opr))
      isFollowed(opr, inMsg, outMsg, appDomId);
    else if ("FindUsers".equals(opr))
      findUsers(opr, inMsg, outMsg, msgClient);
    else if ("GetBans".equals(opr))
      getBans(opr, outMsg, appDomId);
    else if ("GetLogs".equals(opr))
      getLogs(opr, outMsg, appDomId);
    else if ("Follow".equals(opr))
      follow(opr, inMsg, outMsg, appDomId, msgClient);
    else if ("Unfollow".equals(opr))
      unfollow(opr, inMsg, outMsg, appDomId);
    else if ("PostQuip".equals(opr))
      postQuip(opr, inMsg, outMsg, appDomId);
    else
      return super.handleMsg(msg, obj, contextPath, contextRealPath, msgClient);

    return outMsg.toString();
  }

  @Override
  public boolean hasRights(final JsonMsg inMsg, final Obj obj, final DomatarMsgClient msgClient)
      throws DomatarException
  {
    return Auth.isVerified(inMsg);
  }

  // ---------------------------------------------------------------------------

  private void getQuips(final String opr, final JsonMsg outMsg, final DomId appDomId)
      throws DomatarException
  {
    final List<Obj> quips = ObjDb.getObjPrefix(
        appDomId.hstId, "quippin", appDomId.actId, "quip", null, 50);

    final JsonList list = new JsonArrayList(quips.size());
    for (final Obj q : quips)
    {
      final JsonMap entry = new JsonHashMap(4);
      entry.put("QuipId", q.domId.objId);
      entry.put("Text",   safeGet(q.attrs, "Text"));
      entry.put("Time",   safeGet(q.attrs, "Time"));
      entry.put("Likes",  safeGet(q.attrs, "SentimentNum"));
      list.add(entry);
    }

    final ObjAttrs out = new ObjAttrs();
    out.addAttr("Quips", list);
    outMsg.addResponseBody(opr, out);
  }

  /**
   * Returns quips from accounts the caller follows by delegating to each
   * followed account's quips container (up to 10 accounts, 10 quips each).
   */
  private void getFeed(final String opr, final JsonMsg outMsg, final DomId appDomId,
                       final DomatarMsgClient msgClient)
      throws DomatarException
  {
    final DomId followsId = new DomId(appDomId.hstId, "quippin", appDomId.actId, "follows");
    final List<Lnk> follows = LnkDb.getLnks(followsId, "quippin", "follow",
                                             null, null, 10, false);

    final JsonList feed = new JsonArrayList(follows.size() * 10);

    for (final Lnk follow : follows)
    {
      final String followedActId = follow.lnkDomId != null ? follow.lnkDomId.actId : null;
      if (followedActId == null)
        continue;

      final String followedHstId = DomId.subHstId("quippin", followedActId);
      final List<Obj> quips = ObjDb.getObjPrefix(
          followedHstId, "quippin", followedActId, "quip", null, 10);

      for (final Obj q : quips)
      {
        final JsonMap entry = new JsonHashMap(5);
        entry.put("QuipId", q.domId.objId);
        entry.put("Text",   safeGet(q.attrs, "Text"));
        entry.put("Time",   safeGet(q.attrs, "Time"));
        entry.put("Author", followedActId);
        entry.put("Likes",  safeGet(q.attrs, "SentimentNum"));
        feed.add(entry);
      }
    }

    final ObjAttrs out = new ObjAttrs();
    out.addAttr("Quips", feed);
    outMsg.addResponseBody(opr, out);
  }

  private void postQuip(final String opr, final JsonMsg inMsg, final JsonMsg outMsg,
                        final DomId appDomId)
      throws DomatarException
  {
    final String text = inMsg.getAttrs().getAttr("Text");
    if (text == null || text.isEmpty())
    {
      outMsg.addError(opr, "Missing Text");
      return;
    }

    final DomId  quipsId   = new DomId(appDomId.hstId, "quippin", appDomId.actId, "quips");
    final String quipObjId = IdGen.getId("quip");
    final DomId  quipId    = new DomId(quipsId.hstId, "quippin", quipsId.actId, quipObjId);

    final ObjAttrs attrs = new ObjAttrs();
    attrs.addAttr("Text",         text);
    attrs.addAttr("Time",         Long.toString(System.currentTimeMillis()));
    attrs.addAttr("SentimentNum", "0");

    final String shortText = text.length() > 40 ? text.substring(0, 40) : text;
    ObjDb.addObj(new Obj(quipId, "quippin", "quip", shortText, "", attrs));

    LnkDb.addLnk(new Lnk(quipsId, quipId,
                          "quippin", "quip",
                          shortText, "",
                          "quippin", "quip",
                          null, System.currentTimeMillis()));

    final ObjAttrs out = new ObjAttrs();
    out.addAttr("QuipId", quipObjId);
    outMsg.addResponseBody(opr, out);
  }

  /**
   * Returns details for a single quip.
   * ActId defaults to the caller's own actId when not supplied.
   */
  private void getQuip(final String opr, final JsonMsg inMsg, final JsonMsg outMsg,
                       final DomId appDomId)
      throws DomatarException
  {
    final String quipId = inMsg.getAttrs().getAttr("QuipId");
    if (quipId == null || quipId.isEmpty())
    {
      outMsg.addError(opr, "Missing QuipId");
      return;
    }
    String actId = inMsg.getAttrs().getAttr("ActId");
    if (actId == null || actId.isEmpty())
      actId = appDomId.actId;

    final DomId quipDomId = new DomId(DomId.subHstId("quippin", actId), "quippin", actId, quipId);
    final Obj   quip      = ObjDb.getObj(quipDomId);

    if (quip == null)
    {
      outMsg.addError(opr, "Quip not found: " + quipId);
      return;
    }

    final ObjAttrs out = new ObjAttrs();
    out.addAttr("QuipId",   quipId);
    out.addAttr("ActId",    actId);
    out.addAttr("Text",     safeGet(quip.attrs, "Text"));
    out.addAttr("Time",     safeGet(quip.attrs, "Time"));
    out.addAttr("Likes",    safeGet(quip.attrs, "SentimentNum"));
    out.addAttr("ParentId", safeGet(quip.attrs, "ParentId"));
    out.addAttr("ReQuipId", safeGet(quip.attrs, "ReQuipId"));
    outMsg.addResponseBody(opr, out);
  }

  /**
   * Returns the direct replies (child quips) for a given quip.
   * ActId defaults to the caller's own actId when not supplied.
   */
  private void getReplies(final String opr, final JsonMsg inMsg, final JsonMsg outMsg,
                          final DomId appDomId)
      throws DomatarException
  {
    final String quipId = inMsg.getAttrs().getAttr("QuipId");
    if (quipId == null || quipId.isEmpty())
    {
      outMsg.addError(opr, "Missing QuipId");
      return;
    }
    String actId = inMsg.getAttrs().getAttr("ActId");
    if (actId == null || actId.isEmpty())
      actId = appDomId.actId;

    final DomId     quipDomId = new DomId(DomId.subHstId("quippin", actId), "quippin", actId, quipId);
    final List<Lnk> childLnks = LnkDb.getLnks(quipDomId, "quippin", "quip",
                                               null, null, 100, false);

    final JsonList list = new JsonArrayList(childLnks.size());
    for (final Lnk lnk : childLnks)
    {
      final Obj child = ObjDb.getObj(lnk.lnkDomId);
      if (child == null)
        continue;

      final JsonMap entry = new JsonHashMap(5);
      entry.put("QuipId", lnk.lnkDomId.objId);
      entry.put("ActId",  lnk.lnkDomId.actId);
      entry.put("Text",   safeGet(child.attrs, "Text"));
      entry.put("Time",   safeGet(child.attrs, "Time"));
      entry.put("Likes",  safeGet(child.attrs, "SentimentNum"));
      list.add(entry);
    }

    final ObjAttrs out = new ObjAttrs();
    out.addAttr("Quips", list);
    outMsg.addResponseBody(opr, out);
  }

  /**
   * Returns recent quips (last 24 hours) from each actId in the
   * comma-separated ActIds argument, up to 50 per account.
   */
  private void getNews(final String opr, final JsonMsg inMsg, final JsonMsg outMsg)
      throws DomatarException
  {
    final String actIdsCsv = inMsg.getAttrs().getAttr("ActIds");
    if (actIdsCsv == null || actIdsCsv.trim().isEmpty())
    {
      outMsg.addError(opr, "Missing ActIds");
      return;
    }

    final long   cutoff = System.currentTimeMillis() - 24L * 3600 * 1000;
    final JsonList feed = new JsonArrayList();

    for (String actId : actIdsCsv.split(","))
    {
      actId = actId.trim();
      if (actId.isEmpty())
        continue;

      final String hstId = DomId.subHstId("quippin", actId);
      final List<Obj> quips = ObjDb.getObjPrefix(hstId, "quippin", actId, "quip", null, 50);

      for (final Obj q : quips)
      {
        final String timeStr = safeGet(q.attrs, "Time");
        if (timeStr != null)
        {
          try
          {
            if (Long.parseLong(timeStr) < cutoff)
              continue;
          }
          catch (NumberFormatException ignored)
          {
          }
        }

        final JsonMap entry = new JsonHashMap(5);
        entry.put("QuipId", q.domId.objId);
        entry.put("ActId",  actId);
        entry.put("Text",   safeGet(q.attrs, "Text"));
        entry.put("Time",   timeStr);
        entry.put("Likes",  safeGet(q.attrs, "SentimentNum"));
        feed.add(entry);
      }
    }

    final ObjAttrs out = new ObjAttrs();
    out.addAttr("Quips", feed);
    outMsg.addResponseBody(opr, out);
  }

  /**
   * Checks whether the caller is currently following the given ActId.
   */
  private void isFollowed(final String opr, final JsonMsg inMsg, final JsonMsg outMsg,
                          final DomId appDomId)
      throws DomatarException
  {
    final String followActId = inMsg.getAttrs().getAttr("ActId");
    if (followActId == null || followActId.isEmpty())
    {
      outMsg.addError(opr, "Missing ActId");
      return;
    }

    final String followObjId = IdGen.createId("follow", followActId);
    final DomId  followDomId = new DomId(appDomId.hstId, "quippin", appDomId.actId, followObjId);

    final ObjAttrs out = new ObjAttrs();
    out.addAttr("ActId",      followActId);
    out.addAttr("IsFollowed", ObjDb.getObj(followDomId) != null ? "True" : "False");
    outMsg.addResponseBody(opr, out);
  }

  /**
   * Returns the caller's list of banned accounts (act bans only).
   */
  private void getBans(final String opr, final JsonMsg outMsg, final DomId appDomId)
      throws DomatarException
  {
    final List<Obj> banObjs = ObjDb.getObjPrefix(
        appDomId.hstId, "quippin", appDomId.actId, "banAct", null, 500);

    final JsonList bans = new JsonArrayList(banObjs.size());
    for (final Obj ban : banObjs)
    {
      final JsonMap entry = new JsonHashMap(3);
      entry.put("ActId",   safeGet(ban.attrs, "ActId"));
      entry.put("UsrName", safeGet(ban.attrs, "UsrName"));
      entry.put("UsrId",   safeGet(ban.attrs, "UsrId"));
      bans.add(entry);
    }

    final ObjAttrs out = new ObjAttrs();
    out.addAttr("BannedActs", bans);
    outMsg.addResponseBody(opr, out);
  }

  /**
   * Returns the caller's recent activity log (up to 200 entries).
   */
  private void getLogs(final String opr, final JsonMsg outMsg, final DomId appDomId)
      throws DomatarException
  {
    final List<Obj> logObjs = ObjDb.getObjPrefix(
        appDomId.hstId, "quippin", appDomId.actId, "log", null, 200);

    final JsonList logs = new JsonArrayList(logObjs.size());
    for (final Obj log : logObjs)
    {
      final JsonMap entry = new JsonHashMap(5);
      entry.put("LogType",   safeGet(log.attrs, "LogType"));
      entry.put("Operation", safeGet(log.attrs, "Operation"));
      entry.put("Time",      safeGet(log.attrs, "Time"));
      entry.put("UsrName",   safeGet(log.attrs, "UsrName"));
      entry.put("UsrId",     safeGet(log.attrs, "UsrId"));
      logs.add(entry);
    }

    final ObjAttrs out = new ObjAttrs();
    out.addAttr("Logs", logs);
    outMsg.addResponseBody(opr, out);
  }

  /**
   * Returns the list of accounts the caller follows, with live actId / usrName.
   */
  private void getFollows(final String opr, final JsonMsg outMsg, final DomId appDomId,
                          final DomatarMsgClient msgClient)
      throws DomatarException
  {
    final List<Obj> rows = ObjDb.getObjPrefix(
        appDomId.hstId, "quippin", appDomId.actId, "follow", null, 5000);

    final JsonList list = new JsonArrayList(rows.size());
    for (final Obj row : rows)
    {
      final String actId = IdGen.getIdSuffix(row.domId.objId);
      final Act    act   = LoginRemote.getAct(actId, null, msgClient);

      final JsonMap entry = new JsonHashMap(3);
      entry.put("ActId",   actId);
      entry.put("UsrId",   act != null ? act.usrId   : actId);
      entry.put("UsrName", act != null ? act.usrName : actId);
      list.add(entry);
    }

    final ObjAttrs out = new ObjAttrs();
    out.addAttr("Follows", list);
    outMsg.addResponseBody(opr, out);
  }

  /**
   * Searches the shared Quippin directory for users matching the given query
   * string (case-insensitive substring match on ActId, UsrId, or UsrName).
   * Returns up to 20 matches.
   */
  private void findUsers(final String opr, final JsonMsg inMsg, final JsonMsg outMsg,
                         final DomatarMsgClient msgClient)
      throws DomatarException
  {
    final String query = inMsg.getAttrs().getAttr("Query");

    final DomId  dirId = new DomId(DIR_HST_ID, DIR_APP_ID, DIR_ACT_ID, DIR_OBJ_ID);
    final List<Lnk> lnks = LnkDb.getLnks(dirId, "quippin", "dirEntry",
                                          null, null, 500, false);

    final String lowerQuery = query != null ? query.toLowerCase() : "";

    final JsonList results = new JsonArrayList();
    for (final Lnk lnk : lnks)
    {
      final String actId   = lnk.val;
      final String usrName = lnk.lnkObjName;
      final String usrId   = lnk.lnkObjDesc;

      if (!lowerQuery.isEmpty())
      {
        final boolean match = (actId   != null && actId.toLowerCase().contains(lowerQuery))
                           || (usrName != null && usrName.toLowerCase().contains(lowerQuery))
                           || (usrId   != null && usrId.toLowerCase().contains(lowerQuery));
        if (!match)
          continue;
      }

      final JsonMap entry = new JsonHashMap(3);
      entry.put("ActId",   actId);
      entry.put("UsrId",   usrId);
      entry.put("UsrName", usrName);
      results.add(entry);

      if (results.size() >= 20)
        break;
    }

    final ObjAttrs out = new ObjAttrs();
    out.addAttr("Users", results);
    outMsg.addResponseBody(opr, out);
  }

  /**
   * Follows the account identified by ActId.
   * Looks up the current usrName and usrId via LoginRemote for accurate display.
   */
  private void follow(final String opr, final JsonMsg inMsg, final JsonMsg outMsg,
                      final DomId appDomId, final DomatarMsgClient msgClient)
      throws DomatarException
  {
    final String followActId = inMsg.getAttrs().getAttr("ActId");
    if (followActId == null || followActId.isEmpty())
    {
      outMsg.addError(opr, "Missing ActId");
      return;
    }

    final Act act = LoginRemote.getAct(followActId, null, msgClient);
    final String usrId   = act != null ? act.usrId   : followActId;
    final String usrName = act != null ? act.usrName : followActId;

    final String followObjId     = IdGen.createId("follow", followActId);
    final DomId  followDomId     = new DomId(appDomId.hstId, "quippin", appDomId.actId, followObjId);
    final DomId  followsId       = new DomId(appDomId.hstId, "quippin", appDomId.actId, "follows");
    final DomId  followeeQuipsId = new DomId(DomId.subHstId("quippin", followActId), "quippin", followActId, "quips");

    // Idempotent: skip if already following.
    if (ObjDb.getObj(followDomId) != null)
    {
      final ObjAttrs out = new ObjAttrs();
      out.addAttr("Follow",           "True");
      out.addAttr("AlreadyFollowing", "True");
      outMsg.addResponseBody(opr, out);
      return;
    }

    final ObjAttrs attrs = new ObjAttrs();
    attrs.addAttr("TimeFollowed", IdGen.getCurTimeBase64());
    ObjDb.addObj(new Obj(followDomId, "quippin", "follow",
                         followObjId, usrName + " " + usrId, attrs));

    if (LnkDb.getLnk(followsId, followDomId, "quippin", "follow") == null)
      LnkDb.addLnk(new Lnk(followsId, followDomId,
                            "quippin", "follow",
                            followObjId, usrName + " " + usrId,
                            "quippin", "follow",
                            usrId, System.currentTimeMillis()));

    if (LnkDb.getLnk(followDomId, followeeQuipsId, "quippin", "followee") == null)
      LnkDb.addLnk(new Lnk(followDomId, followeeQuipsId,
                            "quippin", "quips",
                            usrName, usrId,
                            "quippin", "followee",
                            usrId, 0));

    final ObjAttrs out = new ObjAttrs();
    out.addAttr("Follow",  "True");
    out.addAttr("ActId",   followActId);
    out.addAttr("UsrName", usrName);
    outMsg.addResponseBody(opr, out);
  }

  /**
   * Unfollows the account identified by ActId.
   */
  private void unfollow(final String opr, final JsonMsg inMsg, final JsonMsg outMsg,
                        final DomId appDomId)
      throws DomatarException
  {
    final String followActId = inMsg.getAttrs().getAttr("ActId");
    if (followActId == null || followActId.isEmpty())
    {
      outMsg.addError(opr, "Missing ActId");
      return;
    }

    final String followObjId     = IdGen.createId("follow", followActId);
    final DomId  followDomId     = new DomId(appDomId.hstId, "quippin", appDomId.actId, followObjId);
    final DomId  followsId       = new DomId(appDomId.hstId, "quippin", appDomId.actId, "follows");
    final DomId  followeeQuipsId = new DomId(DomId.subHstId("quippin", followActId), "quippin", followActId, "quips");

    ObjDb.deleteObj(followDomId);
    LnkDb.deleteLnks(followsId,    followDomId,     "quippin", "follow",   null, null);
    LnkDb.deleteLnks(followDomId,  followeeQuipsId, "quippin", "followee", null, null);

    final ObjAttrs out = new ObjAttrs();
    out.addAttr("Follow", "False");
    out.addAttr("ActId",  followActId);
    outMsg.addResponseBody(opr, out);
  }

  // ---------------------------------------------------------------------------

  private static String safeGet(final ObjAttrs attrs, final String key)
  {
    try
    {
      return attrs.getAttr(key);
    }
    catch (Exception e)
    {
      return null;
    }
  }
}
