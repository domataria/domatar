# SPEC: DOMATAR APP — THE PROVIDER APPLICATION

## PART 1 — PURPOSE

The Domatar platform maintains several infrastructure objects that are
shared across all user accounts: the host directory service (hst.hsts),
the account manager (act.actManager), per-prv account caches (act.actCache),
and the core class descriptors.

These live in the Domatar App: a first-class application (appId="domatar")
owned by the provider itself. A provider administrator logs in as the
provider account and sees the Domatar App in the Navigator alongside
whatever other apps are installed on that provider — Navigator, Login,
Desktop, and so on.

The platform app has two host kinds. Do not confuse them:

- **Provider infrastructure** on `domatar-<prvActId>` (this document).
- **Per-user substrate** on `domatar-<actId>-<prvId>` (PART at the end of
  this spec): membership, binding, userApps, shells. Login, Desktop,
  Navigator, and App Store are ordinary UI JARs that call those Msg ops.

Design principles:

  P1. Login is local. The provider account's central host IS the provider
      itself. There is no dependency on a remote "domatar" server for
      authentication.

  P2. Class objects remain per-host. Every host continues to carry its own
      copies of the class descriptors, exactly as today. This keeps host
      migration simple: a dump (or future file copy) of a host's data is
      self-contained.

  P3. The provider is a normal Domatar account. The Domatar App appears in
      the provider's Navigator tree exactly like any other app — same root,
      same link structure, same icon mechanism. No special-case rendering.

Goals:

  G1. Infrastructure objects (hosts, core class descriptors) are real obj
      rows owned by the provider account and visible in the Navigator.

  G2. A provider administrator logs in with normal credentials on the
      provider's own host (no remote dependency) and navigates the Domatar
      App alongside other installed apps.

  G3. The hosts table becomes browsable: the administrator can see all
      registered hosts, their domains, and their providers.

  G4. Registering a new host and moving a host between providers become
      first-class Domatar messages rather than offline SQL edits.

Non-goals for this version:

  - Automated host migration (moving obj/act/lnk rows between providers).
  - Multi-administrator roles or ACLs.
  - Centralized provider federation or replication.

## PART 2 — APP IDENTITY

  appId          : "domatar"

  central host   : none — there is no single central host for the Domatar
                   App. Each provider IS its own central host for its own
                   provider account (see PART 3). Whether an app has a
                   central host, and how many, is an application design
                   choice; the platform does not require it.

  per-provider   : "domatar-<prvActId>"
  sub-host         e.g. "domatar-<fingerprint>" for provider prv1.
                   Lives on the provider it belongs to (PrvId = prvId).

  act manager    : domatar-<prvActId> / domatar / <prvActId> / actManager
                   A first-class obj on the Domatar App's per-provider
                   sub-host. Uses the same ActManagerImpl as every other
                   app. Since domatar-<prvActId> lives on the provider's
                   own server, all login dispatches are self-dispatched —
                   no network hop.

  icon directory : src/main/webapp/icons/cls/domatar/
                   Already exists (app.svg, cls.svg, clss.svg).
                   New icons to add: hosts.svg, host.svg (PART 11).

## PART 3 — THE PROVIDER ACCOUNT

3.1 Account identity

  Each physical provider prv has exactly one provider account:

    usrId  : <prvId>@<prvId>    (e.g. "prv1@prv1")  — login label; NEVER changes
    actId  : <fingerprint>      (e.g. "wlzD5pWDRqXZcLh4gP64_3~QLMFuKf2~")
                                — minted at bootstrap; stored in provider.config.txt
                                  under key PrvActId via DomatarConfig.setPrvActIdOnce()

  The provider actId is a key fingerprint. It is generated once at first
  bootstrap by DomatarProviderInstall.install() using AccountKeys.generate(),
  and persisted to provider.config.txt. Subsequent startups read it via
  DomatarConfig.getPrvActId().

  The usrId "@<prvId>" suffix still routes login to the provider's own
  domatar central host. The provider's act row lives on the provider's
  own host (principle P1: no remote dependency for login).

3.2 How ActManagerImpl handles @<prvId> accounts

  The dispatch path for login:

    1. User enters usrId = "prv1@prv1" and password.
    2. The "@prv1" suffix identifies the issuing app as "prv1" (the
       provider itself), which is a sentinel for provider accounts.
    3. ActWui dispatches Login to:
         domatar-<prvActId> / domatar / <prvActId> / actManager
       where <prvActId> is read from DomatarConfig.getPrvActId().
    4. Since domatar-<prvActId> has PrvId="prv1" and DOMATAR_HSTID="prv1"
       on the provider's server, HttpClient.send detects self-dispatch
       and calls sendLocal.
    5. No network hop; the login is purely local.

  ActWui detects a provider account when getAppId(usrId) equals getHstId(usrId)
  (i.e. usrId is of the form <x>@<x>) and constructs the actManager DomId
  using DomatarConfig.getPrvActId() for the actId.

  For bootstrap: DomatarProviderInstall calls AccountKeys.generate() to
  mint a keypair, derives the fingerprint actId, seals the private key via
  MasterKey.seal(), and calls ActDb.addAct with the sealed key. The prvActId
  is then stored once in provider.config.txt via DomatarConfig.setPrvActIdOnce().

3.3 The provider's Navigator

  After DomatarProviderInstall runs, the provider's Navigator tree
  looks like this.  Only the three provider-appropriate apps are present.

    [icon] prv1                          (navigator, root)
      [icon] Navigator                   (domatar, app)  â† seqNum 1
      [icon] Login                       (domatar, app)  â† seqNum 2
          [icon] Logins                  (login, logins)      â† user layer (everyone)
          [icon] ActManager              (act, actManager)    â† provider layer only
          [icon] Accounts                (login, accounts)    â† provider layer only
      [icon] Desktop                     (domatar, app)  â† seqNum 3
      [icon] Domatar                     (domatar, app)  â† seqNum 4
          [icon] Hosts                   (domatar, hosts)
              + [icon] prv1              (domatar, host)
              + [icon] prv2              (domatar, host)
              + [icon] quippin           (domatar, host)
              + [icon] login             (domatar, host)
              + [icon] quippin-dave@...  (domatar, host)
                ...
          [icon] Classes                 (domatar, clss)
              [icon] hstsCls             (domatar, cls)
              [icon] actManagerCls       (domatar, cls)
              [icon] actCacheCls         (domatar, cls)
              [icon] clsCls              (domatar, cls)
              [icon] appCls              (domatar, cls)
              [icon] clssCls             (domatar, cls)
              [icon] hostsCls            (domatar, cls)
              [icon] hostCls             (domatar, cls)

  If other apps (Quippin, AI Agent, …) are also installed for the provider
  account, their app nodes appear as additional siblings under the root in
  exactly the same way as for a regular user.

## PART 4 — SUB-HOSTS

The provider account uses the same two-tier sub-host structure as every
other account.

The provider account has exactly four sub-hosts.  It is an
administrative account, not a regular user account, and does not need
social, productivity, or AI apps — but it does need a Desktop to land
on after login.

4.1  navigator-<prvActId>  (e.g. "navigator-<fingerprint>")

  Created by NavigatorInstall. Holds the root obj and the four app-*
  node objs (app-navigator, app-login, app-desktop, app-domatar).

4.2  login-<prvActId>       (e.g. "login-<fingerprint>")

  Created by LoginInstall. Holds the logins container (user layer) plus
  the actManager link and accounts container (provider layer).
  The recordLogin call dispatches RecordLogin to this sub-host — it must
  exist before recordLogin is called.

4.3  desktop-<prvActId>     (e.g. "desktop-<fingerprint>")

  Created by DesktopInstall. The provider lands here after login, just
  like any other user.

4.4  domatar-<prvActId>     (e.g. "domatar-<fingerprint>")

  The Domatar App's per-provider sub-host. Created by
  DomatarProviderInstall. Holds app-domatar, hosts container, clss
  container, host child objs, and class descriptor objs.

  No aiagent-, quippin-, or other app sub-hosts are created for the
  provider account.

## PART 5 — OBJECTS

5.1 Navigator root  — on navigator-<prvActId>

  HstId    : navigator-<prvActId>
  AppId    : navigator
  ActId    : <prvActId>
  ObjId    : root
  ClsAppId : navigator
  ClsId    : root
  ObjName  : <prvId>             (e.g. "prv1")
  ObjDesc  : "Provider root"

  Created by NavigatorInstall, exactly as for a regular user.

5.2 App node  (app-domatar) — on domatar-<prvActId>

  HstId    : domatar-<prvActId>
  AppId    : domatar
  ActId    : <prvActId>
  ObjId    : app-domatar
  ClsAppId : domatar
  ClsId    : app
  ObjName  : Domatar
  ObjDesc  : "Platform infrastructure for this provider"

  Link: root (navigator-<prvActId>) → app-domatar (domatar-<prvActId>)
             (tagAppId: navigator, tag: app, seqNum: 4)
             (seqNum 4: after Navigator=1, Login=2, Desktop=3)

5.3 Hosts container  (hosts) — on domatar-<prvActId>

  HstId    : domatar-<prvActId>
  AppId    : domatar
  ActId    : <prvActId>
  ObjId    : hosts
  ClsAppId : domatar
  ClsId    : hosts
  ObjName  : Hosts
  ObjDesc  : "All hosts registered on this provider"

  Link: app-domatar → hosts  (tagAppId: navigator, tag: container, seqNum: 1)

  Children: one (domatar, host) obj row per hst entry in the local
  hst table, at ObjId = "host-<hstId>" (e.g. "host-prv1",
  "host-quippin-dave@quippin"). Created by DomatarProviderInstall for
  every hst already in the directory. Created/removed by RegisterHst /
  DeregisterHst (PART 7) thereafter.

5.4 Classes container  (clss) — on domatar-<prvActId>

  HstId    : domatar-<prvActId>
  AppId    : domatar
  ActId    : <prvActId>
  ObjId    : clss
  ClsAppId : domatar
  ClsId    : clss
  ObjName  : Classes
  ObjDesc  : "Core Domatar class descriptors"

  Link: app-domatar → clss  (tagAppId: navigator, tag: container, seqNum: 2)

  Children (all are (domatar, cls) obj rows, see PART 8):
    hstsCls       actManagerCls   actCacheCls
    clsCls        appCls          clssCls
    hostsCls      hostCls

  These are the same class descriptors that DomatarInstall already
  creates for every user on navigator-<actId>. They are duplicated here
  following principle P2 (per-host, self-contained). The class descriptor
  objects in this clss container belong to the provider account and are
  independent of any user account's copies.

## PART 6 — THE LOGIN APP — TWO LAYERS

The Login app has a user layer (identical for every account) and a
provider layer (extra objects only in the provider's Login sub-host).

6.1  User layer  — login-<actId>   (created by LoginInstall for everyone)

  Objects:

    logins   (login, logins)  — container of linked login identities
      Each child is a (login, login) record with:
        UsrId, UsrName, AppId, AddedAt, IsRoot

  The provider's Login sub-host (login-<fingerprint>) gets exactly the same
  logins container as any regular user, showing the provider account's
  own linked identities.

6.2  Provider layer — login-<prvActId>   (added by DomatarProviderInstall)

  The provider administers accounts for all users on the host.  Three
  extra objects are added to the provider's login sub-host:

  6.2.1  actManager

    The actManager obj lives on the Domatar App's per-provider sub-host:

      HstId    : domatar-<prvActId>     (e.g. "domatar-<fingerprint>")
      AppId    : domatar
      ActId    : <prvActId>
      ObjId    : actManager
      ClsAppId : act
      ClsId    : actManager
      ObjName  : ActManager
      ObjDesc  : "Account manager for <prvId>"

    DomatarProviderInstall creates this obj row in Phase 2 (Step 5, along
    with the other domatar sub-host objects) so that ImplMap can dispatch
    to ActManagerImpl.

    It is linked from the provider's app-login node so the provider can
    find it in the Login section of the Navigator:

      app-login → actManager
        tagAppId : navigator   tag : container   seqNum : 2

    The provider can open actManager to invoke AddAct, Login, ChangePwd,
    DeleteAct, etc.  Regular users never see this link.

  6.2.2  accounts container

    A (login, accounts) container listing every account registered on
    this provider.  Each child obj is a (login, account) record with
    attrs matching the act table row (ActId, UsrId, UsrName, Email, …).

      HstId    : login-<prvActId>
      AppId    : login
      ActId    : <prvActId>
      ObjId    : accounts
      ClsAppId : login
      ClsId    : accounts
      ObjName  : Accounts
      ObjDesc  : "All accounts on this provider"

    Link:
      app-login → accounts
        tagAppId : navigator   tag : container   seqNum : 3

    In v1 the accounts container can be read-only (Open returns rows from
    ActDb).  Administrative operations (suspend, delete) are PART 12 TODO.

  6.2.3  Future

    sessions  — active session/token records  (PART 12 TODO)
    config    — OAuth / SSO provider settings  (PART 12 TODO)

6.3  hst.hsts

  The static DomId used by HttpClient (domatar / hst / domatar@hst / hsts)
  remains unchanged. Adding a real obj row for it is a PART 12 TODO.

## PART 7 — CLASSES

7.1  (domatar, hosts) — host directory browser

  Attrs : none

  Msgs:

    GetObj()
      -> { ObjName : String, ObjDesc : String }

    Open()
      -> [ { Name     : String,    -- hstId
             Desc     : String,    -- "<domain>  prv:<prvId>"
             DomId    : String,    -- DomId of the (domatar, host) child obj
             ClsAppId : "domatar",
             ClsId    : "host" } ]
      Reads HstDb and returns one entry per row.

    ListHsts()
      -> [ { HstId   : String,
             Domain  : String,
             PrvId   : String,
             Version : Number } ]
      Full hst list for programmatic consumers.
      Authorization: verified + owner-match.

    RegisterHst(HstId, Domain, PrvId)
      -> { HstId : String }
      Inserts or updates the hst row in the local cache and in the global
      directory (via UpdateHst on the directory's hsts object). Creates a
      (domatar, host) child obj row and a hosts→child lnk.
      Authorization: verified + owner-match.

    DeregisterHst(HstId)
      -> {}
      Removes the hst row, the (domatar, host) child obj row, and the lnk.
      Guard: refuse if HstId equals the provider's own prvId or "domatar".
      Authorization: verified + owner-match.

7.2  (domatar, host) — individual host entry

  Attrs :
    HstId   : String
    Domain  : String
    PrvId   : String
    Version : String

  Msgs:

    GetObj()
      -> { ObjName : String,    -- hstId
           ObjDesc : String }   -- "<domain>  prv:<prvId>"

    Open()
      -> []   (leaf node in v1; sub-hosts and app structure are PART 12 TODO)

    UpdateHst(Domain, PrvId)
      -> {}
      Updates Domain and/or PrvId in both the local hst table and the
      global directory. Updates the Attrs on this obj row to match.
      Authorization: verified + owner-match.

## PART 8 — CLASS DESCRIPTOR OBJECTS

Class descriptors for the Domatar App follow the same convention as every
other application ([Class](Class.md)):

  ObjId   = <clsId>Cls
  ObjName = <clsId>Cls

The provider's clss container (5.4) holds descriptors for all classes
introduced by the Domatar App plus the six pre-existing core platform
classes:

  Already defined in DomatarInstall (retained unchanged):
    hstsCls         describes (hst, hsts)
    actManagerCls   describes (act, actManager)
    actCacheCls     describes (act, actCache)
    clsCls          describes (domatar, cls)
    appCls          describes (domatar, app)
    clssCls         describes (domatar, clss)

  New (added by DomatarProviderInstall):
    hostsCls        describes (domatar, hosts)
    hostCls         describes (domatar, host)

Following principle P2, these descriptor objects are per-host copies.
Every user account continues to receive its own copies via DomatarInstall
on navigator-<actId> as today; the copies on the provider's sub-host are
independent. No data migration is needed.

## PART 9 — INSTALL ROUTINE

9.1  DomatarProviderInstall.install(prvId, domain, password)

  Called once at provider setup time, not by ActManagerImpl.addAct.
  Every step is idempotent (safe to re-run).

  Inputs:
    prvId    — the provider's hstId (e.g. "prv1"); also the handle
    domain   — the provider's domain (e.g. "tomcat1:8080")
    password — initial password for the provider account

  Derived values:
    prvActId = prvId + "@" + prvId     (e.g. "prv1@prv1")
    ssHstId  = "domatar-" + prvActId  (e.g. "domatar-<fingerprint>")

  -- Phase 1: standard account bootstrap (same as ActManagerImpl.addAct) --

  Step 1 — Provider account in the local act table.
    ActDb.addAct(prvId, domain, prvId,
                 prvActId, prvActId, prvId, password, "127.0.0.1")
    (ActManagerImpl bootstraps itself: at this point the actManager obj
     row does not yet exist; ActWui must call addAct directly for the
     initial provider setup, bypassing the normal dispatch path.)

  Step 3 — Minimal install chain (only the three provider-appropriate apps):

      NavigatorInstall.install(prvActId, prvId, domain, prvId)
        → creates navigator-<prvActId>, root, app-navigator (seqNum 1)

      DomatarInstall.install(prvActId)
        → creates per-account core class descriptors on navigator-<prvActId>

      LoginInstall.install(prvActId, prvId, domain, prvId)
        → creates login-<prvActId>, logins container, app-login (seqNum 2),
          root→app-login link, Login class descriptors

      DesktopInstall.install(prvActId, prvId, domain, prvId)
        → creates desktop-<prvActId>, app-desktop (seqNum 3),
          root→app-desktop link
          The provider lands on Desktop after login.

      No other app installs (AI Agent, Quippin, Bookstore, Spreadsheet,
      Money) are called.  The provider account is an administrative
      account, not a regular user account.

  Step 4 — recordLogin for the provider account.
    Dispatch RecordLogin to login-<prvActId> / login / <prvActId> / logins
    (same call as ActManagerImpl.recordLogin, using a local msgClient)

  -- Phase 2: provider-layer additions (specific to DomatarProviderInstall) --

  Step 5 — actManager obj row on the Domatar App's sub-host.
    DomId actMgrId = new DomId(ssHstId, "domatar", prvActId, "actManager")
    ObjDb.addObjIfMissing(actMgrId,
      "act", "actManager", "ActManager", "Account manager for " + prvId)

  Step 6 — Link actManager from the provider's app-login node.
    DomId appLoginId = new DomId("login-" + prvActId, "login", prvActId, "app-login")
    LnkDb.addLnkIfMissing(appLoginId → actMgrId,
      tagAppId:"navigator", tag:"container", seqNum:2)

  Step 7 — accounts container.
    DomId accountsId = new DomId("login-" + prvActId, "login", prvActId, "accounts")
    ObjDb.addObjIfMissing(accountsId,
      "login", "accounts", "Accounts", "All accounts on this provider")
    LnkDb.addLnkIfMissing(appLoginId → accountsId,
      tagAppId:"navigator", tag:"container", seqNum:3)

  Step 8 — domatar sub-host.
    HstDb.addHst(ssHstId, domain, prvId)

  Step 9 — app-domatar obj on domatar-<prvActId>.
    DomId appDomatarId = new DomId(ssHstId, "domatar", prvActId, "app-domatar")
    ObjDb.addObjIfMissing(appDomatarId,
      "domatar", "app", "Domatar", "Platform infrastructure")

  Step 10 — root → app-domatar link.
    DomId rootId = new DomId("navigator-" + prvActId, "navigator", prvActId, "root")
    LnkDb.addLnkIfMissing(rootId → appDomatarId,
      tagAppId:"navigator", tag:"app", seqNum:4)

  Step 11 — hosts container.
    DomId hostsId = new DomId(ssHstId, "domatar", prvActId, "hosts")
    ObjDb.addObjIfMissing(hostsId,
      "domatar", "hosts", "Hosts", "All registered hosts")
    LnkDb.addLnkIfMissing(appDomatarId → hostsId, seqNum:1)

  Step 12 — Seed host child objects.
    For each Hst h in HstDb.getAllHsts():
      ObjDb.addObjIfMissing(
        new DomId(ssHstId, "domatar", prvActId, "host-" + h.hstId),
        "domatar", "host", h.hstId, h.domain + "  prv:" + h.prvId,
        attrs{HstId, Domain, PrvId, Version})
      LnkDb.addLnkIfMissing(hostsId → host-<hstId>, seqNum:<index>)

  Step 13 — clss container + class descriptor objects.
    ClsInstall.ensureClssContainer(appDomatarId, "Core class descriptors", 2)
    ClsInstall.addClsObj(...)  for each class in PART 8

9.2  Where DomatarProviderInstall is called

  Option A (recommended): a protected setup servlet or CLI tool run by
  the operator once after first deploy.  The servlet checks that no
  provider account exists before running, to prevent accidental re-runs
  with a different password.

  Option B: a first-run lifecycle hook in Msg.init() that detects the
  absence of the actManager obj row and runs the install automatically
  with a default password printed to the server log.

  The install is separate from ActManagerImpl.addAct (which is per-user
  signup) because the provider setup is a one-time operator action.

9.3  DomatarInstall.install(actId) — unchanged

  The existing per-user DomatarInstall continues to create core class
  descriptors on navigator-<actId> for every new user account, as today.
  No change is needed.  The provider's copies (PART 8) are independent.

## PART 10 — SQL SEED

A new init script seeds the provider accounts and Domatar App objects for
the two pre-existing simulation accounts (dave@quippin, micha@quippin are
on prv1 and prv2 respectively, so we seed one provider account per prv):

  mySQL/dump-2024-MM-DD-domatar-app.sql

Contents:

  1. actManager obj rows on each provider's Domatar App sub-host:
       INSERT IGNORE INTO obj (HstId, AppId, ActId, ObjId, ClsAppId, ClsId, ...)
       VALUES
         ('domatar-<fingerprint>', 'domatar', 'prv1@prv1', 'actManager', 'act', 'actManager', ...),
         ('domatar-prv2@prv2', 'domatar', 'prv2@prv2', 'actManager', 'act', 'actManager', ...)

  2. Provider act rows (one per prv, password hash for a known setup password):
       INSERT IGNORE INTO act (HstId, ActId, UsrId, UsrName, PwdHash, ...)
       VALUES
         ('prv1', 'prv1@prv1', 'prv1@prv1', 'prv1', <hash>, ...),
         ('prv2', 'prv2@prv2', 'prv2@prv2', 'prv2', <hash>, ...)

  3. Provider sub-host rows (exactly four per provider):
       INSERT IGNORE INTO hst (HstId, Domain, PrvId, ...)
       VALUES
         ('navigator-prv1@prv1', 'tomcat1:8080', 'prv1', ...),
         ('login-<fingerprint>',     'tomcat1:8080', 'prv1', ...),
         ('desktop-prv1@prv1',   'tomcat1:8080', 'prv1', ...),
         ('domatar-<fingerprint>',   'tomcat1:8080', 'prv1', ...),
         (... repeat for prv2 ...)

  4. All obj rows for the provider tree:
       - root on navigator-<prvActId>
       - app-navigator, app-login, app-desktop, app-aiagent on their
         respective sub-hosts (created by the standard install chain)
       - app-domatar, hosts, clss, host-* children, class descriptors
         on domatar-<prvActId>
     Use INSERT IGNORE.

  5. All lnk rows, using INSERT IGNORE.

  Note on the password: for the seed script, a known test password is
  hashed using the same algorithm as ActDb.addAct. In production, the
  operator runs DomatarProviderInstall (PART 9) with a chosen password;
  the seed script is only for the local simulation.

## PART 11 — ICONS

New SVG icon files to add under src/main/webapp/icons/cls/domatar/:

  hosts.svg  — a stack of server rectangles (representing multiple hosts).
               Style: filled purple rectangles arranged in a vertical
               stack, consistent with the existing cls.svg purple scheme.

  host.svg   — a single server rectangle with a small indicator dot.
               Style: one filled purple rectangle, consistent with hosts.svg.

Both should be 24Ã—24 SVG, matching the style of cls.svg and clss.svg.

## PART 12 — TODO

T1. AddHst / RegisterHst as a directory-level operation.
    Currently the directory (hst.hsts) only supports GetHst and UpdateHst.
    RegisterHst on (domatar, hosts) calls UpdateHst for now. A dedicated
    RegisterHst message on the directory object, with proper deduplication
    and versioning, is deferred.

T2. hst.hsts as a real obj row.
    The static DomId (domatar / hst / domatar@hst / hsts) used by
    HttpClient is not yet a real obj row.  Adding it makes it addressable
    via ObjDb.getObj and allows it to be linked from the Navigator.

T3. Host child expansion.
    The Open() response for (domatar, host) returns no children in v1.
    Future: expand each host to show the per-user sub-hosts it provides,
    the applications installed on it, and the accounts it serves.

T4. HstDb.getAllHsts().
    This method does not yet exist; HstDb currently only supports point
    lookups and upserts.  DomatarProviderInstall (step 9) and
    HostsImpl.ListHsts/Open require it.  It is a simple SELECT * FROM hst.

T5. Provider account replication.
    If the provider's host goes down, the provider cannot log in (the
    act row is local).  Replication is part of the general account-
    replication TODO in [Login protocol](../apps/Login-Protocol.md) PART 14.

T6. Multiple administrator accounts.
    Only one provider account per prv is described here. Supporting
    additional administrators (e.g. ops@prv1) requires either the same
    provider install for a second account, or a proper admin-role mechanism.

T7. Navigator sub-host for other apps.
    If the provider wants to also install Quippin, Login, etc. for their
    provider account, those installs follow the standard per-app install
    path (QuippinInstall.install(prvActId, ...) etc.) and their app nodes
    appear as siblings of "Domatar" in the Navigator root.  Nothing special
    is needed; the existing install routines already support any actId.

T8. Automated first-run detection.
    If no actManager obj row exists for the provider's own host, run
    DomatarProviderInstall automatically at Msg.init() time and log the
    generated password. This removes the need for a manual setup step
    in simple deployments.

## Per-user substrate (implemented)

The platform app has two host kinds. Do not confuse them.

- **Provider infrastructure** lives on `domatar-<prvActId>`: actManager, hosts, class descriptors, provider-config, app-catalog. Each provider is local for its own provider account.
- **Per-user substrate** lives on `domatar-<actId>-<prvId>` on every object-hosting provider. Login homes hold the full graph; object-only hosts hold a minimal replica (membership + binding).

On each login provider L, host `domatar-<actId>-L` holds:

```
app-domatar          (domatar, app)
  ├─ membership      (domatar, membership) → peer-* (linked providers)
  ├─ binding         (domatar, binding)    actId ↔ ownId
  ├─ userApps        (domatar, userApps)   → app-* installed apps
  └─ shells          (domatar, shells)     RoleId login | desktop | navigator
```

Login, Desktop, Navigator, and App Store are ordinary UI JARs. They call substrate Msg ops (`GetUserApps`, `GetShells`, membership, binding). They do not own those rows. Sign-up (`AddAct`) and attach-provider call `UserSubstrateInstall.ensureUserSubstrate` on each login home; default shell AppIds run `InstallUser` locally.
