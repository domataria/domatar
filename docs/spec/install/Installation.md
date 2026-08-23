# SPEC: DOMATAR INSTALLATION

This document specifies how Domatar and its applications are installed: on
a server, on a provider, and on a user account. It is the operational
companion to [Domatar](../Domatar.md); where [Domatar](../Domatar.md) describes the
abstractions and the runtime, this document describes the lifecycle by
which those abstractions come into existence and how they grow.

Like [Domatar](../Domatar.md), this document is forward-looking: present-tense
statements describe the system as it is built; "Direction:" subsections
describe where the system is going and the work that remains. The
present packaging model (apps as JARs in a single platform WAR) is the
foundation for the longer-term goal of decentralised, third-party
application publication and per-user, cross-provider app installation.

Level 3 writes platform substrate first. `AddAct` / attach-provider call
`UserSubstrateInstall.ensureUserSubstrate` on each login home; default
shell AppIds then `InstallUser` locally ([Platform App](../platform/Platform-App.md)).

## PART 1 — OVERVIEW
--------------------------------------------------------------------------------

Domatar has three independent installation levels. Each is triggered by a
different agent and produces a different artefact.

  Level 1 — PROVIDER INSTALLATION
      Installing Domatar on a new physical server for the first time.
      Triggered by:  the system operator, once per server.
      Produces:      a running Domatar provider with its administrator
                     account, infrastructure objects, and the App Catalog.

  Level 2 — APPLICATION INSTALLATION (provider level)
      Installing a new application on an already-running provider.
      Triggered by:  the system operator, once per (provider, app).
      Produces:      the provider's catalog row for that app, any
                     provider-scoped hosts the app owns, and (transitively)
                     the app's class-descriptor objects on every account
                     that subsequently installs it.

  Level 3 — USER INSTALLATION (account level)
      Installing an application on a user account.
      Triggered by:  either the platform automatically at account
                     sign-up (for default apps), or the user via the
                     AppStore.
      Produces:      the user's per-account sub-host for that app, the
                     app's container and data objects on that sub-host,
                     the user's Navigator and Desktop entries, and the
                     class-descriptor objects on the user's
                     navigator-<actId> sub-host.

Design principles. The whole installation surface is built to honour
these:

  P1. Simplicity.
      Each installation level requires the minimum possible manual steps.
      Configuration is done by editing plain text files, not by running
      scripts or navigating a database. The operator's interaction is
      "drop the artefact, hit /Setup, optionally edit one config file."

  P2. Isolation.
      Files belonging to one application are never placed in a directory
      shared with files from another application. Each app is
      self-contained in its own JAR — handlers, install class, Wui
      servlets, manifest, config, HTML, JS, CSS, icons.

  P3. Per-host self-containment.
      Every Domatar host (provider host, app central host, per-user
      sub-host) carries its own copy of every class descriptor and
      every link it needs. Host migration is then just "copy this
      host's objects, accounts, and links".

  P4. Config files own the variation.
      All site-specific settings live in plain text config files. There
      is one per-provider config (provider.config.txt), one per-provider
      defaults file (default-apps-config.txt), and one per-app config
      (app.config.txt) bundled inside each app JAR.

  P5. Idempotence.
      Every install routine is safe to run more than once. A second run
      of any install step is a no-op. Repeated installs of the same app
      on the same user, repeated /Setup invocations, repeated provider
      bootstraps — all are no-ops.

  P6. No platform recompile per new application.
      Adding a new application to a running Domatar network does not
      require modifying or rebuilding any pre-existing artefact —
      neither the platform WAR nor any other application's JAR. The
      only intentional exception is editing default-apps-config.txt
      to mark a new app as default for new accounts.

P6 — zero-coupling — is the architectural ambition that drives every
choice in this document. PART 10 catalogues how the present design
achieves it and which residual constraints still need work.

## PART 2 — THE ARTEFACT
--------------------------------------------------------------------------------

Domatar ships as one Tomcat WAR: `domatar.war`. It is byte-identical on
every server in a Domatar network. Inside it, applications live as JARs
under `WEB-INF/apps/`. The platform itself is one of those JARs
(`domatar-app.jar`). See [Domatar](../Domatar.md) PART 11 for the full
packaging spec; the parts that matter here:

2.1  Layout

    domatar.war/
      WEB-INF/
        web.xml                          minimal: DbConnection,
                                         AppLoader listener,
                                         Setup servlet
        lib/
          domatar-core-1.0-SNAPSHOT.jar  platform runtime + install SPI
          mysql-connector-j-*.jar
        apps/
          domatar-app.jar                the platform's own app
          navigator.jar
          login.jar
          desktop.jar
          appstore.jar
          quippin.jar
          bookstore.jar
          spreadsheet.jar
          money.jar
          aiagent.jar
          <third-party-appId>.jar        any additional app the provider
                                         has installed
      jquery-3.5.1.js, domatar.css,      shared platform-level assets
      index.html, quip.js, …

2.2  What an application JAR contains

  com/<appId>/objimpl/*.class            handler classes
  com/<appId>/install/<App>Install.class AppInstall implementation
  com/<appId>/webui/*.class              Wui servlet classes (browser
                                         entry points)
  META-INF/domatar/app.manifest          declarative descriptor (PART 4.2)
  <appId>/app.config.txt                 AppName / AppDesc / Version /
                                         DependsOn / ProviderHosts
  <appId>/assets/<file>                  HTML, JS, CSS, PNGs
  <appId>/assets/icons/app.svg           the launcher icon
  <appId>/assets/icons/cls/<clsId>.svg   per-class icons

2.3  How JARs become a running app at startup

  `com.domatar.app.AppLoader` is the sole ServletContextListener
  declared in the platform WAR's web.xml. At Tomcat startup it
  scans `WEB-INF/apps/` and, for each JAR, in filename order:

    1. Creates a URLClassLoader for the JAR (parent = WebappClassLoader).
    2. Reads META-INF/domatar/app.manifest.
    3. Instantiates the install class (the value of `InstallClass:`).
    4. For each `Handler:` line, registers (clsAppId, clsId) → handler
       in `ImplMap`.
    5. Registers `(appId, "install")` → `AppUserInstallHandler(appId)`
       so InstallUser dispatches reach this app's installUser method.
    6. For each `Wui:` line, registers an HttpServlet at
       `/domatar/<appId>/Wui/<WuiName>/*`.
    7. Registers an `AppAssetServlet` at `/domatar/<appId>/*` so the
       JAR's `<appId>/assets/` tree is served as static files.
    8. Records the loaded app in `AppRegistry`.

  After AppLoader completes, every code path in the system can find
  any loaded app's handlers (via ImplMap), URL routes (via the
  ServletContext), classloader (via AppRegistry), and asset bytes
  (via AppAssetServlet). No code in `domatar-core` or any app
  references another app by Java import.

  Direction: hot-reload of individual app JARs (close the
  URLClassLoader and reopen) without restarting the platform WAR.
  Today the deploy cycle is `mvn package -pl domatar -am` followed
  by `docker compose restart`.

2.4  Build vs. operate

  The multi-module Maven reactor in the source tree (parent POM, per-app
  modules, the domatar WAR module that copies each app JAR into
  `WEB-INF/apps/` at package time) is **build** machinery used by the
  ISV that produces the reference distribution. The provider does NOT
  edit pom.xml to install a new application — they receive a
  pre-built `<appId>.jar`, drop it into `WEB-INF/apps/`, and restart.

  The local docker-compose simulation in the development repo is a
  developer convenience for exercising two providers on one machine;
  it is not part of the production provider contract. [Domatar](../Domatar.md)
  PART 14 describes it.

## PART 3 — PROVIDER INSTALLATION
--------------------------------------------------------------------------------

Provider installation sets up Domatar on a fresh server. It is performed
once per server by the system administrator.

3.1  provider.config.txt

  This file lives at `/opt/domatar/provider.config.txt` in production
  (or wherever `DOMATAR_CONFIG_PATH` points; or bundled in the WAR as a
  development default). It holds site-wide identity and connection
  settings, one `Key=Value` per line, `#` for comments.

    # Unique identifier for this host within the Domatar network.
    # Must match the HstId that will be registered in the directory.
    HstId=prv1

    # Provider ID. Determines the provider account identity:
    # <PrvId>@<PrvId>. Defaults to HstId if not set.
    PrvId=prv1

    # Public domain (or host:port for dev) of this server.
    Domain=domatar.example.com

    # JDBC connection URL. May also come from DOMATAR_DB_URL.
    DbUrl=jdbc:mysql://db/domatar?user=root&password=domatar

    # Initial password for the provider administrator account.
    # MUST be changed immediately after first login.
    AdminPassword=changeme

  Every value here is overridden by the corresponding environment
  variable (DOMATAR_HSTID, DOMATAR_PRVID, DOMATAR_DOMAIN, DOMATAR_DB_URL,
  DOMATAR_ADMIN_PASSWORD) and then by a JVM system property of the same
  name lowercased with dots. The full precedence is documented in
  [Domatar](../Domatar.md) PART 12. Default applications for new accounts are
  NOT in this file; see PART 8.

3.2  default-apps-config.txt

  Beside `provider.config.txt`, the operator may place a
  `default-apps-config.txt` listing the appIds that every new user
  account should receive automatically at sign-up. Keeping this in a
  separate file makes "edit defaults" a safe operation that cannot
  accidentally touch HstId, DbUrl, or AdminPassword.

  Format: UTF-8, `#` line comments, comma-separated appIds; multiple
  lines are concatenated in order with later duplicates ignored.

    # Default apps for every new account (order matters).
    navigator,domatar,login,desktop,appstore,quippin,bookstore,
    spreadsheet,money,aiagent

  Override paths:
    * `DOMATAR_DEFAULT_APPS_CONFIG` env / system property names an
      explicit path.
    * `DOMATAR_DEFAULT_APPS` env / system property overrides the whole
      list with an inline comma-separated value.
    * Legacy `DefaultApps=` key in `provider.config.txt` is still read
      if no dedicated file is present.

  Live provider edits ([Provider Customization](Provider-Customization.md)): sign-up now
  resolves via `com.domatar.act.ProviderDefaults.resolveDefaultApps()`,
  which inserts the `provider-config` object (source 2) between the env
  override and the files above. `ResetDefaultApps` deletes that object
  and restores file-driven behaviour.

3.3  Installation steps

  Prerequisites: Java 17+, Apache Tomcat 10.1+, MySQL 8+. The
  reference distribution also runs unchanged under Docker.

  Step 1. Edit `provider.config.txt` (and optionally
          `default-apps-config.txt`) with site-specific values.

  Step 2. Provision the MySQL database `domatar`. Load
          `mySQL/schema.sql` from the source distribution (Compose
          bind-mounts it into `/docker-entrypoint-initdb.d` on first
          DB boot). The file creates tables only; `/Setup` creates
          the provider account and hosts. On a node that is NOT the
          directory, the `hst` table can be left empty — the local
          cache will populate from the directory at first dispatch.

  Step 3. Drop `domatar.war` into Tomcat's `webapps/` (or bind-mount
          the exploded `target/domatar/` directory, as
          docker-compose.yml does).

  Step 4. Start Tomcat. `AppLoader.contextInitialized` discovers
          every `WEB-INF/apps/<appId>.jar`, registers each one's
          handlers / Wui servlets / asset servlet, and records the
          loaded app in `AppRegistry`.

  Step 5. Trigger one-shot provider bootstrap by hitting:
              http://<domain>/domatar/Setup

          `PlatformSetupServlet` (today reached via the thin subclass
          `DomatarSetupServlet`, which wires
          `DomatarProviderInstall.install()` into the
          `doProviderBootstrap` hook) then runs the sequence in 3.4.
          The endpoint short-circuits with `isAlreadyDone()` once
          provider install has succeeded, so accidental re-runs are
          harmless.

  Step 6. Log in as `<PrvId>@<PrvId>` with the AdminPassword.
          Browse:
              http://<domain>/domatar/domatar/login.html
          Change the password from the Account settings page.

3.4  What provider installation creates

  PlatformSetupServlet.doSetup performs two stages in order:

  Stage A — Platform bootstrap (DomatarProviderInstall.install).

    1. Provider account (this realisation: local `act` table):
         actId = fingerprint of the provider genesis key
                 (DomatarConfig.getPrvActId())
         usrId = <PrvId>@<PrvId>
         password hash = SHA-256(AdminPassword)
         ip = 127.0.0.1 (initial)
    2. Standard user-install chain for the four provider-appropriate
       apps, invoked reflectively through AppRegistry so no app is
       named at compile time:
         AppRegistry.get("navigator").installInstance.installUser(...)
         AppRegistry.get("domatar"  ).installInstance.installUser(...)
         AppRegistry.get("login"    ).installInstance.installUser(...)
         AppRegistry.get("desktop"  ).installInstance.installUser(...)
       This creates `navigator-<prvActId>`, `login-<prvActId>`,
       `desktop-<prvActId>`, the root obj, and the four app-* nodes
       linked under it.
    3. The provider's own root login identity, recorded directly into
       the logins container (no msg dispatch needed at bootstrap).
    4. Phase 2 of `DomatarProviderInstall` — the provider-layer
       additions on the Domatar App's sub-host
       (`domatar-<prvActId>`):
         * `actManager` object (act, actManager).
         * Link from `app-login` to `actManager`.
         * `accounts` container obj on the login sub-host.
         * `domatar-<prvActId>` host record.
         * `app-domatar` obj on that sub-host.
         * Link from the Navigator root to `app-domatar`.
         * `hosts` container obj.
         * One `(domatar, host)` child per row in the local `hst`
           table.
         * `clss` container plus all platform class descriptors
           ([Platform App](../platform/Platform-App.md) PART 8).
    5. App Catalog container ensured; `(domatar)` app registered
       in the catalog.

    See [Platform App](../platform/Platform-App.md) for the full object structure produced by
    Stage A.

  Stage B — Per-app provider install.

    PlatformSetupServlet then iterates `AppRegistry.all()` and calls
    each loaded app's `AppInstall.installProvider(prvId, domain)`.
    Every app's `installProvider` is expected to:
      * Register the app in the provider's App Catalog via
        `CatalogInstall.registerInCatalog(prvId, appId)` (idempotent).
      * Create any provider-scoped hosts the app owns (entries in
        `ProviderHosts` of the manifest / app.config.txt).
      * Perform any one-off provider-level DB setup the app needs.
    `installProvider` is idempotent; subsequent /Setup runs are
    no-ops.

3.5  Provider account vs. user accounts

  The provider account `<PrvId>@<PrvId>` is administrative: it owns
  the Domatar App's per-provider sub-host, the App Catalog, and the
  list of all accounts on this provider. It is not pre-loaded with
  Quippin, Bookstore, Money, etc.; the operator may choose to install
  them on the provider account via the AppStore like any other user.
  See [Platform App](../platform/Platform-App.md) PART 3.3 for the resulting Navigator tree.

  Direction: a per-provider catalog admin UI that lets the
  administrator enable / disable / version apps on the provider
  without filesystem manipulation. Today the only ways to add an app
  are dropping a new JAR + restart, or re-running /Setup after a
  new JAR appears.

## PART 4 — APPLICATION INSTALLATION (PROVIDER LEVEL)
--------------------------------------------------------------------------------

The application vendor publishes a single artefact: `<appId>.jar`. The
provider installs the application by dropping the JAR into the platform
WAR's `WEB-INF/apps/` directory and restarting (or hot-reloading; see
PART 2.3 Direction).

4.1  What the vendor ships

  Minimum deliverable:
    * `<appId>.jar` — handlers, install class, Wui servlets, assets,
      manifest, app.config.txt ([Domatar](../Domatar.md) PART 11.1).

  Optional, alongside the JAR:
    * Release notes / changelog.
    * Migration instructions if the app uses an external resource
      (e.g. an LLM API key — see [AI Agent](../apps/AIAgent.md) PART 13.3).

  Nothing else is required. In particular:
    * No platform recompile.
    * No edit to any pre-existing JAR.
    * No edit to `web.xml`.
    * No edit to a central `ContextListener`.
    * No edit to `ImplMap` Java.
    * No central registry of (appId → install class).

  This is principle P6, realised: the only files the provider touches
  are the new JAR itself and (optionally) `default-apps-config.txt`.

4.2  app.manifest

  Every application ships `META-INF/domatar/app.manifest` inside its
  JAR. The manifest is the canonical declaration of the app to the
  platform. Format: UTF-8, `#` line comments, blank lines ignored, one
  `Key: value` per line.

    AppId:          quippin
    Version:        1.0
    InstallClass:   com.quippin.install.QuippinInstall
    AssetDirectory: quippin/assets

    # Optional. Comma-separated host-id patterns the app's
    # installProvider creates. {PrvId} is the provider placeholder.
    ProviderHosts:

    # One Handler line per class the app provides.
    Handler: quippin, quip,        com.quippin.objimpl.QuipImpl
    Handler: quippin, quips,       com.quippin.objimpl.QuipsImpl
    Handler: quippin, follows,     com.quippin.objimpl.FollowsImpl
    Handler: quippin, news,        com.quippin.objimpl.NewsImpl
    ...

    # One Wui line per browser-facing entry point.
    Wui: QuipWui,  com.quippin.webui.QuipWui
    Wui: QuipsWui, com.quippin.webui.QuipsWui
    ...

  Required keys: `AppId`, `InstallClass`, `AssetDirectory`.
  Optional keys: `Version` (default "1.0"), `ProviderHosts` (default
  empty), `Handler*`, `Wui*` (default zero).

  Parsing is handled by `com.domatar.app.AppManifest`. Duplicate
  Handler keys throw at load time; unknown keys are rejected.

  Direction:
    * Allow the manifest to point at, or embed, the app's class
      descriptors ([Class](../platform/Class.md)) so the schema layer becomes part
      of the declarative install rather than living only in install
      Java code.
    * Add a `DependsOn:` key for explicit app dependency declaration
      (e.g. AppStore depends on Domatar catalog support; today the
      DependsOn list lives in app.config.txt instead).

4.3  app.config.txt

  Every application ships `<appId>/app.config.txt` inside its JAR. The
  provider should not normally need to edit this file; it describes
  the application to the platform.

    # Unique identifier for this application.
    AppId=quippin

    # Human-readable name shown in the App Store and on tiles.
    AppName=Quippin

    # Short description shown in the App Store.
    AppDesc=Microblogging and social feed

    # Application version. Checked during upgrades.
    Version=1.0

    # Comma-separated list of appIds that must be installed first.
    # Usually empty or "navigator,login,desktop".
    DependsOn=navigator,login,desktop

    # Comma-separated list of host-id patterns that installProvider()
    # creates on this provider. Use {PrvId} as a placeholder for the
    # provider ID. Informational — the actual creation lives in
    # AppInstall.installProvider. Leave empty if the app creates only
    # per-user hosts.
    ProviderHosts=

  `CatalogInstall.registerInCatalog` reads this file via the app's
  classloader (`AppConfig.load(appId, classLoader)`) when the catalog
  entry is created.

4.4  AppInstall — the install SPI

  Every application JAR provides a class that implements
  `com.domatar.install.AppInstall`:

    public interface AppInstall {
        /**
         * Per-provider install. Idempotent.
         */
        void installProvider(String prvId,
                             String domain) throws DomatarException;

        /**
         * Per-user install. Idempotent. Invoked when InstallUser is
         * dispatched to (appId, install) on the user's sub-host.
         */
        void installUser(String actId,
                         String usrId,
                         String usrName,
                         String prvId,
                         String domain,
                         DomatarMsgClient msgClient)
            throws DomatarException;
    }

  Implementations live in `com.<appId>.install.<App>Install`. The
  manifest's `InstallClass:` line names this class. AppLoader
  instantiates it reflectively at startup; the same instance handles
  both lifecycle events.

  Both methods MUST be idempotent (principle P5). Typical
  implementations achieve this via `ObjDb.addObjIfMissing`,
  `LnkDb.getLnk`-then-`addLnk`, `HstDb.getHst`-then-`addHst`, and
  `CatalogInstall.hasCatalogEntry` guards.

4.5  What a provider-level install does

  `installProvider(prvId, domain)` is called once per app per provider
  — at first /Setup after the JAR appears in `WEB-INF/apps/`, and at
  every subsequent /Setup as a no-op. It typically:

    * Registers the app in the provider's catalog:
        CatalogInstall.registerInCatalog(prvId, appId);
      This reads AppName/AppDesc/Version from the app's app.config.txt
      and creates a `(domatar, catalogEntry)` child obj under the
      `app-catalog` container.
    * Creates every host listed in `ProviderHosts`, seeding any
      provider-wide shared objects on those hosts.
    * Creates any class-descriptor objects required at the provider
      level ([Class](../platform/Class.md); some apps store their descriptors only
      on per-user sub-hosts and so do nothing at the provider level).

  `installProvider` does NOT create per-user objects — those are
  produced by `installUser` at user-install time (PART 5).

  Provider central hosts (e.g. `quippin`, `login`, `bookstore`) are a
  related concept: they are the apps' authoritative servers for
  federated identity and any other globally-shared data. In the
  reference simulation, the central host's `hst` row is seeded by the
  SQL init script alongside the provider rows. A first-class
  RegisterHst protocol operation to register a new central host into
  the directory is the Direction noted in [Domatar](../Domatar.md) PART 4.2.

4.6  Step by step: install a new application on a provider

  Step 1. Obtain `<appId>.jar` from the application vendor.

  Step 2. Place it in the platform WAR's `WEB-INF/apps/`. In the
          reference distribution that is the bind-mounted
          `domatar/target/domatar/WEB-INF/apps/` directory; in a
          production deploy it is whichever directory Tomcat extracts
          the WAR to.

  Step 3. Restart Tomcat (or trigger a hot-reload of the platform
          WAR — currently `docker compose restart` in the simulation).

          AppLoader picks up the new JAR and registers its handlers,
          Wui servlets, and asset servlet.

  Step 4. Hit `http://<domain>/domatar/Setup` once.

          PlatformSetupServlet's iteration over `AppRegistry.all()`
          includes the new app; its `installProvider(prvId, domain)`
          runs, the app appears in the App Catalog, and the AppStore
          surfaces it under "Available apps".

  Step 5. Optionally, add the appId to `default-apps-config.txt` if
          new users should receive it automatically (PART 8). Existing
          users are unaffected; they can install via the AppStore.

  No source-code changes, no central registry edits, no platform
  rebuild.

  Direction: a one-step "install this JAR" protocol on the running
  platform that combines steps 2–4 without a Tomcat restart, plus
  an "uninstall this JAR" inverse. Today restart is required because
  hot-reload of an app JAR is not yet implemented.

## PART 5 — USER INSTALLATION (ACCOUNT LEVEL)
--------------------------------------------------------------------------------

User installation creates the per-account objects for one application on
one provider. It is triggered either automatically at sign-up (for apps
in the default list) or explicitly via the AppStore.

5.1  What user installation creates

  For each app installed on a user, the install routine produces:

    * The app's per-user sub-host row in `hst`:
        `<appId>~<actId>`   (e.g. `quippin-dave@quippin`)
      The sub-host lives on the user's home provider (the provider
      where the user signed up or migrated their account to).

    * The app's container and data objects on that sub-host:
        e.g. for Quippin: `quips`, `follows`, `bans`, `logs`.

    * The app node on the user's navigator sub-host:
        `navigator-<actId> / navigator / <actId> / app-<appId>`

    * Links from the Navigator root to the app node, and from the
      app node to each container.

    * The app tile on the user's Desktop:
        `desktop-<actId> / desktop / <actId> / app-<appId>`

    * Class-descriptor objects ([Class](../platform/Class.md)) for the app's
      classes on the user's navigator sub-host, so the user's
      Navigator can render every node with full type information
      without depending on any other host.

  The shape and contents are defined by each app's
  `AppInstall.installUser`; the platform does not prescribe the
  internal layout beyond the conventions in [Domatar](../Domatar.md) PART 7
  (link naming) and [Navigator](../apps/Navigator.md) (Navigator-visible structure).

5.2  The InstallUser message

  User install is invoked via a Domatar message, not a Java call. This
  is what makes the cross-provider story uniform: a user on prv1 can
  install a Bookstore account on prv2 the same way they would on prv1,
  because the InstallUser message is routed by the standard hst-based
  dispatcher.

  The message:

    Operation:   InstallUser
    Destination: <appId>~<actId> / <appId> / <actId> / userInstall
    Class:       (appId, install)
    Body:        { ActId, UsrId, UsrName, PrvId, Domain }

  Dispatch is via `com.domatar.install.UserInstallDispatch`:

    UserInstallDispatch.sendInstallUser(targetAppId,
                                        actId, usrId, usrName,
                                        prvId, domain,
                                        msgClient);

  Before sending, UserInstallDispatch ensures the destination
  sub-host exists in the local host cache (chicken-and-egg
  safety: a fresh sub-host has no host record yet, so it would be
  unroutable). It then sends the message; the receiving prv resolves
  `<appId>~<actId>` via the directory, dispatches to
  `AppUserInstallHandler(appId)`, which calls
  `AppRegistry.get(appId).installInstance.installUser(...)`. The
  handler enforces:

    * The caller must be verified (`Auth.isVerified(inMsg)`).
    * `Context.actId` must equal the `ActId` parameter — a user
      cannot trigger another user's install.

  On success the response body is empty Success; on failure the
  error string propagates back to the caller (sign-up flow or
  AppStore).

5.3  New-account flow

  When `ActManagerImpl.addAct` creates a new account, the platform:

    1. Creates the `act` row and the user's `<appId>~<actId>` sub-host
       rows for navigator and domatar.

    2. Reads the default app list from `default-apps-config.txt` (or
       the override paths in PART 3.2).

    3. Calls `AppRegistry.get("navigator").installInstance.installUser`
       and `AppRegistry.get("domatar").installInstance.installUser`
       in-process — these two need a known navigator tree to exist
       before any other app installs, so they bootstrap directly.

    4. For each remaining appId in the default list, dispatches
       `UserInstallDispatch.sendInstallUser`. Each app's install runs
       in the same JVM today (apps-as-JARs); in a future Option C
       deployment ([Domatar](../Domatar.md) PART 16) it would run wherever
       that app's central host is hosted, but the dispatch path is
       the same.

    5. On any per-app failure, the AddAct response includes:
         DefaultAppInstallHadErrors = "True"
         DefaultAppInstallFailures  = [ { AppId, Error }, ... ]
       Other default apps still install. The sign-up itself does not
       fail: the user has a valid account, just without one of the
       default apps until they retry from the AppStore.

  Direction: per-app explicit dependency ordering. Today
  default-apps-config.txt is the only ordering signal, and the
  ordering rule "navigator and domatar first" is hard-coded. A
  topological sort of `DependsOn` declarations from each app's
  manifest would make the order self-organising.

5.4  Adding an app to an existing account (the AppStore path)

  When a user clicks Install in the AppStore:

    1. The browser POSTs to `/domatar/appstore/Wui/AppstoreWui` with
       `Action=InstallApp&AppId=<appId>`.
    2. AppstoreWui dispatches the InstallApp message to ActManagerImpl
       (on the user's app-issuing central host, federated as usual).
       AppstoreImpl does NOT handle InstallApp itself — keeping the
       install routing centralised in ActManagerImpl means the
       AppStore JAR does not need compile-time knowledge of every
       sibling app.
    3. ActManagerImpl validates that the target appId is in the
       provider's App Catalog. If not, the response is a clear error
       ("App not registered on this provider; run /Setup or check the
       JAR was loaded").
    4. ActManagerImpl calls
         UserInstallDispatch.sendInstallUser(appId, ...);
       — the same path as the sign-up flow.
    5. On Success, the user's Navigator and Desktop pick up the new
       app on their next refresh.

  Direction: cross-provider InstallApp. When the AppStore picks a
  provider OTHER than the user's home provider as the host of the
  new app's data, the home provider may need to pull the app's JAR
  bytes on demand to be able to serve its assets. [Domatar](../Domatar.md)
  PART 16.2 discusses the planned `(domatar, catalog) GetAppArtifact`
  operation. Reference stacks ship every app JAR to every provider.
  Icon display does not require a local JAR ([Icons](../platform/Icons.md)).

5.5  Uninstalling an app

  The AppStore also offers Uninstall. The app's uninstall routine
  (today: `AppstoreImpl.uninstallApp`) removes the Navigator link and
  the Desktop tile, making the app invisible, but does NOT delete the
  user's data objects. Reinstalling rebinds the user to their
  preserved data.

  Direction:
    * A formal `AppInstall.uninstallUser` SPI method, symmetric to
      `installUser`. Today `AppstoreImpl.uninstallApp` operates
      generically on Navigator and Desktop links, which works for
      the standard install pattern but cannot accommodate apps that
      need to clean up provider-scoped state.
    * A separate, explicitly-confirmed "delete my data" operation
      for full removal.

5.6  Per-account installation invariants

  Across both the sign-up flow and the AppStore flow, the system
  enforces:

    * Idempotence (P5). Re-installing the same app on the same
      account is a no-op.
    * Catalog membership. InstallApp refuses if the appId is not in
      the provider's App Catalog ([Installation](Installation.md) PART 10.3 /
      AppCatalogImpl). This converts misconfigured deployments
      ("forgot to /Setup the new JAR") into clear errors rather than
      obscure routing failures.
    * Caller identity. AppUserInstallHandler refuses if
      `Context.actId` does not match the target ActId.

## PART 6 — CORE APPLICATIONS
--------------------------------------------------------------------------------

A small set of applications is conceptually unavoidable. Every Domatar
provider ships them in the reference distribution and every user
account receives them at sign-up:

  navigator    The object-graph browser. The user's view into Domatar.
               Every account must have this; [Navigator](../apps/Navigator.md).

  login        Federated identity management UI. Every account must
               have this; [Login](../apps/Login.md).

  desktop      The home-screen launcher. Every account must have this;
               [Desktop](../apps/Desktop.md).

  domatar      The platform's own application (host directory, account
               manager, App Catalog, class containers). Provider-only,
               not installed per-user beyond the class-descriptor copy.
               [Platform App](../platform/Platform-App.md).

  appstore     The application discovery and install UI. Every account
               receives this so the user can install other apps.

The remaining nine apps in the reference distribution
(Quippin / Bookstore / Spreadsheet / Money / AI Agent) are conceptually
optional: a provider may choose to ship a subset, and a user may
choose to install a subset. They are nonetheless included by default
in the reference `default-apps-config.txt` so a fresh sign-up lands on
a populated environment.

The platform has no privileged code path for "core" apps versus
others. Even navigator and domatar are loaded by AppLoader from
their JARs in `WEB-INF/apps/`. The only special-casing is in
`DomatarProviderInstall`, which knows to invoke navigator and domatar
in-process during provider bootstrap (because the provider account's
sub-host structure must exist before any other app's installUser can
run). That is one of the few residual coupling points; see PART 10.

## PART 7 — THE APP STORE
--------------------------------------------------------------------------------

The App Store is the Domatar application that lets users browse what is
available on the provider and install / uninstall apps on their
account. It is itself loaded by AppLoader like any other app.

7.1  App identity

  appId         : "appstore"
  central host  : "appstore"  (global registry of listings/offers —
                  [App Store](../apps/AppStore.md); sim on prv2). Local GetApps still
                  reads this provider's App Catalog (precursor fallback).
  per-user host : appstore-<actId>-<prvId>  (shell replica per login
                  home; DomId.HOST_SEP = '-'). Central registry host
                  remains `appstore` ([App Store](../apps/AppStore.md) KD9).

7.2  What the App Store shows

  The browser page at `/domatar/appstore/appstore.html` has two tabs:

    Marketplace (default)
      SearchApps / GetListing against the central `appstore` registry
      ([App Store](../apps/AppStore.md)). Listing detail shows Active offers with a
      provider picker; Install sends InstallApp(AppId, PrvId).

    On this provider
      Local precursor lists from `(appstore, home).GetApps`:

      a. Installed apps — apps the user already has on this account.
         Each row has an Uninstall button (except for core apps).

      b. Available apps — apps registered in THIS provider's App
         Catalog that the user does not yet have.
         Each row has an Install button (home PrvId).

  Provider accounts also see a Register / Withdraw offer panel
  (RegisterOffer / WithdrawOffer). Ordinary users do not.

  Cross-provider InstallApp(AppId, non-home PrvId) is implemented
  ([App Store](../apps/AppStore.md) / MarketplaceInstall). GetApps remains the local
  offline/fallback catalog view when the registry is empty or unreachable.

7.3  The App Catalog

  Each provider's App Store reads from a per-provider catalog on the
  Domatar App sub-host:

    domatar-<prvActId> / domatar / <prvActId> / app-catalog
        ↓  tagAppId="domatar" tag="catalogEntry"
    domatar-<prvActId> / domatar / <prvActId> / catalog-<appId>

  Each catalog-entry obj is an instance of `(domatar, catalogEntry)`
  with Attrs:

    AppId    : the application's appId
    AppName  : human-readable name (from app.config.txt)
    AppDesc  : short description (from app.config.txt)
    Version  : version string (from app.config.txt)

  The catalog handler `(domatar, catalog) = AppCatalogImpl` supports:

    GetCatalog
        → { Apps: [ { AppId, AppName, AppDesc, Version,
                      IsDefault }, ... ] }
        Returns every entry, with `IsDefault` reflecting current
        `default-apps-config.txt` membership.

    RegisterApp(AppId, AppName, AppDesc, Version)
        → { Registered: "True" }
        Idempotent insert / update of one catalog entry. Used by
        out-of-band registration tooling; the standard path is
        `CatalogInstall.registerInCatalog` called from each app's
        `installProvider`.

    Open  (standard Navigator listing of catalog children).

  Populating the catalog is the job of each app's
  `installProvider(prvId, domain)`. Removing entries (when a
  provider uninstalls an app) is not yet implemented; see PART 11.

7.4  Provider selection for cross-provider installs

  InstallApp(AppId, PrvId?) (ActManagerImpl / MarketplaceInstall) accepts
  an optional offering provider. Discovery comes from the central App
  Store registry ([App Store](../apps/AppStore.md) SearchApps / GetListing Offers).

  Rules ([App Store](../apps/AppStore.md) KD5 / PART 9–11):
    * PrvId omitted → install on the home provider (local catalog path).
    * Non-home PrvId requires an Active marketplace offer, a catalog
      probe on that provider, and an existing linked login / membership
      peer there. Error if missing:
      "Attach a login on provider <PrvId> before installing there."
    * Portable per-user host: `<appId>-<actId>` (no prvId in HstId).
      Collision: refuse if that HstId already lives on another PrvId.
    * Desktop tile on the home Desktop may carry HostPrvId / AppHstId
      and an absolute LaunchPath for cross-provider installs.

## PART 8 — DEFAULT APPLICATIONS
--------------------------------------------------------------------------------

8.1  Definition

  A default application is one that every new user account receives
  automatically at sign-up time, without the user needing the App
  Store.

  Whether an app is a default is a PROVIDER decision, not an app
  decision. The application vendor has no say in this. A provider
  may make any installed app default or non-default according to
  the needs of that provider's community.

8.2  Configuration

  Default apps are specified in `default-apps-config.txt` (PART 3.2):
  UTF-8, `#` comments, comma-separated appIds across one or more lines,
  duplicates ignored. The dedicated file is the bootstrap / operator
  default — the single concession to P6 (zero recompile) for a
  config-only operator action.

  Providers may also edit the live default set from the Account app
  ([Provider Customization](Provider-Customization.md)). That path writes a `provider-config`
  object resolved by `ProviderDefaults.resolveDefaultApps()` ahead of
  this file; `ResetDefaultApps` restores file-driven behaviour.

  Apps are installed in the order listed. Apps with `DependsOn`
  entries in their `app.config.txt` must be listed after their
  dependencies. The platform itself always handles `navigator` and
  `domatar` first regardless of where they appear in the list.

8.3  Constraints

  * `navigator`, `domatar`, `login`, and `desktop` are effectively
    mandatory; removing them from the list produces a broken
    account.
  * An app can only be defaulted if it is also installed on the
    provider (catalog row present, JAR loaded). If a default app's
    `installProvider` has not been run, `InstallUser` for that app
    fails at sign-up and the failure is reported in the AddAct
    response (PART 5.3).
  * AppStore should remain in the default list for any account that
    wants the ability to install further apps.

8.4  Changing defaults

  Live (preferred): sign in as `<PrvId>@<PrvId>`, open Account → Site
  customization, Save / Reset ([Provider Customization](Provider-Customization.md)). New
  accounts receive the change immediately; existing accounts are
  unaffected.

  File / operator path:
    To add an app:
      1. Ensure the app is installed on the provider (PART 4).
      2. Add its appId to `default-apps-config.txt` (or Save via the
         live UI).
      3. If a `provider-config` object exists it outranks the file —
         use ResetDefaultApps first, or edit via the live UI.
    To remove an app: uncheck/Save in the live UI, or edit the file
    after Reset. Existing accounts are unaffected.

## PART 9 — UPGRADE
--------------------------------------------------------------------------------

9.1  Application upgrade

  To upgrade an application to a new version:

    Step 1. Replace `<appId>.jar` in `WEB-INF/apps/` with the new
            version. Update `app.config.txt` (inside the JAR) if the
            version changed.
    Step 2. Restart Tomcat (or hot-reload the platform WAR, today
            via `docker compose restart`).
    Step 3. Hit `/domatar/Setup`. PlatformSetupServlet re-iterates
            `AppRegistry.all()` and calls `installProvider` on each
            app — already idempotent.

  An app's install routine that needs to perform schema migration on
  upgrade reads the stored Version from its catalog entry, compares it
  to the version in its bundled `app.config.txt`, and runs the
  migration as part of `installProvider` (or, for per-user schema
  changes, lazily when the user first triggers the relevant op).

  Direction: a formal `AppInstall.upgrade(fromVersion, toVersion)` SPI
  method, separating "first install" from "version migration" so that
  apps don't have to embed version-comparison logic in
  `installProvider`.

9.2  Platform core upgrade

  Upgrading Domatar core (domatar-core.jar) means rebuilding
  `domatar.war` from the source distribution and redeploying it. The
  app JARs travel along with the WAR in the current packaging, so
  every app is on the new core in one atomic step.

  In a future Option C deployment ([Domatar](../Domatar.md) PART 16.1), the
  platform core and each app's host run in separate processes. Core
  upgrades then become a per-host operation; each app process must be
  compatible with the new domatar-core version it bundles. The
  manifest's `Version:` line is the binding affordance.

## PART 10 — ZERO COUPLING — STATE AND GAPS
--------------------------------------------------------------------------------

The architectural ambition is: a provider can install a NEW application
without modifying any pre-existing artefact. The present apps-as-JARs
packaging achieves this for the great majority of cases. This part
catalogues which coupling points are now eliminated, which remain, and
why.

10.1  What zero coupling means concretely

  Adding a binary `<appId>.jar` that the platform source tree has
  never seen MUST satisfy:

    R1. No edits to any existing Java file.
    R2. No edits to any existing JAR's manifest, config, or assets.
    R3. No edits to `web.xml`, no edits to `pom.xml` of any module
        other than (optionally) a packaging build that copies the
        new JAR into `WEB-INF/apps/`.
    R4. No edits to any "central registry" of (appId → install class)
        or (clsAppId → handler).
    R5. The single permitted operator config edit, to opt the new app
        into default sign-up installs, is to
        `default-apps-config.txt` (PART 8).

  The provider's workflow is then exactly:

    1. Drop `<appId>.jar` into `WEB-INF/apps/`.
    2. Restart Tomcat.
    3. Hit `/domatar/Setup`.
    4. Optionally edit `default-apps-config.txt`.

10.2  Coupling points the JAR model has eliminated

  C1. Central ServletContextListener that named every app's handlers.
      ELIMINATED. AppLoader is the only ServletContextListener; each
      app's handlers come from its own manifest.

  C2. Central AppInstallRegistry mapping appId to install lambdas.
      ELIMINATED. AppRegistry is populated by AppLoader from the
      manifests at startup. AppUserInstallHandler delegates per-app
      installs through AppRegistry without naming any specific app.

  C3. Central AppProviderInstallRegistry mapping appId to provider
      install lambdas.
      ELIMINATED. PlatformSetupServlet iterates AppRegistry.all() and
      calls each loaded app's `installProvider` reflectively.

  C4. Three-branch HttpClient.dispatch with `isHandledByThisWar`,
      `getWarContextPath`, `DOMATAR_APP_ID`.
      ELIMINATED. Dispatch is two-branch (self vs. remote);
      [Domatar](../Domatar.md) PART 5.

  C5. Per-app `web.xml` files (each app's WAR declaring its own
      ContextListener / SetupServlet).
      ELIMINATED. Apps are JARs with no WEB-INF/.

  C6. Per-app SetupServlets exposed at distinct HTTP paths.
      ELIMINATED. A single `/domatar/Setup` triggers
      PlatformSetupServlet, which iterates every loaded app's
      `installProvider`.

  C7. Duplicate domatar-core in every app's WEB-INF/lib.
      ELIMINATED. domatar-core lives once in the platform WAR's
      `WEB-INF/lib/` and is the parent classloader of every app's
      URLClassLoader.

10.3  Residual coupling points

  Some coupling remains. Each is documented here with the reason and
  the planned resolution.

  R10.3.a  DomatarProviderInstall knows the order
           navigator → domatar → login → desktop.

           Why it's coupled: the provider's own sub-host structure
           must exist before any other app's installUser can run for
           the provider account, and the four "provider-appropriate"
           apps have an intra-dependency order. Today the order is
           hard-coded as four `AppRegistry.get(name).installInstance.
           installUser(...)` calls in Phase 1 of
           DomatarProviderInstall.

           What's mitigated: the calls go through AppRegistry rather
           than direct Java imports, so adding a new app does not
           require touching DomatarProviderInstall.

           Direction: replace the hard-coded sequence with a generic
           topological sort over `DependsOn` declarations in app
           manifests / app.config.txt. The four names then become
           data, not code.

  R10.3.b  ActManagerImpl.addAct calls navigator and domatar installs
           in-process (not via InstallUser) at sign-up.

           Why it's coupled: at sign-up time the user has no
           navigator tree yet, so the dispatch path for InstallUser
           (which routes to `<appId>~<actId>` and requires the
           sub-host to be set up) has a chicken-and-egg with the
           very thing it would install.

           Direction: have `UserInstallDispatch.sendInstallUser`
           bootstrap the sub-host with a self-dispatch for navigator
           (since the runtime is already this prv), and then route
           every other app via the InstallUser message uniformly.
           Domatar's per-user install is a no-op anyway (it only
           creates class descriptors on the navigator sub-host),
           so it can move to the standard message path the moment
           navigator does.

  R10.3.c  `PLATFORM_CONTEXT_PATH` is a compile-time constant
           `/domatar` in domatar-core.

           Why it's coupled: in Option D every Domatar server runs
           the same platform WAR at the same context. The constant
           lets HttpClient build remote URLs without consulting the
           directory.

           Direction ([Domatar](../Domatar.md) PART 16.1): store per-host
           context path in the `hst` table once apps optionally run
           in their own JVMs / containers (Option C). Apps at a
           non-default mount point then become routable without any
           code change.

  R10.3.d  Each provider's `hst` table needs `quippin`, `login`, etc.
           rows for the per-app central hosts.

           Why it's coupled: `/Setup` and `OfferedHostsInstall`
           (env `DOMATAR_OFFERED_HOSTS`) upsert this node's offered
           central-host names into `hst`. The two-provider sim also
           loads `deploy/mysql/02-central-hosts.sql` so both
           databases know the full topology. Adding a new central
           host still requires a `RegisterHst` / `UpdateHst` call
           against the directory (or an equivalent install step)
           after deploy.

           Direction ([Domatar](../Domatar.md) PART 4.2): make RegisterHst
           a first-class protocol operation that each app's
           `installProvider` can invoke. The seed becomes optional;
           every central host is registered through the same code
           path that user-installs use to register per-user sub-hosts.

  R10.3.e  Cross-provider icon DISPLAY does not require the app JAR on
           every provider that paints the icon.

           Icons remain in the owning app JAR (C7), but [Icons](../platform/Icons.md)
           PART 7 serves them via absolute URLs against the offering /
           object host's Domain (Desktop IconPath; Navigator
           AssetOrigin). The page provider need not load the JAR solely
           to show an icon. Shipping all JARs everywhere remains a valid
           reference-distribution convenience for relative URLs.

           Direction ([Domatar](../Domatar.md) PART 16.2): a new
           `(domatar, catalog) GetAppArtifact` operation that
           streams the JAR bytes from a provider that has it,
           letting the home provider install it on demand for
           installing/running the app locally — separate from icon
           serving.

10.4  Acceptance tests

  The contract is the behaviour, not a specific test harness. The
  three behaviours that demonstrate zero-coupling for provider
  install:

    T1. Third-party JAR.
        Given a binary `myapp.jar` whose source has never been in
        the platform source tree, after dropping it into
        `WEB-INF/apps/`, restarting Tomcat, and hitting `/Setup`:
          * `AppRegistry.get("myapp")` is non-null.
          * The `app-catalog` container has a `catalog-myapp` child.
          * `/domatar/myapp/<assetFile>` and
            `/domatar/myapp/Wui/<WuiName>` are reachable.

    T2. AppStore install of the third-party app.
        A user clicking Install on `myapp` in the App Store causes
        `InstallUser` to be dispatched to `(myapp, install)` and
        the user's account picks up the new app — without any Java
        edit to the platform or to any sibling app.

    T3. Defaulting the third-party app via config.
        Adding `myapp` to `default-apps-config.txt` (and only that)
        causes every subsequent new account to receive `myapp` at
        sign-up. No Java edit required.

  All three pass on the present codebase.

## PART 11 — OPEN WORK (DIRECTION ROLL-UP)
--------------------------------------------------------------------------------

For convenience, the directional items raised throughout this document,
in one list. Items reference the section that motivates them.

App lifecycle
  * Hot-reload of individual app JARs (PART 2.3). Today every
    JAR-level change requires a Tomcat restart.
  * `AppInstall.uninstallUser` SPI for symmetric per-user uninstall
    (PART 5.5). Today the AppStore removes Navigator / Desktop links
    generically.
  * `AppInstall.upgrade(fromVersion, toVersion)` SPI for explicit
    version migration (PART 9.1).
  * Cross-provider InstallApp with on-demand JAR fetch (PART 5.4,
    R10.3.e).

Manifest evolution
  * `DependsOn:` key in app.manifest, used by a topological sort
    that replaces the hard-coded "navigator → domatar → login →
    desktop" order in DomatarProviderInstall (R10.3.a).
  * Manifest declaration / embedding of class descriptors
    ([Class](../platform/Class.md)) so the schema layer is part of the declarative
    install (PART 4.2).

Catalog and AppStore
  * `DeregisterApp` on `(domatar, catalog)` so an app removed from
    a provider also leaves the catalog.
  * Per-provider catalog admin UI (PART 3.5).
  * Federated catalog queries for cross-provider AppStore
    (PART 7.2.c, PART 7.4).

Directory and hosts
  * `RegisterHst` and `MoveHst` as first-class directory operations
    (R10.3.d). Per-app `installProvider` then registers central
    hosts through the same path that user-installs use.
  * `(domatar, catalog) GetAppArtifact` operation (R10.3.e,
    [Domatar](../Domatar.md) PART 16.2).

Per-server defaults and config
  * Live reload of `default-apps-config.txt` (PART 8.4).
  * Per-app config hot-reload, so changing an app's
    `app.config.txt` does not require a Tomcat restart.

Sign-up bootstrap
  * Route navigator and domatar installs through the same
    InstallUser message path as every other app (R10.3.b).

Security / trust
  * A trust model for third-party JARs (signatures, package
    constraints, classloader sandboxing). Today AppLoader extends
    full platform privileges to every JAR in `WEB-INF/apps/`.

Per-host data layout
  * Per-host file root at `/var/domatar/hosts/<hstId>/` plumbed
    through Context, so MoveHst can be implemented as a simple
    file + database move ([Domatar](../Domatar.md) PART 14 Direction).

# END OF SPEC

## User substrate at sign-up (implemented)

Level 3 (user installation) writes platform substrate first. `AddAct` / attach-provider call `UserSubstrateInstall.ensureUserSubstrate` on each login home (membership, binding, userApps, shells). Default shell apps (`login`, `desktop`, `navigator`, `appstore`) then `InstallUser` locally on that home. Marketplace `InstallApp` writes a `userApp` row and still runs the target app's `InstallUser` for app-owned data hosts. Desktop reads `GetUserApps`; Navigator reconciles root children from the same list; shell chrome uses `GetShells`.
