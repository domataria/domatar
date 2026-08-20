# QUIPPIN APP — SPECIFICATION

Quippin is a distributed microblogging and social-feed application built on the
Domatar platform. Each user publishes short posts ("quips") to their own
account; users follow one another, and a news feed aggregates recent quips from
the accounts they follow. Every quip, follow, ban, and log entry lives as a
Domatar object under its owner's account — nothing is stored in a central
timeline. A single shared directory on the Quippin central host lets users
discover one another.

Quippin was the first application hand-crafted on Domatar, alongside the
platform itself. How to write a Domatar app is specified in
[Writing Apps](Writing-Apps.md), which extracts the pattern from Quippin.
This document specifies Quippin's product behaviour.

Quippin's central host runs on prv1 (tomcat1 / db1).

## PART 1 — OVERVIEW

Goals
-----
  * A user can post a short text quip. Quips can also be replies (children of
    another quip), requips (reposts), or quote-quips (repost with commentary).
  * A user can follow and unfollow other users, across providers.
  * A home feed shows recent quips from the accounts a user follows.
  * A user can "like" (add sentiment to) any quip; likes are counted per quip
    and remembered per liking user.
  * A user can moderate their own space: ban an account or an IP so its quips
    no longer appear to them.
  * Every action is recorded in a short-lived per-user activity log.
  * A shared, global directory lets any user discover and search other users.
  * The whole app is reachable through a single LLM-native facade
    (QuippinAppImpl) that exposes coarse, natural-language operations.

Non-goals (deferred / see PART 12)
----------------------------------
  * Direct messages / private conversations.
  * Media attachments (images, video) on quips — text only in v1.
  * Global search of quip content (only user search exists).
  * Notifications when someone follows, replies, or likes.
  * Persistent logs — the activity log auto-expires after one hour.
  * Rich profiles (bio, avatar, header) beyond usrName / handle.

Design principles (inherited by every Domatar app)
---------------------------------------------------
  P1. Data is owned, not centralized. Every quip/follow/ban/log lives on the
      owner's sub-host (quippin-<actId>). The feed is assembled at read time by
      reaching into each followed account's own container.
  P2. Containers and leaves. Each app node owns a small set of singleton
      "container" objects (quips, follows, bans, logs); each container holds
      many "leaf" objects (quip, follow, banAct, log) reachable via lnk rows.
  P3. Everything is a Domatar object addressable by DomId and browsable in the
      Navigator; the same objects power both the web UI and the LLM facade.
  P4. Cross-prv is transparent. Following a user on another provider works the
      same as following a local user; msgClient routes the read.
  P5. Idempotent installs. Installing Quippin for a user twice is a no-op.

## PART 2 — PROVIDER AND HOSTING

2.1  Central host
-----------------
  hstId  : quippin
  prvId  : prv1
  domain : domatar.quippin.com   (tomcat1:8080 on domatar_net)

  The central host owns the shared user directory (PART 9) and, per provider,
  the news aggregation object (PART 6.5). In the local simulation "quippin"
  always resolves to prv1, so the directory Register call from install is a
  local dispatch.

2.2  Per-user sub-hosts
-----------------------
  Each user who installs Quippin gets a personal sub-host:

    hstId : quippin-<actId>     (e.g. quippin-dave@quippin)
    prvId : the prv the user is on when they install
    domain: same as that prv's domain

  The sub-host holds all of that user's Quippin objects: the app node, the
  four containers (follows, quips, bans, logs), the sentiments index, and
  every quip / follow / ban / log leaf. The quippin-<actId> hst row is created
  by ActDb.addAct (the signing-up user's act row); QuippinInstall then adds the
  object rows on it (PART 8).

2.3  System account
-------------------
  Account owning the shared directory object:

    actId   : quippin@quippin
    usrId   : quippin@quippin
    usrName : Quippin
    password: 

2.4  App identity / manifest
----------------------------
  AppId        : quippin
  AppName      : Quippin
  AppDesc      : Microblogging and social feed
  Version      : 1.0
  DependsOn    : navigator, login, desktop
  InstallClass : com.quippin.install.QuippinInstall
  AssetDir     : quippin/assets

  A per-provider aggregation host (quippin-<prvId>) is reserved for a future
  news-feed optimisation; none exists today (app.config.txt: ProviderHosts=).

## PART 3 — DATA MODEL: OBJECT CLASSES

All classes use ClsAppId = "quippin". ObjIds generated with IdGen:
  - IdGen.getId("quip")           → "quip-<base64ts>"  (chronological, unique)
  - IdGen.createId("follow", x)   → "follow-<base64(x)>"
  - IdGen.createId("banAct", x,'_') / ("banIp", x,'_')
  - IdGen.createId("sentiment", <quipSuffix>-<actId>)
  - IdGen.createId("log", <ts>-<rand>)

3.1  (quippin, app)  — the app node
-----------------------------------
  One per user. The Navigator entry-point and the LLM-native facade target.
  ObjId : app-quippin
  HstId : quippin-<actId>
  Handled by QuippinAppImpl (PART 5.1).

3.2  (quippin, quips)  — quips container
----------------------------------------
  One per user. Container of the user's own quips.
  ObjId : quips        HstId : quippin-<actId>
  Handled by QuipsImpl.

3.3  (quippin, quip)  — a single post
-------------------------------------
  One per post/reply/requip. Lives on the author's sub-host.
  ObjId : quip-<base64ts>       HstId : quippin-<actId>
  ObjName/ObjDesc: first 40 / 100 chars of Text (for Navigator labels)
  Attrs :
    Text         : string (may be null for a bare requip)
    SentimentNum : string integer — running like count
    ReQuipId     : string (optional) — DomId of the reposted original
    ParentId     : string (optional) — DomId of the parent quip (this is a reply)
    Time         : string (millis; set by the LLM facade's PostQuip)
  Handled by QuipImpl.

  A quip's shape is derived at read time by Quip.getAttrs into one of three
  presentation Types:
    Quip      — plain post (no ReQuipId).
    ReQuip    — bare repost (ReQuipId set, Text null): carries Orig* fields
                (OrigName, OrigHandle, OrigHost, OrigQuipId, OrigDomId) plus the
                original Text; sorts at repost time under the reposter's identity.
    QuoteQuip — repost with commentary (ReQuipId set, Text present): carries
                Quote* fields describing the quoted original.

3.4  (quippin, follows)  — follows container
--------------------------------------------
  One per user. Container of accounts the user follows.
  ObjId : follows      HstId : quippin-<actId>
  Handled by FollowsImpl.

3.5  (quippin, follow)  — a single follow edge
----------------------------------------------
  One per followed account.
  ObjId : follow-<base64(followActId)>     HstId : quippin-<actId>
  ObjName : "<usrName> <usrId>"
  Attrs :
    TimeFollowed : string (base64 timestamp)
  Uses the default ObjImpl (no dedicated handler needed).

3.6  (quippin, sentiments)  — likes index
-----------------------------------------
  One per user. Per-account index of the quips this user has liked.
  ObjId : sentiments   HstId : quippin-<actId>
  Handled by SentimentsImpl.

3.7  (quippin, sentiment)  — a single like record
-------------------------------------------------
  One per (liker, quip). Lives on the liked quip's host, owned by the liker.
  ObjId : sentiment-<quipSuffix>-<likerActId>
  Attrs :
    Sentiment : "Like" | "None"
    Time      : string (base64 timestamp)
  Handled by SentimentImpl.

3.8  (quippin, bans)  — bans container
--------------------------------------
  One per user. Container of banned accounts and IPs.
  ObjId : bans         HstId : quippin-<actId>
  Handled by BansImpl.

3.9  (quippin, banAct) / (quippin, banIp)  — a single ban record
----------------------------------------------------------------
  banAct ObjId : banAct_<actId>   Attrs: Ips  : [string]  (IPs tied to this act)
  banIp  ObjId : banIp_<ip>       Attrs: Ip   : string, Acts : [string]
  HstId : quippin-<actId>
  Handled implicitly by BansImpl (via the bans container).

3.10 (quippin, logs)  — activity log container
----------------------------------------------
  One per user. Short-lived (entries auto-deleted after 1 hour).
  ObjId : logs         HstId : quippin-<actId>
  Handled by LogsImpl.

3.11 (quippin, log)  — a single log entry
-----------------------------------------
  ObjId : log-<base64ts>-<rand>    HstId : quippin-<actId>
  Attrs : LogType, Operation, Time, UsrName, UsrId, UsrIp, DomIdPath, HttpHeaders
  Uses the default ObjImpl.

3.12 (quippin, news)  — cross-account feed aggregator
-----------------------------------------------------
  Not per-user. A stateless aggregator addressed per provider as
  (prvId, quippin, quippin@quippin, news). Reads recent quips directly from
  the requested accounts' quips ranges. Handled by NewsImpl (PART 6.5).

3.13 (quippin, directory) / (quippin, dirEntry)  — shared user directory
------------------------------------------------------------------------
  Global, shared, owned by quippin@quippin on the central host. Fully
  specified in [Quippin](Quippin.md); summarised in PART 9.

## PART 4 — LINK TYPES

All links use ClsAppId/TagAppId "quippin" unless noted. Navigator tree edges
use TagAppId "navigator".

Navigator tree (built by QuippinInstall):
  root (navigator-<actId>) → app-quippin      Tag=(navigator, app)        seq=1
  app-quippin → follows                        Tag=(navigator, container)  seq=1
  app-quippin → quips                          Tag=(navigator, container)  seq=2
  app-quippin → bans                           Tag=(navigator, container)  seq=3
  app-quippin → logs                           Tag=(navigator, container)  seq=4
  app-quippin → srvs (service descriptors)     (SrvInstall)                seq=5

Content edges:
  quips   → quip        Tag=(quippin, quip)     seq=<millis at post>
  quip    → quip        Tag=(quippin, child)    Val=<posterIp>   (reply edge)
  quip    → quip        Tag=(quippin, requip)   seq=1            (repost → original)
  follows → follow      Tag=(quippin, follow)   Val=<usrId>  seq=<millis>
  follow  → quips        Tag=(quippin, followee) Val=<usrId>  seq=0
           (points at "quippin-<followeeActId>/…/quips" for cross-app / cross-prv
            drill-through from the follow row to the followee's posts)
  bans    → banAct       Tag=(quippin, ban)      Val=<bannedActId> seq=<millis>
  bans    → banIp        Tag=(quippin, ban)      Val=<bannedIp>    seq=<millis>
  sentiments → sentiment Tag=(quippin, sentiment) Val=<sentiment>  seq=<time>
  logs    → log          Tag=(quippin, log)      seq=<millis>

Directory edges (on the central host, see [Quippin](Quippin.md)):
  directory → dirEntry   Tag=(quippin, dirEntry) Val=<actId>
  dirEntry  → app-quippin Tag=(quippin, appQuippin) seq=1

## PART 5 — THE LLM-NATIVE FACADE  (QuippinAppImpl)

Class: com.quippin.objimpl.QuippinAppImpl   Handles: (quippin, app)
Auth : Auth.isVerified for every operation.

This is the single, coarse-grained entry point an LLM (or any programmatic
client) uses. It hides the container/leaf topology behind readable operations
whose descriptions and parameters are published as a service descriptor at
install time (PART 8, quippin.app srv object). Each operation targets the
caller's app-quippin object; the facade resolves the underlying containers,
sub-hosts, and cross-account reads internally.

Operations (SideEffect):

  GetQuips ()                                                          [Read]
    The caller's own posts (up to 50). Returns Quips:[{QuipId,Text,Time,Likes}].

  GetFeed ()                                                           [Read]
    Home feed: reaches into each followed account's quips container (up to 10
    accounts × 10 quips). Returns Quips:[{QuipId,Text,Time,Author,Likes}].

  GetQuip (QuipId, ActId?)                                             [Read]
    One post by id; ActId defaults to caller. Returns Text, Time, Likes,
    ParentId, ReQuipId.

  GetReplies (QuipId, ActId?)                                          [Read]
    Direct replies (child quips) of a quip.

  GetNews (ActIds)                                                     [Read]
    Recent (last 24 h) quips from a comma-separated set of account ids,
    up to 50 per account.

  GetFollows ()                                                        [Read]
    Accounts the caller follows, with live ActId / UsrId / UsrName.

  IsFollowed (ActId)                                                   [Read]
    Whether the caller currently follows ActId → IsFollowed: True|False.

  FindUsers (Query)                                                    [Read]
    Case-insensitive substring search over the shared directory
    (ActId/UsrId/UsrName), up to 20 matches.

  GetBans ()                                                           [Read]
    The caller's banned accounts.

  GetLogs ()                                                           [Read]
    The caller's recent activity log (up to 200).

  Follow (ActId)                                                       [Write]
    Idempotent. Looks up usrName/usrId via LoginRemote, writes the follow leaf,
    the follows→follow lnk, and the follow→followee-quips lnk.

  Unfollow (ActId)                                                     [Write]
    Removes the follow leaf and both lnks.

  PostQuip (Text)                                                      [Write]
    Creates a quip on the caller's quips container and the quips→quip lnk.

The facade is deliberately parallel to, but simpler than, the granular
container handlers (PART 6) that the browser UI drives. New apps should provide
an equivalent (app) facade so the platform's AI Agent can operate them with
minimal, self-describing tools (see [LLM Messages](../platform/LLM-Messages.md), QUIPPIN).

## PART 6 — GRANULAR OBJECT HANDLERS

These are the fine-grained handlers that the web UI servlets dispatch to. They
operate directly on containers and leaves and carry the moderation, sentiment,
reply, and requip logic.

6.1  QuipsImpl  — (quippin, quips)
----------------------------------
  GetQuips
    Reads up to 50 quip objs under the target account's quips container, runs
    each through Quip.getAttrs (which filters banned authors and computes the
    ReQuip/QuoteQuip shape), stamps a per-caller "Liked" flag, returns Quips[].
    Logs a "Quips" activity entry.
  AddQuip (Text?, ReQuipId?, ParentId?)
    Creates the quip obj (Text, SentimentNum=0, plus ReQuipId/ParentId when
    present). For a bare requip (no Text) it fetches the original's text to
    build a meaningful "Requip: …" ObjName. Adds the quips→quip lnk; for a
    requip adds a quip→original (requip) lnk; for a reply sends AddQuipChild to
    the parent quip.
  BanUser (ActId)
    Forwards a Ban to the caller's bans container.

6.2  QuipImpl  — (quippin, quip)
--------------------------------
  GetQuip                → one quip via Quip.getAttrs. Logs "Quip".
  AddQuipChild (QuipId, ObjName, ObjDesc)
                         → adds a parent→child (child) lnk stamped with the
                           poster's IP in Val. Called server-to-server by AddQuip.
  RemoveQuipChild (ChildQuipId)   [Destructive, owner-only]
                         → deletes the parent→child lnk and the quips→child lnk.
  DeleteQuip                       [Destructive, owner-only]
                         → deletes the quip obj, the quips→quip lnk, and the
                           parent→child lnk when it was a reply.
  GetQuipChildren        → replies of this quip (via child lnks). Logs "Quip".
  AddSentiment (Sentiment)
                         → "Like" writes a sentiment obj owned by the caller and
                           increments SentimentNum; "None" deletes it and
                           decrements. Sends LnkSentiment to the caller's
                           sentiments index. Logs "Quip".
  BanChild (ChildQuipId, BanIp?)
                         → bans the child's author (and optionally the IP stored
                           on the child lnk) via the caller's bans container.

6.3  FollowsImpl  — (quippin, follows)
--------------------------------------
  Follow (FollowActId, FollowUsrId, FollowUsrName)
                         → writes the follow leaf + follows→follow lnk +
                           follow→followee-quips lnk. → Follow: "True".
  Unfollow (FollowActId)  [Destructive]  → deletes the leaf and both lnks.
  IsFollowed (FollowActId)               → Follow: "True"|"False".
  GetFollows              → the follows list (live usrName/usrId via LoginRemote)
                           plus a PrvIds map {prvId → [subHst,…]} used by the
                           News feed to fan out per provider.

6.4  SentimentsImpl / SentimentImpl
-----------------------------------
  SentimentsImpl.LnkSentiment (SentimentId, Sentiment, Time, ObjName, ObjDesc)
    Maintains the sentiments→sentiment index lnk: removes any prior lnk, and
    (re)adds it only when Sentiment != "None".
  SentimentImpl.GetSentiment → Name, Handle, QuipId, DomId, Sentiment.

6.5  NewsImpl  — (quippin, news)   [public-by-design read]
----------------------------------------------------------
  GetNews (Acts:[actId,…])
    For each account, queries its quips container for the objId range spanning
    the last 24 h (IdGen time-range on quip ids), collects up to 50 each, sorts
    all results descending by objId (newest first), and renders each via
    Quip.getAttrs. Deliberately does NOT require Auth on the target prv: a
    logged-in user on prv1 must be able to read a followee's public quips on
    prv2 without owning an account there. Only public quip data is returned.

6.6  BansImpl  — (quippin, bans)
--------------------------------
  Ban (ActId?, Ip?)      → creates/updates banAct and/or banIp records and the
                           bans→ban lnks; merges Ips/Acts lists on repeat bans.
  Unban (ActId?, Ip?)    → deletes the matching ban record(s) and lnk(s).
  GetActBans             → BannedActs:[{UsrName,UsrId,ActId,Ips[]}].
  GetIpBans              → BannedIps:[{Ip,Usrs:[{UsrName,UsrId}]}].

  Enforcement lives in Quip.getAttrs.isBanned: when rendering a quip, if the
  viewing caller has a banAct record for the author (or a banIp for the
  author's IP) under the viewer's own namespace, the quip is dropped (returns
  null). Bans are thus per-viewer, not global.

6.7  LogsImpl  — (quippin, logs)
--------------------------------
  Log (LogType, Operation)
    Writes a log obj capturing the caller's context (usrName, usrId, usrIp, the
    DomId path, and HTTP headers) plus the logs→log lnk. Every read/write op in
    the granular handlers calls Log.add(...) which dispatches here.
  GetLogs                → up to 1000 recent entries.
  Housekeeping: on each Log call (throttled to once/minute) entries older than
    one hour are purged from both the obj and lnk tables.

## PART 7 — WEB UI SERVLETS

All servlets extend DomatarServlet (which enforces verified login and provides
srcDomId / srcAct / context / msgClient), build a JsonMsg, and dispatch to the
appropriate object on quippin-<actId>. Registered in app.manifest.

  QuipsWui   (/QuipsWui/*)   → quips container.
    GetQuips (UsrId? — defaults to caller; else resolves via LoginRemote),
    AddQuip (Text, ReQuipId?, ParentId?), BanUser (QuipId → derives ActId).

  QuipWui    (/QuipWui/*)    → a single quip (dst = the quip's DomId).
    GetQuip (UsrId, Quip → objId via IdGen), GetQuipChildren (QuipId),
    RemoveQuipChild (ParentQuipId, ChildQuipId), AddSentiment (QuipId, Sentiment),
    DeleteQuip (QuipId), BanChild (ParentQuipId, ChildQuipId, BanIp?).

  FollowsWui (/FollowsWui/*) → follows container.
    Follow / Unfollow / IsFollowed (UsrId; Unfollow may pass ActId directly to
    skip the remote lookup), GetFollows.

  NewsWui    (/NewsWui/*)    → the per-provider news aggregator.
    GetNews (PrvId, Acts) → dst = (PrvId, quippin, quippin@quippin, news).
    The client calls this once per provider using the PrvIds map from GetFollows.

  BansWui    (/BansWui/*)    → bans container.
    Ban / Unban (ActId?, Ip?), GetActBans, GetIpBans.

  LogsWui    (/LogsWui)      → logs container. GetLogs.

  SentimentsWui              → quips container (handle-based routing variant).
    GetQuips / AddQuip. (Legacy handle-based sibling of QuipsWui.)

  QuippinDirectoryWui (/QuippinDirectoryWui) → shared directory.
    GetDirectory. (See [Quippin](Quippin.md).)

## PART 8 — INSTALL ROUTINE  (QuippinInstall.java)

Package: com.quippin.install    Implements: AppInstall

installProvider(prvId, domain):
  CatalogInstall.registerInCatalog(prvId, "quippin").

installUser(actId, usrId, usrName, prvId, domain, msgClient) — idempotent:

  1. Create the four container objs on quippin-<actId> (addObjIfMissing):
       follows "Follows"  / quips "Quips" / bans "Bans" / logs "Logs".
  2. Create app-quippin on quippin-<actId>, class (quippin, app).
  3. Add root → app-quippin lnk (navigator/app, seq 1) on navigator-<actId>.
  4. Add app-quippin → {follows, quips, bans, logs} lnks
       (navigator/container, seq 1..4).
  5. Service descriptors (SrvInstall.ensureSrvsContainer + upsertSrvObj /
     ClsInstall.upsertClsImplementing) for every class:
       quippin.app        — the LLM-native facade descriptor: Description + Msgs
                            (Name/Description/SideEffect/Parms) for all PART 5
                            operations. This is what the AI Agent reads to learn
                            how to drive Quippin.
       quippin.quips, .quip, .follows, .news, .sentiments, .sentiment,
       .bans, .logs, .directory — SrvObj (Attrs + Msgs type signatures) plus a
       ClsImplementing descriptor carrying the MsgPolicy (per-msg SideEffect:
       Read / Write / Destructive).
  6. Register the user in the shared directory: send Register {ActId,UsrId,
     UsrName} to (quippin, quippin, quippin@quippin, directory) via msgClient.

QuippinInstall is invoked from ActManagerImpl.addAct for every new account
(the standard per-app install hook). Sim fixtures may also be present in
the database dump.

## PART 9 — SHARED DIRECTORY  (summary)

The directory is a global, shared registry of every Quippin user, owned by
quippin@quippin on the central "quippin" host. Handled by QuippinDirectoryImpl
((quippin, directory)) with:

  Register (ActId, UsrId?, UsrName?)  — idempotently creates the directory
    container (if missing), the dir-<actId> entry obj, the directory→dirEntry
    lnk, and the dirEntry→app-quippin drill-through lnk. Called by
    QuippinInstall at sign-up; never exposed to the browser.
  GetDirectory ()  — returns Users:[{ActId,UsrId,UsrName}] for the lookup panel.

FindUsers on the LLM facade (PART 5) reads the same directory. Full details,
including the directory seed and the follows.html lookup modal, are
in [Quippin](Quippin.md).

## PART 10 — FRONTEND PAGES

Served from the quippin asset directory (quippin/assets), styled with
domatar.css and driven by jQuery + quip.js. Pages POST to the Wui servlets and
render the JSON responses.

  quips.html      — a user's timeline. ?user=<handle> shows another user's
                    quips; no param shows the caller's own. Calls
                    Wui/QuipsWui?Action=GetQuips.
  quip.html       — a single quip with its reply thread (GetQuip +
                    GetQuipChildren), like button, reply, requip, delete.
  newQuip.html    — compose a new quip / reply / requip (AddQuip).
  news.html       — the home feed. Loads GetFollows, then fans out GetNews per
                    provider using the returned PrvIds map, merges and sorts.
  follows.html    — manage follows; includes the directory "Browse users"
                    lookup modal ([Quippin](Quippin.md) PART 5).
  bans.html       — manage banned accounts and IPs (GetActBans / GetIpBans /
                    Ban / Unban).
  logs.html       — view the recent activity log (GetLogs).
  sideMenu.html / networkAccordion.html — shared navigation fragments.

Icons live under quippin/assets/icons (app.svg and per-class cls/*.svg:
quip, quips, follow, follows, ban, bans, log, logs), plus like/liked/notLiked
PNGs for the sentiment button.

## PART 11 — DATABASE SEED

Legacy simulation users (dave@quippin on prv1, micha@quippin on prv2) predate
the directory. The migration mySQL/dump-2024-01-21-quippin-directory.sql seeds:

  * the quippin@quippin system act row (both db1 and db2);
  * the directory container obj on hstId="quippin";
  * a dir-<actId> entry obj per legacy user;
  * the directory→dirEntry lnks and the dirEntry→app-quippin lnks.

The per-user Quippin containers and Navigator links for dave/micha are seeded
by the same dual-DB init-script mechanism (INSERT IGNORE, run by both MySQL
containers on first boot). New users get everything from QuippinInstall.

## PART 12 — FUTURE WORK

  * Media attachments on quips (image/video via a binary object store).
  * Global quip-content search (today only user search via the directory).
  * Notifications: new follower, reply, like, mention.
  * Direct messages / private threads.
  * Rich profiles: bio, avatar, banner, pinned quip.
  * Per-provider news aggregation host (quippin-<prvId>) to reduce cross-prv
    fan-out on GetNews (ProviderHosts hook already reserved in app.config.txt).
  * Owner-match authorization: today any verified caller may hit any local
    container; tighten so only the owner may write to their own follows/bans.
  * Cross-prv verified writes for Register when a new user signs up on a prv
    other than the one hosting "quippin" (see [Domatar](../Domatar.md) cross-prv TODO).
  * Directory entry updates when a user changes their display name, and
    deregistration on account deletion ([Quippin](Quippin.md) PART 9).
  * Persistent / configurable log retention (currently fixed 1-hour expiry).

## Quippin Directory

## PART 1 — PURPOSE

The Quippin Directory is a shared, per-app registry of every Quippin user.
Its primary use case is discovery: on the Follows page, a logged-in user
can open a "Browse users" lookup panel, see all Quippin users, and click
one to pre-fill the "Follow user" input field.

Defining properties:

  * The directory is global and shared, not per-user.  It lives on the
    quippin central host (hstId = "quippin"), owned by the system account
    "quippin@quippin".  One copy covers all Quippin users, regardless of which
    prv they are on.

  * Entries are plain Domatar objects.  The directory has a container obj
    and one entry obj per registered user; edges between them are rows in
    the lnk table.  Nothing is synthesised at query time.

  * Population is automatic.  QuippinInstall.install() (run at sign-up)
    registers the new user in the directory as part of its install
    sequence.  No admin step is needed.

  * The data stored per entry (actId, usrName, usrId / handle) is the
    immutable-at-registration snapshot.  If a user can later change their
    display name, a separate "update directory entry" op will be needed
    (TODO).  For v1, registration only happens once.

  * Search is a future extension.  v1 returns the full list.  A
    server-side search op (by prefix, by handle) will be added when
    the user base is large enough for the full list to be unwieldy.

## PART 2 — DATA MODEL

2.0 The quippin@quippin system account

  The directory objects are owned by a dedicated system account whose
  usrId is "quippin@quippin".  This account must exist in the `act` table
  on the quippin central host (prv1 in the local simulation) before any
  directory objects can be written.

  The actId is a key fingerprint:
    ActId   = "X_CkxbZFSQaGm_lApfc50oLRD1m5maMv" (fingerprint)
    UsrId   = "quippin@quippin"                    (login label; unchanged)
    UsrName = "Quippin"
    Pwd     = SHA-1("123") in Domatar Base64 = GBp05MC8NxDHPJAUdVySNhkRkjx

  This account is not intended for everyday login; the password is simple
  only because the environment is a local test machine.  On a production
  system the password should be replaced with a strong random value or the
  account should be disabled for interactive login altogether.

  The act row must appear in BOTH db1 and db2 (like all other seeded rows)
  since both MySQL containers run the init script on first boot.

2.1 Directory container object

  DomId:
    HstId   = "quippin"          (the quippin central host on prv1)
    AppId   = "quippin"
    ActId   = <fingerprint>     (quippin system account)
    ObjId   = "directory"

  ClsAppId  = "quippin"
  ClsId     = "directory"
  ObjName   = "Directory"
  ObjDesc   = "Quippin user directory"
  Attrs     = {}

2.2 Per-user directory entry object

  One obj per registered user, living on the same quippin central host:

  DomId:
    HstId   = "quippin"
    AppId   = "quippin"
    ActId   = <fingerprint>     (quippin system account)
    ObjId   = "dir-" + <actId>   (e.g. "dir-<fingerprint>")

  ClsAppId  = "quippin"
  ClsId     = "dirEntry"
  ObjName   = <usrName>          (display name at registration time)
  ObjDesc   = <usrId>            (human label, e.g. "dave@quippin")
  Attrs:
    ActId   = <actId>            (fingerprint, e.g. "X_CkxbZF...")
    UsrId   = <usrId>            (login label, e.g. "david@quippin")
    UsrName = <usrName>          e.g. "David B."

2.3 Link: directory -> dirEntry

  One lnk per user, stored on the quippin central host (hstId="quippin"):

  Source DomId  = (quippin, quippin, <fingerprint>, directory)
  Target DomId  = (quippin, quippin, <fingerprint>, dir-<actId>)

  LnkClsAppId   = "quippin"
  LnkClsId      = "dirEntry"
  LnkObjName    = <usrName>
  LnkObjDesc    = <usrId>
  TagAppId      = "quippin"
  Tag           = "dirEntry"
  Val           = <actId>
  SeqNum        = <timestamp-ms when registered>

The lnk lets the Navigator tree (and any future tools) walk the directory
without loading each entry's obj row individually.

2.4 Link: dirEntry -> app-quippin  (the "Quippin link")

  Each directory entry carries a persistent link pointing at the registered
  user's Quippin app node in their Navigator tree.  This is the same
  cross-app pointer pattern used by follow entries (PART 5.3 of
  [Navigator](Navigator.md)), except the target is app-quippin rather than the
  Navigator root.

  Rationale: the directory is specifically a Quippin concept, so drilling
  into the user's Quippin sub-tree (Quips, Follows, Bans, Logs) is more
  direct and meaningful than landing at the Navigator root.  Follow entries
  already link to the root for the general "who is this person" drill-
  through; the directory entry's link gives the Quippin-specific view.

  Source DomId  = (quippin, quippin, quippin@quippin, dir-<actId>)
  Target DomId  = (navigator-<actId>, navigator, <actId>, app-quippin)
                  e.g. (navigator-dave@quippin, navigator, dave@quippin,
                         app-quippin)

  LnkClsAppId   = "navigator"
  LnkClsId      = "app"
  LnkObjName    = <usrName>
  LnkObjDesc    = "Quippin"
  TagAppId      = "quippin"
  Tag           = "appQuippin"
  Val           = <actId>
  SeqNum        = 1

  This lnk is written by QuippinDirectoryImpl.Register alongside the
  directory -> dirEntry lnk (PART 3.1).  Both are idempotent.

2.5 seqNum considerations

The directory container and all dirEntry lnks live on the quippin central
host's lnk table.  The directory -> dirEntry SeqNum is the registration
timestamp (milliseconds since epoch), giving natural chronological
ordering; duplicate timestamps are allowed by the primary key because
Val (=actId) distinguishes them.  The dirEntry -> app-quippin SeqNum is
always 1 (there is exactly one such link per entry).

## PART 3 — SERVER-SIDE COMPONENTS

3.1 QuippinDirectoryImpl   (com.quippin.objimpl.QuippinDirectoryImpl)

  Extends ObjImpl.  Registered in ImplMap under ("quippin", "directory").

  Authorization: Auth.isVerified(inMsg) for all ops (any logged-in user
  may register themselves or read the directory; richer policies are TODO).

  Operations:

  Register (op)
    Purpose:  Add a new user to the directory.  Called by
              QuippinInstall.install() over HttpClient (in-process or
              cross-prv sendLocal against the quippin central host).
    Inputs:   ActId, UsrId, UsrName   (all from the install context)
    Behaviour (idempotent):
      1. dirId    = DomId("quippin", "quippin", "quippin@quippin", "directory")
         entryId  = DomId("quippin", "quippin", "quippin@quippin",
                          "dir-" + actId)
         appQpId  = DomId("navigator-" + actId, "navigator", actId, "app-quippin")

      2. If ObjDb.getObj(dirId) == null:
           ObjDb.addObj(dirId, "quippin", "directory",
                        "Directory", "Quippin user directory", {})

      3. If ObjDb.getObj(entryId) == null:
           ObjAttrs attrs = {ActId, UsrId, UsrName}
           ObjDb.addObj(entryId, "quippin", "dirEntry", usrName, usrId, attrs)

      4. If LnkDb.getLnk(dirId, entryId, "quippin", "dirEntry") == null:
           LnkDb.addLnk(dirId, entryId,
                        "quippin", "dirEntry",
                        usrName, usrId,
                        "quippin", "dirEntry",
                        actId, System.currentTimeMillis())

      5. If LnkDb.getLnk(entryId, appQpId, "quippin", "appQuippin") == null:
           LnkDb.addLnk(entryId, appQpId,
                        "navigator", "app",
                        usrName, "Quippin",
                        "quippin", "appQuippin",
                        actId, 1)

    Response: standard Success with no extra attrs.

  GetDirectory (op)
    Purpose:  Return all registered users, for the lookup dialog.
    Inputs:   none (future: SearchQuery for prefix search)
    Behaviour:
      1. dirId = DomId("quippin", "quippin", "quippin@quippin", "directory")
         lnks  = LnkDb.getLnks(dirId, "quippin", "dirEntry", 500)
      2. Build a JsonList "Users" where each element has:
           ActId   = lnk.val
           UsrId   = lnk.lnkObjDesc
           UsrName = lnk.lnkObjName
      3. Return outMsg with Attrs.Users = JsonList above.
    Response body example:
      {
        "Operation": "GetDirectory",
        "Attrs": {
          "Users": [
            { "ActId": "dave@quippin",
              "UsrId": "dave@quippin",
              "UsrName": "David B." },
            { "ActId": "micha@quippin",
              "UsrId": "micha@quippin",
              "UsrName": "Michael B." }
          ]
        }
      }

  Any other op: fall through to super.handleMsg (ObjImpl default).

3.2 QuippinDirectoryWui   (com.quippin.webui.QuippinDirectoryWui)

  @WebServlet("/QuippinDirectoryWui")
  Extends DomatarServlet.

  Actions:

  Action=GetDirectory
    Builds:
      dstDomId = (quippin, quippin, quippin@quippin, directory)
      clsAppId = "quippin",  clsId = "directory"
      op       = "GetDirectory"
    Dispatches via msgClient.send(dstDomId, msg).
    Returns the JSON response verbatim.

  No browser-facing Register action.  Register is called exclusively
  from QuippinInstall, which runs server-side via HttpClient.

3.3 ImplMap changes

  ImplMap.java gains one new entry:
    put("quippin", "directory", new QuippinDirectoryImpl());

  No entry is needed for ("quippin", "dirEntry"): ImplMap.get() returns
  the default ObjImpl for any class not explicitly registered, which is
  exactly the behaviour required for leaf entry objects.

3.4 QuippinInstall changes

  QuippinInstall.install(actId, usrName, domain, prvId) gains one new
  step at the END of its existing sequence:

    5. Register this user in the central directory:
         DomId dstDomId = new DomId("quippin", "quippin", "quippin@quippin",
                                    "directory");
         JsonMsg regMsg = new JsonMsg();
         regMsg.setDstId(dstDomId);
         regMsg.setClsAppId("quippin");
         regMsg.setClsId("directory");
         regMsg.setOperation("Register");
         ObjAttrs regAttrs = new ObjAttrs();
         regAttrs.addAttr("ActId",   actId);
         regAttrs.addAttr("UsrId",   <usrId>);    // handle@appId
         regAttrs.addAttr("UsrName", usrName);
         regMsg.setBody(regAttrs);
         msgClient.send(dstDomId, regMsg);

  The msgClient available in the install context is the same
  DomatarMsgClient passed to ActManagerImpl.addAct.  The quippin
  central host resolves to the local prv (prv1) in both the local
  simulation and production, so this is a sendLocal call (no network hop).

  The registration is idempotent: running install twice is a no-op because
  QuippinDirectoryImpl.Register checks ObjDb / LnkDb before every write.

3.5 web.xml change

  Add a servlet entry for QuippinDirectoryWui:
    <servlet>
      <servlet-name>QuippinDirectoryWui</servlet-name>
      <servlet-class>com.quippin.webui.QuippinDirectoryWui</servlet-class>
    </servlet>
    <servlet-mapping>
      <servlet-name>QuippinDirectoryWui</servlet-name>
      <url-pattern>/QuippinDirectoryWui</url-pattern>
    </servlet-mapping>

## PART 4 — DATABASE SEED

The sim dump includes the directory system account and the fixture
users' directory entries. Illustrative SQL (actId values in a live
dump are fingerprints, PART 2.0 — not usrIds):

  mySQL/dump-2024-01-21-quippin-directory.sql

  Contents:

    -- System account quippin@quippin (password "123", same hash as other
    -- test accounts that share this password)
    INSERT IGNORE INTO act (ActId, UsrId, UsrName, Pwd)
    VALUES ('quippin@quippin', 'quippin@quippin', 'Quippin',
            'GBp05MC8NxDHPJAUdVySNhkRkjx');

    -- Directory container obj
    INSERT IGNORE INTO obj
      (HstId, AppId, ActId, ObjId, ClsAppId, ClsId, ObjName, ObjDesc, Attrs)
    VALUES
      ('quippin','quippin','quippin@quippin','directory',
       'quippin','directory','Directory','Quippin user directory','{}');

    -- Entry obj for dave
    INSERT IGNORE INTO obj
      (HstId, AppId, ActId, ObjId, ClsAppId, ClsId, ObjName, ObjDesc, Attrs)
    VALUES
      ('quippin','quippin','quippin@quippin','dir-dave@quippin',
       'quippin','dirEntry','David B.','dave@quippin',
       '{"ActId":"dave@quippin","UsrId":"dave@quippin","UsrName":"David B."}');

    -- Entry obj for micha
    INSERT IGNORE INTO obj
      (HstId, AppId, ActId, ObjId, ClsAppId, ClsId, ObjName, ObjDesc, Attrs)
    VALUES
      ('quippin','quippin','quippin@quippin','dir-micha@quippin',
       'quippin','dirEntry','Michael B.','micha@quippin',
       '{"ActId":"micha@quippin","UsrId":"micha@quippin","UsrName":"Michael B."}');

    -- Link: directory -> dir-dave@quippin
    INSERT IGNORE INTO lnk
      (HstId, AppId, ActId, ObjId,
       LnkHstId, LnkAppId, LnkActId, LnkObjId,
       LnkClsAppId, LnkClsId, LnkObjName, LnkObjDesc,
       TagAppId, Tag, Val, SeqNum)
    VALUES
      ('quippin','quippin','quippin@quippin','directory',
       'quippin','quippin','quippin@quippin','dir-dave@quippin',
       'quippin','dirEntry','David B.','dave@quippin',
       'quippin','dirEntry','dave@quippin',1);

    -- Link: directory -> dir-micha@quippin
    INSERT IGNORE INTO lnk
      (HstId, AppId, ActId, ObjId,
       LnkHstId, LnkAppId, LnkActId, LnkObjId,
       LnkClsAppId, LnkClsId, LnkObjName, LnkObjDesc,
       TagAppId, Tag, Val, SeqNum)
    VALUES
      ('quippin','quippin','quippin@quippin','directory',
       'quippin','quippin','quippin@quippin','dir-micha@quippin',
       'quippin','dirEntry','Michael B.','micha@quippin',
       'quippin','dirEntry','micha@quippin',2);

    -- Link: dir-dave@quippin -> app-quippin (dave's Quippin node in Navigator)
    INSERT IGNORE INTO lnk
      (HstId, AppId, ActId, ObjId,
       LnkHstId, LnkAppId, LnkActId, LnkObjId,
       LnkClsAppId, LnkClsId, LnkObjName, LnkObjDesc,
       TagAppId, Tag, Val, SeqNum)
    VALUES
      ('quippin','quippin','quippin@quippin','dir-dave@quippin',
       'navigator-dave@quippin','navigator','dave@quippin','app-quippin',
       'navigator','app','David B.','Quippin',
       'quippin','appQuippin','dave@quippin',1);

    -- Link: dir-micha@quippin -> app-quippin (micha's Quippin node in Navigator)
    INSERT IGNORE INTO lnk
      (HstId, AppId, ActId, ObjId,
       LnkHstId, LnkAppId, LnkActId, LnkObjId,
       LnkClsAppId, LnkClsId, LnkObjName, LnkObjDesc,
       TagAppId, Tag, Val, SeqNum)
    VALUES
      ('quippin','quippin','quippin@quippin','dir-micha@quippin',
       'navigator-micha@quippin','navigator','micha@quippin','app-quippin',
       'navigator','app','Michael B.','Quippin',
       'quippin','appQuippin','micha@quippin',1);

This script is mounted alongside the other dumps in the MySQL container
via docker-compose.yml's /docker-entrypoint-initdb.d bind-mount.  It
runs automatically on the next "docker compose down -v && docker compose up".

Both DBs (db1 and db2) run all init scripts at first boot.  The directory
object lives on hstId="quippin" which resolves to prv1, so it is only
ever actively served by tomcat1.  Having the rows in db2 too is harmless
(the fallback cache model); it matches the existing dual-seeding pattern.

## PART 5 — WEB UI (follows.html changes)

5.1 Lookup button

  In the "Follow user" card, next to the existing input field, add a small
  lookup button labelled with a magnifying-glass icon (or the text "Browse"):

    Current:
      <input id="followInput" ... />
      <button onclick="follow()">Follow</button>

    Updated:
      <div style="display:flex;gap:6px;align-items:center;">
        <input id="followInput" ... style="flex:1;" />
        <button type="button" id="lookupButton"
                onclick="openDirectoryLookup()"
                title="Browse Quippin users">&#128269;</button>
      </div>
      <br/>
      <button type="button" id="FollowButton" onclick="follow()">Follow</button>

5.2 Lookup modal

  A simple CSS modal overlay, hidden by default, shown by openDirectoryLookup():

    HTML structure (appended to <body> on page load, or inline):

      <div id="directoryModal" style="display:none; ...overlay styles...">
        <div id="directoryDialog" style="...dialog styles...">
          <div style="display:flex;justify-content:space-between;">
            <h3 style="margin:0;">Browse Quippin Users</h3>
            <button onclick="closeDirectoryLookup()">&#10005;</button>
          </div>
          <div id="directoryList">Loading...</div>
        </div>
      </div>

    Overlay CSS:
      position:fixed; top:0; left:0; width:100%; height:100%;
      background:rgba(0,0,0,0.5); z-index:1000; display:flex;
      align-items:center; justify-content:center;

    Dialog CSS:
      background:#fff; border-radius:6px; padding:16px;
      min-width:320px; max-height:80vh; overflow-y:auto;

5.3 JavaScript

  function openDirectoryLookup()
    Shows the modal.  If the directory list has not yet been fetched this
    session, fetches it via Ajax to /domatar/QuippinDirectoryWui
    (Action=GetDirectory) and populates #directoryList.

  function closeDirectoryLookup()
    Hides the modal.

  function selectDirectoryUser(usrId)
    Sets the value of #followInput to usrId.
    Calls closeDirectoryLookup().
    Does NOT auto-submit: the user still clicks "Follow" to confirm.

  function loadDirectoryList(users)
    Called with the parsed JSON response (retObj.Attrs.Users).
    Renders a table in #directoryList:
      Columns: Name | Handle | (action)
      Each row has a "Select" button that calls selectDirectoryUser(usrId).

    If users is empty: show "No users found."

  fetch logic (in openDirectoryLookup):

    var users = null; // module-level cache; cleared on unfollow / follow

    function openDirectoryLookup() {
        document.getElementById('directoryModal').style.display = 'flex';
        if (users !== null) { loadDirectoryList(users); return; }
        document.getElementById('directoryList').textContent = 'Loading...';
        $.ajax({
            type: 'POST', url: '/domatar/QuippinDirectoryWui',
            data: 'Action=GetDirectory', cache: false,
            success: function(json) {
                var ret = JSON.parse(json);
                users = (ret.Attrs && ret.Attrs.Users) ? ret.Attrs.Users : [];
                loadDirectoryList(users);
            },
            error: function(jqXHR, textStatus, errorThrown) {
                document.getElementById('directoryList').textContent =
                    'Error loading directory: ' + textStatus;
            }
        });
    }

## PART 6 — AUTHORIZATION

  QuippinDirectoryImpl.hasRights:
    All ops require Auth.isVerified(inMsg).
    (Rationale: a handle/display-name directory should not be public to
    unauthenticated crawlers, but every logged-in Quippin user may read it.)

  QuippinDirectoryWui:
    Extends DomatarServlet, which enforces verified login before dispatch.
    No additional per-op checks needed.

  Register is never called from the browser; it is always called
  programmatically by QuippinInstall over HttpClient (server-side), so
  it inherits the verified context of the addAct request.

  Future: tighten Register so only the system account (quippin@quippin) or
  a call originating from the quippin central host's own install context
  may invoke it.  For v1, verified-caller is sufficient.

## PART 7 — NAVIGATOR INTEGRATION

The directory is a Domatar object and participates in the Navigator tree
automatically, via the standard Open protocol.

  app-quippin (on navigator-<actId>) already links to the user's own
  quippin containers (follows, quips, bans, logs).  The directory is a
  SHARED object, not per-user, so it is NOT linked from individual users'
  navigator trees.

  TODO: expose the shared directory as a node under a future
        "Quippin (global)" or "Quippin system" branch, once the Navigator
        supports cross-account / system-account browsing.

  The directory object IS browsable if a user manually opens it via its
  DomId (e.g. by typing it into a future Navigator address bar).

## PART 8 — SOURCE LAYOUT

New files:

  src/main/java/com/quippin/objimpl/QuippinDirectoryImpl.java
      Handler for (quippin, directory).  Implements Register and
      GetDirectory.  Extends ObjImpl.

  src/main/java/com/quippin/webui/QuippinDirectoryWui.java
      Browser-facing servlet.  Handles Action=GetDirectory.
      @WebServlet("/QuippinDirectoryWui").

  mySQL/dump-2024-01-21-quippin-directory.sql
      Schema seed for the directory container, two user entries (dave,
      micha), and their lnks (PART 4).

Edits to existing files:

  src/main/java/com/quippin/install/QuippinInstall.java
      Add step 5: call Register on the quippin directory central host
      after the existing four steps (PART 3.4).

  src/main/java/com/domatar/core/ImplMap.java
      Register ("quippin", "directory") only (PART 3.3).
      No entry needed for ("quippin", "dirEntry") since ImplMap
      falls back to ObjImpl for unregistered classes.

  src/main/webapp/WEB-INF/web.xml
      Add servlet + mapping for QuippinDirectoryWui (PART 3.5).

  src/main/webapp/follows.html
      Add lookup button and modal dialog (PART 5).

  docker-compose.yml
      The new SQL dump file is already picked up automatically by the
      existing bind-mount of ./mySQL into /docker-entrypoint-initdb.d
      (the MySQL container runs all *.sql files in lexicographic order).
      No compose edit is needed.

## PART 9 — TODO

  * Update directory entry when a user changes their display name.
    Today usrName is stored at registration time and never updated.
    QuippinDirectoryImpl should gain an UpdateEntry op, called from
    wherever display-name changes are implemented.

  * Server-side search.  Add a SearchDirectory op that accepts a
    query string and returns matching entries, for use when the user
    base exceeds what is practical to return in full.

  * Pagination.  GetDirectory currently returns up to 500 entries
    (LnkDb default).  Add cursor-based paging once needed.

  * Handle the case where the quippin central host is on a different
    prv from the caller.  In the local simulation prv1 always hosts
    "quippin", so Register is always a sendLocal call.  In a real
    deployment where prv2 signs up a new user, the Register message
    would cross prvs.  This requires cross-prv verified writes to work
    (see [Domatar](../Domatar.md) PART 9 cross-prv authorization TODO).

  * Deregistration.  When a user deletes their account, their directory
    entry and lnk should be removed.  No account-deletion feature
    exists yet, so this is deferred.

  * Navigator link.  Surface the directory as a browsable node under a
    "Quippin (system)" or "quippin@quippin" branch in the Navigator, once
    the Navigator supports browsing accounts other than one's own.

# END OF SPEC
