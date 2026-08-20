# DOMATAR - APP STORE (FEDERATED MARKETPLACE)

This document specifies the App Store as a CROSS-PROVIDER marketplace: a
central registry of application METADATA (not JAR binaries) together with the
PROVIDERS that offer each app; provider registration of offers; user search
and discovery; and install onto the user's Desktop by CHOOSING a hosting
provider.

InstallApp writes the platform UserAppRegistry (userApps). Navigator root
edges come from NavRootReconcile, not from InstallUser alone
([Platform App](../platform/Platform-App.md), [Navigator](Navigator.md)).
Cross-provider InstallApp requires presence (login-home OR object-only peer).
If missing, HostProvision then InstallUser. Do not require a linked login
and do not AddAct inside InstallApp
([Foreign Provider](../install/Foreign-Provider.md)).

It is the marketplace half of the availability model in
[Provider Customization](../install/Provider-Customization.md) PART 2.5. Levels (a) PROVIDER-AVAILABLE and
(b) DEFAULT stay local to each provider. Level (c) PERSONAL generalizes here
so the app's hosting provider need not equal the user's home provider.

Companion documents:

- [Installation](../install/Installation.md) — AppInstall / UserInstallDispatch / catalog
- [Provider Customization](../install/Provider-Customization.md) — availability levels (a)/(b)/(c); PART 9.5
- [Domatar](../Domatar.md) — App Catalog, directory, routing, apps-as-JARs
- [Desktop](Desktop.md) — Desktop tiles / LaunchPath / sync
- [Login protocol](Login-Protocol.md) — multi-provider identity; replica hosts for login/desktop/navigator only (PART 5) — not the pattern for ordinary app data
- [Identifiers](../platform/Identifiers.md) — actId / ownId / binding
- [Security](../platform/Security.md) — delegations, origin signatures, TLS
- [Money](Money.md) — payment channel (Direction only here)

Present-tense statements describe the system AS IT WILL BE. Where behaviour
already exists in the local precursor (`appstore` module), that is named so an
implementation plan can upgrade rather than rewrite blindly.

## PART 1 - MOTIVATION

Today's App Store (`com.appstore`, [Installation](../install/Installation.md) PART 7) is a LOCAL
surface: `(appstore, home).GetApps` reads THIS provider's App Catalog via
`LnkDb`, and `InstallApp` always provisions on `DomatarConfig.getPrvId()`.
A user on prv1 cannot discover that bookstore is offered on prv2, cannot pick
a host, and cannot land a tile that launches against another provider's
domain.

The platform already has the hard parts for a marketplace:

  * Global self-certifying identity (actId works on any provider).
  * Cross-provider messaging (directory / getRemoteHst / credential chain /
    delegation).
  * Cross-provider host routing via the directory (one HstId → one location).
  * Apps as independent JARs loaded per node by AppLoader.
  * Per-provider App Catalog as the SUPPLY fact: "this provider has deployed
    and registered this app" (CatalogInstall / AppCatalogImpl).

What is missing is a GLOBAL registry of listings and offers, provider
selection at install time, cross-provider InstallUser onto a PORTABLE
per-user app host (PART 9), and Desktop tiles that resolve to the chosen
host's domain. That subsystem is this document.

## PART 2 - GOALS AND NON-GOALS

2.1  Goals

  G1  Central store of app METADATA (AppId, name, description, version,
      builder identity) — not the implementation JAR.
  G2  For each listed app, the set of PROVIDERS that currently offer it
      (each offer points at a provider that has the app in its local catalog).
  G3  A provider that SUPPORTS an app can REGISTER that offer with the App
      Store (and later withdraw it).
  G4  Users can SEARCH / browse the App Store for applications across
      providers.
  G5  When installing, the user CHOOSES which offering provider hosts the
      app for them; the app is provisioned on that provider and a Desktop
      tile appears so the user can begin using it.
  G6  Reuse existing install SPI (`AppInstall.installUser` /
      `UserInstallDispatch`) and identity/delegation — do not invent a
      parallel install stack.
  G7  Keep ordinary app user-host ids PORTABLE (`<appId>-<actId>`): no prvId
      in the HstId, so an installation can later move by export/import (or
      copying a per-host DB file). Apps that need an extra host-id segment
      supply it via an AppInstall hook (PART 9) — the marketplace does not.
  G8  Preserve the local precursor UX as a FALLBACK view ("apps on my home
      provider") so single-provider deployments keep working without a
      populated global registry.

2.2  Non-goals (this document)

  * Automatic JAR distribution / binary marketplace (Direction, PART 12).
    An offer implies the offering provider ALREADY has the JAR deployed and
    catalog-registered (PART 5). How the JAR got there is an operator /
    distribution concern outside the install protocol.
  * Payment, licensing meters, or revenue share (Direction; relate to
    [Money](Money.md)).
  * Ratings, reviews, curated collections, paid placement.
  * Changing [Provider Customization](../install/Provider-Customization.md) levels (a)/(b) or the default-apps
    editor.
  * Uninstalling an app's OBJECTS (today Uninstall hides Navigator/Desktop
    links; data retention policy stays as [Installation](../install/Installation.md) PART 5.5).

## PART 3 - CORE CONCEPTS

3.1  Listing

  A LISTING is the App Store's record of an application as a product:
  AppId, human-readable AppName / AppDesc, Version, and optional builder
  attribution. One listing per AppId in the registry. The listing does NOT
  contain the JAR.

3.2  Offer

  An OFFER is a provider's claim that it can HOST the app for users:
  PrvId, Domain (or directory-resolvable host), the Version it runs, and
  Status (Active / Withdrawn). An offer is valid only while the provider's
  LOCAL App Catalog still contains that AppId (PART 5.3).

3.3  Offering provider vs home provider

  HOME PROVIDER — where the user signed in / where their Desktop replica for
  this session lives ([Desktop](Desktop.md)).

  OFFERING / HOSTING PROVIDER — the provider chosen at InstallApp time; it
  owns the user's app sub-host and serves the app's runtime.

  These MAY be the same (local install — today's behaviour) or different
  (cross-provider install — the marketplace case).

3.4  Registry host

  The App Store maintains a single logical REGISTRY on a well-known central
  host (PART 4). Providers push RegisterOffer / WithdrawOffer to it; users
  query SearchApps / GetListing against it. This is CENTRALIZED discovery,
  not a gossip of every provider's catalog. Local catalogs remain the supply
  source of truth on each node.

3.5  Relationship to the per-provider App Catalog

  [Provider Customization](../install/Provider-Customization.md) PART 2.5:

    (a) PROVIDER-AVAILABLE — local catalog entry (unchanged).
    (b) DEFAULT             — ProviderDefaults / sign-up (unchanged).
    (c) PERSONAL            — THIS document: install via App Store, possibly
                              onto a non-home offering provider.

  Registering an offer REQUIRES (a) on that provider. It does NOT require (b).
  Completing InstallApp produces (c) for that user.

## PART 4 - HOSTING AND IDENTITY

4.1  App identity

  appId         : "appstore"
  AppName       : "App Store"
  central host  : "appstore"   (directory-registered; local two-provider
                                stack typically hosts it on prv2)
  per-user host : appstore-<actId>-<prvId>
                  (shell replica per login home — PART 9.5;
                   [Platform App](../platform/Platform-App.md)).
                  The central registry host remains `appstore` (KD9).

4.2  Registry ownership

  The registry objects live on the App Store central host under the App Store
  system account (sim: appstore@appstore). Ordinary users and providers reach
  it by DomId / directory routing — they do not need a login ON the registry
  host beyond the usual verified session / delegation used for cross-provider
  messages.

4.3  Registry host

  The central host `appstore` is the registry location.
  `AppstoreInstall.installProvider` registers the catalog entry and
  ensures the central host / registry containers exist (idempotent).

## PART 5 - SUPPLY SIDE: DEPLOY, CATALOG, REGISTER

5.1  Deploy (unchanged)

  Operator places `<appId>.jar` in `WEB-INF/apps/` on the offering provider,
  restarts / reloads, runs /Setup (or `/<appId>/Setup`). AppLoader loads the
  JAR; `installProvider` registers the local catalog entry
  (CatalogInstall.registerInCatalog). Result: level (a) on that provider.

5.2  RegisterOffer

  After (a) is true, the PROVIDER ACCOUNT (`<PrvId>@<PrvId>`, fingerprint
  actId = DomatarConfig.getPrvActId()) — or a delegated operator tool acting
  as that account — sends RegisterOffer to the App Store registry:

    Request:  { AppId, AppName?, AppDesc?, Version?, Domain?, PublicDomain? }
    Auth:     verified session; caller actId MUST equal the offering
              provider's PrvActId (same isProvider rule as SiteConfig),
              OR a future explicit "marketplace operator" role (Direction).

  Behaviour:

    1. Verify the caller's local catalog contains AppId (the registry may
       ask the offering provider via GetCatalog / hasCatalogEntry, or trust
       a signed attestation in a later revision; v1: registry sends a
       VerifyOfferProbe to the offering provider's domatar catalog host).
    2. Upsert LISTING for AppId (create if missing; refresh AppName/AppDesc/
       Version from the request or from the probe).
    3. Upsert OFFER child for this PrvId (Status=Active, Domain = wire
       address for Msg/hst, optional PublicDomain = browser front door
       for IconPath/LaunchPath, Version, RegisteredAt / Version stamp).
    4. Respond with the listing + this offer.

  Idempotent: re-registering refreshes metadata and Status=Active.

5.3  Offer validity

  An Active offer is STALE if the offering provider no longer has the app in
  its local catalog (JAR removed, catalog entry deleted). InstallApp MUST
  re-check hasCatalogEntry on the chosen provider at install time and refuse
  with a clear error if missing ("Not in provider app catalog" — same class
  of failure as today's ActManagerImpl.InstallApp).

  Periodic revalidation / auto-Withdraw on probe failure is Direction
  (PART 12).

5.4  WithdrawOffer

  Request: { AppId }
  Auth:    same as RegisterOffer for that PrvId.

  Sets the offer Status=Withdrawn (or deletes the offer object). Does NOT
  delete the listing while other Active offers remain. If no Active offers
  remain, the listing may remain searchable as "unavailable" or be hidden
  from default Search — implementation choice recorded in the plan; default
  in this spec: hide from default SearchApps results, still GetListingable
  for uninstall/history.

5.5  Builder registration

  The first RegisterOffer for an AppId creates the listing. Optionally the
  listing records BuilderPrvId = the first offering provider (or an explicit
  BuilderActId later). "Builder sells to other providers" (PART 12) means
  OTHER providers also RegisterOffer for the same AppId after they deploy
  the JAR — the marketplace does not ship the binary for them in v1.

## PART 6 - DATA MODEL (REGISTRY)

6.1  Containers on the App Store central host

  HstId : appstore
  AppId : appstore
  ActId : <appstore system actId>

    registry                          class (appstore, registry)
      ↓ tagAppId=appstore tag=listing
    listing-<appId>                   class (appstore, listing)
      Attrs: AppId, AppName, AppDesc, Version,
             BuilderPrvId?, UpdatedAt / Version
      ↓ tagAppId=appstore tag=offer
    offer-<prvId>                     class (appstore, offer)
      Attrs: PrvId, Domain, PublicDomain?, AppVersion, Status
             (Active|Withdrawn), RegisteredAt / Version

6.2  Why not reuse (domatar, catalog)?

  The per-provider App Catalog answers "what is installed ON THIS NODE".
  The registry answers "what exists in the NETWORK and who offers it".
  Keeping them separate avoids coupling every provider's local catalog
  graph to global search, and lets offers carry Status / Domain without
  polluting catalogEntry attrs. GetApps on a user's home App Store may
  STILL read the local catalog for the local-only fallback (PART 8.2).

6.3  Per-user App Store objects (precursor, upgraded)

  After installUser, each user has (on their home provider's appstore
  sub-host):

    app-appstore   class (appstore, app)     — LLM / Navigator entry
    home           class (appstore, home)    — UI landing object

  home continues to own GetInstalled / UninstallApp locally. Search and
  Install against the registry are messaged to the central host (or proxied
  by home). Exact split is an implementation choice; the PROTOCOL surface
  in PART 7 is normative.

## PART 7 - PROTOCOL

Operations below are the marketplace contract. Names may map onto existing
AppstoreImpl / AppstoreWui / ActManagerImpl entry points during the upgrade;
new ops may be added rather than overloading GetApps beyond recognition.

7.1  SearchApps  (Read; any verified user)

  Request:  { Query?, Limit? }     // Query matches AppId / AppName / AppDesc
  Dest:     registry (central)

  Response:
    { Listings: [
        { AppId, AppName, AppDesc, Version,
          OfferCount,                          // Active offers
          OffersPreview?: [ { PrvId, Domain, PublicDomain? } ]
        }, ... ]
    }

  Default Search returns listings with at least one Active offer. Empty Query
  = browse. Ranking is unspecified in v1 (AppId / AppName order is fine).

7.2  GetListing  (Read; any verified user)

  Request:  { AppId }
  Response:
    { AppId, AppName, AppDesc, Version, BuilderPrvId?,
      Offers: [
        { PrvId, Domain, PublicDomain?, AppVersion, Status }, ...
      ],
      AlreadyInstalled?: [
        { PrvId, Domain, HstId } ...  // where this user's install lives
      ]
    }

  AlreadyInstalled is filled from the directory / Desktop tiles for this
  actId's app host(s) (PART 9). Ordinarily there is at most one HstId per
  AppId (the portable `<appId>-<actId>`), currently registered to whichever
  offering provider last installed or received a move.

7.3  RegisterOffer / WithdrawOffer  (Write; provider account)

  See PART 5.2 / 5.4. Response shape matches GetListing for that AppId
  (so UIs re-render from the server).

7.4  InstallApp  (Write; verified user)

  Request:  { AppId, PrvId }
            // PrvId = chosen offering provider. REQUIRED in the marketplace.
            // Precursor compatibility: if PrvId omitted, default to the
            // caller's home provider (today's AppstoreWui behaviour).

  Behaviour:

    1. Auth: verified session; ctx.actId is the installing user.
    2. Resolve Active offer for (AppId, PrvId); else error "No active offer
       for this app on that provider".
    3. On the offering provider: verify local catalog has AppId; else refuse
       (PART 5.3).
    4. Resolve the portable user sub-host id for this app (PART 9). If that
       HstId is already registered to a DIFFERENT provider, refuse with a
       clear error (suggest move / uninstall first) — do NOT mint a second
       host by baking prvId into the id.
    5. Provision on the OFFERING provider (PART 10): dispatch InstallUser to
       `(appId, install)` for this actId / usrId / usrName, addressed at the
       PART 9 host id. Cross-provider: existing msgClient / directory /
       credential chain / delegation ([Security](../platform/Security.md);
       Binding in [Identifiers](../platform/Identifiers.md));
       PART 10.2).
    6. Create or update a Desktop TILE on the user's Desktop (home provider
       replica is enough for "appears on Desktop"; sync propagates per
       [Desktop](Desktop.md)) with LaunchPath / host binding that
       targets the OFFERING provider (PART 11).
    7. Ensure Navigator link exists if the app's installUser expects one
       (precursor AppstoreInstall / per-app installUser already do this for
       local installs; cross-provider must not skip it).
    8. Respond { Status: "Installed", AppId, PrvId, HstId }.

  Idempotent: installing again for the same (user, AppId) when the host
  already lives on that PrvId returns success without duplicating hosts.

7.5  GetInstalled  (Read; replaces / extends local half of GetApps)

  Request: { }
  Dest:    user's appstore home (local)

  Response:
    { InstalledApps: [
        { AppId, AppName, AppDesc, IsCore,
          PrvId, Domain, HstId, LaunchPath? }, ... ]
    }

  Lists personal installs across providers when known (from Desktop tiles
  and/or directory). Core apps (navigator, login, desktop, domatar,
  appstore) remain non-uninstallable.

7.6  UninstallApp  (Destructive; verified user)

  Request: { AppId, PrvId? }
  If PrvId omitted and only one install exists, that one is targeted; if
  several, PrvId is required.

  Removes Desktop tile and Navigator app link for that install (data objects
  retained — [Installation](../install/Installation.md) PART 5.5). Does NOT WithdrawOffer and does
  NOT remove the offering provider's catalog entry.

7.7  Precursor GetApps / AvailableApps

  Today's GetApps returns InstalledApps + AvailableApps from the LOCAL
  catalog only. Under this spec:

    * InstalledApps  → GetInstalled (possibly enriched with PrvId).
    * AvailableApps  → either SearchApps filtered to offers where
      PrvId = home provider, OR a dedicated LocalAvailable that keeps the
      precursor LnkDb read for offline/local mode.

  The HTML UI (`appstore.html`) gains: search box, listing detail with
  provider picker, then Install.

## PART 8 - USER EXPERIENCE

8.1  Entry

  Desktop tile LaunchPath `/domatar/appstore/appstore.html` (as today).
  Navigator entry `app-appstore` → `home`.

8.2  Browse / search

  Default view: SearchApps results (marketplace). A secondary tab or toggle
  "On this provider" shows local AvailableApps (precursor), useful when the
  registry is empty or unreachable.

8.3  Listing detail / provider choice

  Selecting an app opens GetListing. The UI lists Active offers (PrvId,
  Domain, Version). The user picks one and confirms Install.

  If the only Active offer is the home provider, the picker may be skipped
  (one-click Install — precursor UX).

8.4  After install

  Desktop shows the new tile; opening it navigates to the app on the hosting
  provider's domain (PART 11). The user can begin using the app immediately
  subject to that app's own auth (usually the same actId / linked login).

8.5  Provider console (Direction for Spec-Provider-Setup / this card)

  A provider-facing "My offers" view (Register / Withdraw) may live in the
  App Store UI when IsProvider, or on the Site customization card. Not
  required for user install acceptance; required for a complete supply-side
  demo.

## PART 9 - PORTABLE USER SUB-HOSTS (AND THE APP EXTENSION HOOK)

9.1  Design objective: movable installations

  A standing goal of Domatar is that a user's installation can MOVE from one
  host to another by exporting that host's objects and importing them on the
  destination (and, later, by copying a per-host database file). The HstId
  is the stable name of that installation in the directory; the host record's
  Domain / PrvId say where it currently lives.

  Therefore the marketplace MUST NOT bake the offering provider into ordinary
  app user-host ids. A host named `bookstore-<actId>-prv2` cannot be moved to
  prv1 without renaming every object graph and every inbound reference — the
  opposite of export/import mobility.

9.2  Default host id (portable)

  Marketplace InstallApp creates / uses the same shape the precursor already
  uses for ordinary apps:

    HstId = <appId>-<actId>
            // DomId.subHstId(appId, actId) — NO prvId suffix

  The directory maps that single HstId to the OFFERING provider's Domain at
  install (or re-maps it on a future move). One personal install of AppId per
  actId is the normal case; concurrent installs of the same app on two
  providers under two different HstIds is NOT a problem this document solves
  by renaming hosts.

9.3  App-supplied host-id extension hook

  Some apps may need a suffix beyond `<appId>-<actId>` for reasons of THEIR
  own (e.g. multiple named instances). The platform provides a HOOK on the
  app's install SPI; the App Store / UserInstallDispatch asks the app and
  does not invent a provider-based default.

  Shape (normative intent; exact Java signature in the Update plan):

    AppInstall.userHostIdExtension(actId, usrId, prvId, …) → String|null

      null / empty  → HstId = <appId>-<actId>          (PART 9.2)
      non-empty ext → HstId = <appId>-<actId>-<ext>
                      // ext MUST NOT be a raw prvId "because marketplace";
                      // MUST be valid in a HstId segment (no HOST_SEP '-',
                      // alphabet compatible with directory parsing).

  The app owns the meaning of `ext`. The marketplace passes context (including
  the chosen PrvId) but MUST NOT append PrvId itself when the hook returns
  null.   Today's UserInstallDispatch hard-codes -<prvId> only for login /
  desktop / navigator / appstore (PART 9.5); that special case is NOT
  generalized here.

9.4  Collision / already-installed policy

  If `<appId>-<actId>` (plus any app extension) is already registered to
  provider A and the user asks InstallApp(..., PrvId=B):

    * Refuse (default): "Already installed on provider A" — user uninstalls
      or uses a future MoveInstall.
    * Do NOT silently create `<appId>-<actId>-B`.

  MoveInstall (export host → import on B → rewrite hst Domain/PrvId) is
  Direction; it preserves the HstId.

9.5  Login / Desktop / Navigator / App Store replicas are a different pattern

  [Login protocol](Login-Protocol.md) / [Desktop](Desktop.md) use
  `<app>-<actId>-<prvId>` for IDENTITY and LAUNCHER replicas so each home
  provider keeps a local copy. The App Store shell follows the same
  replica pattern (`appstore-<actId>-<prvId>`) because it is a thin
  local UI over domatar userApps + the central registry — not portable
  per-user app data. [Login protocol](Login-Protocol.md) PART 4 explicitly leaves
  per-app DATA hosts (`quippin-<actId>`, `money-<actId>`, …) single-home.
  Those replica ids must not be used as the template for marketplace
  app installs. The central registry host `appstore` stays one copy
  (KD9); directory redundancy is out of scope.

## PART 10 - CROSS-PROVIDER INSTALL

10.1  Local install (PrvId == home)

  Same path as today: ActManagerImpl (or successor) →
  CatalogInstall.hasCatalogEntry → UserInstallDispatch.sendInstallUser on
  the local node, addressed at the PART 9 portable host id (plus any
  app-supplied extension).

10.2  Remote install (PrvId != home)

  1. Home App Store / ActManager validates the offer (PART 7.4).
  2. Presence: HostPresence.hasPeer (login-home OR object-only peer
     on N). If missing, HostProvision (HostProvisionDriver.ensure)
     then InstallUser. Do not require a linked login. Do not AddAct
     inside InstallApp. [Foreign Provider](../install/Foreign-Provider.md).
  3. Resolve HstId via PART 9; enforce the already-installed policy (9.4).
  4. Dispatch InstallUser to the offering provider addressed at that HstId,
     cls `(appId, install)`. Register / update the directory host record so the
     portable HstId resolves to the offering provider's Domain.
  5. On success, create Desktop tile on the home Desktop replica (PART 11).

10.3  Failures

  Surface clear errors: no offer, catalog miss, not authorized on offering
  provider, InstallUser failed for app X. Partial success (host created,
  tile missing) should be repaired by retry of InstallApp (idempotent) or a
  explicit RepairInstall Direction.

## PART 11 - DESKTOP TILES AND LAUNCH

11.1  Tile contents

  A marketplace-installed tile stores at least:

    AppId, DisplayName, IconPath,
    PrvId,           // offering provider (where the host currently lives)
    HstId,           // portable <appId>-<actId>[ -<ext> ] — PART 9
    LaunchPath       // see 11.2

11.2  LaunchPath

  Local precursor tiles use relative paths (`/domatar/<app>/...`) served by
  the page's own origin. Cross-provider tiles MUST launch against the
  offering provider's browser-facing host:

    Prefer PublicDomain (nginx front door) when set and distinct from
    Domain — strip `/domatar` because the front door re-adds context:
      http(s)://<PublicDomain>/<appId>/<appId>.html
    Else wire Domain with context:
      http(s)://<Domain>/domatar/<appId>/<appId>.html
    Alternative: store Domain + relative path; the Desktop shell resolves
      at click time via directory lookup of HstId.

  Cross-provider tiles MUST store absolute IconPath with the same
  PublicDomain / Domain policy as LaunchPath ([Icons](../platform/Icons.md) PART 5.2 /
  7.2). Local tiles use relative `/domatar/<appId>/icons/app.svg`.
  Authority: [Icons](../platform/Icons.md) PART 7.2.

11.3  Sync

  Tiles are ordinary Desktop objects; [Desktop](Desktop.md)
  replication applies. All replicas should show the same LaunchPath /
  PrvId so opening the tile from any home provider reaches the same
  offering host.

## PART 12 - DISTRIBUTION, LICENSING, PAYMENT (DIRECTION)

  * JAR distribution channel: builders publish artifacts; offering providers
    obtain and deploy them (manual drop today; future signed package pull).
  * Licensing: offer attrs may grow License, Price, Currency; InstallApp may
    require a Money payment or license grant before step 4.
  * "Builder sells to providers": commercial relationship OUTSIDE the
    InstallApp path; the marketplace only reflects who has RegisterOffer'd.
  * On-demand JAR fetch at install time ([Domatar](../Domatar.md) PART 16.2 /
    [Installation](../install/Installation.md) Direction) remains optional and AFTER the registry /
    provider-selection path works with pre-deployed JARs.

## PART 13 - AUTHORIZATION AND TRUST

  * SearchApps / GetListing: any verified user (read).
  * RegisterOffer / WithdrawOffer: offering provider account only
    (actId == that provider's PrvActId), mirroring SiteConfig isProvider.
  * InstallApp: verified user; may not install core apps that the Store
    marks non-installable if already present; may not uninstall core apps.
  * Registry writes are NOT anonymous. Listings are not signed by a separate
    "app publisher key" in v1; trust is "the offering provider's catalog +
    RegisterOffer under that provider's identity". Stronger publisher
    attestation is Direction.
  * Cross-provider InstallUser obeys existing Auth / delegation rules on the
    offering node — the marketplace does not weaken ObjImpl auth.

## PART 14 - ACCEPTANCE

  1. With only the local catalog populated and an empty registry, the App
     Store still shows local AvailableApps and can InstallApp on the home
     provider (precursor regression).

  2. Provider prv2 RegisterOffer(bookstore) succeeds only if bookstore is in
     prv2's catalog; the listing appears in SearchApps from a user on prv1.

  3. GetListing(bookstore) from prv1 shows an Active offer for prv2 (and
     prv1 if also registered).

  4. User on prv1 InstallApp(bookstore, PrvId=prv2) creates portable
     HstId bookstore-<actId> registered to prv2's Domain, a Desktop tile
     on the user's Desktop, and the tile launches against prv2's domain.
     A second InstallApp(bookstore, PrvId=prv1) while that host still
     points at prv2 is refused (PART 9.4) — not solved by -<prvId>.

  5. InstallApp with a PrvId that has no Active offer, or whose catalog
     lacks the app, fails clearly and creates no host/tile.

  6. WithdrawOffer(bookstore) by prv2 removes prv2 from default Search /
     GetListing Active offers; existing user installs are unaffected.

  7. UninstallApp(bookstore, prv2) removes tile + navigator link; data
     objects remain; offer registry unchanged.

  8. Re-RegisterOffer is idempotent; InstallApp twice for the same
     (user, AppId, PrvId) is idempotent.

  9. Core apps remain non-uninstallable; provider-available / default
     ([Provider Customization](../install/Provider-Customization.md) (a)/(b)) behaviour unchanged.

## PART 15 - CURRENT CODEBASE (LOCAL PRECURSOR)

Normative for UPGRADE planning — not for the target behaviour above.

  Module:   appstore/  (Maven artifact appstore)
  Manifest: AppId appstore; Handlers (appstore, app)=AppstoreAppImpl,
            (appstore, home)=AppstoreImpl; Wui AppstoreWui;
            InstallClass AppstoreInstall.

  GetApps / UninstallApp     — AppstoreImpl; local LnkDb catalog + navigator.
  InstallApp                 — AppstoreWui → ActManagerImpl.InstallApp →
                               CatalogInstall.hasCatalogEntry →
                               UserInstallDispatch (LOCAL PrvId/Domain).
  installProvider            — CatalogInstall.registerInCatalog only
                               (no registry host bootstrap).
  UI                         — appstore/assets/appstore.html
                               (Installed / Available grids; no search,
                               no provider picker).
  Host naming                — per-user App Store shell is
                               appstore-<actId>-<prvId> (PART 4.1 / 9.5).
                               Marketplace target apps remain
                               <app>-<actId> (PART 9.2). UserHostIds
                               special-cases login/desktop/navigator/
                               appstore with -<prvId>.
  Central host               — `appstore` (directory-registered registry).

These classes / the Wui / ActManager install path are the App Store
surface. AppInstall.userHostIdExtension (default null) is PART 9.3.

## PART 16 - OUT OF SCOPE / OWNED ELSEWHERE

  * Default-apps editor, provider-config — [Provider Customization](../install/Provider-Customization.md).
  * First-run provider setup wizard — Direction
    ([Installation](../install/Installation.md)).
  * Desktop seed alignment with defaults — [Provider Customization](../install/Provider-Customization.md)
    PART 9.2.
  * Federated App Store as a rewrite of the App Catalog itself — rejected;
    catalog stays per-provider (PART 6.2).

## PART 17 - RELATED SPECS

Registry, RegisterOffer / WithdrawOffer / SearchApps / GetListing, and
InstallApp(PrvId) onto portable PART 9 hosts are live. Presence is
login-home OR object-only; HostProvision when missing
([Foreign Provider](../install/Foreign-Provider.md)). Distribution /
licensing / JAR marketplace / Money payment remain Direction (PART 12).
[Provider Customization](../install/Provider-Customization.md) covers
default-apps / PERSONAL install across providers.
