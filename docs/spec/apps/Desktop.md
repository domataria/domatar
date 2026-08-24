# SPEC: DESKTOP - the Domatar home-screen app

## PART 1 — PURPOSE

The Desktop is a Domatar app whose UI is the user's home screen. It is
the page the browser lands on immediately after a successful login.
Desktop renders a grid of icons, one per Domatar app the user has
installed; clicking an icon navigates the browser to that app's own
landing page.

Desktop is intentionally minimal. It owns no application data of its
own (no quips, no spreadsheets, no orders); its only job is to present
a launcher for the apps that DO own data. Future Domatar apps, once
they exist, are expected to register an icon on every user's Desktop
the same way Quippin does in PART 8 below:

  - Navigator    - browses the user's Domatar objects.
  - Spreadsheet  - cells, formulas, etc.
  - Amazon       - a front end to amazon.com.
  - ...

Launcher inventory is GetUserApps from the platform userApps object
([Platform App](../platform/Platform-App.md)). Desktop tiles are a
presentation cache, not the authority. Shell chrome (which app is
login / desktop / navigator) uses GetShells.

## PART 2 — APP IDENTITY

  appId          : "desktop"
  per-user host  : desktop-<actId>-<prvId>
  hosting prv    : the login-home provider that owns this replica

Every login-home replica has a Desktop sub-host. The naming convention
`<appId>-<actId>-<prvId>` is the same as other shell apps
([Login protocol](Login-Protocol.md)):

  host record       hstId=desktop-<actId>-prv1   prvId=prv1
  host record       hstId=desktop-<actId>-prv2   prvId=prv2

Because every Desktop request is addressed to the calling user's OWN
desktop-<actId>-<prvId> replica, HttpClient.dispatch routes same-provider
calls via sendLocal. Cross-provider tile sync is PART 11 (content sync).

## PART 3 — DATA MODEL

The Desktop reuses the same obj-table conventions Quippin uses (see
[Domatar](../Domatar.md) PART 4): one singleton container per logical collection,
plus one row per item in the container, all keyed by an ObjId of the
form "<family>-<suffix>".

Each home provider holds a replica at desktop-<actId>-<prvId>.
Sync semantics (TileVersion / Tombstone / container Version /
ReconcileApps) follow in PART 4–8.

3.1 Container

  HstId    = desktop-<actId>-<prvId>
  AppId    = desktop
  ActId    = <actId>
  ObjId    = apps                    (singleton, like quippin's "follows")
  ClsAppId = desktop
  ClsId    = apps
  ObjName  = "Apps"
  ObjDesc  = "Installed applications"
  Attrs    = {
    "Version": "<max TileVersion>"   (changed-since probe; [Desktop](Desktop.md)
                                      PART 6.3)
  }

3.2 Per-app rows

  HstId    = desktop-<actId>-<prvId>
  AppId    = desktop
  ActId    = <actId>
  ObjId    = app-<appId>             (e.g. app-quippin, app-navigator)
  ClsAppId = desktop
  ClsId    = app
  ObjName  = <DisplayName>           (e.g. "Quippin")
  ObjDesc  = <one-liner>             (e.g. "Microblog and follow feed")
  Attrs    = {
    "DisplayName": "Quippin",
    "IconPath":    "/quippin/icons/quippin.svg",
    "LaunchPath":  "/quippin/news",
    "Position":    "1",
    "TileVersion": "<base64 time>",
    "Tombstone":   "False"           ("True" after UninstallApp)
  }

Notes on the attrs:

  - DisplayName : human-readable label rendered under the icon.
  - AppUrl      : optional browser base for this app (`http://host/domatar`
                  or a vanity origin). Empty means inherit the offering
                  provider ([Icons](../platform/Icons.md) PART 7.2). Changeable
                  after install via SetAppUrl. GetUserApps derives
                  IconPath and LaunchPath from the resolved base.
  - IconPath    : same-origin path (`/domatar/<appId>/icons/app.svg`) or
                  a full http(s) URL to the offering provider's icon for
                  cross-provider marketplace tiles ([Icons](../platform/Icons.md)
                  PART 7.2). The icon asset itself is owned by the target
                  app's JAR. Desktop never bundles other apps' icons; it
                  renders `<img src=IconPath>` (or AppUrl + convention)
                  and on error falls back to
                  `/domatar/domatar/icons/cls/default/obj.svg`.
  - LaunchPath  : the URL the browser is sent to when the icon is
                  clicked. Derived from AppUrl when set. Marketplace
                  cross-provider installs store an absolute URL on the
                  offering provider's BrowserOrigin or PublicDomain
                  ([App Store](AppStore.md) PART 11).
  - HostPrvId / AppHstId (optional): offering provider and portable
                  app host id for marketplace installs
                  ([App Store](AppStore.md) PART 11). Emitted by GetApps /
                  PullApps when present.
  - Position    : install-time sort hint on app rows. Desktop
                  launcher order is TileOrder on the apps container
                  (GetTileOrder / SetTileOrder), not this attribute.
                  Still emitted by GetApps / PullApps for sync and for
                  secondary ordering of newcomers not yet in TileOrder.
                  String, zero-padded later if needed; for v1 a small
                  integer is fine.
  - TileVersion / Tombstone : multi-provider LWW sync stamps
                  ([Desktop](Desktop.md) PART 4 / 6).

ObjId family. "app-" sorts at 'a', well below "log-" at 'l', so the
LogsImpl purge bug fixed in [Domatar](../Domatar.md) would have eaten these rows
before. Now that ObjDb.deleteOldObjs takes an explicit prefix, no
purge will ever touch the desktop family unless something explicitly
asks to purge "app-" rows.

## PART 4 — WEB UI

  /<context>/desktop
      The home-screen page. Renders the icon grid by calling AppsWui
      with Action=GetApps. JSP-mapped to desktop.html via web.xml,
      exactly like /<context>/news -> news.html.

  /<context>/AppsWui
      The single Wui endpoint backing the home-screen page (PART 5).

  /<context>/icons/<appId>.svg
      Static icon assets, one per app. Each app contributes its own
      file. For v1 only quippin.svg ships.

  <context> note. Today every Domatar app shares a single WAR mounted
  at /quippin/, so the actual URLs are /quippin/desktop, /quippin/AppsWui,
  and /quippin/icons/quippin.svg. Once the per-host context-path TODO
  in [Domatar](../Domatar.md) PART 11 lands, Desktop will move to /desktop/... and
  ship as its own WAR. The   IconPath/LaunchPath strings stored on those objects are fully-qualified absolute URLs, so they don't change
  shape when that move happens - only the Desktop pages themselves
  relocate.

4.1 desktop.html flow

  On load:

    1. POST UserSubstrateWui { Action: "GetShells" } and
       POST AppsWui { Action: "GetUserApps" } (fallback: GetApps)
       for launcher inventory. Shell AppIds are painted in the same
       grid (Account / Desktop / App Store / Navigator). GetShells
       must still return existing shell rows when provider-config
       {@code PrvActId} is unset (stock DefaultShells; do not fail
       the launcher).
    2. POST AppsWui { Action: "GetTileOrder" } for Desktop-owned
       layout (CSV AppIds on the apps container).
    3. Merge for display only: keep saved AppIds that are still in
       inventory (relative order preserved); append missing AppIds at
       the end (stable secondary order by Position then AppId). If the
       merged list differs from saved TileOrder, eagerly
       SetTileOrder. Do not write shell/userApps Position for layout.
    4. Render the ordered Apps[] as a grid of
         <a draggable target="_blank" rel="noopener" href=LaunchPath>...
       Drag-and-drop (including shells) reorders the grid and eagerly
       SetTileOrder; on save failure the previous order is restored.

  No live polling. Clicking an icon is a normal anchor navigation to
  LaunchPath, with target="_blank" so the destination app opens in a
  new browser tab and the Desktop stays available in the original tab.
  (rel="noopener" prevents the spawned tab from inheriting a
  window.opener handle - standard hardening for _blank links.)
  Install/uninstall change inventory elsewhere; Desktop picks that up
  on the next paint (no InstallApp/Uninstall hooks for layout).

## PART 5 — WUI SERVLET (AppsWui)

  com.desktop.webui.AppsWui  @WebServlet("/AppsWui/*")
  extends com.domatar.servlet.DomatarServlet

Mirrors FollowsWui in shape. Per-action request handling:

  Action=GetApps
      Builds dst = DomId(desktop-<srcActId>, desktop, <srcActId>, apps)
      addRequestBody("GetApps", null)
      addClsId("desktop", "apps")

  Action=GetTileOrder
      Same dst as GetApps.
      addRequestBody("GetTileOrder", null)
      addClsId("desktop", "apps")
      Reply Attrs: AppIds (CSV), Version (TileOrderVersion stamp)

  Action=SetTileOrder
      Body param: AppIds (comma-separated AppId list)
      Same dst as GetApps.
      addRequestBody("SetTileOrder", attrs)
      addClsId("desktop", "apps")
      Persists TileOrder + TileOrderVersion on the apps container;
      does not bump the sync Version used by PullApps/MergeApps.

  Action=InstallApp
      Body params: AppId, DisplayName, IconPath, LaunchPath, Position
      Same dst as GetApps.
      addRequestBody("InstallApp", attrs)
      addClsId("desktop", "apps")

  Action=UninstallApp
      Body params: AppId
      Same dst as GetApps.
      addRequestBody("UninstallApp", attrs)
      addClsId("desktop", "apps")

Like every other Wui servlet, AppsWui does NOT verify credentials
itself - DomatarServlet's outer dispatch already does that, populating
the Context that the framework will then carry into AppsImpl.

## PART 6 — OBJ HANDLER (AppsImpl)

  com.desktop.objimpl.AppsImpl  extends com.domatar.util.ObjImpl

Routed to by ImplMap entry (desktop, apps) -> AppsImpl.

6.1 Authorization

  Owner-match ([Desktop](Desktop.md) PART 9 / D8):

    public boolean hasRights(JsonMsg inMsg, Obj obj, DomatarMsgClient msgClient)
    {
      if (!Auth.isVerified(inMsg))
        return false;
      Context ctx = inMsg.getContext();
      DomId   dst = inMsg.getDstId();
      return ctx != null && ctx.actId != null && dst != null
          && ctx.actId.equals(dst.actId);
    }

  Cross-provider PullApps / MergeApps carry the existing credential chain
  in Head.Sec ([Security](../platform/Security.md) PART 6); no new published record.

6.2 Operations

  GetApps(opr, inMsg, outMsg)
      dst = inMsg.getDstId()
      objs = ObjDb.getObjPrefix(dst.hstId, "desktop", dst.actId, "app", null, 1000)
      Skip rows with Tombstone=True. For each live obj, pull DisplayName,
      IconPath, LaunchPath, Position, TileVersion from obj.attrs. Build a
      JsonArrayList of JsonMaps in Position order. Reply: { "Apps": [...] }.

      LAZY BOOTSTRAP. Seed the default catalog (PART 8) when needed and
      re-read. Newly added default tiles appear for users who already had
      an older catalog.

  GetAppsVersion / PullApps / MergeApps / ReconcileApps
      [Desktop](Desktop.md) PART 8 / 7. Sync ops; PullApps includes
      tombstones; MergeApps LWW-skips intrinsic {login,navigator,desktop}.

  GetTileOrder(opr, inMsg, outMsg)
      Desktop UI layout only. Reads attrs TileOrder (CSV AppIds) and
      TileOrderVersion from the apps container. Does not consult
      userApps or shells. Reply: { "AppIds": "...", "Version": "..." }.

  SetTileOrder(opr, inMsg, outMsg)
      Body: AppIds = comma-separated AppId list (deduped, trimmed).
      Writes TileOrder + TileOrderVersion (IdGen stamp) on the apps
      container. Does not bump sync Version / does not rewrite app-row
      Position. Lazy-seeds the apps container if missing (same path as
      GetApps). Reply: { "AppIds": normalized, "Version": stamp }.

  InstallApp(opr, inMsg, outMsg)
      dst = inMsg.getDstId()
      attrs = inMsg.getAttrs()
      appId       = attrs.getAttr("AppId")
      displayName = attrs.getAttr("DisplayName")
      iconPath    = attrs.getAttr("IconPath")
      launchPath  = attrs.getAttr("LaunchPath")
      position    = attrs.getAttr("Position")

      objId = IdGen.createId("app", appId)
      domId = new DomId(dst.hstId, "desktop", dst.actId, objId)

      Build Obj with class (desktop, app), attrs above, plus TileVersion=now
      and Tombstone=False. Upsert. Bump apps container Version. Best-effort
      fan-out to peer replicas ([Desktop](Desktop.md) PART 7.2).
      Reply: { "Installed": "True" }.

  UninstallApp(opr, inMsg, outMsg)
      Soft-delete: set Tombstone=True + fresh TileVersion (do NOT physically
      delete the row — [Desktop](Desktop.md) PART 6.4). Bump
      container Version; best-effort fan-out. Reply: { "Installed": "False" }.

6.3 Dispatch

  Every Desktop call addresses dst.hstId = desktop-<actId>-<localPrvId>
  (DomId.localSubHstId), whose host record points at the user's home prv,
  which is also the prv serving the request - so HttpClient.dispatch
  takes the sendLocal path on the GetApps hot path. Cross-prv traffic
  happens only during ReconcileApps / fan-out.

## PART 7 — POST-LOGIN REDIRECT

After a successful login the iframe posts `GoTo` with
`/domatar/desktop/desktop.html`. `login.html` allows that path (and
the context-stripped `/desktop/desktop.html` used behind nginx) and
`location.replace`s a URL that keeps the WAR context when the
browser is already under `/domatar/`. Direct Tomcat
(`http://localhost:9080/domatar/...`) therefore lands on
`/domatar/desktop/desktop.html`; the nginx front door that strips
`/domatar` lands on `/desktop/desktop.html`.

The whitelist exists to stop a compromised iframe from turning the
login page into an open redirector.

## PART 8 — INITIAL APP CATALOG

For v1 the catalog has TWO entries, Quippin and the Login app
(see [Login](Login.md) PART 11 for the Login app's icon spec).
Lazy bootstrap in AppsImpl.GetApps (PART 6.2) materialises both
rows the first time a user visits the Desktop:

  Obj seed for app-quippin
  ------------------------
    HstId       = desktop-<actId>
    AppId       = desktop
    ActId       = <actId>
    ObjId       = app-quippin
    ClsAppId    = desktop
    ClsId       = app
    ObjName     = "Quippin"
    ObjDesc     = "Microblog and follow feed"
    Attrs       = {
      "DisplayName": "Quippin",
      "IconPath":    "/quippin/icons/quippin.svg",
      "LaunchPath":  "/quippin/news",
      "Position":    "1"
    }

  Obj seed for app-login
  ----------------------
    HstId       = desktop-<actId>
    AppId       = desktop
    ActId       = <actId>
    ObjId       = app-login
    ClsAppId    = desktop
    ClsId       = app
    ObjName     = "Account"
    ObjDesc     = "Manage your Domatar identity"
    Attrs       = {
      "DisplayName": "Account",
      "IconPath":    "/login/icons/login.svg",
      "LaunchPath":  "/login/account",
      "Position":    "2"
    }

The DisplayName is "Account" (not "Login") because by the time the
icon is reachable the user IS logged in - the icon's job is to take
them to the manage page, not back to a sign-in form.

The icon asset for Quippin lives in the Quippin webapp:

  src/main/webapp/icons/quippin.svg

The icon asset for the Login app lives in the Login webapp:

  src/main/webapp/icons/login.svg

A simple vector mark - a stylised speech bubble or a "Q" - is fine
for v1. Important properties:

  - Square aspect ratio (the grid renders in a fixed square cell).
  - Transparent background (Desktop renders on a dark page).
  - No text outside the mark (the DisplayName is rendered as a
    separate label by Desktop).
  - SVG with viewBox so the icon scales cleanly to whatever cell
    size Desktop picks.

## PART 9 — SOURCE LAYOUT

New files:

  src/main/webapp/desktop.html
      The home-screen page. Same skeleton as follows.html: jQuery,
      sideMenu, single AJAX call to AppsWui, render result.

  src/main/webapp/icons/quippin.svg
      Quippin's icon asset. Owned by Quippin, served by Quippin.

  src/main/java/com/desktop/webui/AppsWui.java
      Wui servlet (PART 5).

  src/main/java/com/desktop/objimpl/AppsImpl.java
      ObjImpl handler (PART 6).

Edits to existing files:

  src/main/webapp/WEB-INF/web.xml
      Add a servlet entry mapping /desktop -> desktop.html, mirroring
      the existing News mapping:

        <servlet>
          <servlet-name>Desktop</servlet-name>
          <jsp-file>/desktop.html</jsp-file>
        </servlet>
        <servlet-mapping>
          <servlet-name>Desktop</servlet-name>
          <url-pattern>/desktop/*</url-pattern>
        </servlet-mapping>

  src/main/java/com/domatar/util/ImplMap.java   (or wherever the
                                                 temporary class
                                                 registry lives)
      Register (desktop, apps) -> com.desktop.objimpl.AppsImpl.

  src/main/webapp/login.html
      Update allowedRedirects (PART 7).

  src/main/webapp/remoteLogin.html
      Update the post-login GoTo value (PART 7).

  mySQL/dump-*.sql  (optional, for the docker-compose seed)
      Add per-user host records for desktop-<actId>:

        INSERT INTO hst VALUES
          ('desktop-dave@quippin',  'localhost', 'prv1', 1, 0),
          ('desktop-micha@quippin', 'localhost', 'prv2', 1, 0);

      The per-user "apps" container and the seed app-quippin row
      do NOT need to live in the SQL seed - lazy bootstrap in
      AppsImpl.GetApps (PART 6.2) materialises them on first read.

## PART 10 — DISPATCH TRACE (worked example)

Dave logs in at http://prv1.local:8080/quippin/login. Login succeeds,
remoteLogin.html posts GoTo=desktop, login.html replaces location to
/quippin/desktop. The browser GETs /quippin/desktop, which web.xml
maps to desktop.html. desktop.html on load:

  1. POST /quippin/AppsWui  with body  Action=GetApps

  2. AppsWui (running on tomcat1 / prv1):
       - DomatarServlet outer dispatch verifies Dave's session token
         against the local account store -> Context.verified=true,
         actId=dave@quippin.
       - AppsWui.getMsg builds:
           srcDomId = (prv1, desktop, dave@quippin, AppsWui)
           dstDomId = (desktop-dave@quippin, desktop, dave@quippin, apps)
           clsId    = (desktop, apps)
       - msgClient.send(dstDomId, msg).

  3. HttpClient.dispatch:
       - sendDomId.hstId = "desktop-dave@quippin", != DOMATAR_HSTID,
         so the self-dispatch shortcut does not fire.
       - getHst("desktop-dave@quippin") returns Hst(Domain=tomcat1:8080,
         PrvId=prv1).
       - PrvId=prv1 matches DOMATAR_HSTID, so HttpClient takes the
         sendLocal path - no HTTP hop.

  4. ImplMap (desktop, apps) -> AppsImpl. Msg.doAction loads the apps
     container obj (or null on first hit), invokes AppsImpl.handleMsg.

  5. AppsImpl.GetApps queries
       ObjDb.getObjPrefix("desktop-dave@quippin", "desktop",
                          "dave@quippin", "app", null, 1000)
     gets [] on the very first call, lazy-bootstraps app-quippin
     (PART 6.2 / PART 8), re-queries, returns
       { Apps: [ { DisplayName:"Quippin",
                   IconPath:"/quippin/icons/quippin.svg",
                   LaunchPath:"/quippin/news",
                   Position:"1" } ] }

  6. desktop.html renders the grid; the user sees the Quippin icon.
     Click -> normal navigation to /quippin/news -> Quippin takes
     over.

## PART 11 — TODO

  - Eager catalog seeding at AddAct time. Today AppsImpl.GetApps
    bootstraps the apps container on first read. Once AddAct knows
    about Desktop it should create the desktop-<actId> host record, the
    apps container, and the default app rows in one transaction. The
    lazy path then becomes a defensive fallback rather than the
    primary install mechanism.
  - System-wide app catalog. Today the install rows are hard-coded
    in AppsImpl's lazy seed. The next step is a directory-style
    catalog of "apps available on this prv", from which the user
    picks via an /<context>/apps install page (mirroring follows.html
    in shape). InstallApp/UninstallApp already exist for this; only
    the picker UI is missing.
  - Drag-and-drop ordering. IMPLEMENTED: Desktop-owned TileOrder /
    TileOrderVersion on the apps container via GetTileOrder /
    SetTileOrder. desktop.html merges inventory with saved order
    (filter + append newcomers) and eagerly persists on display merge
    and on drag. Shells are reorderable. Layout does not write
    userApps/shells Position and does not bump sync Version.
  - Per-app context paths. As soon as [Domatar](../Domatar.md) PART 11's per-host
    context-path entry in the directory lands, move Desktop out of
    the shared /quippin/ WAR into /desktop/. The IconPath and
    LaunchPath strings already use absolute paths, so existing rows
    survive that move untouched.
  - Owner-match in hasRights. Today any verified caller may invoke
    GetApps/InstallApp/UninstallApp on any Desktop sub-host, exactly
    the same hole FollowsImpl has. Tighten to require
    inMsg.getContext().actId == dstDomId.actId.
  - Cross-prv app-state, if a future app needs to surface anything
    on Desktop beyond a static icon (e.g. "3 unread"). For now
    Desktop is read-once, so this is not yet a real requirement.

## Desktop content sync

The Desktop launcher is replicated across a user's login-home providers
so the home screen is the same no matter which provider they signed in
through, and so the launcher survives the loss of any one provider.

GetUserApps is authoritative for which apps are installed
([Platform App](../platform/Platform-App.md)). Tiles on
`desktop-<actId>-<prvId>` are a presentation cache. Shell chrome uses
GetShells. Membership ([Login protocol](Login-Protocol.md)) says which
providers to sync with. Cross-provider writes verify the credential
chain including the ownId binding ([Identifiers](../platform/Identifiers.md)
PART 11). Tiles live on `desktop-<actId>-<prvId>` (navigator roots on
`navigator-<actId>-<prvId>`).

## PART 1 - PURPOSE

1.1  Goals

  D1  IDENTICAL LAUNCHER. Every home provider holds a Desktop replica;
      the tile sets converge so the user's home screen looks the same on
      each provider.

  D2  REDUNDANCY (not failover). If a provider is unavailable, the user
      signs in elsewhere ([Login protocol](Login-Protocol.md) R1) and finds their
      Desktop already there. Losing a provider permanently loses no
      launcher state, because every other provider has a copy.

  D3  MINIMAL, LOCAL-FIRST. The Desktop hot path stays local
      ([Desktop](Desktop.md) PART 2); peers are contacted only to reconcile,
      and only at natural moments (on display), never by a background
      daemon.

1.2  Non-goals

  * Strong consistency. Replicas are EVENTUALLY consistent (PART 6, 11).
  * Automatic failover / promotion. None (D2).
  * Replicating app DATA behind the tiles (that is per-app work; see
    [Login protocol](Login-Protocol.md) PART 4.3). This spec replicates the launcher
    (which tiles, their labels, icons, positions, install/uninstall
    state) ONLY.
  * Discovering the provider set itself. That is owned by
    [Login protocol](Login-Protocol.md); this spec asks for it (PART 5).
  * Syncing the intrinsic system-app tiles (Login, Navigator, Desktop).
    These are per-provider: seeded locally on every replica and EXEMPT from
    tile sync (PART 4.4). Each desktop shows its OWN login/navigator/desktop
    pointing at that provider's local objects; redundancy is surfaced INSIDE
    each app, not as synced tiles.

## PART 2 - RELATIONSHIP TO THE BASE DESKTOP

The base Desktop ([Desktop](Desktop.md)) already defines:
  * a per-user sub-host desktop-<actId> on the user's home provider;
  * an "apps" container obj and one "app-<appId>" tile row per installed
    app, carrying DisplayName / IconPath / LaunchPath / Position
    ([Desktop](Desktop.md) PART 3). Marketplace installs may also store
    HostPrvId / AppHstId and an absolute LaunchPath ([App Store](AppStore.md)
    PART 11 / KD8); sync preserves those attrs with the tile.

This spec makes three changes to that base:
  (a) the sub-host becomes provider-qualified, desktop-<actId>-<prvId>,
      one replica per home provider (PART 3);
  (b) each tile row gains a version stamp and a tombstone flag so tiles
      can be merged and deleted across replicas (PART 4);
  (c) rendering the Desktop triggers a reconcile against peer replicas
      (PART 6, 7).

Everything else about the base Desktop (the icon grid, GetApps rendering,
InstallApp/UninstallApp semantics) is unchanged in shape.

The sibling Navigator host is provider-qualified in the same way,
navigator-<actId>-<prvId> (PART 3.1), because it too is an intrinsic
per-provider surface (PART 4.4).

## PART 3 - HOST NAMING (provider-qualified replicas)

Each home provider holds exactly one Desktop replica:

    desktop-<actId>-<prvId>

resolved through the directory to that one provider, preserving "one
hstId -> one location" ([Login protocol](Login-Protocol.md) PART 5). Parsing is
unambiguous for the same reasons (fixed 32-char actId, '-'-free appId and
prvId; [Login protocol](Login-Protocol.md) PART 5.2).

Local replica. On the provider the user is signed in to, the Desktop is
desktop-<actId>-<DOMATAR_HSTID>. GetApps therefore reads local rows via
sendLocal - the [Desktop](Desktop.md) PART 2 hot-path property is preserved.
Peer replicas (desktop-<actId>-<otherPrv>) are reached by ordinary
directory-routed messages during reconcile (PART 7), NOT by any bypass.

One replica per PROVIDER, not per peer. Peers (app-logins) may share a
provider ([Login protocol](Login-Protocol.md) PART 3.2); they share that provider's
single Desktop replica. The sync target set is the DISTINCT providers
(PART 5.2).

3.1  The Navigator sub-host is provider-qualified too

Because the Navigator is likewise an intrinsic per-provider surface
(PART 4.4), its sub-host is ALSO provider-qualified:

    navigator-<actId>-<prvId>

one per home provider, resolved through the directory the same way.
Each provider serves its own navigator roots locally so the tree
survives loss of any one provider. The Navigator tree is NOT
content-merged across providers (PART 4.4); each replica shows that
provider's own roots and the user drills down per provider.

The three intrinsic sub-hosts:

    login-<actId>-<prvId>      ([Login protocol](Login-Protocol.md) PART 5)
    desktop-<actId>-<prvId>    (this spec, PART 3)
    navigator-<actId>-<prvId>  (this spec, PART 3.1)

## PART 4 - DATA MODEL (versioned, tombstoned tiles)

4.1  Container (per replica)

    HstId    = desktop-<actId>-<prvId>
    AppId    = desktop
    ActId    = <actId>
    ObjId    = apps                       (singleton, as [Desktop](Desktop.md))
    ClsAppId = desktop
    ClsId    = apps
    Attrs    = {
      "Version": "<base64 time>"          (max TileVersion below; the
                                           changed-since stamp, PART 6.3)
    }

4.2  Per-app tile row (extends [Desktop](Desktop.md) PART 3.2)

    HstId    = desktop-<actId>-<prvId>
    AppId    = desktop
    ActId    = <actId>
    ObjId    = app-<appId>                (e.g. app-quippin)
    ClsAppId = desktop
    ClsId    = app
    ObjName  = <DisplayName>
    Attrs    = {
      "DisplayName": "...",
      "IconPath":    "...",
      "LaunchPath":  "...",
      "Position":    "...",
      "TileVersion": "<base64 time>",     (last-modified stamp; the LWW
                                           clock for this tile, PART 6.2)
      "Tombstone":   "True"|"False"       (soft-delete, PART 6.4)
    }

  The first four attrs are exactly [Desktop](Desktop.md) PART 3.2. TileVersion
  and Tombstone are the only additions, and they are what make merging
  and deletion correct across replicas.

4.3  GetApps ignores tombstones

  GetApps ([Desktop](Desktop.md) PART 6.2) returns only rows with
  Tombstone!=True, sorted by Position. The rendered grid therefore never
  shows a deleted tile, even before tombstones are purged (PART 6.4).

4.4  Intrinsic system-app tiles are per-provider (excluded from sync)

  Three apps are intrinsic to every provider a user signs in through and are
  therefore per-provider surfaces, NOT replicated, LWW-merged tiles like
  ordinary app tiles:

    * Login     (app-login)     - manages identity and shows the peer set.
    * Navigator (app-navigator) - shows THIS provider's own roots.
    * Desktop   (app-desktop)   - the launcher itself, one per provider.

  For each of these:

    a. Every desktop-<actId>-<prvId> replica is SEEDED with its OWN local
       copy of the tile at bootstrap ([Desktop](Desktop.md) PART 6.2 lazy
       bootstrap), independent of any peer.
    b. The tile is EXCLUDED from the sync protocol (PART 8): a reconciler
       does not pull, merge, tombstone, or fan it out, and MergeApps ignores
       any such row it receives.
    c. Consequently these tiles can never be removed from a desktop by a
       peer's tombstone, and a freshly attached provider
       ([Login protocol](Login-Protocol.md) PART 8) has a working set of system tiles
       immediately, before its first reconcile.

  Rationale ([Login protocol](Login-Protocol.md) PART 6): each provider's
  Login/Navigator/Desktop must point at THAT
  provider's own LOCAL objects - the Login tile at its Login replica
  login-<actId>-<prvId>, the Navigator at that provider's roots, the Desktop
  at that provider's own launcher. Redundancy is surfaced INSIDE each app
  (e.g. the Login account page reads the local membership replica to show all
  peers), not by converging system-app tiles across providers.

  Scope. This exclusion covers ONLY these intrinsic per-provider system
  surfaces. Ordinary app tiles (quippin, money, bookstore, ...) sync normally
  (PART 6). Implementations treat the excluded set as a fixed list of appIds
  {login, navigator, desktop}.

## PART 5 - PEER DISCOVERY (from the Login membership)

5.1  Ask the local Login replica

  The Desktop does not track providers. When it needs its peer set it
  calls the LOCAL Login membership replica ([Login protocol](Login-Protocol.md)
  PART 7):

    dst = login-<actId>-<DOMATAR_HSTID>, (login, membership),
          Action=ListMembership

  Because this is the local replica, the call is in-process (sendLocal);
  no cross-prv traffic to learn the peer set.

5.2  Reduce to the distinct-provider replica set

  ListMembership returns peers (app-logins), each with a PrvId
  ([Login protocol](Login-Protocol.md) PART 7.1). The Desktop:
    a. drops tombstoned peers;
    b. takes the DISTINCT set of PrvId values;
    c. removes its own DOMATAR_HSTID (that is the local replica);
    d. for each remaining prvId P, forms the peer Desktop host id
       desktop-<actId>-P.
  That derived list is the set of Desktop replicas to reconcile with.

5.3  Freshness

  The membership itself is only as current as its own last reconcile
  ([Login protocol](Login-Protocol.md) PART 10). That is fine: a provider added since
  the last membership sync is simply reconciled one display later. Both
  layers are eventually consistent and re-check on display.

## PART 6 - RECONCILIATION MODEL

6.1  Eventual consistency

  Replicas converge over time; they are never required to be identical at
  a single instant (which is impossible while a provider is down - the
  exact case redundancy exists for). "Identical" is the steady state
  between changes, not an invariant during them.

6.2  Last-writer-wins per tile

  The unit of conflict resolution is one tile. The merge key is
  (ActId, ObjId) - e.g. (<actId>, app-quippin) - NOT HstId, because the
  same tile is a different row on each replica (different HstId). When two
  replicas hold different versions of the same tile, the higher
  TileVersion wins; the older is discarded.

  This resolves the realistic cases: install A on prv1 and install B on
  prv2 are DIFFERENT tiles (different ObjId), so both survive; two edits
  to the SAME tile (e.g. reposition app-quippin on both) keep the newer.

6.3  Changed-since version stamp

  The container Version (PART 4.1) is the max TileVersion across its
  tiles. A reconciler first asks a peer for its container Version
  (GetAppsVersion, PART 8); if it is not newer than what was last seen
  from that peer, the peer is skipped with no tile transfer. Only a newer
  Version triggers a full tile pull + merge.

6.4  Deletion via tombstones (prevents resurrection)

  UninstallApp does NOT physically delete the tile row; it sets
  Tombstone=True with a fresh TileVersion. This is essential: a physical
  delete on prv1 would be silently RESURRECTED on the next merge by an
  older, still-present copy on prv2. As a tombstone, the deletion carries
  a timestamp and wins under LWW (PART 6.2) against any older install.

  Tombstones are retained for a purge window, then physically removed
  from all replicas. The window must exceed the longest realistic gap
  between a replica's reconciles (e.g. a provider that was down); a
  conservative default is measured in days. A tombstone must not be
  purged from one replica while another replica still holds a
  pre-tombstone copy it has not yet merged.

  Retention window = 30 days
  (DesktopTombstonePurge.TOMBSTONE_PURGE_WINDOW_MS).
  Optional local purge: Setup?action=purge-desktop-tombstones deletes
  Tombstone=True rows on THIS node's desktop-<actId>-<localPrvId>
  replicas whose TileVersion is older than now−window. Operators should
  only run purge when all known replicas have had a chance to reconcile
  (otherwise a lagging peer can resurrect the tile under LWW).

6.5  Positions and reordering

  Position is a tile attribute, so a reorder is just tile edits with new
  TileVersions, merged by LWW like any other change ([Desktop](Desktop.md)
  PART 11 already models reordering as re-issued installs). Concurrent
  reorders on two replicas resolve per tile by newest TileVersion; the
  result is well-defined though not necessarily either user's exact
  intended ordering - acceptable for a launcher.

## PART 7 - WHEN SYNC HAPPENS

7.1  On display (the primary trigger)

  When the Desktop page renders (which, per [Desktop](Desktop.md) PART 7, is
  immediately after login in the common case), it:

    1. GetApps from the LOCAL replica and render immediately (fast, no
       network wait) - the user sees their home screen at once.
    2. In the background of that same load: discover peers (PART 5),
       probe each peer's Version (PART 6.3), PULL tiles from peers whose
       Version is newer, MERGE by LWW into the local replica (PART 6.2),
       and if the merge changed anything, refresh the rendered grid.

  Unreachable peers are skipped and retried at the next display. This is
  the whole of the "redundancy, not failover" behaviour: a down provider
  costs nothing beyond not contributing updates this time.

7.2  On change (best-effort fan-out, for durability)

  When the user installs/uninstalls/reorders on the local replica, the
  local Desktop additionally PUSHES the changed tiles (best-effort) to the
  reachable peer replicas. This propagates the change promptly so it is
  durable (D2) before any single provider might be lost, rather than
  waiting for each peer to be displayed.

  Fan-out is best-effort and non-fatal: any peer that misses a push picks
  the change up via its own display-time pull (7.1). No two-phase commit;
  eventual consistency covers the gap.

7.3  No background daemon

  There is no periodic timer and no always-on replicator. Sync is
  entirely driven by (a) displaying a Desktop and (b) changing one. A
  replica on a provider the user never visits stays stale until either
  the user visits it (then it pulls) or another replica pushes to it.
  Because the user only ever SEES a replica by displaying it - at which
  point it pulls first - the user-facing guarantee ("I always see my
  current Desktop wherever I sign in") holds without any daemon.

## PART 8 - THE SYNC PROTOCOL (operations on AppsImpl)

Extends AppsImpl ([Desktop](Desktop.md) PART 6). All operate on the apps
container of some desktop-<actId>-<prvId> replica.

  GetApps(inMsg, outMsg)                       [unchanged in shape]
      As [Desktop](Desktop.md) PART 6.2, but filters Tombstone!=True (PART 4.3)
      and includes TileVersion in each returned tile (so a caller can
      merge).

  GetAppsVersion(inMsg, outMsg)                [new; cheap probe]
      Returns the container Version attr (PART 6.3). Used by a reconciler
      to decide whether to pull at all.

  InstallApp / UninstallApp(inMsg, outMsg)     [amended]
      InstallApp writes/updates a tile with a fresh TileVersion.
      UninstallApp sets Tombstone=True + fresh TileVersion (PART 6.4),
      never a physical delete. Both bump the container Version. Both may
      trigger the best-effort fan-out push (PART 7.2).

  PullApps(inMsg, outMsg)                       [new; reconcile pull]
      Returns ALL tiles including tombstoned ones, each with TileVersion,
      so the caller has the full state to LWW-merge. (GetApps hides
      tombstones for rendering; PullApps exposes them for merging.)

  MergeApps(inMsg, outMsg)                      [new; reconcile push]
      Accepts a set of tiles (with TileVersion + Tombstone) and merges
      them into this replica by LWW (PART 6.2). Used by fan-out push
      (7.2). Idempotent: re-merging the same tiles is a no-op. MUST skip the
      intrinsic system-app rows (app-login, app-navigator, app-desktop) in
      the input (they are per-provider, PART 4.4).

  Reconcile is orchestrated by the DISPLAYING replica: for each peer with
  a newer Version, call PullApps, LWW-merge locally, and optionally
  MergeApps back the entries where local is newer (accelerates the peer's
  convergence). Pull-only is sufficient for correctness; push-back is an
  optimization.

## PART 9 - AUTHORIZATION

  Owner-match, verified. Only the actId that owns the sub-host may read or
  write its Desktop replicas (stricter than the base Desktop's
  verified-only; adopt the [Login](Login.md) PART 6.1 owner-match rule):

    verified AND Context.actId == dst.actId

  Cross-replica reconcile messages (PullApps / MergeApps between
  providers) are cross-prv and therefore carry the credential chain
  ([Security](../platform/Security.md) PART 7; [Identifiers](../platform/Identifiers.md) PART 11, including the
  genesis-signed actId->ownId binding hop); a replica accepts a merge
  only if the chain verifies for the actId AND the actId matches the
  sub-host. A provider whose delegation does not chain to the current
  ownId for the actId cannot read or write its Desktop (mirrors
  [Login protocol](Login-Protocol.md) PART 10.5). A rebind ([Identifiers](../platform/Identifiers.md) PART 10)
  therefore also cuts off an evicted provider's Desktop access, with no
  effect on any tile.

## PART 10 - WORKED EXAMPLE

Dave has peers dave@quippin (prv1) and dave@avatarvia (prv2), so replicas
desktop-<actId>-prv1 and desktop-<actId>-prv2.

  1. Dave signs in on prv1, installs "Money". AppsImpl on
     desktop-<actId>-prv1 writes app-money with TileVersion=T1, bumps
     container Version=T1. Best-effort push MergeApps to
     desktop-<actId>-prv2 succeeds -> prv2 now also has app-money@T1.

  2. prv2 is down when Dave uninstalls "Bookstore" on prv1: app-bookstore
     Tombstone=True, TileVersion=T2, Version=T2. The push to prv2 fails
     (down); non-fatal.

  3. Later Dave cannot reach prv1 (down), so he signs in on prv2
     (redundancy, no failover). Desktop on prv2 renders its LOCAL tiles
     first (it has app-money from step 1 but still shows Bookstore,
     because it missed the step-2 tombstone). It then discovers peers
     from the local Login membership; prv1 is unreachable, so nothing to
     pull; Bookstore remains until prv1 returns.

  4. prv1 returns. Next time Dave displays Desktop on prv2, it probes
     prv1's Version (T2 > last-seen), pulls, and LWW-merges: app-bookstore
     tombstone@T2 beats prv2's older install, so Bookstore disappears.
     The two replicas are now identical again.

This is the intended behaviour: never wrong for long, converges on
display, tolerates any single provider being absent, loses nothing
permanently.

## PART 11 - CONSISTENCY PROPERTIES AND EDGE CASES

  * Convergence. Given LWW per tile (PART 6.2), tombstones for deletes
    (PART 6.4), and display-time pull from every reachable peer
    (PART 7.1), all reachable replicas converge to the same tile set.
  * Provider abandonment ([Security](../platform/Security.md) G4). Dropping a provider
    tombstones its peer in membership ([Login protocol](Login-Protocol.md) PART 11)
    and abandons its Desktop replica; NO tile row on any other replica is
    rewritten, because replicas never shared rows (different HstId).
    Cryptographic eviction (a rebind, [Identifiers](../platform/Identifiers.md) PART 10) is
    likewise tile-neutral: it rotates the ownId, never the actId these
    rows key on, so the launcher survives an eviction untouched.
  * Clock skew. LWW needs comparable clocks; providers already keep them
    for the message freshness window ([Security](../platform/Security.md) PART 13). Grossly
    wrong clocks could let a stale write win; out of scope beyond the
    existing skew assumption.
  * A brand-new replica (just attached, [Login protocol](Login-Protocol.md) PART 8)
    starts empty and fills on its first display-time pull, or from the
    attach-time seed push - whichever happens first. Both are safe
    (MergeApps is idempotent, PART 8).
  * Lazy bootstrap ([Desktop](Desktop.md) PART 6.2) still applies PER replica:
    an empty replica that has no peer to pull from seeds the default
    catalog, so a lone first provider is never blank.

## PART 12 - LOCAL SIMULATION

Using the two-provider sim ([Login protocol](Login-Protocol.md)
PART 15):

  * Seed desktop-<actId>-prv1 (tomcat1) and desktop-<actId>-prv2
    (tomcat2) for a user who has peers on both providers.
  * Seed each tile with TileVersion and Tombstone=False; set container
    Version to the max.
  * Exercise: install an app on prv1, confirm it appears on prv2 after
    displaying prv2's Desktop; uninstall on prv1 while prv2 is stopped,
    restart prv2, display, confirm the tombstone propagates and the tile
    disappears.
  * Cross-replica PullApps/MergeApps travel tomcat-to-tomcat; TLS is
    bypassed in the sim ([Security](../platform/Security.md) PART 9).

## PART 13 - IMPLEMENTATION SURFACE

  com.desktop.objimpl.AppsImpl ([Desktop](Desktop.md) PART 6):
      GetApps (filters Tombstone=True, emits TileVersion); GetAppsVersion;
      PullApps (includes tombstones; skips intrinsic {login,navigator,desktop});
      MergeApps (LWW by TileVersion; skips intrinsic); ReconcileApps
      (display-time pull from Login membership peers); InstallApp /
      UninstallApp stamp TileVersion, set Tombstone on uninstall, bump
      container Version, best-effort fan-out (PART 7.2). hasRights is
      owner-match (verified AND ctx.actId == dst.actId).

  com.desktop.sync.DesktopFanout: discoverPeerPrvIds from local Login
  membership peer rows; pushTiles → MergeApps to peer Desktop replicas.

  desktop.html: GetApps first (local, non-blocking); then ReconcileApps;
  re-GetApps and re-render only if the tile fingerprint changed (PART 7.1).

  com.domatar.install.DesktopReplica / NavigatorReplica.ensure: create
  qualified hosts + directory registration + intrinsic seed / descriptors.

  com.domatar.install.DesktopTombstonePurge + Setup?action=purge-desktop-
  tombstones: optional physical delete of aged tombstones (PART 6.4 / 14).

  Follow [Code Style](../platform/CodeStyle.md) for all Java.

## PART 14 - TODO / FUTURE WORK

  DONE — Tombstone purge policy (PART 6.4): 30-day window
  (DesktopTombstonePurge.TOMBSTONE_PURGE_WINDOW_MS); optional Setup action
  purge-desktop-tombstones (local replica only). See PART 6.4 DECIDED note.

  - Push-back during reconcile (PART 8) to speed peer convergence; verify
    it cannot loop (idempotent MergeApps + Version compare should prevent
    it).
  - Per-app DATA sync. This spec syncs the launcher only; syncing the app
    data behind each tile is the broader per-app effort noted in
    [Login protocol](Login-Protocol.md) PART 17.
  - Ordering conflicts (PART 6.5): if per-tile LWW on Position proves
    unsatisfying, consider an explicit ordered-list CRDT for the apps
    container. Not needed for v1.
  - Change-driven sync signal instead of display-only pull, if
    convergence latency is ever shown to matter (parallels
    [Login protocol](Login-Protocol.md) PART 17).

# END OF SPEC
