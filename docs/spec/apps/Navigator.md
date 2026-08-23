# SPEC: NAVIGATOR - the Domatar object browser

## PART 1 — PURPOSE

The Navigator is a Domatar app whose UI is a Windows-Explorer-style
two-pane object browser. The left pane is a tree of Domatar objects:
each node has a +/- expander, an icon, and the object's ObjName.
The right pane shows the currently-selected object's metadata
(ClsAppId, ClsId, ObjName, ObjDesc, attrs).

Every node in the tree is a real Domatar object. Every
parent->child edge is a real link. Nothing about
the tree is synthesised at request time - if the Navigator can
show it, that object and that link exist.

On Open, NavRootReconcile adds root→app links from GetUserApps
([Platform App](../platform/Platform-App.md)). The tree is the user's
installed apps plus whatever those apps linked under themselves.

  +------------------------------------------------------+
  | - [icon] Root                ┌─── selected obj ──────┤
  |   - [icon] Quippin           │ ClsAppId : quippin    │
  |     - [icon] Follows         │ ClsId    : follows    │
  |       + [icon] Micha B.      │ ObjName  : Follows    │
  |       + [icon] Alex T.       │ ObjDesc  : ...        │
  |     + [icon] Quips           │ Attrs    :            │
  |     + [icon] Bans            │   ...                 │
  |     + [icon] Logs            │                       │
  |   + [icon] Login             │                       │
  |   + [icon] Desktop           │                       │
  +------------------------------------------------------+

The tree is the user's view of the Domatar object graph. Every
node IS a Domatar object; every parent->child edge IS a link.
The Navigator does not invent any new structure -
it just renders what already exists.

PART 2 onward fills in the details. Four things drive everything:

  1. Open(DomId)        protocol that any obj answers, returning
                        its details + its outgoing lnks.
  2. links              the existing edges. Apps that want
                        their data to show up in the tree
                        maintain links alongside their objects.
  3. App install        the moment an app's per-user surface is
                        first created on this account. Install
                        creates the app's per-user sub-host, the
                        app's app-obj on navigator-<actId>, the
                        app's per-kind container objs, and all the
                        skeleton lnks tying them to the user's
                        root.
  4. browser-side cache every node remembers its full Open
                        response after the first fetch. +/- toggles
                        don't re-fetch.

The Navigator is read-only in v1. Editing, creating, deleting,
copying, moving and dragging objects across the tree are all listed
under PART 14 TODO.

## PART 2 — APP IDENTITY

  appId          : "navigator"
  central host   : "navigator"   (registered in hst, lives on prv1
                                  in the local simulation; same
                                  shape as the quippin / login
                                  central hosts).
  per-user host  : navigator-<actId>-<prvId>
                   (one per login-home provider; [Desktop](Desktop.md)
                   PART 3.1). Separator is `-` (`DomId.HOST_SEP`; it is
                   not in the fingerprint alphabet). actId is a 32-char
                   fingerprint ([Identifiers](../platform/Identifiers.md) PART 4.1).
                   The navigator TREE is per-provider and is NOT
                   content-merged across providers (unlike Desktop tiles);
                   each provider serves its own roots locally.
  hosting prv    : the provider that owns this replica (DomatarConfig.getPrvId()).

The host record for navigator-<actId>-<prvId> is created by NavigatorReplica.ensure
(via NavigatorInstall / DesktopInstall / DesktopSyncCutover) on sign-up or
migration. Routing then follows the existing hstId -> prvId resolution
path with no Navigator-specific machinery.

## PART 3 — THE ROOT, AND HOW THE TREE GROWS FROM IT

3.1 The root DomId

Every act has exactly one root per provider replica:

  HstId  = navigator-<actId>-<prvId>
  AppId  = navigator
  ActId  = <actId>
  ObjId  = root

This DomId is computable from <actId> alone, so the browser knows
it without a server call. When navigator.html loads, it stamps the
top-of-tree row with that DomId (data-sovid attribute) and is then
ready to issue Open against it.

ObjName for the root obj is the user's display name, e.g. "David B."
(populated at sign-up time by ActManagerImpl). ObjDesc, Attrs,
ClsAppId/ClsId all come from the object itself; the page sees
them on the first Open.

3.2 How a node's children appear

The user clicks '+' on a node N:

  1. Browser sends N.DomId to the server (POST /<context>/NavWui
     with Action=Open, DomId=...).
  2. The server's NavWui wraps it as a JsonMsg with dst=N.DomId
     and op="Open", and dispatches.
  3. HttpClient routes by N.DomId.hstId; the message lands on the
     prv where the obj actually lives.
  4. Msg.doAction loads the obj — in this realisation from the
     local `obj` table — this
     is what the user described as "instantiate an Obj with that
     DomId" -- and dispatches into the obj's class implementation.
  5. The class's Open handler returns:
       a) the obj's own metadata (ClsAppId, ClsId, ObjName,
          ObjDesc, Attrs)
       b) the obj's outgoing lnks (List<Lnk>) read via
          obj.getLnks(maxN), which delegates to LnkDb.
  6. NavWui returns the response to the browser.
  7. Browser:
       - renders one child tree-node per lnk, labelled with
         lnk.lnkObjName, addressed by lnk.lnkDomId
       - displays the obj's metadata in the right pane
       - caches the full response on the parent tree-node so
         subsequent +/- toggles do not re-fetch
       - flips the parent's expander '+' -> '-'

A subsequent click on '-' simply hides the cached children (no
network). A subsequent click on '+' on the same node shows them
again (still no network). Re-fetching is an explicit "Refresh"
action (PART 6.5).

3.3 What a node displays

Tree pane (left), per node:
  - icon (from ClsAppId / ClsId, see PART 6.3)
  - +/- expander (or no expander for known leaves; see PART 4.4)
  - the obj's ObjName, taken from:
      * the parent's lnk.lnkObjName before the node has been
        opened (best information available without a round-trip)
      * the obj's own ObjName from the Open response after the
        first opening (refreshes the label in case the lnk's
        cached copy was stale)

Right pane, when the node is the selected one:
  - ClsAppId, ClsId, ObjName, ObjDesc
  - the obj's Attrs map, rendered as a definition list

Anything else - SeqNum, Tag, lnk Val, etc. - is metadata about
the EDGE the user came in on (the parent->child lnk), not about
the obj itself, so it does not appear in the right pane. (A
power-user "show edge" view is PART 14 TODO.)

3.4 Browser-side cache

Each opened tree-node caches the whole Open response in a JS
object hung off the DOM (e.g. via jQuery .data() on the <li>):

  {
    domId       : "...",
    obj         : { ClsAppId, ClsId, ObjName, ObjDesc, Attrs },
    lnks        : [ {DomId, ClsAppId, ClsId, ObjName, ObjDesc,
                     Tag, TagAppId, Val, SeqNum}, ... ],
    fetchedAt   : <ms-since-epoch>
  }

Selecting a cached node updates only the right pane (no fetch).
Re-expanding a cached node toggles visibility (no fetch). The
Refresh button (PART 6.5) clears the subtree's cache and forces
the next + click on that subtree to re-open.

## PART 4 — THE Open PROTOCOL

4.1 Operation shape

Request body, sent to the obj being opened:

  {
    "Operation" : "Open",
    "Body"      : { "Attrs" : { "MaxLnks" : "100", "StartSeqNum" : "0" } }
  }

(MaxLnks defaults to 500 when absent and caps the page size.
StartSeqNum is the optional paging cursor: when present, only links
whose SeqNum is at or beyond it are returned, in SeqNum order. To walk
a large object's children, the caller issues Open with no StartSeqNum,
then re-issues it with StartSeqNum set to the NextSeqNum the previous
response handed back, repeating while HasMore is true. Because SeqNum
is the canonical sibling ordering this gives a stable forward cursor.)

Response body:

  {
    "Operation" : "Open",
    "Attrs"     : {
      "DomId"    : "<dotted DomId of this obj>",
      "PrvId"    : "<hst.PrvId of this obj's host>",
      "ClsAppId" : "...",
      "ClsId"    : "...",
      "ObjName"  : "...",
      "ObjDesc"  : "...",
      "Attrs"    : { ... obj's flat attrs map ... },
      "Lnks"     : [
        {
          "DomId"    : "<dotted DomId of the linked obj (Lnk.lnkDomId)>",
          "ClsAppId" : "<Lnk.lnkClsAppId>",
          "ClsId"    : "<Lnk.lnkClsId>",
          "ObjName"  : "<Lnk.lnkObjName>",
          "ObjDesc"  : "<Lnk.lnkObjDesc>",
          "Tag"      : "<Lnk.tag>",
          "TagAppId" : "<Lnk.tagAppId>",
          "Val"      : "<Lnk.val>",
          "SeqNum"   : "<Lnk.seqNum as a string>"
        },
        ...
      ],
      "HasMore"    : "true" | "false",
      "NextSeqNum" : "<cursor for the next page; present only when HasMore is true>"
    }
  }

(ObjImpl fetches one link beyond MaxLnks to decide HasMore: when the
extra link exists it is dropped from Lnks and its SeqNum is returned as
NextSeqNum. SeqNum is assumed unique among an object's children, which
is the Navigator ordering invariant.)

The lnk fields map 1:1 onto the existing Lnk class
(src/main/java/com/domatar/util/Lnk.java); the response is just
that class's contents projected into JSON.

4.2 Default implementation

ObjImpl gains an Open default handler that, given the loaded obj
and msgClient, returns the response shape above. Pseudocode:

  // ObjImpl.handleOpen(inMsg, obj, msgClient):
  //   if (obj == null)
  //       return outMsg.addError("Open", "Obj not found");
  //   List<Lnk> lnks = obj.getLnks(maxLnks);
  //   ObjAttrs out = ...
  //   out.addAttr("DomId",    obj.domId.toString());
  //   out.addAttr("PrvId",    hst.prvId);
  //   out.addAttr("ClsAppId", obj.clsAppId);
  //   out.addAttr("ClsId",    obj.clsId);
  //   out.addAttr("ObjName",  obj.objName);
  //   out.addAttr("ObjDesc",  obj.objDesc);
  //   out.addAttr("Attrs",    obj.attrs.toMap());
  //   out.addAttr("Lnks",     marshal(lnks));
  //   return out;

This default works for ANY class - the Navigator does not need
class-specific code on the server side as long as the objects
and links already exist.

A null obj is a hard error, identical to the existing GetObj
behaviour: the address pointed at no object. The
Navigator never receives a null-obj reply because it never
addresses anything that has not been installed (PART 5 / PART 10).

4.3 Subclass override

A subclass MAY override Open if it wants to filter or reshape the
default response - e.g. to redact attrs, paginate lnks differently,
or add follow-up "Did you mean..." Lnks. v1 ships zero overrides;
the default handler is enough for every node in the v1 tree.

4.4 hasChildren hint

Open does NOT return a separate "hasChildren" flag. The browser
already knows: if Lnks is non-empty, the parent renders '+'; if
Lnks is empty, the parent renders no expander.

There is one wrinkle: a leaf that has ZERO lnks today might gain
some later (e.g. a quip gains child quips). For unopened nodes the
browser's only signal comes from the parent's lnk to it, which
does not carry a child-count. v1 therefore renders '+' optimistically
on every unopened node and learns "actually empty" on first open
(at which point the expander is hidden / replaced with a leaf
glyph). PART 14 TODO covers a cheap server-side count.

4.5 Authorization

Each impl's existing hasRights() applies unchanged. ObjImpl.Open
calls hasRights() the same way every other op does. Because
hasRights() is per-class and per-obj, the Navigator inherits the
correct policy for free: a user with no session at prv2 hitting a
quip on quippin-micha@quippin gets "Not authorized" from the
existing Msg.verifyAndStamp gate, and the UI renders that node's
expansion as a greyed-out stub (PART 6.4).

## PART 5 — THE TREE STRUCTURE (what gets lnk'd to what)

The Navigator does not impose any tree structure of its own; it
just walks the links. But for the tree to LOOK like a sensible
filesystem to the user, the apps must maintain a sensible set of
lnks. This part lays out the v1 tree shape.

5.1 The skeleton

  Root                          navigator-<actId>.navigator.<actId>.root
   ├── Quippin                  navigator-<actId>.navigator.<actId>.app-quippin
   │    ├── Follows             quippin-<actId>.quippin.<actId>.follows
   │    │    └── <follow rows>
   │    ├── Quips               quippin-<actId>.quippin.<actId>.quips
   │    │    └── <quip rows>
   │    ├── Bans                quippin-<actId>.quippin.<actId>.bans
   │    │    └── <ban rows>
   │    └── Logs                quippin-<actId>.quippin.<actId>.logs
   │         └── <log rows>
   ├── Login                    navigator-<actId>.navigator.<actId>.app-login
   │    └── Logins              login-<actId>.login.<actId>.logins
   │         └── <login rows>
   ├── Desktop                  navigator-<actId>.navigator.<actId>.app-desktop
   │    └── Apps                desktop-<actId>.desktop.<actId>.apps
   │         └── <app-* rows>
   └── Navigator                navigator-<actId>.navigator.<actId>.app-navigator
        (no children in v1)

Three observations:

  - The Root and the four "app-..." nodes are real objects on
    navigator-<actId>, with class (navigator, root) and (navigator,
    app) respectively (PART 7.1). The Navigator owns these objects.

  - The per-kind containers (Follows, Quips, Logins, Apps, ...)
    are real objects too, written at install time on each app's
    own sub-host. v1 does NOT synthesise these: they exist or the
    Navigator does not show them.

  - Every edge in the tree is one link, stored on
    the prv where the SOURCE obj lives. The cross-prv hop happens
    on the OUTGOING dispatch from a follow object to the followee's
    root, not on edge enumeration.

5.2 The lnks the skeleton needs

Per user, on the prv hosting navigator-<actId>:

  Source: navigator-<actId>.navigator.<actId>.root
    -> navigator-<actId>.navigator.<actId>.app-quippin    tag=("navigator","app")
    -> navigator-<actId>.navigator.<actId>.app-login      tag=("navigator","app")
    -> navigator-<actId>.navigator.<actId>.app-desktop    tag=("navigator","app")
    -> navigator-<actId>.navigator.<actId>.app-navigator  tag=("navigator","app")

  Source: navigator-<actId>.navigator.<actId>.app-quippin
    -> quippin-<actId>.quippin.<actId>.follows            tag=("navigator","container")
    -> quippin-<actId>.quippin.<actId>.quips              tag=("navigator","container")
    -> quippin-<actId>.quippin.<actId>.bans               tag=("navigator","container")
    -> quippin-<actId>.quippin.<actId>.logs               tag=("navigator","container")

  Source: navigator-<actId>.navigator.<actId>.app-login
    -> login-<actId>.login.<actId>.logins                 tag=("navigator","container")

  Source: navigator-<actId>.navigator.<actId>.app-desktop
    -> desktop-<actId>.desktop.<actId>.apps               tag=("navigator","container")

The tag/tagAppId pairs ("navigator","app") and ("navigator","container")
are NOT functionally required (the Navigator's default Open passes
null/null as the tag filter and returns every outgoing lnk).
They exist so that future ops can selectively walk only certain
kinds of edges - e.g. a future "show me only navigator-installed
apps" filter.

5.3 The lnks the apps need

Once an app has its <appId>-<actId> sub-host, its job is to
maintain lnks from each of its per-kind containers to each of
its per-item rows. Concretely:

  quippin.follows
    On follow:    Two lnks are written.

                  (1) <follows> -> <follow row>   (parent -> child,
                                                   same prv):
                  LnkDb.addLnk(<follows>, <follow row>,
                               clsAppId="quippin", clsId="follow",
                               objName=usrName, objDesc=usrId,
                               tagAppId="quippin", tag="follow",
                               val=usrId, seqNum=<now>)

                  (2) <follow row> -> <followee's nav root>
                                                   (the cross-app
                                                    drill-through;
                                                    PART 5.4):
                  LnkDb.addLnk(<follow row>,
                               navigator-<followedActId>.navigator
                                  .<followedActId>.root,
                               clsAppId="navigator", clsId="root",
                               objName=usrName, objDesc=usrId,
                               tagAppId="navigator", tag="followee",
                               val=usrId, seqNum=0)

                  Both lnks live on the SOURCE prv (the follower's
                  prv). The followee's nav root DomId is computable
                  from the followee's actId; the link records
                  it as a string and the Navigator's UI follows
                  it cross-prv on click.

    On unfollow:  Both lnks are deleted symmetrically.
                  LnkDb.deleteLnks(<follows>, <follow row>,
                                   "quippin", "follow", null, null)
                  LnkDb.deleteLnks(<follow row>, <followee root>,
                                   "navigator", "followee",
                                   null, null)

  quippin.quips
    On addQuip:    LnkDb.addLnk(<quips>, <new quip row>,
                                "quippin", "quip", <objName>,
                                <objDesc>, "quippin", "quip",
                                <text-prefix>, <now>)
    On deleteQuip: LnkDb.deleteLnks on the same edge.

  quippin.bans, quippin.logs    same pattern.

  login.logins
    On recordLogin / forgetLogin: lnk between <logins> and
    <login row>.

  desktop.apps
    On installApp / uninstallApp: lnk between <apps> and <app row>.

Each is one extra LnkDb call per existing op. None of them adds
business logic; they all add denormalised metadata
(lnkObjName / lnkObjDesc) that the Navigator reads cheaply
without loading each object.

5.4 Cross-app pointer lnks

A handful of places need the tree to "jump" - e.g. clicking a
follow row should drill into the followee's tree. All v1
jumps are persisted lnks, not synthesised:

  quippin.follow (single follow row)
    PART 5.3 above already specifies the persistent
    <follow row> -> <followee's nav root> lnk written by
    FollowsImpl.follow. The Navigator picks it up via the
    default Open with no class-specific code.

  quippin.quip   (single quip row)
    Already has child-quip lnks (existing tag "child"). The
    Navigator picks them up unchanged.

  desktop.app    (an installed-app row)
    Has a LaunchPath (URL). The Navigator does not follow URLs;
    the right pane just renders the LaunchPath as plain attr text.
    Clicking the icon in Desktop is what opens the app, not the
    Navigator.

  login.login    (single linked login row)
    Could carry a persistent lnk pointing at the account at
    <appId>'s central host. PART 14 TODO.

## PART 6 — WEB UI (navigator.html)

6.1 URL

  /<context>/navigator
      JSP-mapped to navigator.html via web.xml, exactly like
      /<context>/desktop -> desktop.html. v1 ships under the shared
      /quippin/ context, like Desktop and Account.

  /<context>/NavWui
      The single Wui endpoint backing the Navigator (PART 7).

No Quippin side menu - same rule as Desktop / Account: this is a
non-Quippin app.

6.2 Layout

A two-pane layout, left fixed-width or resizeable (CSS flex),
right fluid:

  +------------------+----------------------------------+
  |                  | (selected obj's attrs go here)   |
  |   tree pane      |                                  |
  |                  |   PrvId    : ...                 |
  |                  |   ClsAppId : ...                 |
  |   - expand/      |   ClsId    : ...                 |
  |     collapse     |   ObjName  : ...                 |
  |   - select       |   ObjDesc  : ...                 |
  |     row          |   Attrs    :                     |
  |                  |     ...                          |
  +------------------+----------------------------------+

6.3 Icons

The browser picks an icon per node from the (ClsAppId, ClsId)
pair in the lnk metadata (or, after expansion, in the obj
metadata). Icons live in each app JAR and are served at:

  /domatar/<ClsAppId>/icons/cls/<ClsId>.svg

Installed-app rows are class `(<appId>, app)`. That app's
`cls/app.svg` is the identity tile (same art as the launcher).
Navigator does not special-case ClsId `app`.

(see [Icons](../platform/Icons.md) PART 3 / 5). Unknown classes fall back to
`/domatar/domatar/icons/cls/default/obj.svg`. New apps add SVG
files under their own `assets/icons/cls/`; Navigator needs no code
change to recognise them.

Open GetLnks responses include:

  AssetOrigin      = `{scheme}://{browserDomain}` of the answering host
                     (PublicDomain when set, else Domain; when configured).
  AssetContextPath = `""` when PublicDomain is a distinct nginx front
                     door, otherwise `/domatar`.

Navigator resolution ([Icons](../platform/Icons.md) PART 7.3):

  1. Absolute IconUrl on the node if present (deferred; not used yet).
  2. Else if the page URL is under `/domatar/` (direct WAR / Tomcat),
     use the same-origin WAR path
     `/domatar/<ClsAppId>/icons/cls/<ClsId>.svg`. Open's PublicDomain
     AssetOrigin is ignored here so localhost:9080/9081 still loads
     icons when the hosts file does not map the front-door name.
  3. Else if AssetOrigin's host differs from the page origin, use
     AssetOrigin + path, where path is
     `/domatar/<ClsAppId>/icons/cls/<ClsId>.svg` unless
     AssetContextPath is `""` (then strip `/domatar`).
  4. Else same-origin relative path.
  5. onerror → default object glyph.

Children inherit the parent's AssetOrigin / AssetContextPath until
their own Open supplies a different one (cross-host edge).

6.4 Open / select / expand semantics

  click + on row R       -> if R is already cached: toggle visibility
                            else: Open(R.domId), cache, render
                                  children, select R.

  click - on row R       -> hide R's children (no network).

  click on R's name      -> select R. If R is not cached, Open(R.domId)
                            (this fetches the obj's metadata and any
                            children R currently has, but does not
                            expand the row).
                            Update the right pane from the cache.

  click + on a row whose
  parent's lnk metadata
  promised children but
  Open returns Lnks=[]   -> expander disappears (the lnk-side
                            estimate was wrong; the obj is actually
                            a leaf). The empty result is still
                            cached so further clicks do nothing.

  Open returns "Not
  authorized"            -> render a single greyed-out stub child
                            "(not authorized)" with a tooltip
                            showing the failed DomId. The negative
                            result is cached.

  Open transport error   -> render a single red stub child "(retry)"
                            with a Retry link. NOT cached.

6.5 Toolbar

A small toolbar above the tree. v1 buttons:

  Refresh root      Re-open the root DomId, invalidating the entire
                    cache. Tree is reset to "Root expanded one
                    level".
  Refresh node      With a node selected, re-Open its DomId only,
                    invalidating that subtree's cache. Children
                    re-render.
  Sign out          Same as Account's Sign out.

Address bar / breadcrumbs / search box are TODO (PART 14).

6.6 Right pane (attrs view)

Renders DomId (with HstId/AppId/ActId/ObjId split), PrvId (the
hosting provider from hst.PrvId), ClsAppId, ClsId, ObjName,
ObjDesc, Attrs from the cached Open response. Attrs is rendered as
a definition list. Values that
look like DomIds (regex: contains exactly three '.'s and looks
like a DomId) are rendered as clickable links that scroll the
tree to that DomId, expanding it on the way if needed.

## PART 7 — WUI SERVLET (NavWui)

  com.navigator.webui.NavWui  @WebServlet("/NavWui/*")
  extends com.domatar.servlet.DomatarServlet

Per-action request handling:

  Action=Open
      Body params:
        DomId       (dotted string; defaults to the caller's root
                     DomId if absent)
        MaxLnks     (optional; defaults to "500")
        StartSeqNum (optional paging cursor; when present, only links
                     with SeqNum >= it are returned, in SeqNum order -
                     PART 4.1)
      Builds dst from DomId, addRequestHead, addRequestBody("Open",
      attrs), addClsId. The (ClsAppId, ClsId) on addClsId is
      derived from the dst DomId's ObjId family if the object
      itself is not yet known to the dispatcher; in v1 we use the
      simple heuristic table in PART 12 (e.g. "follow-*" ->
      ("quippin","follow")), and accept that on a miss the
      message lands on ObjImpl's default class which still answers
      Open correctly because the response shape does not depend
      on a class-specific override.

NavWui does no credential checking; DomatarServlet's outer
dispatch already does that.

## PART 8 — OBJ HANDLERS

This is the heart of the spec on the server side. The single
contract is Open. Three places implement it:

  (a) ObjImpl                  default Open: obj details + getLnks.
  (b) Each existing ObjImpl    delegates to (a) on its handleMsg's
      subclass                 unknown-op branch (one-line
                               refactor).
  (c) NavRootImpl, NavAppImpl  trivial: just register two new
                               classes. Open is fine as inherited.

No new class is needed for follow / quip / ban / login / app
rows to participate. The follow->followee-root jump is a real
persistent lnk written by FollowsImpl.follow (PART 5.3); the
default Open reads it without class-specific code.

8.1 Default handler in ObjImpl

ObjImpl.handleMsg gains an "Open" branch alongside the existing
"GetObj":

  if ("Open".equals(opr))
    return openObj(inMsg, obj, msgClient).toString();

  if ("GetObj".equals(opr))
    return getObj(...).toString();

  // existing default: "Unknown operation"

`openObj` is a new protected method on ObjImpl that returns a
JsonMsg shaped per PART 4.1, using:
  - obj.objName, obj.objDesc, obj.attrs    (obj details)
  - obj.getLnks(maxLnks)                    (the lnks)

For null obj, openObj returns an "Obj not found" error - the
same shape the existing GetObj path uses. There is no synthesis;
every node in the v1 tree is a real object written by an
install routine (PART 10).

8.2 Existing impls' fallback

Each existing ObjImpl subclass (FollowsImpl, QuipsImpl, BansImpl,
LogsImpl, NewsImpl, QuipImpl, AppsImpl, LoginsImpl, ActManagerImpl,
ActCacheImpl, HstsImpl, SentimentImpl, SentimentsImpl) currently
ends its handleMsg with:

  else
    outMsg.addError(opr, "Unknown operation");

That single "else" line is replaced with:

  else
    return super.handleMsg(msg, obj, contextPath, contextRealPath, msgClient);

so that any op the subclass does not recognise (notably "Open"
and "GetObj") flows up to ObjImpl. No other change.

This refactor is mechanical and risk-free: today's behaviour for
unknown ops is "Unknown operation"; tomorrow's behaviour is "ask
ObjImpl, which falls back to Unknown operation if it does not
know either" - identical for every op except the two ObjImpl
recognises.

8.3 NavRootImpl, NavAppImpl

  com.navigator.objimpl.NavRootImpl  extends ObjImpl
      Class (navigator, root). Inherits Open and GetObj from
      ObjImpl unchanged. Override hasRights to verified+owner-match
      (PART 14 TODO; v1 settles for verified-only, like FollowsImpl).

  com.navigator.objimpl.NavAppImpl   extends ObjImpl
      Class (navigator, app). Same shape as NavRootImpl. The two
      classes exist mainly to give the UI a clean (ClsAppId,
      ClsId) pair to choose icons from; behavioural difference is
      none in v1.

In effect, the Navigator's server-side surface is "two empty
subclasses + one default Open in ObjImpl + one mechanical refactor
across 13 existing impls". The new code is small.

8.4 ImplMap registration

ImplMap gets two new entries:

  put("navigator", "root", new NavRootImpl());
  put("navigator", "app",  new NavAppImpl());

The act/actCache/actManager classes are already registered for
the navigator central host (same factoring quippin/login use).
No FollowImpl, QuipImpl, BanImpl, etc. is needed in v1: the
default ObjImpl is enough for individual objects because every edge
they care about is a persisted lnk, and the object itself
already exists.

## PART 9 — CROSS-PRV NAVIGATION

9.1 The transparent path

Every Open is one HttpClient.dispatch round-trip routed by the
target DomId's hstId. This is the existing path - the Navigator
does not bend it.

For Dave (on prv1) clicking '+' on a Quippin follow that targets
Micha (whose home prv is prv2):

  - Dave's browser POSTs Action=Open, DomId=quippin-dave@quippin
    .quippin.dave@quippin.follow-micha@quippin.
  - NavWui dispatches; sendLocal (the follow row lives on prv1).
  - Bare ObjImpl.openObj returns the follow row's obj details
    plus its outgoing lnks - one of which is the persistent
    "->navigator-micha@quippin..." lnk written by
    FollowsImpl.follow at follow time (PART 5.3).
  - Browser renders that lnk as a single child labelled
    "Michael B." under the follow row.
  - Dave clicks '+' on Michael B.
  - Browser POSTs Action=Open, DomId=navigator-micha@quippin
    .navigator.micha@quippin.root.
  - NavWui dispatches; HttpClient resolves navigator-micha@quippin
    via the directory: PrvId=prv2, Domain=tomcat2:8080. Cross-prv
    POST tomcat2:8080/quippin/Msg.
  - Msg.doAction on prv2: verifyAndStamp runs LoginRemote
    .verifyLogin against the navigator central host on prv1
    (Dave's session is valid; Context.verified=true). Dispatches
    into NavRootImpl on prv2.
  - NavRootImpl.Open returns Micha's root obj + outgoing lnks
    (his app-quippin / app-login / app-desktop / app-navigator
    rows on his navigator-micha@quippin sub-host).
  - Response travels back; Dave's browser renders Micha's four
    apps under the "Michael B." follow.

There is no Navigator-specific cross-prv code. HttpClient handles
the routing; Msg.verifyAndStamp handles authentication; everything
else falls out.

9.2 Authorization at the boundary

Cross-prv dispatch reuses the existing trust boundary: Msg
.verifyAndStamp on the receiver runs LoginRemote.verifyLogin
against the central host of <usrId>'s suffix. If Dave has no
session at the navigator central host, the receiver answers
"Not authorized" and the UI greys out that subtree. This is the
same gate every cross-prv op already uses.

9.3 Performance

Each '+' click is one round trip plus, per pointer-following node
(persistent cross-app lnks like the follow's
"->navigator-micha@quippin..."), one optional second round trip
when the user actually drills through. Pre-fetching is PART 14
TODO.

## PART 10 — APP INSTALL AND BOOTSTRAP

The Navigator's tree is materialised one app at a time, by an
"install" routine each app declares. Install for an act/app pair
is the moment that:

  - the app's per-user sub-host (<appId>-<actId>) gets created,
  - the app's per-user singleton container objs get written,
  - the app's app-obj on navigator-<actId> gets written,
  - lnks from root -> app-obj and from app-obj -> containers
    get written.

Subsequent ordinary use of the app (following someone, posting a
quip, recording a login, installing an icon on Desktop) adds and
removes per-item lnks alongside the per-item objects it already
writes (PART 10.4).

10.1 The InstallApp routine

Each app contributes one static method, conventionally:

  com.<app>.install.<App>Install
      public static void install(String actId,
                                 String usrName,
                                 String navigatorHstId,
                                 String hostingDomain,
                                 String hostingPrvId)
        throws DomatarException

Concrete v1 routines:

  com.navigator.install.NavigatorInstall.install
      Always runs FIRST (because every other app's install needs
      the root + app-obj location to exist).
        1. HstDb.addHst(navigator-<actId>, hostingDomain,
                        hostingPrvId)               (idempotent)
        2. ObjDb.addObj(<root>,
                        clsAppId=navigator, clsId=root,
                        objName=usrName, objDesc="Root",
                        attrs={})
        3. ObjDb.addObj(<app-navigator>,
                        clsAppId=navigator, clsId=app,
                        objName="Navigator",
                        objDesc="Browse your Domatar objects",
                        attrs={})
        4. LnkDb.addLnk(<root>, <app-navigator>,
                        lnkClsAppId=navigator, lnkClsId=app,
                        lnkObjName="Navigator",
                        lnkObjDesc="Browse your Domatar objects",
                        tagAppId=navigator, tag=app,
                        val=null, seqNum=4)

  com.quippin.install.QuippinInstall.install
        1. (the user's quippin-<actId> host record is already created
            by ActDb.addAct's existing logic; idempotent re-add)
        2. ObjDb.addObj for each of the four containers on
           quippin-<actId>: follows, quips, bans, logs.
              clsAppId=quippin, clsId=<follows|quips|bans|logs>,
              objName=("Follows" | "Quips" | "Bans" | "Logs"),
              objDesc=<one-liner>, attrs={}.
        3. ObjDb.addObj(<app-quippin>,
                        clsAppId=navigator, clsId=app,
                        objName="Quippin",
                        objDesc="Microblog and follow feed",
                        attrs={}).
        4. LnkDb.addLnk(<root>, <app-quippin>, ...).
        5. LnkDb.addLnk(<app-quippin>, <follows>, ...) and three
           more for quips, bans, logs.

  com.login.install.LoginInstall.install
        1. HstDb.addHst(login-<actId>, ...) - same as the existing
           ensureLoginSubHst.
        2. ObjDb.addObj(<logins>) on login-<actId>.
        3. ObjDb.addObj(<app-login>) on navigator-<actId>.
        4. LnkDb.addLnk(<root>, <app-login>, ...).
        5. LnkDb.addLnk(<app-login>, <logins>, ...).

  com.desktop.install.DesktopInstall.install
        1. HstDb.addHst(desktop-<actId>, ...) - same as the
           existing ensureDesktopSubHst.
        2. ObjDb.addObj(<apps>) on desktop-<actId>.
        3. ObjDb.addObj(<app-desktop>) on navigator-<actId>.
        4. LnkDb.addLnk(<root>, <app-desktop>, ...).
        5. LnkDb.addLnk(<app-desktop>, <apps>, ...).

Every step inside each install is idempotent. Running install
twice is a no-op (HstDb.getHst / ObjDb.getObj guards on every
add). Crashes mid-install are recovered by re-running.

10.2 ActManagerImpl.addAct integration

The root-sign-up branch of ActManagerImpl.addAct (the one that
runs when isRoot==true) calls the install routines in order:

  NavigatorInstall.install(actId, usrName, ...);
  QuippinInstall  .install(actId, usrName, ...);
  LoginInstall    .install(actId, usrName, ...);
  DesktopInstall  .install(actId, usrName, ...);

This replaces the existing ensureLoginSubHst / ensureDesktopSubHst
calls (whose hst-creation logic moves into LoginInstall /
DesktopInstall, step 1).

The order matters. NavigatorInstall must run first because the
other installs lnk into <root>. QuippinInstall normally runs
second because the existing addAct code path is "Quippin on the
Quippin central host" (the Quippin host record exists already by
the time we get here). Login and Desktop are independent of
each other.

For LINK-mode AddAct (the user is linking a new <usrId>@<appId>
to an existing actId via the Login app, [Login](Login.md) PART 7),
the navigator/quippin/login/desktop install routines are NOT
re-run. The actId already has its skeleton from the original
sign-up; link-mode just adds another account.

10.3 Legacy users (SQL backfill)

Dave (dave@quippin) and Micha (micha@quippin) were created before
the Navigator existed, so their object/link skeleton is missing.
mySQL/dump-2024-01-19-navigator.sql (NEW) creates everything
their AddAct's install routines would have written today:

  - host records:
      navigator                  -> tomcat1:8080, prv1
      navigator-<fingerprint>     -> tomcat1:8080, prv1
      navigator-micha@quippin    -> tomcat2:8080, prv2
  - objects on navigator-<actId>:
      root, app-navigator, app-quippin, app-login, app-desktop
      (per user, x2 = 10 objects)
  - objects on quippin-<actId>:
      follows, quips, bans, logs (per user, x2 = 8 objects)
  - objects on login-<actId>:
      logins (per user, x2 = 2 objects)
  - objects on desktop-<actId>:
      apps (per user, x2 = 2 objects)
  - lnks (per user):
      root -> app-* (4 lnks)
      app-quippin -> follows / quips / bans / logs (4 lnks)
      app-login -> logins (1 lnk)
      app-desktop -> apps (1 lnk)
      app-navigator -> (none)
      Total: 10 lnks per user, x2 = 20 links.

For every seeded follow row (dave -> micha), the SQL also writes:

  - <follows> -> <follow-micha@quippin>
  - <follow-micha@quippin> -> navigator-micha@quippin...root

Same for any seeded quip / ban / login / app. The seed walks
objects in the families "follow-", "quip-", "ban-", "log-", "login-",
"app-" and emits the parent->child + (where applicable)
cross-app lnks.

After this migration, dave@quippin's tree on first /quippin/
navigator load is fully populated - he can see his follow on
Micha and drill through to her sub-tree on prv2.

10.4 Apps' ongoing lnk maintenance

Once the skeleton is installed, every per-item op gains lnk
maintenance alongside its existing obj writes:

  FollowsImpl.follow
      Two addLnk calls per PART 5.3:
        (1) <follows> -> <new follow row>          tag=follow
        (2) <new follow row> -> <followee's nav root>
                                                   tag=followee

  FollowsImpl.unFollow
      Two deleteLnks calls (the symmetric pair).

  QuipsImpl.addQuip
      addLnk(<quips>, <new quip row>, ..., tag="quip", val=text-prefix)

  QuipImpl.removeQuipChild
      deleteLnks for the quip-> child edge AND
      deleteLnks for <quips> -> <removed quip>.

  BansImpl.ban / unban
      addLnk / deleteLnks between <bans> and the <ban row>.

  LogsImpl.add
      addLnk between <logs> and the <log row>. Cleanup is
      handled by LogsImpl.deleteLogs already; that op gains
      a matching deleteLnks call so dead lnks don't accumulate.

  LoginsImpl.recordLogin / forgetLogin / updateLoginRow
      addLnk / deleteLnks / (delete + add) between <logins> and
      the <login row>.

  AppsImpl.installApp / uninstallApp
      addLnk / deleteLnks between <apps> and the <app row>.

Each of these is one extra LnkDb call per existing op. None
changes any existing behaviour of the op.

## PART 11 — POST-LOGIN INTEGRATION (Desktop tile)

11.1 Catalog entry

The Navigator gets one tile in Desktop's seeded catalog
([Desktop](Desktop.md) PART 8). AppsImpl.seedDefaultCatalog grows a
third row:

  Obj seed for app-navigator
  --------------------------
    HstId       = desktop-<actId>
    AppId       = desktop
    ActId       = <actId>
    ObjId       = app-navigator
    ClsAppId    = desktop
    ClsId       = app
    ObjName     = "Navigator"
    ObjDesc     = "Browse your Domatar objects"
    Attrs       = {
      "DisplayName": "Navigator",
      "IconPath":    "/quippin/icons/navigator.svg",
      "LaunchPath":  "/quippin/navigator",
      "Position":    "3"
    }

The icon asset (navigator.svg, a stylised tree / file-manager
glyph) lives in src/main/webapp/icons/, like the other app icons.

11.2 Hst rows

Per PART 10.1 / 10.3 above. Summary:

  ('navigator',                'tomcat1:8080', 'prv1', ...),
  ('navigator-<fingerprint>',   'tomcat1:8080', 'prv1', ...),
  ('navigator-micha@quippin',  'tomcat2:8080', 'prv2', ...);

11.3 Docker compose

tomcat1 wears a fifth hat on domatar_net: alias "navigator"
alongside "domatar" / "tomcat1" / "quippin" / "login". One-line
edit to docker-compose.yml.

## PART 12 — SOURCE LAYOUT

New files:

  src/main/webapp/navigator.html
      The two-pane object browser. Same skeleton as desktop.html
      and account.html: jQuery, no Quippin side menu, custom CSS
      for the tree and attrs panes, AJAX to NavWui.

  src/main/webapp/icons/navigator.svg
      The Navigator's icon for Desktop. Square, transparent,
      rounded-square tile to match quippin.svg / login.svg.

  src/main/webapp/icons/cls/<clsAppId>/<clsId>.svg
      One file per (ClsAppId, ClsId) pair the Navigator will
      render. v1 ships icons for the classes listed in PART 6.3.

  src/main/java/com/navigator/webui/NavWui.java
      Wui servlet (PART 7).

  src/main/java/com/navigator/objimpl/NavRootImpl.java
      Root handler. Trivial subclass of ObjImpl.

  src/main/java/com/navigator/objimpl/NavAppImpl.java
      App handler. Trivial subclass of ObjImpl.

  src/main/java/com/navigator/install/NavigatorInstall.java
  src/main/java/com/quippin/install/QuippinInstall.java
  src/main/java/com/login/install/LoginInstall.java
  src/main/java/com/desktop/install/DesktopInstall.java
      Per-app install routines (PART 10.1). Each one is a small
      static method that idempotently writes the app's per-user
      host record, container objects, app-obj on navigator-<actId>,
      and skeleton lnks.

  mySQL/dump-2024-01-19-navigator.sql
      Legacy backfill (PART 10.3): host records + objects + skeleton
      lnks + per-item lnks for dave@quippin and micha@quippin.

Edits to existing files:

  src/main/java/com/domatar/util/ObjImpl.java
      Add the Open op alongside the existing GetObj op (PART 8.1).
      `openObj` returns obj details + lnks, and errors with
      "Obj not found" on null obj.

  src/main/java/com/quippin/objimpl/FollowsImpl.java
  src/main/java/com/quippin/objimpl/QuipsImpl.java
  src/main/java/com/quippin/objimpl/BansImpl.java
  src/main/java/com/quippin/objimpl/LogsImpl.java
  src/main/java/com/quippin/objimpl/NewsImpl.java
  src/main/java/com/quippin/objimpl/QuipImpl.java
  src/main/java/com/quippin/objimpl/SentimentImpl.java
  src/main/java/com/quippin/objimpl/SentimentsImpl.java
  src/main/java/com/desktop/objimpl/AppsImpl.java
  src/main/java/com/login/objimpl/LoginsImpl.java
  src/main/java/com/domatar/act/ActManagerImpl.java
  src/main/java/com/domatar/act/ActCacheImpl.java
  src/main/java/com/domatar/hst/HstsImpl.java
      Replace the final "Unknown operation" branch in handleMsg
      with `return super.handleMsg(...)`. Mechanical refactor
      (PART 8.2). No other change.

      In addition, the container/per-item impls
      (FollowsImpl, QuipsImpl, QuipImpl, BansImpl, LogsImpl,
      LoginsImpl, AppsImpl) get one extra LnkDb.addLnk /
      deleteLnks call per add/remove op (PART 5.3 / 10.4).
      FollowsImpl gets two: one for the parent-child container
      lnk and one for the cross-app follow->followee-root lnk.

  src/main/java/com/domatar/core/ImplMap.java
      Register (navigator, root) -> NavRootImpl and
      (navigator, app) -> NavAppImpl. No new entry for follow /
      quip / etc. (default ObjImpl is enough; PART 8.4).

  src/main/java/com/domatar/act/ActManagerImpl.java
      Replace ensureLoginSubHst() / ensureDesktopSubHst() with
      calls to NavigatorInstall / QuippinInstall / LoginInstall
      / DesktopInstall (PART 10.2). The link-mode AddAct branch
      does NOT call these (PART 10.2).

  src/main/java/com/desktop/objimpl/AppsImpl.java
      seedDefaultCatalog() grows a third row for app-navigator.

  src/main/webapp/WEB-INF/web.xml
      <servlet>/<servlet-mapping> entries for /navigator ->
      navigator.html, mirroring /desktop and /account.

  docker-compose.yml
      Add "navigator" to tomcat1's domatar_net aliases.

  ObjId-family heuristic table inside NavWui (PART 7):

    follow-*    -> ("quippin", "follow")
    quip-*      -> ("quippin", "quip")
    ban-*       -> ("quippin", "ban")
    log-*       -> ("quippin", "log")
    sentiment-* -> ("quippin", "sentiment")
    app-*       -> ("desktop", "app")
    apps        -> ("desktop", "apps")
    follows / quips / bans / logs -> ("quippin", <objId>)
    logins      -> ("login", "logins")
    login-*     -> ("login", "login")
    root        -> ("navigator", "root")
    app-quippin / app-login / ...    -> ("navigator", "app")

## PART 13 — DISPATCH TRACE (worked example)

Dave is logged in on prv1. He clicks the Navigator tile on his
Desktop, which opens /quippin/navigator in a new tab.

  1. Browser GETs /quippin/navigator -> navigator.html.

  2. navigator.html on load:
       Compute rootDomId = "navigator-" + actId + ".navigator." +
                           actId + ".root".
       Render one tree row labelled with usrName (read from the
       cookie's "usrId"; updated to obj.objName after the first
       Open) and addressed at rootDomId. Mark it expanded by
       default. Issue the first Open immediately:
         POST /quippin/NavWui  { Action=Open, DomId=rootDomId }

  3. NavWui (running on tomcat1 / prv1):
       - DomatarServlet outer dispatch verifies Dave's session.
       - Builds:
           srcDomId = (prv1, navigator, dave@quippin, NavWui)
           dstDomId = the rootDomId
           clsId    = (navigator, root)
       - msgClient.send(dstDomId, msg).

  4. HttpClient.dispatch:
       - dst.hstId = "navigator-<fingerprint>", != DOMATAR_HSTID,
         self-dispatch shortcut does not fire.
       - Directory hit: PrvId=prv1, Domain=tomcat1:8080.
       - PrvId == DOMATAR_HSTID, sendLocal path.

  5. NavRootImpl inherits ObjImpl.handleMsg, which routes Open
     to ObjImpl.openObj:
       - obj = the seeded root row (per PART 10).
       - obj.getLnks(500) returns the four nav skeleton lnks
         to app-quippin, app-login, app-desktop, app-navigator.
       - Response: Attrs.DomId=rootDomId, Attrs.ObjName="David B.",
         Attrs.ObjDesc="Root", Attrs.Attrs={}, Attrs.Lnks=[...4...].

  6. Browser caches the response on the root tree-row's <li>,
     refreshes the row's label to "David B.", renders four child
     rows (Quippin, Login, Desktop, Navigator), and shows the root's
     details in the right pane.

Dave clicks '+' on Quippin:

  7. Browser POSTs Action=Open, DomId=navigator-<fingerprint>
     .navigator.dave@quippin.app-quippin.

  8. sendLocal again. NavAppImpl inherits openObj.
     - obj = the seeded app-quippin row.
     - getLnks(500) returns four lnks to <quippin-dave@quippin
       follows / quips / bans / logs>.

  9. Browser caches, renders four children (Follows, Quips, Bans,
     Logs).

Dave clicks '+' on Follows:

 10. Browser POSTs Action=Open, DomId=quippin-dave@quippin
     .quippin.dave@quippin.follows.

 11. sendLocal (still on prv1). FollowsImpl inherits openObj.
     - obj = the seeded follows container row on quippin-dave@quippin,
       written by QuippinInstall.install (PART 10.1).
     - LnkDb.getLnks(<this DomId>, ...) returns one lnk per
       follow Dave maintains, populated by FollowsImpl.follow at
       follow time (PART 5.3 / PART 10.4).
     - Suppose Dave follows Micha: one lnk
         lnkDomId = quippin-dave@quippin.quippin.dave@quippin
                    .follow-micha@quippin
         lnkObjName = "Michael B."
         lnkClsAppId = "quippin", lnkClsId = "follow"
         tag = "follow", val = "micha@quippin".

 12. Browser caches; renders one child labelled "Michael B."
     under "Follows".

Dave clicks '+' on Michael B.:

 13. Browser POSTs Action=Open, DomId=quippin-dave@quippin
     .quippin.dave@quippin.follow-micha@quippin.

 14. sendLocal. (quippin, follow) has no entry in ImplMap, so
     dispatch lands on bare ObjImpl.openObj.
       - obj = the follow row, written by FollowsImpl.follow.
       - getLnks(500) returns the one persistent
         <follow row> -> <navigator-micha@quippin...root> lnk
         that FollowsImpl.follow wrote alongside the row
         (PART 5.3).

 15. Browser renders one child under Michael B. labelled
     "Michael B." again (lnkObjName = followee's display name).

Dave clicks '+' on that drill-through Michael B.:

 16. Browser POSTs Action=Open, DomId=navigator-micha@quippin
     .navigator.micha@quippin.root.

 17. NavWui dispatches; HttpClient.dispatch resolves
     navigator-micha@quippin via the directory: PrvId=prv2,
     Domain=tomcat2:8080. Cross-prv POST tomcat2:8080/quippin/Msg.

 18. Msg.doAction on prv2:
       - verifyAndStamp runs LoginRemote.verifyLogin against the
         navigator central host on prv1, which says yes.
       - obj = Micha's seeded root on prv2.
       - getLnks(500) returns Micha's four nav skeleton lnks.
       - Response travels back.

 19. Browser renders Micha's four apps under his Michael B. node
     in Dave's tree.

Every cross-prv hop is one Open call. Every authorization decision
is one verifyLogin (cached by Msg.verifyAndStamp). The UI does
not need to know which prv is answering.

## PART 14 — TODO

  - Per-row impls for tighter authorization on individual obj
    rows. Today FollowImpl / QuipImpl / BanImpl / LogImpl /
    LoginImpl / AppImpl are NOT registered in ImplMap; Open on
    a single row falls through to bare ObjImpl whose hasRights
    is the framework default ("any verified caller"). Tighter
    policy (e.g. "only the owner sees their bans") requires
    these subclasses with hasRights overrides.

  - User-driven InstallApp / UninstallApp. v1 hard-codes which
    apps the install routine runs at AddAct (Navigator, Quippin,
    Login, Desktop). v2 surfaces InstallApp(actId, appId) /
    UninstallApp(actId, appId) as a real op (probably on the
    Navigator central host) so the Desktop's "+" tile becomes
    a real install, and uninstall removes the app's per-user
    sub-host + objects + lnks.

  - Owner-match in hasRights. NavRootImpl/NavAppImpl run with
    verified-only in v1; tighten to inMsg.getContext().actId ==
    dst.actId on every Navigator class.

  - Per-app manifest. PART 5.2 hard-codes which directories each
    app exposes; PART 10.1 hard-codes which apps the install
    chain calls. Replace both with a manifest each app declares
    (e.g. a static method ImplMap reads, or a "manifest" obj
    per app on the central host) so adding an app is one new
    file plus one ImplMap registration, with no edits in
    ActManagerImpl.addAct.

  - Generic "follow Target" semantic. The Open response includes
    arbitrary obj attrs; extend the right pane's DomId-link
    rendering (PART 6.6) so that any attr whose value parses as
    a DomId becomes navigable from the right pane, regardless of
    which app produced it.

  - Address bar / breadcrumbs / search box. The current tree has
    only +/- expansion; v2 lets the user type or paste a DomId
    and have the UI scroll to it; ditto for searching by ObjName.

  - Edit / create / delete from the Navigator. v1 is read-only.
    Inline rename of ObjName, deletion, and drag-to-move (which
    rewrites lnks) come next; they are single-op rewrites
    against ObjDb / LnkDb.

  - Drilling into login.login rows. PART 5.4 lists this;
    LoginsImpl.recordLogin should write a persistent lnk from
    the new <login row> to the account at the linked appId's
    central host (mirroring how FollowsImpl.follow already
    writes the <follow row> -> <followee root> lnk).

  - Cross-prv pre-fetch. PART 9.3; every '+' click costs a
    round-trip today. v2 batches the first-N children eagerly
    when a parent is rendered.

  - Pagination on Open. DONE (PART 4.1). MaxLnks caps the page and
    the optional StartSeqNum param drives a SeqNum cursor; the reply
    carries HasMore plus NextSeqNum so callers can walk thousands of
    children one page at a time. v2 work that remains is purely UI:
    the Navigator tree does not yet auto-fetch the next page when a
    parent has HasMore=true.

  - HasChildren accuracy. v1 always renders '+' on unopened
    nodes (PART 4.4); v2 should cheap-count children on the
    server and let Open carry an optional "ChildCount" hint.

  - System-wide app catalog reflected in the Navigator. PART 5.2
    today seeds the four current apps; once a real
    apps-available-on-this-prv directory exists ([Desktop](Desktop.md)
    PART 11 TODO), the Navigator should pick up new apps
    automatically.
