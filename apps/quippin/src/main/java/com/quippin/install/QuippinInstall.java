/*
 * Copyright (c) 2024 Domatar
 */

package com.quippin.install;

import com.domatar.db.LnkDb;
import com.domatar.db.ObjDb;
import com.domatar.install.AppInstall;
import com.domatar.install.CatalogInstall;
import com.domatar.install.ClsInstall;
import com.domatar.install.SrvInstall;
import com.domatar.util.JsonMsg;
import com.domatar.util.Lnk;
import com.domatar.util.ObjAttrs;
import com.domatar.util.DomId;
import com.domatar.util.DomatarException;
import com.domatar.util.DomatarMsgClient;

/**
 * Install routine for the Quippin app (Spec-Navigator PART 10.1).
 *
 * The quippin~<actId> hst row is already created by ActDb.addAct
 * (the act row for the signing-up user). This routine adds the
 * per-user singleton container obj rows on quippin~<actId>, the
 * app-quippin obj on navigator~<actId>, and the skeleton lnks.
 *
 * Every step is idempotent: a second call is a no-op.
 */
public class QuippinInstall implements AppInstall
{
  @Override
  public void installProvider(final String prvId, final String domain) throws DomatarException
  {
    CatalogInstall.registerInCatalog(prvId, "quippin");
  }

  @Override
  public void installUser(final String actId,
                          final String usrId,
                          final String usrName,
                          final String prvId,
                          final String domain,
                          final DomatarMsgClient msgClient) throws DomatarException
  {
    install(actId, usrId, usrName, domain, prvId, msgClient);
  }

  private static void install(final String actId,
                              final String usrId,
                              final String usrName,
                              final String domain,
                              final String prvId,
                              final DomatarMsgClient msgClient) throws DomatarException
  {
    final String qHstId  = DomId.subHstId("quippin", actId);
    final String navHstId = DomId.subHstId("navigator", actId, prvId);

    final DomId rootId     = new DomId(navHstId, "navigator", actId, "root");
    final DomId appQId     = new DomId(qHstId,   "quippin",   actId, "app-quippin");
    final DomId followsId  = new DomId(qHstId,   "quippin",   actId, "follows");
    final DomId quipsId    = new DomId(qHstId,   "quippin",   actId, "quips");
    final DomId bansId     = new DomId(qHstId,   "quippin",   actId, "bans");
    final DomId logsId     = new DomId(qHstId,   "quippin",   actId, "logs");

    // 1. Container objs on quippin~<actId>
    ObjDb.addObjIfMissing(followsId, "quippin", "follows", "Follows",
                          "Users you follow");
    ObjDb.addObjIfMissing(quipsId,   "quippin", "quips",   "Quips",
                          "Your microblog posts");
    ObjDb.addObjIfMissing(bansId,    "quippin", "bans",    "Bans",
                          "Banned accounts and IPs");
    ObjDb.addObjIfMissing(logsId,    "quippin", "logs",    "Logs",
                          "Activity log");

    // 2. app-quippin obj on quippin~<actId>  (seqNum 1: appears first)
    ObjDb.addObjIfMissing(appQId, "quippin", "app", "Quippin",
                          "Microblog and follow feed");
    ObjDb.reclassObj(appQId, "quippin", "app");

    // 3. root -> app-quippin lnk
    addLnkIfMissing(rootId, appQId,
                    "quippin", "app",
                    "Quippin", "Microblog and follow feed",
                    "navigator", "app", null, 1);

    // 4. app-quippin -> container lnks
    addLnkIfMissing(appQId, followsId,
                    "quippin", "follows",
                    "Follows", "Users you follow",
                    "navigator", "container", null, 1);

    addLnkIfMissing(appQId, quipsId,
                    "quippin", "quips",
                    "Quips", "Your microblog posts",
                    "navigator", "container", null, 2);

    addLnkIfMissing(appQId, bansId,
                    "quippin", "bans",
                    "Bans", "Banned accounts and IPs",
                    "navigator", "container", null, 3);

    addLnkIfMissing(appQId, logsId,
                    "quippin", "logs",
                    "Logs", "Activity log",
                    "navigator", "container", null, 4);

    // 5. Services container + class container + service/slim-class descriptor objects.
    SrvInstall.ensureSrvsContainer(appQId,
        "Service descriptors for Quippin", "quippin", 5);
    ClsInstall.ensureClssContainer(appQId,
        "Class descriptors for Quippin", "quippin", 6);

    // quippin.app — structured LLM-native entry point with natural-language descriptions
    SrvInstall.upsertSrvObj(appQId, "quippin", "app",
        "Quippin app entry point (LLM-native operations)",
        "{" +
        "\"Description\":\"Quippin microblog entry point. Read posts, threads, follows, bans, logs, and news; search users; follow/unfollow; post.\"," +
        "\"Msgs\":[" +
        "{\"Name\":\"GetQuips\",\"Description\":\"Get the current user's own microblog posts.\",\"SideEffect\":\"Read\"}," +
        "{\"Name\":\"GetFeed\",\"Description\":\"Get the current user's home feed: posts from people they follow.\",\"SideEffect\":\"Read\"}," +
        "{\"Name\":\"GetQuip\",\"Description\":\"Get a specific post by its ID and the account ID of its author.\",\"SideEffect\":\"Read\"," +
        "\"Parms\":[{\"Name\":\"QuipId\",\"Type\":\"String\",\"Description\":\"The ID of the post.\"},{\"Name\":\"ActId\",\"Type\":\"String\",\"Description\":\"The account ID of the post's author.\"}]}," +
        "{\"Name\":\"GetReplies\",\"Description\":\"Get the replies to a specific post.\",\"SideEffect\":\"Read\"," +
        "\"Parms\":[{\"Name\":\"QuipId\",\"Type\":\"String\",\"Description\":\"The ID of the post.\"},{\"Name\":\"ActId\",\"Type\":\"String\",\"Description\":\"The account ID of the post's author.\"}]}," +
        "{\"Name\":\"GetNews\",\"Description\":\"Get recent posts from a set of specific accounts.\",\"SideEffect\":\"Read\"," +
        "\"Parms\":[{\"Name\":\"ActIds\",\"Type\":\"String\",\"Description\":\"Comma-separated list of account IDs to fetch posts from.\"}]}," +
        "{\"Name\":\"GetFollows\",\"Description\":\"Get the list of accounts that the current user is following.\",\"SideEffect\":\"Read\"}," +
        "{\"Name\":\"IsFollowed\",\"Description\":\"Check whether the current user is following a specific account.\",\"SideEffect\":\"Read\"," +
        "\"Parms\":[{\"Name\":\"ActId\",\"Type\":\"String\",\"Description\":\"The account ID to check.\"}]}," +
        "{\"Name\":\"FindUsers\",\"Description\":\"Search for users by name or username.\",\"SideEffect\":\"Read\"," +
        "\"Parms\":[{\"Name\":\"Query\",\"Type\":\"String\",\"Description\":\"The search query.\"}]}," +
        "{\"Name\":\"GetBans\",\"Description\":\"Get the list of accounts banned by the current user.\",\"SideEffect\":\"Read\"}," +
        "{\"Name\":\"GetLogs\",\"Description\":\"Get the moderation activity log for the current user's account.\",\"SideEffect\":\"Read\"}," +
        "{\"Name\":\"Follow\",\"Description\":\"Follow another user's account.\",\"SideEffect\":\"Write\"," +
        "\"Parms\":[{\"Name\":\"ActId\",\"Type\":\"String\",\"Description\":\"The account ID to follow.\"}]}," +
        "{\"Name\":\"Unfollow\",\"Description\":\"Unfollow an account the current user is currently following.\",\"SideEffect\":\"Write\"," +
        "\"Parms\":[{\"Name\":\"ActId\",\"Type\":\"String\",\"Description\":\"The account ID to unfollow.\"}]}," +
        "{\"Name\":\"PostQuip\",\"Description\":\"Post a new microblog entry as the current user.\",\"SideEffect\":\"Write\"," +
        "\"Parms\":[{\"Name\":\"Text\",\"Type\":\"String\",\"Description\":\"The text content of the post.\"}]}" +
        "]," +
        "\"Attrs\":\"DisplayName, IconPath, LaunchPath\"" +
        "}");

    ClsInstall.upsertClsImplementing(appQId, "quippin", "app",
        "Quippin app entry point (LLM-native operations)",
        "{\"ClsAppId\":\"quippin\",\"ClsId\":\"app\"," +
        "\"Implements\":[\"quippin.app\"]," +
        "\"Auth\":\"isVerified\"," +
        "\"MsgPolicy\":[" +
        "{\"Srv\":\"quippin.app\",\"Name\":\"GetQuips\",\"SideEffect\":\"Read\"}," +
        "{\"Srv\":\"quippin.app\",\"Name\":\"GetFeed\",\"SideEffect\":\"Read\"}," +
        "{\"Srv\":\"quippin.app\",\"Name\":\"GetQuip\",\"SideEffect\":\"Read\"}," +
        "{\"Srv\":\"quippin.app\",\"Name\":\"GetReplies\",\"SideEffect\":\"Read\"}," +
        "{\"Srv\":\"quippin.app\",\"Name\":\"GetNews\",\"SideEffect\":\"Read\"}," +
        "{\"Srv\":\"quippin.app\",\"Name\":\"GetFollows\",\"SideEffect\":\"Read\"}," +
        "{\"Srv\":\"quippin.app\",\"Name\":\"IsFollowed\",\"SideEffect\":\"Read\"}," +
        "{\"Srv\":\"quippin.app\",\"Name\":\"FindUsers\",\"SideEffect\":\"Read\"}," +
        "{\"Srv\":\"quippin.app\",\"Name\":\"GetBans\",\"SideEffect\":\"Read\"}," +
        "{\"Srv\":\"quippin.app\",\"Name\":\"GetLogs\",\"SideEffect\":\"Read\"}," +
        "{\"Srv\":\"quippin.app\",\"Name\":\"Follow\",\"SideEffect\":\"Write\"}," +
        "{\"Srv\":\"quippin.app\",\"Name\":\"Unfollow\",\"SideEffect\":\"Write\"}," +
        "{\"Srv\":\"quippin.app\",\"Name\":\"PostQuip\",\"SideEffect\":\"Write\"}" +
        "]}");

    // quippin.quips — container
    SrvInstall.addSrvObj(quipsId, "quippin", "quips",
        "Container of microblog posts for one account",
        "{\"SrvAppId\":\"quippin\",\"SrvId\":\"quips\",\"Attrs\":[]," +
        "\"Msgs\":[" +
        "{\"Name\":\"GetQuips\",\"Type\":{\"Quips\":[{\"DomId\":\"String\",\"Text\":\"String\",\"QuipId\":\"String\",\"SentimentNum\":\"String\",\"Liked\":\"String\"}]},\"Parms\":[]}," +
        "{\"Name\":\"AddQuip\",\"Parms\":[{\"Name\":\"Text\",\"Type\":\"String?\"},{\"Name\":\"ReQuipId\",\"Type\":\"String?\"},{\"Name\":\"ParentId\",\"Type\":\"String?\"}],\"Type\":{}}," +
        "{\"Name\":\"BanUser\",\"Parms\":[{\"Name\":\"ActId\",\"Type\":\"String\"}],\"Type\":{}}]}");

    ClsInstall.upsertClsImplementing(quipsId, "quippin", "quips",
        "Container of microblog posts for one account",
        "{\"ClsAppId\":\"quippin\",\"ClsId\":\"quips\"," +
        "\"Implements\":[\"quippin.quips\"]," +
        "\"MsgPolicy\":[" +
        "{\"Srv\":\"quippin.quips\",\"Name\":\"GetQuips\",\"SideEffect\":\"Read\"}" +
        "]}");

    // quippin.quip — individual post
    SrvInstall.addSrvObj(quipsId, "quippin", "quip",
        "A single microblog post",
        "{\"SrvAppId\":\"quippin\",\"SrvId\":\"quip\"," +
        "\"Attrs\":[{\"Name\":\"Text\",\"Type\":\"String\"},{\"Name\":\"SentimentNum\",\"Type\":\"String\"},{\"Name\":\"ReQuipId\",\"Type\":\"String?\"},{\"Name\":\"ParentId\",\"Type\":\"String?\"}]," +
        "\"Msgs\":[" +
        "{\"Name\":\"GetQuip\",\"Type\":{\"DomId\":\"String\",\"Text\":\"String\",\"QuipId\":\"String\",\"SentimentNum\":\"String\"},\"Parms\":[]}," +
        "{\"Name\":\"AddQuipChild\",\"Parms\":[{\"Name\":\"QuipId\",\"Type\":\"String\"},{\"Name\":\"ObjName\",\"Type\":\"String\"},{\"Name\":\"ObjDesc\",\"Type\":\"String\"}],\"Type\":{}}," +
        "{\"Name\":\"RemoveQuipChild\",\"Parms\":[{\"Name\":\"ChildQuipId\",\"Type\":\"String\"}],\"Type\":{}}," +
        "{\"Name\":\"DeleteQuip\",\"Type\":{},\"Parms\":[]}," +
        "{\"Name\":\"GetQuipChildren\",\"Type\":{\"Quips\":[{\"DomId\":\"String\",\"Text\":\"String\",\"QuipId\":\"String\"}]},\"Parms\":[]}," +
        "{\"Name\":\"AddSentiment\",\"Parms\":[{\"Name\":\"Sentiment\",\"Type\":\"String\"}],\"Type\":{}}," +
        "{\"Name\":\"BanChild\",\"Parms\":[{\"Name\":\"ChildQuipId\",\"Type\":\"String\"},{\"Name\":\"BanIp\",\"Type\":\"String?\"}],\"Type\":{}}]}");

    ClsInstall.upsertClsImplementing(quipsId, "quippin", "quip",
        "A single microblog post",
        "{\"ClsAppId\":\"quippin\",\"ClsId\":\"quip\"," +
        "\"Implements\":[\"quippin.quip\"]," +
        "\"MsgPolicy\":[" +
        "{\"Srv\":\"quippin.quip\",\"Name\":\"GetQuip\",\"SideEffect\":\"Read\"}," +
        "{\"Srv\":\"quippin.quip\",\"Name\":\"RemoveQuipChild\",\"SideEffect\":\"Destructive\"}," +
        "{\"Srv\":\"quippin.quip\",\"Name\":\"DeleteQuip\",\"SideEffect\":\"Destructive\"}," +
        "{\"Srv\":\"quippin.quip\",\"Name\":\"GetQuipChildren\",\"SideEffect\":\"Read\"}" +
        "]}");

    // quippin.follows — follows container
    SrvInstall.addSrvObj(quipsId, "quippin", "follows",
        "Container tracking accounts a user follows",
        "{\"SrvAppId\":\"quippin\",\"SrvId\":\"follows\",\"Attrs\":[]," +
        "\"Msgs\":[" +
        "{\"Name\":\"Follow\",\"Parms\":[{\"Name\":\"FollowActId\",\"Type\":\"String\"},{\"Name\":\"FollowUsrId\",\"Type\":\"String\"},{\"Name\":\"FollowUsrName\",\"Type\":\"String\"}],\"Type\":{\"Follow\":\"String\"}}," +
        "{\"Name\":\"Unfollow\",\"Parms\":[{\"Name\":\"FollowActId\",\"Type\":\"String\"}],\"Type\":{\"Follow\":\"String\"}}," +
        "{\"Name\":\"IsFollowed\",\"Parms\":[{\"Name\":\"FollowActId\",\"Type\":\"String\"}],\"Type\":{\"Follow\":\"String\"}}," +
        "{\"Name\":\"GetFollows\",\"Type\":{\"Follows\":[{\"ActId\":\"String\",\"UsrId\":\"String\",\"UsrName\":\"String\"}],\"PrvIds\":{}},\"Parms\":[]}]}");

    ClsInstall.upsertClsImplementing(quipsId, "quippin", "follows",
        "Container tracking accounts a user follows",
        "{\"ClsAppId\":\"quippin\",\"ClsId\":\"follows\"," +
        "\"Implements\":[\"quippin.follows\"]," +
        "\"MsgPolicy\":[" +
        "{\"Srv\":\"quippin.follows\",\"Name\":\"Unfollow\",\"SideEffect\":\"Destructive\"}," +
        "{\"Srv\":\"quippin.follows\",\"Name\":\"IsFollowed\",\"SideEffect\":\"Read\"}," +
        "{\"Srv\":\"quippin.follows\",\"Name\":\"GetFollows\",\"SideEffect\":\"Read\"}" +
        "]}");

    // quippin.news — cross-account feed
    SrvInstall.addSrvObj(quipsId, "quippin", "news",
        "Cross-account news feed aggregating quips from followed accounts",
        "{\"SrvAppId\":\"quippin\",\"SrvId\":\"news\",\"Attrs\":[]," +
        "\"Msgs\":[{\"Name\":\"GetNews\",\"Parms\":[{\"Name\":\"Acts\",\"Type\":[\"String\"]}]," +
        "\"Type\":{\"Quips\":[{\"DomId\":\"String\",\"Text\":\"String\",\"QuipId\":\"String\",\"SentimentNum\":\"String\"}]}}]}");

    ClsInstall.upsertClsImplementing(quipsId, "quippin", "news",
        "Cross-account news feed aggregating quips from followed accounts",
        "{\"ClsAppId\":\"quippin\",\"ClsId\":\"news\"," +
        "\"Implements\":[\"quippin.news\"]," +
        "\"MsgPolicy\":[" +
        "{\"Srv\":\"quippin.news\",\"Name\":\"GetNews\",\"SideEffect\":\"Read\"}" +
        "]}");

    // quippin.sentiments — likes index
    SrvInstall.addSrvObj(quipsId, "quippin", "sentiments",
        "Per-account index of liked quips",
        "{\"SrvAppId\":\"quippin\",\"SrvId\":\"sentiments\",\"Attrs\":[]," +
        "\"Msgs\":[{\"Name\":\"LnkSentiment\",\"Parms\":[{\"Name\":\"SentimentId\",\"Type\":\"String\"},{\"Name\":\"Sentiment\",\"Type\":\"String\"},{\"Name\":\"Time\",\"Type\":\"String\"},{\"Name\":\"ObjName\",\"Type\":\"String\"},{\"Name\":\"ObjDesc\",\"Type\":\"String\"}],\"Type\":{}}]}");

    ClsInstall.upsertClsImplementing(quipsId, "quippin", "sentiments",
        "Per-account index of liked quips",
        "{\"ClsAppId\":\"quippin\",\"ClsId\":\"sentiments\"," +
        "\"Implements\":[\"quippin.sentiments\"]}");

    // quippin.sentiment — single like record
    SrvInstall.addSrvObj(quipsId, "quippin", "sentiment",
        "A single sentiment record (Like or None) on a quip",
        "{\"SrvAppId\":\"quippin\",\"SrvId\":\"sentiment\"," +
        "\"Attrs\":[{\"Name\":\"Sentiment\",\"Type\":\"String\"},{\"Name\":\"Time\",\"Type\":\"String\"}]," +
        "\"Msgs\":[{\"Name\":\"GetObj\",\"Type\":{\"ObjName\":\"String\",\"ObjDesc\":\"String\"},\"Parms\":[]}]}");

    ClsInstall.upsertClsImplementing(quipsId, "quippin", "sentiment",
        "A single sentiment record (Like or None) on a quip",
        "{\"ClsAppId\":\"quippin\",\"ClsId\":\"sentiment\"," +
        "\"Implements\":[\"quippin.sentiment\"]," +
        "\"MsgPolicy\":[" +
        "{\"Srv\":\"quippin.sentiment\",\"Name\":\"GetObj\",\"SideEffect\":\"Read\"}" +
        "]}");

    // quippin.bans — bans container
    SrvInstall.addSrvObj(quipsId, "quippin", "bans",
        "Container of banned accounts and IPs for one user",
        "{\"SrvAppId\":\"quippin\",\"SrvId\":\"bans\",\"Attrs\":[]," +
        "\"Msgs\":[" +
        "{\"Name\":\"Ban\",\"Parms\":[{\"Name\":\"ActId\",\"Type\":\"String?\"},{\"Name\":\"Ip\",\"Type\":\"String?\"}],\"Type\":{}}," +
        "{\"Name\":\"Unban\",\"Parms\":[{\"Name\":\"ActId\",\"Type\":\"String?\"},{\"Name\":\"Ip\",\"Type\":\"String?\"}],\"Type\":{}}," +
        "{\"Name\":\"GetActBans\",\"Type\":{\"BannedActs\":[{\"UsrName\":\"String\",\"UsrId\":\"String\",\"ActId\":\"String\",\"Ips\":[\"String\"]}]},\"Parms\":[]}," +
        "{\"Name\":\"GetIpBans\",\"Type\":{\"BannedIps\":[{\"Ip\":\"String\",\"Usrs\":[{\"UsrName\":\"String\",\"UsrId\":\"String\"}]}]},\"Parms\":[]}]}");

    ClsInstall.upsertClsImplementing(quipsId, "quippin", "bans",
        "Container of banned accounts and IPs for one user",
        "{\"ClsAppId\":\"quippin\",\"ClsId\":\"bans\"," +
        "\"Implements\":[\"quippin.bans\"]," +
        "\"MsgPolicy\":[" +
        "{\"Srv\":\"quippin.bans\",\"Name\":\"GetActBans\",\"SideEffect\":\"Read\"}," +
        "{\"Srv\":\"quippin.bans\",\"Name\":\"GetIpBans\",\"SideEffect\":\"Read\"}" +
        "]}");

    // quippin.logs — activity log
    SrvInstall.addSrvObj(quipsId, "quippin", "logs",
        "Activity log container for one account",
        "{\"SrvAppId\":\"quippin\",\"SrvId\":\"logs\",\"Attrs\":[]," +
        "\"Msgs\":[" +
        "{\"Name\":\"Log\",\"Parms\":[{\"Name\":\"LogType\",\"Type\":\"String\"},{\"Name\":\"Operation\",\"Type\":\"String\"}],\"Type\":{}}," +
        "{\"Name\":\"GetLogs\",\"Type\":{\"Logs\":[{\"LogType\":\"String\",\"Operation\":\"String\",\"Time\":\"String\",\"UsrName\":\"String\",\"UsrId\":\"String\",\"UsrIp\":\"String\"}]},\"Parms\":[]}]}");

    ClsInstall.upsertClsImplementing(quipsId, "quippin", "logs",
        "Activity log container for one account",
        "{\"ClsAppId\":\"quippin\",\"ClsId\":\"logs\"," +
        "\"Implements\":[\"quippin.logs\"]," +
        "\"MsgPolicy\":[" +
        "{\"Srv\":\"quippin.logs\",\"Name\":\"GetLogs\",\"SideEffect\":\"Read\"}" +
        "]}");

    // quippin.directory — shared user directory
    SrvInstall.addSrvObj(quipsId, "quippin", "directory",
        "Shared Quippin user directory for account discovery",
        "{\"SrvAppId\":\"quippin\",\"SrvId\":\"directory\",\"Attrs\":[]," +
        "\"Msgs\":[" +
        "{\"Name\":\"Register\",\"Parms\":[{\"Name\":\"ActId\",\"Type\":\"String\"},{\"Name\":\"UsrId\",\"Type\":\"String?\"},{\"Name\":\"UsrName\",\"Type\":\"String?\"}],\"Type\":{\"Registered\":\"String\"}}," +
        "{\"Name\":\"GetDirectory\",\"Type\":{\"Users\":[{\"ActId\":\"String\",\"UsrId\":\"String\",\"UsrName\":\"String\"}]},\"Parms\":[]}]}");

    ClsInstall.upsertClsImplementing(quipsId, "quippin", "directory",
        "Shared Quippin user directory for account discovery",
        "{\"ClsAppId\":\"quippin\",\"ClsId\":\"directory\"," +
        "\"Implements\":[\"quippin.directory\"]," +
        "\"MsgPolicy\":[" +
        "{\"Srv\":\"quippin.directory\",\"Name\":\"GetDirectory\",\"SideEffect\":\"Read\"}" +
        "]}");

    // 6. Register this user in the shared Quippin directory.
    // The directory lives on the quippin central host; msgClient routes
    // the message there (sendLocal on prv1, cross-prv on prv2).
    final DomId dirDomId = new DomId("quippin", "quippin", "quippin@quippin", "directory");
    final JsonMsg regMsg = new JsonMsg();
    final ObjAttrs regAttrs = new ObjAttrs();
    regAttrs.addAttr("ActId",   actId);
    regAttrs.addAttr("UsrId",   usrId);
    regAttrs.addAttr("UsrName", usrName);
    regMsg.addRequestBody("Register", regAttrs);
    regMsg.addClsId("quippin", "directory");
    msgClient.send(dirDomId, regMsg);
  }

  private static void addLnkIfMissing(final DomId domId,
                                      final DomId lnkDomId,
                                      final String lnkClsAppId,
                                      final String lnkClsId,
                                      final String lnkObjName,
                                      final String lnkObjDesc,
                                      final String tagAppId,
                                      final String tag,
                                      final String val,
                                      final long seqNum) throws DomatarException
  {
    if (LnkDb.getLnk(domId, lnkDomId, tagAppId, tag) == null)
      LnkDb.addLnk(new Lnk(domId, lnkDomId,
                            lnkClsAppId, lnkClsId,
                            lnkObjName, lnkObjDesc,
                            tagAppId, tag,
                            val, seqNum));
  }
}
