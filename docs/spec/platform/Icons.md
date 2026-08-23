# SPEC: ICONS - storage, serving, and cross-server access

PURPOSE. Define where Domatar icons live, how browsers load them, how
Desktop / Navigator / App Store resolve icon URLs, and how icons work when
the page is on one provider and the owning app JAR is on another.

IconPaths helpers, AppAssetServlet Cache-Control/ETag, absolute marketplace
IconPath, Open AssetOrigin + Navigator resolution, and App Store absolute
launcher URLs are in the live system (PART 7–11).

## PART 1 - GOALS AND NON-GOALS

1.1  Goals

  G1  Every app owns its own icon bytes (launcher + per-class). Icons are
      ordinary static assets in the app JAR (design principle C7).

  G2  A web page may display an app or class icon without installing that
      app's JAR on the page's provider. Running the app (handlers / user
      data) and serving its icons are decoupled.

  G3  Same-origin relative URLs remain the fast path when the JAR is
      loaded locally (reference distribution today).

  G4  Cross-server display uses absolute HTTP(S) URLs pointing at a
      provider that has the JAR. The browser fetches the SVG directly;
      no icon proxy through Desktop or Navigator.

  G5  Missing or unreachable icons degrade to a single platform default
      glyph — never a broken image.

  G6  Caching uses the browser's HTTP cache, driven by Cache-Control
      (and preferably ETag) on asset responses. No custom JS icon cache.

1.2  Non-goals

  N1  Pulling a full app JAR onto the home provider solely to render an
      icon (`GetAppArtifact`). That remains a separate install/deploy
      Direction ([Domatar](../Domatar.md) PART 16.2); icons MUST NOT depend on it.

  N2  Registry-hosted icon copies outside the app JAR (optional future;
      not required by this spec).

  N3  Authenticated icon URLs. Icons are public static assets.

  N4  Changing v1 actId / ownId / host-id math or provider host names.

## PART 2 - TERMINOLOGY

  Launcher icon     The SVG shown on Desktop / App Store tiles for an app.
  Class icon        The SVG shown on a Navigator tree node for a
                    (ClsAppId, ClsId) pair.
  Asset origin      The `{scheme}://{domain}` of a provider that can serve
                    that app's static assets (has the app JAR loaded and
                    an AppAssetServlet registered for it).
  Relative icon
  path              Path under the WAR context, always starting with
                    `/domatar/…`. Resolves against the page's origin when
                    used as an `<img src>`.
  Absolute icon
  URL               `{scheme}://{domain}` + relative icon path. Used when
                    the asset origin differs from the page origin (or when
                    a stored tile must survive being rendered from any
                    home provider).
  Default icon      Platform fallback SVG for unknown / failed class icons.

## PART 3 - STORAGE (in the app JAR)

3.1  Layout

  Every application JAR places icons under its asset directory
  (manifest `AssetDirectory`, conventionally `<appId>/assets`):

    <appId>/assets/icons/app.svg           launcher icon (REQUIRED for
                                           any app that appears on Desktop
                                           or in App Store grids)
    <appId>/assets/icons/cls/app.svg       identity tile for class
                                           `(<appId>, app)` (REQUIRED for
                                           any app that appears in Navigator;
                                           same art as the launcher)
    <appId>/assets/icons/cls/<clsId>.svg   per-class icon (one file per
                                           other ClsId the app wants a
                                           distinct glyph for)
    <appId>/assets/icons/<name>.svg        optional concept icons owned by
                                           that app (e.g. platform
                                           placeholders)

  The platform app (`domatar`) additionally owns:

    domatar/assets/icons/cls/default/obj.svg   DEFAULT class / object glyph
    domatar/assets/icons/chat.svg              (and calendar, maps, mail,
    domatar/assets/icons/calendar.svg           photos, search — concept
    …                                           tiles that are not real
                                                installable apps yet)

3.2  Ownership rules

  * An app's launcher and class icons live ONLY in that app's JAR.
  * Desktop, Navigator, and App Store MUST NOT bundle other apps' icons.
  * The only cross-app icon the platform ships is the default object glyph
    (and the concept placeholders under `domatar/icons/`).
  * Format: SVG. Square, transparent background preferred. No size
    requirement in the file; CSS in the consuming page sizes the `<img>`.

3.3  Source tree ↔ deployed artefact

  Source (Maven module):
    <module>/src/main/resources/<appId>/assets/icons/...

  Deployed:
    WEB-INF/apps/<appId>.jar  →  <appId>/assets/icons/...

  Served by that app's AppAssetServlet (PART 4).

## PART 4 - SERVING (AppAssetServlet)

4.1  Who owns the servlet

  `com.domatar.servlet.AppAssetServlet` is PLATFORM code (`domatar-core`).
  It does not belong to any application.

  `AppLoader` constructs ONE INSTANCE per loaded app:

    new AppAssetServlet(appId, assetDirectory, appClassLoader)

  and registers it as servlet name `<appId>.assets` at mapping
  `/<appId>/*` (WAR context `/domatar` → public URL prefix
  `/domatar/<appId>/`).

  So every loaded app has its own AppAssetServlet instance; all share
  the same class.

4.2  URL → resource mapping

  Request:   GET /domatar/<appId>/<path>
  Resource:  ClassLoader resource  `<AssetDirectory>/<path>`

  Icon examples:

    GET /domatar/quippin/icons/app.svg
      → quippin/assets/icons/app.svg

    GET /domatar/quippin/icons/cls/quip.svg
      → quippin/assets/icons/cls/quip.svg

    GET /domatar/domatar/icons/cls/default/obj.svg
      → domatar/assets/icons/cls/default/obj.svg

  Security (unchanged): reject paths containing `..`, `\`, or starting
  with `/WEB-INF` (400). Missing resource → 404.

4.3  Content-Type

  `.svg` → `image/svg+xml` (already required; keep).

4.4  Cache headers  (required)

  Icons are immutable for the life of a deployed JAR version. Responses
  for paths under `…/icons/` MUST set:

    Cache-Control: public, max-age=86400

  ETag (KD4): '"' + Base64Encoder.encode(KeyOps.sha256(bodyBytes)) + '"'
  (strong validator; not a weak W/"…" tag). Honour `If-None-Match` →
  304 when the request header equals the computed ETag.

  Do NOT set `Cache-Control: no-store` on icon paths. HTML/JS under the
  same servlet MAY use shorter or no-cache policies; this requirement
  applies to `/icons/` only (path segment after the appId).

  Rely on the browser HTTP cache. Consumers MUST NOT maintain a
  parallel in-memory icon URL → blob map.

## PART 5 - CANONICAL PATH HELPERS

5.1  Relative paths (always)

  Launcher:   /domatar/<appId>/icons/app.svg
  Class:      /domatar/<appId>/icons/cls/<clsId>.svg
  Default:    /domatar/domatar/icons/cls/default/obj.svg

  Empty / null appId or clsId → use the Default path.

  Today `LaunchPaths.icon(appId)` implements the launcher form. Extend
  (or add `IconPaths`) so class + default helpers live in one place in
  `domatar-core` and are used by MarketplaceInstall, seeds, and tests.
  Browser pages duplicate the same string rules in JS (no shared module
  yet); keep JS helpers in sync with the Java helpers.

5.2  Absolute URLs

  absolute(scheme, domain, relativePath) =
      {scheme}://{domain}{relativePath}

  scheme  = DomatarConfig.getWireScheme() on the server that mints the
            URL (typically `https`; local docker may be `http`).

  Two domains may be configured per provider:

    Domain (wire)         DOMATAR_DOMAIN / Domain
                          Msg routing and hst publish address
                          (may be Docker-internal, e.g. `tomcat2:8080`).

    PublicDomain (browser) DOMATAR_PUBLIC_DOMAIN / PublicDomain
                          Optional nginx / reverse-proxy hostname
                          browsers can resolve
                          (e.g. `domatar.avatarvia.com`).
                          When unset, browser URLs use Domain.

    BrowserOrigin          DOMATAR_BROWSER_ORIGIN / BrowserOrigin
                          Optional full origin browsers actually open
                          (`http://localhost:9080`). Wins over
                          PublicDomain when minting AppUrl / AssetOrigin.
                          Appends `/domatar` unless the value already
                          includes a path.

  browserAbsolute(scheme, publicDomain, wireDomain, relativePath):

    * When PublicDomain is set and differs from Domain, mint
        {scheme}://{PublicDomain}{stripContext(relativePath)}
      where stripContext removes the leading `/domatar` prefix
      (nginx front doors typically re-add `/domatar`).
    * Otherwise mint
        {scheme}://{Domain}{relativePath}
      (wire host keeps the context path).

  Example (prv2 offers bookstore, Domain `tomcat2:8080`,
  PublicDomain `domatar.avatarvia.com`, http):

    http://domatar.avatarvia.com/bookstore/icons/app.svg

  Wire-only (PublicDomain unset or equal to Domain):

    http://tomcat2:8080/domatar/bookstore/icons/app.svg

## PART 6 - CONSUMERS (current behaviour to preserve locally)

6.1  Desktop

  Tile attr `IconPath` is rendered as `<img src="{IconPath}">`.
  Desktop never interprets appId for icons; it trusts the stored path/URL.

  Local / seed tiles today use relative paths, e.g.:

    /domatar/quippin/icons/app.svg

6.2  Navigator

  For each tree node, JS builds a class icon URL from (ClsAppId, ClsId)
  via `classIconUrl`, then `<img onerror=fallback>`.

  Local form:

    /domatar/<ClsAppId>/icons/cls/<ClsId>.svg

  App objects (and their root lnks) are class `(<appId>, app)`, so that
  path is `/domatar/<appId>/icons/cls/app.svg`. That file is the app's
  identity tile (same art as `icons/app.svg`).

  On any load failure → Default icon (PART 5.1). Fallback must run only
  once (avoid loops).

6.3  App Store UI

  Grid tiles currently build:

    /domatar/<AppId>/icons/app.svg

  from the listing's AppId (relative to the page origin).

## PART 7 - CROSS-SERVER ACCESS (TARGET BEHAVIOUR)

7.1  Principle

  The browser loads the icon from an asset origin that HAS the JAR.
  That is normally the provider that runs the app for the relevant
  user/object (handlers require the JAR). The page's provider does NOT
  need the JAR solely to paint the icon.

  Pre-shipping every JAR to every provider remains a valid reference-
  distribution convenience (local relative URLs keep working). It is
  no longer a correctness requirement for cross-provider icons.

7.2  Desktop / marketplace tiles

  Each installed app has an AppUrl (browser base). At install:

    1. Use the request / app.config.txt AppUrl if set.
    2. Else inherit the offering provider: BrowserOrigin
       (DOMATAR_BROWSER_ORIGIN, e.g. http://localhost:9080) when set;
       else PublicDomain front door. Do not mint Docker-only wire
       Domain (e.g. `quippin:8080`) as AppUrl — the user's browser
       cannot resolve it. Home fetches SiteRole from the wire host
       to obtain BrowserOrigin when the offer row lacks it.
    3. AppUrl may be changed later (SetAppUrl). IconPath and LaunchPath
       are derived from the resolved base:

         {AppUrl}/<appId>/icons/app.svg
         {AppUrl}/<appId>/<LaunchPage>

       LaunchPage comes from app.config.txt (default `<appId>.html`).
       Quippin's launcher is `news.html` (there is no quippin.html).

    Local install with no AppUrl: relative paths
      /domatar/<appId>/icons/app.svg
      /domatar/<appId>/<LaunchPage>

    Cross-provider with inherited BrowserOrigin:
      http://localhost:9080/domatar/quippin/icons/app.svg
      http://localhost:9080/domatar/quippin/news.html

    Cross-provider with only PublicDomain (nginx front door):
      {scheme}://{PublicDomain}/<appId>/icons/app.svg

  GetUserApps re-derives IconPath / LaunchPath from stored AppUrl or
  inherited HostBrowserOrigin / HostPublicDomain. Docker-only HostDomain
  is never used as a browser URL; GetUserApps may SiteRole-fetch
  BrowserOrigin from that wire host. A later SetAppUrl updates both
  the tile image and the click target.

  Desktop HTML: prefer AppUrl; else IconPath / LaunchPath. On a direct
  WAR page, if IconPath is absolute to another host, fall back to the
  same-origin launcher path so existing tiles still paint.

  `MarketplaceInstall` applies the same resolve to LaunchPath and
  IconPath. Offers carry BrowserOrigin so the home node can inherit
  the offering provider's browser-reachable origin.

7.3  Navigator class icons

  When the object (or the link metadata for a child) is served from a
  host whose browser asset origin differs from the Navigator page origin,
  the class icon MUST be fetched from that object's asset origin — not
  from the Navigator page's origin.

  Resolution order for each tree node (implement in navigator.html):

    1. If the node carries an absolute `IconUrl` (http/https), use it.
    2. Else if the page is under `/domatar/`, use the WAR-relative
       class path (PART 5.1 / 6.2)
       `/domatar/<ClsAppId>/icons/cls/<ClsId>.svg`. This covers
       localhost Tomcat when Open still names a PublicDomain the
       browser is not using.
    3. Else if the node (or its Open response context) carries
       `AssetOrigin` (`{scheme}://{browserDomain}`) whose host differs
       from the page, use AssetOrigin + path, where path is
       `/domatar/<ClsAppId>/icons/cls/<ClsId>.svg` unless
       `AssetContextPath` is `""` (nginx front door), in which case
       strip `/domatar` from the path.
    4. Else use the relative class path (PART 5.1) — same-origin /
       local-JAR / matching front door.
    5. onerror → Default icon.

  How `AssetOrigin` / `IconUrl` is supplied (server side):

  The host that answers `Open` (and thus knows its own Domain and that
  it has the JAR for the classes it serves) MUST expose asset origin to
  the client. Preferred shape for this iteration:

    * On the Open response (or each Lnk / child descriptor), include
        "AssetOrigin": "{scheme}://{browserDomain}"
      using WireScheme + getBrowserDomain() (PublicDomain when set,
      else Domain), and
        "AssetContextPath": "" | "/domatar"
      (empty when PublicDomain is a distinct front door).
    * Navigator caches AssetOrigin / AssetContextPath on the expanded
      node and applies them to that node and its children until a child
      Open supplies a different origin (cross-host edge).

  Alternative acceptable shape: each Lnk includes a fully built
  `IconUrl`. If both are present, IconUrl wins (step 1).

  KD7: the answering host always emits AssetOrigin when a browser
  Domain is set. `asset-paths.js` then: if the page path is under
  `/domatar/`, uses same-origin WAR paths (localhost Tomcat); else if
  AssetOrigin's host equals `window.location.host`, keeps a relative
  URL (nginx front door); otherwise builds an absolute URL (honouring
  AssetContextPath).

7.4  App Store grids (cross-provider listings)

  When a listing/offer is known to live on another provider (SearchApps
  / offer row carries offering Domain / PublicDomain), the grid MUST
  use an absolute launcher URL against PublicDomain when present,
  otherwise Domain — same stripContext rule as PART 5.2. When the offer
  is local, keep the relative path.

  If Domain is unknown, relative path + Default/onerror degradation is
  acceptable for that row.

7.5  What does NOT change

  * Icons remain in the owning app JAR (C7).
  * AppAssetServlet remains platform-owned, one instance per loaded app.
  * No icon bytes are copied into Desktop or Navigator.
  * No Msg round-trip to fetch icon bytes through the platform; the
    browser GETs the SVG as a normal static asset.
  * Cross-origin `<img>` does not require CORS for display.

## PART 8 - FAILURE AND FALLBACK

  * HTTP 404 / network error / blocked mixed content on an icon URL →
    consumer swaps to the Default icon (PART 5.1). Prefer same-origin
    Default (`/domatar/domatar/icons/cls/default/obj.svg`) so the
    fallback does not depend on the remote provider.
  * Launcher tiles (Desktop / App Store): onerror → Default icon is
    REQUIRED (App Store already shows an img; Desktop should match).
  * Never leave a permanently broken `<img>` in the tree or grid.
  * Do not retry remote origins in a loop; one fallback is enough.

## PART 9 - SECURITY AND OPS

  * Icon GETs are unauthenticated. Do not place secrets in SVG files.
  * Path traversal rules on AppAssetServlet stay as today.
  * Prefer https WireScheme in real deployments so absolute icon URLs
    are not mixed-content blocked when the page is https.
  * Local docker (http://localhost:8080 vs :8081) is a first-class
    test topology for PART 11.

## PART 10 - IMPLEMENTATION NOTES (for the update tasks)

10.1  Likely touch list

  Platform / core
    * AppAssetServlet — Cache-Control (and ETag) for `/icons/` paths.
    * LaunchPaths and/or new IconPaths — relative launcher, class,
      default; absolute builder; unit tests.

  Install / marketplace
    * MarketplaceInstall — set absolute IconPath when remote (same
      inputs as LaunchPath).
    * Any other InstallApp / seed path that writes Desktop IconPath for
      a remote offering.

  Open / Navigator protocol
    * ObjImpl default Open (or the Navigator-facing Open assembler) —
      attach AssetOrigin / AssetContextPath using DomatarConfig
      getWireScheme + getBrowserDomain / getPublicDomain.
    * navigator.html — classIconUrl(clsAppId, clsId, assetOrigin,
      assetContextPath); plumb through expand/select; keep onerror
      fallback.
    * desktop.html — onerror → default icon on tile images (if missing).

  App Store UI
    * appstore.html — absolute launcher URL when offer PublicDomain /
      Domain is remote (prefer PublicDomain; strip /domatar for front
      doors).

  Specs to cross-link after code lands (doc-only follow-ups)
    * [App Store](../apps/AppStore.md) PART 11.2 IconPath sentence → point here.
    * [Domatar](../Domatar.md) PART 16.2 / Installation R10.3.e → icons no longer
      require local JAR; JAR pull remains for running/installing apps.
    * [Navigator](../apps/Navigator.md) PART 6.3 → AssetOrigin / absolute resolution.
    * [Desktop](../apps/Desktop.md) IconPath note → may be absolute URL.

10.2  Compatibility

  * Existing Desktop rows with relative IconPath keep working when the
    JAR is local (reference dist).
  * KD3: newly minted remote tiles get absolute IconPath at install
    time (`IconPaths.forInstall`). There is NO Desktop render-time
    rewrite of existing relative remote IconPath rows; those remain
    until re-install or sync. Local relative tiles stay correct when
    the JAR is present.
  * Stale absolute URLs after an offering Domain change are an ops/
    re-install concern; out of scope for v1 of this spec.

10.3  Out of scope for the first conforming change set

  * GetAppArtifact / writing JARs into WEB-INF/apps on demand.
  * Content-hash URLs (`app.svg?v=…` or hashed filenames).
  * CDN / registry-hosted icons.

## PART 11 - ACCEPTANCE TESTS

  T1  Local launcher
      Open Desktop on prv1 with Quippin JAR present.
      Quippin tile img src is `/domatar/quippin/icons/app.svg` (or
      absolute same-origin equivalent) and returns 200 SVG.

  T2  Local class icon
      Open Navigator on prv1, expand a quippin quips node.
      Tree img requests `/domatar/quippin/icons/cls/quips.svg` (200).

  T3  Default fallback
      Force a missing class icon URL; UI shows default obj.svg, no
      broken-image icon.

  T4  Cache headers
      GET any `/domatar/<app>/icons/...` → Cache-Control includes
      `public` and `max-age` ≥ 86400.

  T5  Cross-provider Desktop tile  (requires offering Domain ≠ page)
      User home Desktop on prv1; install app whose data/host is on prv2
      ONLY (or simulate by removing the app JAR from prv1 while leaving
      handlers/data on prv2 — in full topology, omit the JAR on prv1).
      Tile IconPath MUST be an absolute URL targeting prv2's browser
      host (PublicDomain when set, else Domain). With docker-compose
      PublicDomain `domatar.avatarvia.com`:
        http://domatar.avatarvia.com/bookstore/icons/app.svg
      Browser loads the SVG from that front door; tile shows the real
      icon.

  T6  Cross-provider Navigator
      Navigator page on prv1; Open a tree node whose object host is on
      prv2; prv1 lacks that app JAR (or AssetOrigin forces remote).
      Class icon URL MUST use prv2's asset origin; 200 from prv2; on
      prv2 down → default glyph.

  T7  No JAR install required for icons
      Completing T5/T6 MUST NOT require copying the app JAR onto prv1.

## PART 12 - RELATION TO OTHER SPECS

  Cross-provider tiles use absolute IconPath ([App Store](../apps/AppStore.md)
  PART 11.2 / this PART 7.2). Display does not require the app JAR on
  the page's provider; shipping all JARs everywhere remains a reference
  convenience ([Installation](../install/Installation.md),
  [Domatar](../Domatar.md) PART 16.2). Navigator class icons are JAR
  assets (PART 3); URL form is PART 5; cross-server is PART 7.3
  ([Navigator](../apps/Navigator.md) PART 6.3).

  Does not change:

    * Desktop tile sync ([Desktop](../apps/Desktop.md)).
    * Marketplace install / portable hosts ([App Store](../apps/AppStore.md)) except
      IconPath minting.
    * AppLoader / manifest / AssetDirectory mechanics.

## PART 13 - SUMMARY FOR IMPLEMENTERS

  Store icons in the app JAR under assets/icons/.
  Serve them with the per-app AppAssetServlet instance.
  Use relative /domatar/<appId>/icons/… when the JAR is local.
  Use absolute {scheme}://{domain}/domatar/<appId>/icons/… when the
  page should load the icon from another provider — Desktop stores that
  on the tile; Navigator gets AssetOrigin (or IconUrl) from Open.
  Cache with HTTP headers; fall back to domatar default obj.svg.
  Do not install a JAR just to show an icon.
)
