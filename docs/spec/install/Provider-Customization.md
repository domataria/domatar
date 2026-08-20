# DOMATAR - PROVIDER SITE CUSTOMIZATION

This document specifies how a PROVIDER customizes its own Domatar site from
within the running platform, without editing files on disk or restarting the
server. A provider-only section (SiteConfigWui) exposes the first control:
which applications are installed by default for every NEW account created on
this provider, and which AppIds fill the login / desktop / navigator /
appstore *roles* (DefaultShells). Stock appId checkboxes alone are not the
shell contract ([Platform App](../platform/Platform-App.md)).

GetDefaultAppsConfig / SetDefaultApps / ResetDefaultApps live on
AppCatalogImpl. Resolution is `com.domatar.act.ProviderDefaults`. The
provider-config row is `(domatar-<prvActId>, domatar, <prvActId>,
provider-config)` with attrs DefaultApps + Version. Browser path:
`/domatar/domatar/Wui/SiteConfigWui`.

The section is designed to GROW. "Default apps" is the first control; later
controls (branding, theme, welcome copy, sign-up policy, …) attach to the same
provider-only card and the same authorization rule. Only the default-apps
control is in scope for this document (PART 5); the rest is Direction (PART 9).

Companion documents:

- [Installation](Installation.md) — default-apps-config.txt, provider bootstrap, the sign-up install chain (PART 3 / 8 / 11)
- [Login](../apps/Login.md) — the Account app this section sits beside
- [Domatar](../Domatar.md) — the App Catalog, provider account identity, config precedence

The present-tense statements describe the implemented system. Where it depends
on existing behaviour, the relevant class / column / method is named.

## PART 1 - MOTIVATION

File-based defaults (`default-apps-config.txt`, `DOMATAR_DEFAULT_APPS`,
or `DefaultApps=` in provider.config.txt, read by
`com.domatar.core.DomatarConfig.getDefaultApps()`) are the bootstrap
fallback ([Installation](Installation.md) PART 3.2 / 8). After bootstrap, the
provider account edits the live list from SiteConfigWui without touching
disk or restarting.

A provider is itself an ordinary Domatar account (`<PrvId>@<PrvId>`, fingerprint
actId `DomatarConfig.getPrvActId()`; [Domatar](../Domatar.md) / [Identifiers](../platform/Identifiers.md)). When
that account signs in to the Account app, it should see controls that ordinary
users do not, and be able to change site policy live. The first such control
lets the provider pick the default app set from the apps already installed in
this provider's App Catalog, while GUARANTEEING that the apps required for a
usable account can never be removed.

## PART 2 - CORE CONCEPTS

2.1  Provider account

  An account is THIS PROVIDER'S account when the verified session's
  `context.actId` equals `DomatarConfig.getPrvActId()` (the fingerprint actId
  written once at bootstrap by DomatarProviderInstall). Call this predicate
  `isProvider(ctx)`. It is FALSE for every ordinary user and for a different
  provider's account.

  Customization is PER-PROVIDER and LOCAL. Each provider node has its own
  provider account and its own default-apps policy; there is no cross-provider
  replication of site customization (contrast Login membership / Desktop tiles,
  which do replicate). A user who is a member across providers still sees only
  the customization of whichever provider served the page.

2.2  Default apps (for new accounts)

  The ordered list of appIds every NEW account receives at sign-up
  ([Installation](Installation.md) PART 8). Consumed today by:
    * com.domatar.act.ActManagerImpl  — the root sign-up install chain
      (iterates DomatarConfig.getDefaultApps()).
    * com.domatar.objimpl.AppCatalogImpl.GetCatalog — the IsDefault flag.

  This spec does NOT change how a new account is provisioned from a list; it
  changes WHERE the list comes from, adding a live, provider-editable source
  ahead of the file/config fallbacks (PART 6).

2.3  Mandatory apps

  Apps that MUST always be in the effective default list, cannot be toggled
  off, and are force-injected if absent:

    navigator   the object graph + root obj every other install links into
                ([Navigator](../apps/Navigator.md) PART 10.2); installed FIRST.
    domatar     the platform's own core class objects; installed SECOND.
    login       the Account app  (user-facing "Account").
    desktop     the home screen   (user-facing "Desktop").

  navigator and domatar are already installed unconditionally by
  ActManagerImpl regardless of the list; login and desktop are only installed
  when present in the list, so the guarantee in PART 6.3 is what makes
  "Account and Desktop are always there" true.

  USER-VISIBLE mandatory apps (shown in the UI as locked/"Required"):
  Navigator, Account (login), Desktop. `domatar` is a structural platform
  dependency, not a normal app tile, and is enforced silently (not shown as a
  toggle).

2.4  Effective default apps

  The list returned to the sign-up path after policy resolution (PART 6) and
  mandatory-app normalization (PART 6.3). This is the single value that
  actually governs new-account provisioning.

2.5  App availability levels (provider-available → default → personal)

  An app relates to a provider and its users at THREE independent levels. Only
  the middle one is edited by this document's default-apps control; the other
  two already exist and are named here so the boundaries are clear:

    (a) PROVIDER-AVAILABLE — the app is in this provider's App Catalog
        ((domatar, catalog) on domatar-<prvActId>). This is the pivotal act:
        an app becomes available on the provider by being deployed
        (WEB-INF/apps/<appId>.jar) and catalog-registered via its
        installProvider (run at /Setup, or /<appId>/Setup for one added
        later; CatalogInstall.registerInCatalog, idempotent). A provider can
        add a new app AFTER first installation this way at any time.

    (b) DEFAULT — the app is in the effective default set (PART 2.4), so every
        NEW account receives it automatically. Editing this set is what
        SetDefaultApps (PART 4.2) does. Only PROVIDER-AVAILABLE apps are
        eligible (SetDefaultApps rejects non-catalog appIds).

    (c) PERSONAL — an individual user has installed the app onto their own
        account/desktop via the App Store ([App Store](../apps/AppStore.md);
        [Installation](Installation.md) PART 7). The user may choose an offering
        provider other than home; InstallApp provisions on that provider and
        places a Desktop tile (hosting provider may differ from home).
        Local "On this provider" GetApps / InstallApp remains the precursor
        fallback. Removing an app from DEFAULT never uninstalls it from
        anyone, and never removes it from PROVIDER-AVAILABLE.

  Consequence: the moment a provider adds an app at level (a), it AUTOMATICALLY
  becomes available to every user to add personally at level (c) via the App
  Store — no default-set change required. Adding it to DEFAULT (b) is the
  optional extra step that makes it show up for future sign-ups without the
  user having to add it. This document's editor operates on (b); it depends on
  (a) and complements (c).

## PART 3 - WHERE IT LIVES (the Account app section)

3.1  A new card in account.html

  The Login app's Account page (login/src/main/resources/login/assets/
  account.html) gains a third card, AFTER "Linked logins / providers":

    ┌───────────────────────────────────────────────┐
    │  Site customization                             │
    │  Settings for this provider's site.             │
    │                                                 │
    │  Default apps for new accounts                  │
    │   [x] Navigator      (Required)                 │
    │   [x] Account        (Required)                 │
    │   [x] Desktop        (Required)                 │
    │   [x] Quippin                                   │
    │   [ ] Bookstore                                 │
    │   [x] Money                                     │
    │   … one row per app installed in this           │
    │     provider's App Catalog …                    │
    │                                    [ Save ]     │
    └───────────────────────────────────────────────┘

  The card is RENDERED ONLY when the page learns it is signed in as the
  provider account (PART 4.1). For every ordinary user the card is absent —
  it is not merely hidden with CSS; the page does not request or build it.

3.2  Client behaviour

  On load, in addition to the existing ListMembership call, account.html
  requests the site-config view (PART 4.1). If `IsProvider` is false it does
  nothing further. If true it renders the card:

    * One checkbox row per app in `Apps`, labelled by AppName, ordered as
      returned (mandatory apps first in canonical order, then the rest).
    * Mandatory rows are checked and DISABLED, with a "Required" badge.
    * Non-mandatory rows reflect the current IsDefault state and are editable.
    * If `Locked` is true (operator pinned the list via env; PART 6.1) the
      whole control is read-only with an explanatory note; Save is hidden.

  Save collects the checked appIds and POSTs SetDefaultApps (PART 4.2). On
  success the card re-renders from the server's response; on error the message
  is shown inline (same pattern as the existing modals).

  Mandatory checkboxes being disabled is a UX affordance only; the SERVER is
  authoritative (PART 6.3 / 7).

## PART 4 - PROTOCOL

The authoritative handler for site customization is the App Catalog handler
`com.domatar.objimpl.AppCatalogImpl`, class `(domatar, catalog)`, on the
provider account's Domatar sub-host:

    dst = DomId( subHstId("domatar", prvActId), "domatar", prvActId,
                 "app-catalog" )

This handler already owns the catalog, already computes IsDefault, and is
already owned by the provider account, so it is the natural home. The Account
page reaches it through the platform Domatar-app Wui (the same servlet that
serves ActWui / the catalog), NOT through the Login app's AccountsWui — the
default-apps list is platform policy, not per-login identity.

4.1  GetDefaultAppsConfig  (Read)

  Request:  { }   (destination + verified session identify the caller)

  Response body:
    {
      "IsProvider": "True" | "False",
      "Locked":     "True" | "False",     // env override pins the list
      "Apps": [
        { "AppId": "navigator", "AppName": "Navigator",
          "IsDefault": "True", "IsMandatory": "True" },
        { "AppId": "login",     "AppName": "Account",
          "IsDefault": "True", "IsMandatory": "True" },
        { "AppId": "desktop",   "AppName": "Desktop",
          "IsDefault": "True", "IsMandatory": "True" },
        { "AppId": "quippin",   "AppName": "Quippin",
          "IsDefault": "True", "IsMandatory": "False" },
        …
      ]
    }

  * When IsProvider is False the handler returns `{ "IsProvider": "False" }`
    with no Apps (do NOT leak catalog/policy shape to non-providers beyond
    what GetCatalog already exposes).
  * Apps lists EVERY app in this provider's catalog. `IsMandatory` is true for
    navigator, login, desktop (the user-visible mandatory set; PART 2.3);
    `domatar` is omitted from Apps (structural, not a toggle).
  * Ordering: mandatory first (Navigator, Account, Desktop), then the rest in
    the effective default order, then remaining installed-but-not-default apps.

  This is an additive convenience view. `GetCatalog` (existing) keeps its
  current contract; a plan may implement GetDefaultAppsConfig by extending
  GetCatalog internally, but the response shape above is the one account.html
  depends on.

4.2  SetDefaultApps  (SideEffect: Write, provider-only)

  Request body:
    { "AppIds": "navigator,login,desktop,quippin,money" }   // ordered CSV

  Behaviour:
    1. Authorize: verified session AND isProvider(ctx). Otherwise NotAuthorized
       (PART 7). This check is MANDATORY and independent of the client hiding
       the card.
    2. Reject if Locked (env override present): error "Default apps are pinned
       by DOMATAR_DEFAULT_APPS and cannot be edited here."
    3. Parse AppIds (comma-separated, trim, drop empties).
    4. Reject any appId that is not in this provider's catalog
       (mirrors the sign-up "not in provider catalog" guard).
    5. Normalize (PART 6.3): force-include mandatory apps in canonical order
       at the front, dedupe preserving first occurrence, keep the provider's
       order for the remainder.
    6. Persist the normalized ordered list (PART 6.2) and bump its Version.
    7. Respond with the same shape as GetDefaultAppsConfig (so the client
       re-renders from the authoritative result).

  Idempotent: saving the same list twice changes nothing but the Version
  stamp is refreshed.

4.3  ResetDefaultApps  (SideEffect: Write, provider-only)

  Request body: { }

  Deletes the provider customization object (PART 6.2), so resolution
  (PART 6.1) falls back to the file / config / built-in default. This is the
  fix for the precedence footgun: once a provider has Saved, the object
  (source 2) outranks default-apps-config.txt (source 3), so later edits to
  that file would otherwise appear to do nothing; ResetDefaultApps restores
  file-driven behaviour. Authorized to isProvider(ctx); rejected when Locked.
  Responds with the same shape as GetDefaultAppsConfig (now reflecting the
  fallback source). The client exposes this as a "Reset to default" control on
  the card.

## PART 5 - SCOPE OF THIS DOCUMENT

IN SCOPE:
  * The provider-only "Site customization" card in account.html.
  * The default-apps editor: GetDefaultAppsConfig / SetDefaultApps.
  * Mandatory-app enforcement (navigator, domatar, login, desktop).
  * Live persistence of the provider's chosen default list and its use by the
    sign-up path.
  * Clarifying the app availability model (PART 2.5): how an app added to the
    provider after installation becomes user-available and eligible for the
    default set.

ALREADY DELIVERED BY EXISTING MECHANISMS (named here, not re-specified):
  * Making an app available to users who want to add it to their personal
    setup. Deploying + catalog-registering the app (PART 2.5 level (a)) makes
    it appear in every user's App Store as an AvailableApp, and InstallApp
    (appstore) adds it to a user's account. No new mechanism is required for
    "make it available to users."

OUT OF SCOPE (Direction, PART 9):
  * An in-UI provider control to ADD/enable an app on the provider after
    installation (deploy discovery + run installProvider + catalog register)
    from the Site customization card, rather than via file drop + /Setup.
    See PART 9.4. Until then, adding an app is the deploy + /Setup step of
    PART 2.5 (a); this document's editor then lets the provider toggle it
    into the default set.
  * Any other customization (branding, theme, colors, welcome text, sign-up
    open/closed policy, custom icons).
  * Aligning the hard-coded Desktop seed catalog
    (com.desktop.objimpl.AppsImpl.seedDefaultCatalog) with the default-apps
    policy. Today that method seeds Desktop tiles from a fixed list that is
    INDEPENDENT of getDefaultApps(); reconciling the two is deferred (PART 9.2)
    and MUST NOT be assumed by this spec's implementation.
  * Changing what an EXISTING account has installed. This control affects
    FUTURE sign-ups only (PART 6.4).
  * The global, cross-provider App Store MARKETPLACE (builders registering
    apps for discovery, users on other providers searching and choosing a
    hosting provider, cross-provider install). PART 2.5's App Store is the
    LOCAL precursor; the federated marketplace is a separate subsystem — see
    PART 9.5 and the future [App Store](../apps/AppStore.md).

## PART 6 - RESOLUTION, PERSISTENCE, ENFORCEMENT

6.1  Precedence of the default-apps source

  The effective default list is resolved by a new helper
  `com.domatar.act.ProviderDefaults.resolveDefaultApps()` in the domatar-app
  module (which already has DB access and is where sign-up consumes the list).
  It does NOT live in domatar-core, so DomatarConfig keeps no DB dependency.

  Precedence (first hit wins):

    1. DOMATAR_DEFAULT_APPS env / system property.
       Operator HARD override. When set, the UI is Locked (PART 4.1) and
       SetDefaultApps is rejected. This is the escape hatch that lets an
       operator freeze policy regardless of the in-app control.

    2. Provider customization object (PART 6.2), if present.
       This is what SetDefaultApps writes and what the provider edits live.

    3. default-apps-config.txt        (existing file; bootstrap default).
    4. DefaultApps= in provider.config.txt   (legacy).
    5. Built-in DomatarConfig.DEFAULT_APPS.

  Sources 1, 3, 4, 5 are exactly today's DomatarConfig.getDefaultApps() chain;
  this spec inserts source 2 between the env override and the files, and routes
  all sign-up / catalog reads through ProviderDefaults.resolveDefaultApps()
  instead of calling DomatarConfig.getDefaultApps() directly.

  On FIRST use (no customization object yet) resolution falls through to the
  file/config/built-in default, so behaviour is unchanged until a provider
  saves once. The initial GetDefaultAppsConfig therefore reflects the file
  default; the provider's first Save materializes source 2.

6.2  Persistence — the provider customization object

  Domatar-native: state is an object, not a file. A single object holds this
  provider's site customization, on the provider account's Domatar sub-host,
  alongside the app-catalog container:

    HstId : domatar-<prvActId>          (subHstId("domatar", prvActId);
                                         HOST_SEP is '-', see DomId.HOST_SEP)
    AppId : domatar
    ActId : <prvActId>
    ObjId : provider-config
    Class : (domatar, providerConfig)   // stamped on the row; no handler /
                                         // descriptor yet (KD2). Reads/writes
                                         // go through AppCatalogImpl (KD1).

    Attrs:
      "DefaultApps" : "navigator,login,desktop,quippin,money"   (ordered CSV;
                       the NORMALIZED list, mandatory apps included)
      "Version"     : "<base64 time>"   (LWW stamp; future-proofing even though
                                         customization is not replicated today)

  Created lazily on the first SetDefaultApps. Owned by the provider account;
  read/write authorized to isProvider(ctx) only. Being a normal obj row it is
  captured by mysqldump and survives restarts (unlike a container-FS file),
  which is the whole point of moving off default-apps-config.txt for live edits.
  Deleted by ResetDefaultApps (PART 4.3), after which resolution falls back to
  the file / config / built-in default (sources 3–5).

  The object is a natural growth point: future customizations (PART 9) add more
  attrs here rather than new files.

6.3  Mandatory-app normalization

  Whenever the effective list is produced (both in SetDefaultApps before
  persisting, AND in resolveDefaultApps after reading any source), it is
  normalized:

    1. Start with the canonical mandatory prefix, in this exact order:
         navigator, domatar, login, desktop
    2. Append the caller's remaining appIds in their given order.
    3. Dedupe, keeping the first occurrence (so a mandatory app the provider
       also listed later does not appear twice, and its canonical position
       wins).

  This guarantees Account (login), Desktop, and Navigator are always present
  and correctly ordered, that domatar stays second (its install-order
  requirement), and that no source — object, file, env, or a hand-edited row
  — can drop a mandatory app. `domatar` is injected even though it is not shown
  as a UI toggle.

  Fail-safe: normalization NEVER throws for a missing mandatory app; it injects
  it. An unknown (non-catalog) appId is rejected at the SetDefaultApps boundary
  (PART 4.2 step 4), not silently dropped, so the provider gets clear feedback.

6.4  When it takes effect

  resolveDefaultApps() is read at SIGN-UP time (ActManagerImpl root install
  chain). Saving a new list therefore affects accounts created AFTER the save.
  Existing accounts are never retro-provisioned or de-provisioned by this
  control — consistent with the platform's per-account, minting-time philosophy
  (cf. act.FpVersion, [Identifiers](../platform/Identifiers.md) PART 18). Removing an app from the
  default set does NOT uninstall it from anyone.

## PART 7 - SECURITY / AUTHORIZATION

  * Every operation requires a verified session (as all Wui dispatch does).
  * GetDefaultAppsConfig returns provider-only detail ONLY when
    isProvider(ctx); otherwise `{ "IsProvider": "False" }`.
  * SetDefaultApps is provider-only. A non-provider verified session MUST get
    NotAuthorized — the server never trusts the client having hidden the card.
  * The identity test is `context.actId == DomatarConfig.getPrvActId()`, using
    the fingerprint actId, never a parsed `<PrvId>@<PrvId>` string or a
    display name.
  * Locked (env override) makes SetDefaultApps reject even for the provider —
    the operator's file/env intent outranks the in-app editor.
  * Input is constrained to appIds already present in this provider's catalog;
    the persisted value is the normalized list, so a compromised or buggy
    client cannot inject an app the provider has not installed, nor drop a
    mandatory app.

## PART 8 - ACCEPTANCE

  1. Signed in as an ordinary user, account.html shows NO "Site customization"
     card; GetDefaultAppsConfig returns IsProvider=False; SetDefaultApps
     returns NotAuthorized.

  2. Signed in as `<PrvId>@<PrvId>`, the card appears and lists every app in
     the provider catalog. Navigator, Account, and Desktop are checked and
     disabled with a "Required" badge.

  3. Unchecking a non-mandatory app (e.g. Bookstore) and Save, then reloading,
     shows the change persisted; the provider-config obj row carries the
     normalized DefaultApps CSV and a Version.

  4. Attempting (via a crafted request) to Save a list WITHOUT login/desktop/
     navigator results in a stored list that STILL contains
     navigator, domatar, login, desktop in canonical order (normalization).

  5. Attempting to Save an appId not in the catalog is rejected with a clear
     error and nothing is persisted.

  6. A NEW sign-up created after a Save receives exactly the effective default
     set (mandatory apps + the provider's chosen extras); a sign-up's installed
     apps change when the provider changes the list, and prior accounts are
     unaffected.

  7. With DOMATAR_DEFAULT_APPS set, the card renders read-only (Locked), Save
     is hidden, and SetDefaultApps is rejected; resolveDefaultApps returns the
     env list (normalized).

  8. The provider-config row is present in a fresh mysqldump and survives a
     tomcat recreate (no file on the container FS is required).

  9. After a Save, ResetDefaultApps removes the provider-config object and the
     effective default list reverts to the file / built-in default; a
     subsequent GetDefaultAppsConfig reflects that fallback.

## PART 9 - DIRECTION

9.1  More customization controls

  The provider-only card is the home for future site policy: display/branding
  (site name, logo, colors), sign-up policy (open / invite-only / closed),
  default Desktop layout, per-site welcome copy. Each attaches to the same
  card, the same isProvider(ctx) gate, and (preferably) the same
  provider-config object — new attrs rather than new files.

9.2  Desktop seed alignment

  com.desktop.objimpl.AppsImpl.seedDefaultCatalog currently seeds Desktop tiles
  from a fixed, code-level list independent of the default-apps policy. A later
  change could drive the seeded tiles (or at least their membership) from the
  effective default set so that "what a new account has installed" and "what
  tiles it sees" agree. Deferred and explicitly OUT OF SCOPE here.

9.3  Replication

  Customization is per-provider and local today. The Version stamp on the
  provider-config object is reserved so a future multi-node provider (or a
  provider-account replica) could LWW-merge site policy if that ever becomes
  desirable.

9.4  In-UI provider app management

  Today a provider adds a new app after installation by deploying the JAR and
  running /Setup (PART 2.5 (a)); the Site customization card can then toggle it
  into the default set. A later enhancement adds an "Apps on this provider"
  control to the same provider-only card that lets the provider, from the
  browser:
    * see every app the platform has LOADED (AppRegistry) vs. those already
      catalog-registered (PROVIDER-AVAILABLE);
    * enable a loaded-but-unregistered app — running its installProvider and
      registering it in the catalog (the deploy of the JAR itself stays an
      operator/file-system or upload step, out of scope here);
    * optionally, in one action, also add the newly enabled app to the default
      set (PART 4.2).
  Enabling an app this way immediately makes it an AvailableApp in every user's
  App Store (PART 2.5 (c)); adding it to DEFAULT additionally gives it to
  future sign-ups (PART 2.5 (b)). Authorization is the same isProvider(ctx)
  gate; provider-available/default state persists as it does in PART 6.2.

9.5  Relationship to the global App Store

  PART 2.5 describes the LOCAL availability ladder on one provider. Level (c)
  PERSONAL install is [App Store](../apps/AppStore.md): a central
  registry of listings and per-provider offers, SearchApps, provider selection
  at InstallApp, portable per-user app hosts (optional app host-id extension
  hook — not prvId-in-HstId), cross-provider InstallUser, and Desktop tiles
  that launch on the offering provider's domain. Levels (a) supply and (b)
  default in THIS document are unaffected. Treat PART 2.5 (c) as "install via
  App Store, hosting provider may differ from home provider."
