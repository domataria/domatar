# DOMATAR - SPECIFICATION

This document is the umbrella specification for Domatar. It defines the
ambitions of the project, the core abstractions, and the architecture that
realises them. It is forward-looking: where current implementation lags
behind the design, the relevant section says so and points at the work
that closes the gap. Reading order and the full index:
[Specification](../Specification.md).

The present-tense statements below describe the system as it is built.
The "Direction" subsections describe where the system is going and the
work that remains.

## PART 1 - VISION

Domatar is a general, distributed, interoperable application environment.
"General" means it has no built-in notion of any one application; the
platform speaks only objects, classes, hosts, and messages. "Distributed"
means a Domatar network is many independent servers on the public
internet, each running the same artefact, each owning its own data, each
addressable by every other. "Interoperable" means applications written
to the platform can share classes, share users, and route messages to
each other across server and provider boundaries without prior bilateral
arrangement.

The unit of value on a Domatar network is the Application. Applications
are written, packaged, and distributed independently of the platform.
A provider installs an application by dropping its artefact onto the
server; a user installs the same application onto their own account by
clicking Install in the App Store. Anyone may publish an application;
anyone may run a provider; anyone may sign up. Domatar is the substrate
that lets those three populations cooperate.

The protocol is objects, classes, hosts, accounts, links, and messages.
How those are stored is realisation-specific. Today the reference
substrate is a single Tomcat 10.1 / Jakarta EE 10 / Java 17 web
application, persisted in MySQL 8, exchanging JSON messages over HTTP. Nine applications ship in the reference
distribution (Navigator, Login, Desktop, AppStore, Quippin, Bookstore,
Spreadsheet, Money, AI Agent), plus the platform's own self-described
application (Domatar). The architecture is positioned for, but does not
yet require, splitting each application into its own process or its own
machine (see PART 16).

## PART 2 - CORE ABSTRACTIONS

The platform is built from a small set of named things. Every other
notion in the system is composed from these.

  * Object         An addressable actor with persistent state,
                   identified by a DomId (below). All behaviour
                   reaches an object through messages. Persistence is
                   realisation-specific; this tree stores objects in
                   MySQL (PART 7).

  * Class          A code unit, identified by the pair (clsAppId, clsId),
                   that implements an object's behaviour. Many objects
                   share a class. Classes are namespaced by the defining
                   application: two applications may use the same clsId
                   without collision.

                   The runtime locates a class's Java handler via
                   com.domatar.core.ImplMap, an in-memory registry keyed
                   by (clsAppId, clsId). ImplMap is no longer hard-coded:
                   it is populated at startup by AppLoader (PART 11) from
                   each app JAR's manifest. A class is always LOCAL — its
                   clsAppId is always its owning application's appId — and
                   has (or ought to have) a class-descriptor object
                   ([Class](platform/Class.md)) that carries the class's
                   implementation, policy, and presentation and names the
                   Services (below) it implements. ImplMap is the dispatch
                   table; the class and service descriptors are the schema
                   layer; the two are intentionally kept separate so a
                   class can be implemented in one place while the
                   interface it speaks is declared, shared, and reused in
                   another.

  * Service        A named, immutable interface — a collection of
                   attribute and message declarations — identified by the
                   pair (srvAppId, srvId). Services are the shared,
                   reusable schema of Domatar: one application defines a
                   service, and many applications implement it. A class
                   declares (in an "Implements" list) the services it
                   implements; any two classes that implement the same
                   service are interoperable through it, regardless of
                   which applications, hosts, or accounts they belong to.
                   A service carries interface only — no handler binding,
                   no icon, no authorization or side-effect policy; those
                   stay on the implementing class. This interface /
                   implementation split is specified in full in
                   [Service](platform/Service.md) and summarised in PART 8. It is how
                   cross-application interoperability ([Platform App](platform/Platform-App.md))
                   is expressed concretely: applications agree on a
                   service, not on each other's code.

  * Application    The unit of development, distribution, install, and
                   ownership, identified by `appId`. Applications define
                   classes, define logical hosts, and ship as JAR
                   artefacts (PART 11). The appId appears in three roles
                   across the system:
                     - as the appId field on every object the app owns;
                     - as the clsAppId of every class the app defines;
                     - as the hstId of any logical host the app provides
                       (typically the app's own "central host").
                   The same appId binds the three roles together. The
                   system does not enforce the binding; it is a naming
                   convention realised by the app's install routine.

  * Account        The owner of objects. All activity is per-account.
                   Accounts are identified by an immutable `actId` that
                   is a 32-character self-certifying fingerprint derived
                   from the account's Ed25519 genesis public key
                   ([Identifiers](platform/Identifiers.md) PART 4.1). The fingerprint has no
                   `@` and is provider-free: it is globally unique and
                   does not encode who issued it.

                   Separately, every account has a human-readable `usrId`
                   (login label) of the form `<localName>@<appId>`.
                   The `usrId` is used for login routing (the `@appId`
                   suffix dispatches to that app's central host) and for
                   display; it can change without affecting the `actId`.
                   The `actId` is the stable identity; the `usrId` is
                   the mutable login label.

                   The account lives on whichever provider the user
                   was on when it was created (or on additional
                   providers if the user has explicitly replicated it).
                   Login is always against the local prv's account
                   store and is routed by `usrId`.

                   Direction: Account replication / migration across
                   providers so a user can keep the same account on
                   more than one host without depending on any one of
                   them. See [Login protocol](apps/Login-Protocol.md) PART 14 / PART 17 (TODO
                   T1).

  * Host           A first-class entity, identified by `hstId`, with a
                   network address (`Domain`) and a provider (`PrvId`,
                   which is itself a hstId). Hosts and applications
                   share a namespace: a typical application has a
                   central host whose hstId equals the appId. When an
                   application installs itself for an account, it also
                   creates an abstract per-user sub-host named
                   `appId-actId` using `-` as separator
                   (e.g. `quippin-<fingerprint>`). `-` is
                   `DomId.HOST_SEP`; it is not in the fingerprint
                   alphabet, so a host id cannot be ambiguous with an
                   encoded actId.

                   The hstId is opaque to the runtime; the
                   appId / appId-actId convention is what registers
                   hosts in practice. Routing uses only the host
                   record; the runtime does not parse hstId strings.

  * Link           A directed edge from one object to another (parent
                   → child in the Navigator tree, membership, catalog
                   entries, …). Links are first-class: if the Navigator
                   can show an edge, that link exists. Tag, SeqNum, and
                   Val name and order them ([Navigator](apps/Navigator.md);
                   PART 7 for this realisation's columns).

  * Provider       A role a host plays when it physically hosts other
                   hosts. A provider is a host whose hstId is the
                   `PrvId` of one or more other hosts. A provider runs
                   the server, owns the local store, and serves
                   messages addressed to any of the hsts whose PrvId
                   is its own hstId. A self-hosting host (one that
                   provides itself) has PrvId == HstId. One provider
                   commonly serves many hsts at many distinct domains.

                   Each physical provider has its own administrator
                   account, `<prvId>@<prvId>` (e.g. `prv1@prv1`), and
                   its own Domatar-app sub-host `domatar-<prvId>@<prvId>`
                   that exposes provider-scoped infrastructure
                   ([Platform App](platform/Platform-App.md)).

  * Domain         An internet address (host:port) at which a single
                   host can be reached for Msg / hst publish. Stored
                   on the host record. May be Docker-internal
                   in local stacks (e.g. `tomcat2:8080`). A hosting-
                   service provider commonly serves many domain names
                   from one machine, one per tenant host, so per-host
                   Domains are not redundant with PrvId.

  * PublicDomain   Optional browser-facing hostname (nginx front
                   door), e.g. `domatar.avatarvia.com`. Distinct from
                   Domain when the wire address is not browser-
                   resolvable. Used for absolute IconPath / LaunchPath
                   / AssetOrigin ([Icons](platform/Icons.md) PART 5.2). Config:
                   DOMATAR_PUBLIC_DOMAIN / PublicDomain.

  * DomId          A 4-tuple (hstId, appId, actId, objId) that
                   addresses one Object. The actId itself has the form
                   `name@appId`, so a DomId carries appId twice in
                   different roles: the top-level appId is the
                   application namespace of *this object*, while the
                   appId embedded in actId is the application that
                   issued the *account*. They can differ — that is the
                   whole point of cross-app interoperability.

  * Message        A JSON envelope carrying an operation name, a body,
                   a source DomId, a destination DomId, and a Context
                   (PART 5/6). Communication is exclusively
                   object-to-object: every interaction in the system,
                   from a click in the browser to an HTTP request
                   between providers, is ultimately a message
                   addressed to one object.

The platform itself is just another application: appId="domatar". Its
classes (hosts directory, account manager, app catalog, class
container, host child) are defined in the domatar-app JAR and loaded
by the AppLoader exactly the same way as any other application's
classes (PART 11). There is no built-in code; everything is opt-in.

## PART 3 - IDENTITY: HSTS, APPS, AND ACCOUNTS

Domatar has three independent identity axes:

  * `hstId` identifies a host. Every persisted reference uses hstId
    (in DomIds, cookies, links, audit records); it never appears in
    code as a hard-coded literal. Every running Domatar server has its
    own hstId (the per-server `DOMATAR_HSTID`), its own Domain
    (`DOMATAR_DOMAIN`), and its own PrvId (which equals its hstId for
    self-hosting providers).

  * `appId` identifies an application. The set of appIds is open:
    anyone may publish an application; the only constraint is global
    uniqueness within a network (today enforced socially, not by the
    directory).

  * `actId` identifies an account. It is a 32-character Base64-encoded
    fingerprint of the account's Ed25519 genesis public key
    ([Identifiers](platform/Identifiers.md) PART 4.1). It contains no `@` and
    is provider-free. An account is "the same account" across providers
    iff it has the same actId; account replication (Direction) makes the
    same actId resolvable on more than one provider.

  * `usrId` is the human-readable login label of the form
    `<localName>@<appId>`. It is used for routing login verifications
    to the right app's central host. The `usrId` can change (e.g. if
    the user renames their login) without changing the `actId`.

The federation rule is: login verification is routed by the `usrId`
suffix (`@appId`) to that app's central host. Every Domatar provider
trusts the same authority for the same `usrId`, and a token
issued on prv1 is recognised on prv2 ([Login protocol](apps/Login-Protocol.md) PART 6).

## PART 4 - HOSTS AND THE GLOBAL DIRECTORY

4.1  Host records

Every host known to the network has a record in the directory:

    HstId, Domain, PrvId, Version, FetchedAt, PubKey, RecordSig

  HstId      this host's identifier (primary key)
  Domain     this host's network address
  PrvId      another HstId; may equal HstId for self-hosting
  Version    monotonic version of this record
  FetchedAt  millis-since-epoch of last refresh into a cache;
             0 in the directory
  PubKey     provider operational Ed25519 public key
             (Base64Encoder, 43 chars). Absent for sub-hosts;
             set at provider bootstrap.
  RecordSig  directory-root Ed25519 signature over canonical
             JSON {HstId, Domain, PrvId, Version, PubKey}.
             Absent until the directory node signs.

Every provider also keeps a local cache of host records with the
same fields; FetchedAt is the cache freshness on cached records.
PubKey and RecordSig are cached together with the routing data.

This realisation stores those records as rows in MySQL `hst`
(PART 7). A typical reference distribution includes:
  - per-provider hosts               e.g. `prv1`, `prv2`
  - per-app central hosts            e.g. `quippin`, `login`,
                                          `navigator`, `aiagent`,
                                          `bookstore`, `spreadsheet`,
                                          `money`, `appstore`,
                                          `desktop`
  - per-user sub-hosts               e.g. `quippin-<fingerprint>`,
                                          `navigator-<fingerprint>`,
                                          `desktop-<fingerprint>`,
                                          `domatar-<prvFingerprint>`

The set of app central hosts is open-ended: any new application
that needs a central host registers one at provider-level install
time.

4.2  The directory service

The directory is a Domatar object addressable by the well-known DomId
(domatar, hst, domatar@hst, hsts), handled by `com.domatar.hst.HstsImpl`
(declared in domatar-app.jar). Clients reach it via the
`DOMATAR_DIRECTORY` config value (host:port). Operations exposed on
the directory's /Msg endpoint:

  * GetHst    (HstId)                  -> { HstId, Domain, PrvId, Version
                                            [, PubKey] [, RecordSig] }
                                          on success, "Hst not found" on miss.
                                          PubKey/RecordSig omitted for hosts
                                          that have not published a key yet.
  * UpdateHst (HstId, Domain, PrvId    -> Success.
               [, PubKey])               If PubKey is present the directory
                                          node signs the record immediately
                                          (when it has DirectoryRootPrivKey)
                                          and stores RecordSig.

GetHst includes PubKey and RecordSig. HttpClient verifies RecordSig
against the pinned DirectoryRootPubKey before caching the record; a
bad signature is treated as "Hst not found". Directory writes use
mTLS when configured ([Security](platform/Security.md) PART 9).

Direction:
  - Add a first-class RegisterHst (new hsts joining the directory)
    and MoveHst (HstId, NewPrvId) (host migration). RegisterHst is
    partially realised by (domatar, hosts).RegisterHst on each
    provider's Domatar App sub-host, which calls UpdateHst against
    the directory.
  - Emit a structured "Hst moved" reply when a server receives a
    message for an hstId that the directory says now lives elsewhere.
    The client-side retry is already implemented in
    HttpClient.shouldRefreshAndRetry; no server actually issues
    "Hst moved" today.

4.3  Local host cache

Each provider caches host records locally, with the same fields as
the authoritative directory. In this realisation that cache is the
MySQL `hst` table (`HstDb` plus `HttpClient.getHst` / `refreshHst`):

  * Local lookup first. If a record exists and FetchedAt is within
    HST_TTL_MILLIS (60s by default), it is returned as-is.
  * Otherwise the directory's GetHst is called and the record is
    upserted with FetchedAt = now.
  * If the directory is unreachable but a stale record exists, the
    stale record is used. The local cache is a soft fallback, not a
    hard consistency boundary.
  * On a send failure, refresh-and-retry runs once for any of
    transport failure, "Hst not found", or (eventually) "Hst moved".

The directory is the truth; the cache is an optimisation.

4.4  Self-hosting providers and the directory

In ordinary deployments the directory runs on a dedicated host. In
the local simulation it is colocated with one of the tomcats (tomcat1,
via the `domatar` alias on `domatar_net`); this is a substrate decision
recorded in docker-compose, not a code decision.

## PART 5 - ROUTING

Routing lives in `com.domatar.core.HttpClient.dispatch`. Two branches:

  1. SELF-DISPATCH (sendLocal):
     If `dstDomId.hstId` equals this server's `DOMATAR_HSTID`, the
     destination is unconditionally local and the runtime dispatches
     in-process via sendLocal. A provider is the authoritative source
     for its own hst record, so a directory round-trip would be wasted
     work — and worse, would create a recursive loop when the running
     server also serves as the directory (e.g. tomcat1 in the local
     simulation, where the loop re-enters the same JVM and starves
     the worker pool). The shortcut terminates that chain.

  2. REMOTE DISPATCH (sendHttp):
     Otherwise the runtime resolves the destination via
     `getHst(dstHstId)`, giving its Domain and PrvId.
       * If `hst.PrvId` equals `DOMATAR_HSTID`, the dispatch is local
         after all (the destination is a sub-host we provide), and
         the runtime calls sendLocal.
       * Otherwise the runtime HTTP-POSTs to
           "http://" + dst.Domain + "/domatar/Msg"
         where `/domatar` is `DomatarConfig.PLATFORM_CONTEXT_PATH` —
         the context path of the platform WAR. It is hard-coded
         because every Domatar server runs the same platform WAR at
         the same context.

  On any "Hst not found", "Hst moved", or generic transport failure,
  the runtime refreshes the destination's host record from the directory
  and retries the dispatch once.

A "to me" shortcut: if the caller leaves `dstDomId.hstId` empty, the
runtime substitutes the sender's own hstId before dispatch.

Removed in the present iteration:
  - The three-branch HttpClient (same-WAR / sibling-WAR / remote)
    that existed under the old per-app WAR layout. With apps as JARs
    (PART 11), every locally-handled message is in the same JVM and
    in the same ImplMap, so sendLocal serves both "same app" and
    "different app on same provider".
  - DomatarConfig.getAppId / getWarContextPath and the
    isHandledByThisWar helper. The platform no longer asks "which
    WAR handles this clsAppId" — every WAR is the same WAR.
  - Domain-equality fallback (srcDomain==dstDomain). Routing is
    by hstId only.

Direction (Option C):
  - When apps eventually run in their own JVMs / containers
    (PART 16), the same dispatch suffices: every app central host's
    `hst` row points to its own domain, and `sendHttp` reaches it
    directly. No code changes; only `hst` rows change.
  - Storing each peer's context path per-host in the directory
    becomes useful only if some peers run at a non-default mount
    point. Today the constant `PLATFORM_CONTEXT_PATH` covers it.

## PART 6 - OBJECT DISPATCH, VERIFICATION, AND AUTHORIZATION

6.1  Two dispatch entry points, one dispatch shape

  Two trust boundaries deliver messages into the in-JVM dispatcher:

    * `DomatarServlet` (browser entry; e.g. /domatar/<appId>/Wui/<W>):
        Reads cookies, runs LoginRemote.verifyLogin against the local
        `act` table (cookie/token path), builds a Context with
        verified=true on success (or refuses the request on a
        hard-required-verified page), and dispatches the WUI operation
        via HttpClient.

        HttpClient.send attaches a signed OriginBlock +
        account Delegation to the outbound message before the first
        cross-prv hop (if the context is verified and a fingerprint
        actId is present).  The signing uses THIS provider's operational
        key (ProviderKeyStore) and the account's current Delegation.

    * `Msg.doAction` (/domatar/Msg; cross-prv inbound):
        verifies the credential chain from Head.Sec
        ([Security](platform/Security.md) PART 7.3):
          (a) Parse Head.Sec.Origin + Head.Sec.Delegation.
              If absent → verified=false, dispatch continues.
          (b) Verify Delegation: self-cert (actId == fingerprint
              (rootPubKey)), signature, expiry, PrvId == SignerPrvId.
          (c) Fetch SignerPrv's operational pubkey from the local hst
              cache. Verify OriginSig over the canonical body.
          (d) Verify BodyHash matches.
          (e) Timestamp within ±DOMATAR_MSG_SKEW_MS AND nonce not seen
              (NonceCache in-memory sliding window).

        On full success: verified=true, actId = Origin.ActId.
        On any failure: verified=false.  NEVER trusts the wire's
        Verified flag.

        Directory traffic (hst/hsts) is exempt — verified=false,
        dispatch continues: provider-key lookups must be reachable
        before any credential chain can be verified.

  In-process local handler chains (`HttpClient.sendLocal`) inherit
  the verified flag unchanged: the JsonMsg is rebuilt from the same
  in-memory Context the entry point produced, so there are no
  per-handler credential checks.

  Cross-prv crossings reset verification at the Msg boundary:
  the receiving Msg.doAction runs its own credential-chain check
  and overwrites the Verified flag.  A prv only trusts signatures
  it has verified itself.

6.2  Class identification

  The message envelope optionally carries (clsAppId, clsId). If
  present, it identifies the handler and the destination object is
  NOT loaded — the handler receives obj=null. If absent, the object
  is loaded by DomId and (clsAppId, clsId) is taken from it. If
  neither yields a class, dispatch fails with "Obj not found".

  This makes container services (quips, news, follows, sentiments,
  bans, logs, accounts, hosts, …) work without a per-instance
  object: the browser sets clsId in the envelope, the handler runs
  with obj=null, and it queries objects and links for the instances
  it needs.

  The envelope also carries an OPTIONAL service qualifier
  (srvAppId, srvId). Dispatch never uses it — a message always
  reaches the single Java handler of the destination object's
  class — but a handler that implements two services declaring the
  same message name may read it to disambiguate. When absent,
  dispatch and behaviour are exactly as they were before services
  existed. The qualifier is the schema/interoperability axis, not a
  second routing axis ([Service](platform/Service.md) PART 9).

  Direction: harden envelope-class dispatch. Today a malicious client
  can send a class envelope with a dstDomId pointing at another
  user's account, which invokes the handler with that user's actId
  in the destination DomId. Handlers that read dstDomId.actId must
  defend themselves, or the caller's identity should be checked
  against dstDomId before dispatch.

6.3  Verification is identity; authorization is per-object

  Verification ("are you who you say you are") is established at the
  trust boundary once per request lifecycle and never re-evaluated
  inside the same provider. Authorization ("may you do this on this
  object") is per-object and per-message, evaluated at the top of
  every handler's handleMsg().

  Every handler extends `com.domatar.util.ObjImpl` and calls
  `hasRights(inMsg, obj, msgClient)`. ObjImpl provides a default
  hasRights that returns true (public); subclasses override it to
  declare stricter policies. Today the standard policies are:

    - public:    return true;                  (no check)
    - verified:  return Auth.isVerified(inMsg); (caller verified by
                                                 an upstream trust
                                                 boundary on this prv)
    - mixed:     switch on inMsg.getOperation() and return one of the
                 above per-op (e.g. ActManagerImpl: Login / AddAct /
                 VerifyLogin are public, GetAct / Logout are verified).

  `Auth.isVerified` is a flag read on Context.verified — no DB hit,
  no recursion. The framework's LoginRemote.verifyLogin is invoked
  exactly twice per request lifecycle (once at the entry trust
  boundary; never again as the message bounces through local
  handlers).

  Direction: richer dynamic policies (owner-match, follower-status,
  ban-list consultation, ...) are each just another branch inside a
  handler's hasRights() override. The class-descriptor object
  ([Class](platform/Class.md)) is the natural home for declaring policy
  intent alongside the message itself, so that authorization rules
  travel with the class declaration rather than living only in Java.

6.4  Directory exemption

  `Msg.doAction.verifyAndStamp` deliberately skips its verifyLogin
  call when the inbound class envelope is (hst, hsts) — i.e. a
  directory lookup. Directory traffic must be reachable without a
  verified caller because:
    1. HstsImpl is public-by-design (its hasRights returns true).
    2. Verification itself requires the directory: LoginRemote
       dispatches to the central act manager via HttpClient, which
       needs getHst(<central-host-id>) to find it. Requiring a
       verified caller for directory traffic creates a recursive
       dependency cycle (verifyLogin → getHst → POST /Msg →
       verifyAndStamp → verifyLogin → …) that exhausts the worker
       pool, especially when the running prv also serves as the
       directory. The exemption is a layering rule, not a perf
       optimisation: directory traffic is below verification, not
       above it.

6.5  VerifyLogin: yes and no are both Success

  VerifyLogin is a question whose two possible answers ("yes" and
  "no") are both valid responses to a valid question. The
  actManager always replies with a Success body; the verdict lives
  in the body, not in the success/error flag:

    - Yes: LoggedIn="True" plus the resolved ActId/UsrId/UsrName.
    - No:  LoggedIn="False" with no ActId.

  isSuccess()==false is reserved for transport- or message-level
  failures (the actManager could not even formulate an answer).
  Callers read identity from the body and require a non-null ActId
  in the reply before stamping the dispatch Context as verified.

6.6  Account dispatch is federated

  LoginRemote (VerifyLogin / GetAct) and ActWui (Login / AddAct /
  Logout / VerifyLogin) build their destination DomId with
  `hstId = parseAppId(usrId)` — i.e. they dispatch to the issuing
  app's CENTRAL HOST, not to the local prv. This is what makes a
  sign-in world-wide ([Login protocol](apps/Login-Protocol.md)): every prv asks the same
  authority for the same account, so one Login mints a single
  token that every prv recognises.

  Provider accounts ([Platform App](platform/Platform-App.md) PART 3): for accounts of
  the form `<prv>@<prv>` the central host IS the provider itself,
  so the login is purely local and does not depend on any remote
  authority. ActWui detects this by `parseAppId(usrId) ==
  parseHstId(usrId)`.

## PART 7 - PERSISTENCE (this realisation)

This PART is the reference Java / MySQL 8 store. Other realisations
may persist objects, accounts, links, and hosts differently; the
protocol in PART 2 does not require these tables.

Schema is materialised by the init scripts under `mySQL/` and
is loaded automatically via `/docker-entrypoint-initdb.d` on first DB
startup in the local simulation. The schema is identical on every
provider; per-row data is partitioned by hstId.

Tables, with the columns that matter to the runtime:

  obj  (HstId, AppId, ActId, ObjId,
        ClsAppId, ClsId, ObjName, ObjDesc, Attrs JSON)
       PK (HstId, AppId, ActId, ObjId).
       The runtime authoritative source of (clsAppId, clsId) per
       object (used when no class envelope is supplied).

  act  (ActId PK, UsrId UNIQUE, UsrName,
        Pwd, Ip1/Token1/Time1 ... Ip3/Token3/Time3,
        Encryption, FpVersion, OwnPrvKey,
        GenesisPubKey, OwnPubKey, BindingVersion, BindingNotBefore, BindingSig,
        Delegation, DelegSig, DelegNotAfter)
       One row per account; holds the password hash and the
       rotating session tokens. FpVersion is the fingerprint algorithm
       that minted ActId/OwnId (default 1 = SHA-256[0..24)->Base64->32ch;
       [Identifiers](platform/Identifiers.md)). OwnPrvKey
       holds the AES-256-GCM sealed Ed25519 OWNERSHIP private key used
       for issuing delegations ([Identifiers](platform/Identifiers.md) PART 5).
       GenesisPubKey / OwnPubKey /
       BindingVersion / BindingNotBefore / BindingSig store the
       genesis-signed actId->ownId binding ([Identifiers](platform/Identifiers.md) PART 6);
       the genesis PRIVATE key is never stored here. ActId is a
       fingerprint of GenesisPubKey (v1: 32 chars); UsrId is the mutable
       login label.

  Login membership classes ([Login protocol](apps/Login-Protocol.md); IMPLEMENTED):
       (login, membership)  — peer directory container on
                              login-<actId>-<prvId>
       (login, peer)        — one linked login; ObjId peer-<usrId>
       (login, binding)     — replicated actId->ownId binding (LWW by Version)

  Desktop / Navigator hosts ([Desktop](apps/Desktop.md); IMPLEMENTED):
       desktop-<actId>-<prvId>     — Desktop apps container + tile rows
       navigator-<actId>-<prvId>   — per-provider navigator root (not
                                     content-merged across providers)
       Tile attrs: TileVersion, Tombstone; apps container Version =
       max TileVersion. Intrinsic tiles {login,navigator,desktop} are
       per-provider and exempt from content-sync.

  lnk  (HstId, AppId, ActId, ObjId,              ← source object ("from")
        LnkHstId, LnkAppId, LnkActId, LnkObjId,  ← target object ("to")
        LnkClsAppId, LnkClsId,                    ← class of the target
        LnkObjName, LnkObjDesc,                   ← display metadata
        TagAppId, Tag,                            ← relationship name
        Val,                                      ← optional secondary key
        SeqNum)                                   ← ordering index
       PK (HstId, AppId, ActId, ObjId, TagAppId, Tag, SeqNum, Val).
       LnkHstId..LnkObjId are NOT part of the PK, so a given source
       object can only have one target per
       (TagAppId, Tag, SeqNum, Val) combination.

  hst  (HstId PK, Domain, PrvId, Version, FetchedAt, PubKey, RecordSig)
       On the directory: authoritative. On every other provider: a
       local cache. Version and FetchedAt are bookkeeping for cache
       freshness.

Link naming convention — TagAppId / Tag / SeqNum / Val
-------------------------------------------------------
A link is a named pointer (or named array element) owned by the source
object. The four fields encode that ownership:

  TagAppId  The app that defines and "owns" this relationship name.
            Normally equals the appId of the app that creates the link.

            There is one legitimate case where TagAppId differs from
            the creating app: cross-app interoperability. An app may
            create objects whose class is defined by a different app
            (e.g. app B creates objects of class (A, Book) so they
            interoperate with app A's handlers). When it does, it
            may also need to create links whose relationship is
            defined by app A — because the graph structure is part
            of the contract that app A's handlers depend on. In that
            case TagAppId=A is correct: A defines the relationship,
            B is merely participating in it.

            The only other exception is the Navigator root → app-*
            links, where TagAppId="navigator" is correct because
            the Navigator app itself creates and owns that part of
            the tree. The same is true of the "container" tag used
            for app-internal skeleton links on Navigator pages:
            TagAppId="navigator" Tag="container" identifies a node
            that the Navigator will render as a structural child.

  Tag       The relationship name — the field/variable name on the
            source object that holds this pointer. Choose a name
            meaningful within the context of TagAppId. Examples:

              source: app-bookstore (bookstore)
                tag="forsale"    → the forsale container
                tag="library"    → the library container
                tag="cart"       → the cart container
                tag="clss"       → the class-descriptor container

              source: library (bookstore)
                tag="book"       → one book in the library collection

              source: app-money (money)
                tag="profile"    → the user's profile
                tag="myaccounts" → the accounts index
                tag="bank"       → the bank node (bank users only)

              source: root (navigator)
                tag="app"        → one installed app entry

              source: app-catalog (domatar)
                tag="catalogEntry" → one installed-on-this-provider
                                      app entry

            Avoid generic words like "container" or "children" that
            convey no meaning about the specific relationship,
            except for the Navigator's structural-container links
            described above.

  SeqNum    Ordering index within one (TagAppId, Tag) collection.
            Use it ONLY to order members of the same logical group.
            Singleton pointers use SeqNum=0. Collections use
            SeqNum=<timestamp-millis> so items sort by insertion
            time. Never use SeqNum to distinguish functionally
            different relationships — give those different Tag
            values instead.

  Val       Optional string key for members of the same
            (TagAppId, Tag) group. Useful when the natural key of
            the target is a string (e.g. an actId, a currency code,
            a hstId, an appId). Val="" when unused.

The Navigator ([Navigator](apps/Navigator.md)) is read-only with respect to link
structure: it displays whatever links exist on each object,
regardless of TagAppId. The (TagAppId, Tag) fields are for the
owning app's own queries and for future filtering operations — the
Navigator does not filter by them.

Persistence direction:
  - Per-host partitioning of object / account / link seeds: today every
    provider's MySQL dump contains the same rows for convenience. A
    follow-up should split the seed by HstId so each server only
    carries the data for the hsts it actually serves.
  - Object-level migration tooling (export/import of one host's
    objects, accounts, and links plus its per-host files), to support
    MoveHst.

## PART 8 - CLASS OBJECTS AND SERVICES

The schema layer of Domatar is made of two kinds of self-describing
object, and they are deliberately separate. A SERVICE declares an
INTERFACE — what attributes an object has and what messages it
understands. A CLASS declares an IMPLEMENTATION — which handler runs,
which services it implements, how instances are named, and what
authorization / side-effect policy applies. Interface is shared and
immutable; implementation is local to the one application that owns
the class. This split is the operative form of "the class descriptor
is the interoperability contract": the contract is now its own
first-class object, the service. [Class](platform/Class.md) and [Service](platform/Service.md)
are authoritative; the summary that matters at platform level:

8.1  Service objects (the interface)

  * A service is identified by the pair (srvAppId, srvId), where
    srvAppId is the application that DEFINES the service (not the one
    that installs it). It is an object with ClsAppId="domatar",
    ClsId="srv", AppId = srvAppId, and ObjId = "<srvId>Srv"
    (e.g. the (bookstore, book) service has ObjId "bookSrv").

  * Its Attrs JSON is
        { "SrvAppId", "SrvId", "Description"?, "Extends"?,
          "Attrs": [...], "Msgs": [...] }
    listing every attribute and every message of the interface, each
    with the JSON type given by the type grammar (`"String"`,
    `"Number"`, `[type]`, `{ "name": type, … }`, `?` for optional).
    A service carries NO SideEffect, NO Auth, NO handler, NO icon —
    interface only.

  * Services may inherit: "Extends" lists parent services, and a
    service's resolved interface is the union of its own members and
    its parents', each member keeping its DEFINING service's identity
    (srvAppId, srvId, Name). Diamonds deduplicate by that identity.
    Backward-compatible evolution is a NEW service that Extends the
    old one; a breaking change is simply an unrelated service. Every
    member in the system is identified by the triple
    (srvAppId, srvId, Name).

  * Services are IMMUTABLE: a published (srvAppId, srvId) denotes one
    interface forever. Implementing apps may COPY a service descriptor
    onto their own sub-hosts (for convenience, or so the interface
    survives the defining app becoming unreachable); because the
    definition never changes, copies dedup safely by identity through
    the idempotent create path.

8.2  Class objects (the implementation)

  * A class is identified by (clsAppId, clsId) and is always LOCAL:
    clsAppId is always the owning application's appId. Its descriptor
    is a row in `obj` with ClsAppId="domatar", ClsId="cls", obj
    AppId = clsAppId, and ObjId = "<clsId>Cls" (e.g. "listingCls").

  * Its Attrs JSON carries no members of its own. Instead it holds
        { "ClsAppId", "ClsId", "Description"?, "Conventions"?,
          "Implements": [ "srvAppId.srvId", … ],
          "ImplRef"?, "MsgPolicy"?, "AttrStorage"? }
    — the services it implements, the optional handler binding, the
    instance-naming Conventions, the per-message SideEffect / Auth
    (MsgPolicy), and any per-attribute storage-key overrides
    (AttrStorage). Authorization and side effects live here, on the
    implementation, never on the shared interface.

  * Interoperability is by implementing a shared SERVICE with your
    OWN local class, not by adopting another app's class. If the
    Bookstore defines the (bookstore, book) service, a future Library
    app implements that same service with its own (library, book)
    class; the two are interoperable because both classes'
    implemented-service closures contain (bookstore, book). The
    former "declare a foreign class" mechanism is retired.

  * Class and service descriptors are created at install time by each
    app's install routine, on every sub-host on which they are used.
    Per design principle P2 ([Platform App](platform/Platform-App.md)) each sub-host
    carries its own copies, so host migration stays self-contained.

  * The (domatar, cls) and (domatar, srv) meta-classes are
    self-describing: there is a class object that describes class
    objects and a service that describes service objects, created by
    DomatarInstall on every navigator-<actId> and domatar-<prvActId>
    sub-host.

8.3  Resolution: GetCls, GetSrv, and ClsMap

  * GetSrv on any (domatar, srv) object accepts (SrvAppId, SrvId) and
    returns the service's interface document, located by the
    "<srvId>Srv" ObjId convention with obj AppId = SrvAppId.

  * GetCls on any (domatar, cls) object accepts (ClsAppId, ClsId) and
    returns the RESOLVED class descriptor: the union of the
    implemented services' interfaces (flattened through Extends), with
    the class's SideEffect / Auth and storage keys overlaid, plus an
    "ImplementsClosure" listing every service the class satisfies. The
    top-level shape { ClsAppId, ClsId, ObjName, ObjDesc, Attrs } is what
    Navigator ([Navigator](apps/Navigator.md)), WUI builders, and the AI
    Agent tool catalogue ([AI Agent](apps/AIAgent.md)) consume.

  * Resolution is LOCAL: it reads only objects on the host where the
    class descriptor lives. The runtime caches resolved descriptors in
    ClsMap, an in-memory registry analogous to ImplMap. Unlike ImplMap
    (which maps to code fixed at startup), ClsMap caches DATA and so
    supports invalidation; coherence is cheap because services are
    immutable, so only a local class edit invalidates an entry.

  * A class with NO "Implements" field is treated as an inline
    descriptor and resolves to its own Attrs/Msgs. Apps that have not
    published a service still GetCls-describe themselves that way
    ([Service](platform/Service.md) PART 15).

Direction ([Service](platform/Service.md) PART 17):
  - A network-wide reverse index — "find every object that implements
    service S" — of which the per-class ImplementsClosure is the unit.
  - Converge ImplMap and ClsMap: carry the ImplRef binding in the
    resolved descriptor so the runtime binds handlers from descriptors
    rather than from the app manifest (PART 11), completing the
    ImplMap-replacement direction.
  - Type references by service name in the type grammar (e.g. a
    parameter Type of "bookstore.book" referring to that service's
    attribute shape) instead of inlining the shape.
  - Enforcement of service immutability once a publishing /
    distribution mechanism exists.

## PART 9 - APPLICATIONS AND THE APP CATALOG

9.1  What an application is, concretely

An application is a JAR (PART 11) that ships:

  * Handler classes — one per class the app defines, implementing
    `DomatarInterface` (typically by extending `ObjImpl`).
  * Wui (Web UI) servlet classes — one per browser-facing entry
    point, extending `DomatarServlet`.
  * An install class — implementing `AppInstall`, with two methods:
        installProvider(prvId, domain)                 (provider-level)
        installUser(actId, usrId, usrName, prvId,
                    domain, msgClient)                 (user-level)
  * `META-INF/domatar/app.manifest` — the declarative descriptor
    (PART 11) listing AppId / Version / InstallClass /
    AssetDirectory and one line per Handler and Wui.
  * `<appId>/app.config.txt` — AppName, AppDesc, Version,
    DependsOn, ProviderHosts.
  * Static assets under `<appId>/assets/` — HTML, JS, CSS, icons,
    PNGs. Reachable as `/domatar/<appId>/<file>` via a per-app
    AppAssetServlet.

The reference distribution today contains nine applications plus
the platform app:

  Application    appId         Role
  ──────────────────────────────────────────────────────────────────
  Domatar        domatar       The platform's own app: host
                               directory, account manager, account
                               cache, app catalog, class container,
                               default object icon. Provider-only;
                               no per-user install.
  Navigator      navigator     Object-graph browser. Every account
                               has it. Owns (navigator, root) and
                               the per-account navigator-<actId>
                               sub-host. [Navigator](apps/Navigator.md).
  Login          login         Identity-management UI. Every account
                               has it. Owns the linked-logins
                               directory and the per-account
                               login-<actId> sub-host.
                               [Login](apps/Login.md).
  Desktop        desktop       Home-screen launcher. Every account
                               has it. Owns the apps grid and the
                               per-account desktop-<actId>
                               sub-host. [Desktop](apps/Desktop.md).
  AppStore       appstore      Discovery / install UI. Lets the
                               user browse the provider's app
                               catalog and install or uninstall
                               apps on their account.
  Quippin        quippin       Social microblog: quips, news,
                               follows, sentiments, bans, logs,
                               Quippin Directory. [Quippin](apps/Quippin.md).
  Bookstore      bookstore     Marketplace + library. Catalog,
                               ForSale, Library, Cart classes.
                               [Bookstore](apps/Bookstore.md).
  Spreadsheet    spreadsheet   Cells and formulas. Sheets and Sheet
                               classes; formula parser/evaluator.
                               [Spreadsheet](apps/Spreadsheet.md).
  Money          money         Accounts, profile, bank. Profile,
                               Bank, MyAccounts, Accounts, Account
                               classes. [Money](apps/Money.md).
  AI Agent       aiagent       LLM-backed conversations.
                               Conversations, Conv, Msg classes;
                               Groq adapter today. [AI Agent](apps/AIAgent.md).

The platform does not encode any knowledge of these specific apps in
its code (with the exception of provider bootstrap, see PART 10).
Every app is loaded the same way: an entry in `WEB-INF/apps/`, an
`app.manifest`, an `AppInstall` instance. Anyone publishing a new
application drops the JAR in the apps directory and runs /Setup; no
recompile of the platform.

9.2  The App Catalog

Each provider maintains an App Catalog: a (domatar, catalog) obj on
its Domatar sub-host, with one (domatar, catalogEntry) child per app
the provider has installed:

    domatar-<prvActId> / domatar / <prvActId> / app-catalog
        ↓ tagAppId="domatar" tag="catalogEntry"
    domatar-<prvActId> / domatar / <prvActId> / catalog-<appId>
        Attrs: { AppId, AppName, AppDesc, Version }

The catalog is populated by each app's `installProvider` (which
calls `CatalogInstall.registerInCatalog`), and consumed by the
AppStore to show a user what is installable on this provider. The
AppStore's InstallApp operation refuses with a clear error if the
target appId is not in the catalog ([Installation](install/Installation.md) PART 10.3),
so misconfigured deployments fail loudly rather than producing
silent routing errors.

Site customization ([Provider Customization](install/Provider-Customization.md)) extends the same
`(domatar, catalog)` handler (`AppCatalogImpl`) with:

  GetDefaultAppsConfig / SetDefaultApps / ResetDefaultApps

Browser entry: `/domatar/domatar/Wui/SiteConfigWui`. Persistence is the
`provider-config` object on `domatar-<prvActId>` (attrs DefaultApps,
Version). Sign-up and GetCatalog IsDefault resolve through
`com.domatar.act.ProviderDefaults.resolveDefaultApps()`.

Federated discovery / install ([App Store](apps/AppStore.md)):
  - Central registry of listings + per-provider offers; SearchApps /
    GetListing; InstallApp(AppId, PrvId) onto a portable per-user host
    with a Desktop tile that may launch on the offering provider.
  - Per-provider App Catalog remains the SUPPLY fact (this PART); the
    registry does not replace it.

Direction (still open):
  - Cross-provider JAR fetch on demand if the home provider does not
    already have the app binary (PART 16.2 / [App Store](apps/AppStore.md) PART 12).

## PART 10 - INSTALLATION: PROVIDER, APPLICATION, USER

There are three independent installation levels. The full
specification is [Installation](install/Installation.md); the summary that matters
here is which agent triggers each level and what each level produces.

  PROVIDER INSTALLATION  (one-time per physical server)
      Trigger: operator hits /domatar/Setup once after first deploy.
      Action: `PlatformSetupServlet` (today reached via the thin
              subclass `DomatarSetupServlet`, which wires
              `DomatarProviderInstall.install()` into the
              `doProviderBootstrap` hook) creates the provider
              account `<prvId>@<prvId>`, the domatar-<prvActId>
              sub-host graph, the App Catalog container, and runs
              the provider-account's own user-install chain for
              navigator / domatar / login / desktop via
              AppRegistry. It then iterates `AppRegistry.all()`,
              calling each loaded app's `installProvider(prvId,
              domain)` so the app registers in the catalog and
              creates any provider-scoped hosts it owns.
      Result: a running Domatar provider with all bundled apps
              available to its users and visible in the AppStore.

  APPLICATION INSTALLATION  (per provider, per new app)
      Trigger: operator drops `<appId>.jar` into the platform WAR's
              `WEB-INF/apps/` directory and restarts the platform.
              `AppLoader.contextInitialized` loads the new app, and
              on the next /Setup (or the next AppRegistry iteration)
              the new app's `installProvider` runs.
      Result: the provider's App Catalog gains a new entry; the
              new app's central host (if any) is created; AppStore
              can offer it to users.

  USER INSTALLATION  (per account, per app)
      Trigger: either automatic at sign-up (appIds from
              ProviderDefaults.resolveDefaultApps()) or explicit via
              the AppStore (InstallApp operation).
      Action: the platform dispatches `InstallUser` to (appId,
              install) on the per-user sub-host `<appId>~<actId>`
              via `UserInstallDispatch.sendInstallUser`. The
              receiving prv routes to `AppUserInstallHandler`,
              which delegates to the app's
              `AppInstall.installUser(actId, usrId, usrName,
              prvId, domain, msgClient)`.
      Result: the app's per-user sub-host `<appId>~<actId>` is
              registered, the app's container objs and per-app
              data objs are created on that sub-host, the
              user's Navigator root gains a new app-<appId>
              link, the user's Desktop gains a new app tile,
              and the app's class descriptors are created on the
              user's navigator-<actId> sub-host.

Provider bootstrap also seeds the provider's own per-account
structure: at first /Setup the provider account is created as a
normal account, and the install chain runs `installUser` for the
four provider-appropriate apps (navigator, domatar, login, desktop)
on the provider account itself. The provider therefore lands on its
own Desktop after login, like any other user, and sees the Domatar
app alongside Navigator / Login / Desktop in its Navigator tree
([Platform App](platform/Platform-App.md) PART 3.3).

10.1  Idempotence

Every install routine is idempotent. Re-running provider install,
re-deploying an app, or installing the same app on the same user
twice are all no-ops. Idempotence is implemented by
`ObjDb.addObjIfMissing`, `HstDb.getHst`-then-`addHst`,
`LnkDb.getLnk`-then-`addLnk`, and `CatalogInstall.hasCatalogEntry`
guards. PlatformSetupServlet additionally short-circuits if the
provider account already exists and the App Catalog has been
created.

10.2  Direction

  - First-class RegisterHst / MoveHst operations on the directory,
    so that adding a new central host or migrating a host to a
    different provider becomes an in-protocol operation rather than
    an offline SQL edit.
  - The AppStore's InstallApp on a cross-provider target should
    fetch the JAR bytes from the chosen provider on demand
    (R7.b above) so providers no longer need to ship every app
    JAR pre-installed.
  - A per-provider catalog admin UI (today's `(domatar, catalog)`
    obj is mostly read-only) for enabling, disabling, and
    versioning apps without operator-level file manipulation.

## PART 11 - PACKAGING: APPS AS JARS

11.1  The artefact

One Tomcat WAR is deployed: `domatar.war`, the platform WAR. It is
identical on every Domatar server in a network. Inside it:

  domatar.war/
    WEB-INF/
      web.xml                          Minimal: DbConnection,
                                       AppLoader listener, Setup
                                       servlet.
      lib/
        domatar-core-1.0-SNAPSHOT.jar  All domatar-core code.
        mysql-connector-j-*.jar
      apps/
        domatar-app.jar                The platform's own app.
        navigator.jar
        login.jar
        desktop.jar
        appstore.jar
        quippin.jar
        bookstore.jar
        spreadsheet.jar
        money.jar
        aiagent.jar
    jquery-3.5.1.js                    Shared platform assets,
    domatar.css                        reachable as /domatar/<file>.
    index.html
    quip.js, quip2.js, sideMenu.js
    beep.mp3

Each app JAR contains:

  com/<appId>/objimpl/*.class          Handler classes.
  com/<appId>/install/*.class          AppInstall implementation.
  com/<appId>/webui/*.class            Wui servlet classes.
  META-INF/domatar/app.manifest        Declarative descriptor.
  <appId>/app.config.txt               AppName, AppDesc, Version,
                                       DependsOn, ProviderHosts.
  <appId>/assets/<file>                HTML, JS, CSS, PNGs.
  <appId>/assets/icons/app.svg         The app's launcher icon.
  <appId>/assets/icons/cls/<clsId>.svg Per-class icons.

11.2  Manifest format

`META-INF/domatar/app.manifest` is UTF-8, with `#` line comments,
blank lines ignored, one `Key: value` per line. Example:

  AppId:          quippin
  Version:        1.0
  InstallClass:   com.quippin.install.QuippinInstall
  AssetDirectory: quippin/assets

  Handler: quippin, quip,        com.quippin.objimpl.QuipImpl
  Handler: quippin, quips,       com.quippin.objimpl.QuipsImpl
  ...
  Wui: QuipWui,  com.quippin.webui.QuipWui
  Wui: QuipsWui, com.quippin.webui.QuipsWui
  ...

Required keys: AppId, InstallClass, AssetDirectory.
Optional keys: Version (default "1.0"), ProviderHosts (default
empty), Handler*, Wui* (default zero).

The manifest is the canonical declaration: whatever loads the app —
the present AppLoader, or a future standalone Option C host
(PART 16) — reads the same file.

11.3  The AppLoader

`com.domatar.app.AppLoader` is the sole `ServletContextListener`
declared in the platform WAR's `web.xml`. On startup:

  1. Discover every *.jar in WEB-INF/apps/ (sorted by filename).
  2. For each JAR:
       a. Create a `URLClassLoader` for the JAR, parented to the
          platform WebappClassLoader.
       b. Parse `META-INF/domatar/app.manifest`.
       c. Instantiate the install class via reflection.
       d. For each Handler line, instantiate the handler and
          register it in ImplMap as (clsAppId, clsId) → handler.
       e. Register `(appId, "install")` → `AppUserInstallHandler(appId)`
          so InstallUser dispatches reach the app's installUser
          method via AppRegistry.
       f. For each Wui line, instantiate the servlet and register
          it programmatically at `/domatar/<appId>/Wui/<WuiName>/*`.
       g. Register an `AppAssetServlet` at `/domatar/<appId>/*` so
          the JAR's asset directory is served as static files.
       h. Record the loaded app in `AppRegistry`.

On shutdown, each URLClassLoader is closed (best-effort). Hot-reload
of individual app JARs is not supported in the present iteration.

11.4  AppRegistry

`com.domatar.app.AppRegistry` is a static map of loaded apps, keyed
by appId. Every code path that needs an app's ClassLoader,
manifest, or `AppInstall` instance reads it from here. Iteration
order is insertion order (which is filename order from
AppLoader.discover).

`AppRegistry` lets the platform invoke app behaviour without
compile-time references to the app — `DomatarProviderInstall.install`
walks `AppRegistry.all()` and calls each app's `installUser` /
`installProvider` reflectively. Per design principle, no class in
domatar-core or domatar-app names any application by appId.

11.5  URL conventions

  /domatar/Msg                            inbound message dispatch
  /domatar/Setup                          one-shot provider bootstrap

  /domatar/<appId>/Wui/<WuiName>          per-app HTTP entry points
                                          (registered by AppLoader)

  /domatar/<appId>/<file>                 per-app static asset
                                          (served by AppAssetServlet
                                          from the JAR's asset dir)

  /domatar/<appId>/icons/app.svg          per-app launcher icon
  /domatar/<appId>/icons/cls/<clsId>.svg  per-class icon
                                          (falls back to
                                          /domatar/domatar/icons/
                                          cls/default/obj.svg)
  /domatar/<appId>/<file>.png             per-app binary asset
                                          (e.g. /domatar/quippin/like.png)

  /domatar/<file>                         shared platform asset
                                          (jquery, domatar.css,
                                          index.html, shared JS)

  /domatar/domatar/icons/<file>.svg       platform-owned icon
                                          (default obj.svg fallback;
                                          concept icons such as
                                          calendar.svg, chat.svg)

HTML inside an app uses relative URLs ("Wui/<W>" for the local Wui
servlet, "../<file>" for shared platform assets). Icon URLs are
emitted in absolute form because they are constructed from many
pages at different depths (Desktop tile, AppStore tile, Navigator
tree-row, class-detail panel). Both forms survive an eventual
Option C deployment unchanged (PART 16).

## PART 12 - CONFIGURATION

Per-server configuration is read at startup with this precedence,
highest to lowest:

  1. Environment variable             (e.g. DOMATAR_HSTID)
  2. JVM system property              (e.g. -Ddomatar.hstid=...)
  3. provider.config.txt              (filesystem or classpath)
  4. Hardcoded default                (where one exists)

The reader is `com.domatar.core.DomatarConfig`.

12.1  Keys

  DOMATAR_HSTID              This server's own hstId. Required.
                             The single per-server identity value.

  DOMATAR_PRVID              This server's PrvId. Defaults to
                             DOMATAR_HSTID for self-hosting
                             providers.

  DOMATAR_DOMAIN             This server's wire network address
                             (host:port) for Msg / hst publish.
                             Required for provider install. May be
                             Docker-internal (e.g. tomcat2:8080).

  DOMATAR_PUBLIC_DOMAIN      Optional browser-facing hostname
                             (nginx front door), e.g.
                             domatar.avatarvia.com. When set and
                             distinct from DOMATAR_DOMAIN, absolute
                             IconPath / LaunchPath / AssetOrigin use
                             this host and strip /domatar. Config key:
                             PublicDomain. See [Icons](platform/Icons.md) PART 5.2.

  DOMATAR_DB_URL             JDBC URL override; falls back to the
                             DbConnection literal in web.xml.

  DOMATAR_DIRECTORY          host:port of the global directory.
                             Defaults to "domatar:8080".

  DOMATAR_ADMIN_PASSWORD     Initial password for the provider
                             administrator account.

  DOMATAR_CONFIG_PATH        Path to provider.config.txt (used
                             instead of /opt/domatar/provider.config.txt).

  DOMATAR_DEFAULT_APPS       Comma-separated appIds that override
                             the default-app list for new accounts.

  DOMATAR_DEFAULT_APPS_CONFIG
                             Path to default-apps-config.txt
                             (overrides discovery next to
                             provider.config.txt).

  AIAGENT_API_KEY            Per [AI Agent](apps/AIAgent.md) PART 13.3; an
                             example of an app-scoped env var. The
                             platform passes such values through;
                             apps read them via their own config
                             plumbing.

  PLATFORM_CONTEXT_PATH      Constant ("/domatar"). Used by
                             HttpClient.sendHttp to build remote
                             /Msg URLs. Not currently overridable;
                             becomes overridable in Option C
                             (PART 16).

12.2  Config files

  provider.config.txt        Site-wide settings: HstId, PrvId,
                             Domain, DbUrl, AdminPassword. Lives
                             at /opt/domatar/provider.config.txt
                             in production; bundled in the
                             domatar WAR as a development /
                             docker default. UTF-8, key=value
                             per line, # comments.

  default-apps-config.txt    Comma-separated appIds that every
                             new user account receives at sign-up.
                             Lives next to provider.config.txt;
                             keeping it separate prevents
                             accidental edits to the security-
                             sensitive provider settings. UTF-8,
                             # comments, multi-line.

  app.config.txt             Per-app: AppName, AppDesc, Version,
                             DependsOn, ProviderHosts. Bundled
                             in each app JAR under
                             `<appId>/app.config.txt`; read at
                             install time via the app's
                             ClassLoader.

The set of hosts a server provides is NOT configured. It is
derivable at any time from the local hst cache:

    SELECT HstId FROM hst WHERE PrvId = :DOMATAR_HSTID

A self-hosting server (PrvId == HstId) appears in its own served
set.

## PART 13 - SOURCE LAYOUT

The Maven multi-module build at the repo root:

  pom.xml                       Parent POM. Modules in build order:
                                  domatar-core
                                  navigator
                                  login
                                  desktop
                                  quippin
                                  bookstore
                                  spreadsheet
                                  money
                                  aiagent
                                  appstore
                                  domatar-app
                                  domatar

  domatar-core/                 The platform library. Produces
                                domatar-core.jar. Contains the
                                runtime (HttpClient, Msg, ImplMap,
                                Context, ObjImpl, AppLoader, …),
                                the DB layer (ObjDb, LnkDb, ActDb,
                                HstDb), the install SPI
                                (AppInstall, AppUserInstallHandler,
                                UserInstallDispatch, CatalogInstall,
                                ClsInstall), and the JSON utilities.
                                Contains NO application code.

  domatar-app/                  The platform's own application
                                (appId="domatar"). Produces
                                domatar-app.jar. Contains the
                                directory handler (HstsImpl), the
                                account manager / cache
                                (ActManagerImpl, ActCacheImpl),
                                the ActWui browser servlet, the
                                Domatar-app obj handlers
                                (HostsImpl, HostImpl,
                                AppCatalogImpl), the Navigator's
                                app-node handler (NavAppImpl), and
                                the platform's own provider /
                                user install class
                                (DomatarAppInstall plus
                                DomatarInstall / DomatarProviderInstall).

  navigator/                    Each per-app module produces
  login/                        <appId>.jar, with its own
  desktop/                      META-INF/domatar/app.manifest,
  appstore/                     <appId>/app.config.txt, and
  quippin/                      <appId>/assets/ tree. No app
  bookstore/                    module references the Servlet API
  spreadsheet/                  (except its own Wui classes) or
  money/                        Tomcat directly; no app module
  aiagent/                      references "the WAR I live in" or
                                "the context path".

  domatar/                      The platform WAR module. Produces
                                domatar.war. Depends on every app
                                JAR at runtime scope; the
                                maven-dependency-plugin copies
                                each app JAR into
                                target/domatar/WEB-INF/apps/ at
                                the package phase.

  mySQL/                        SQL init scripts loaded by both
                                local-simulation MySQL containers
                                on first boot.

  tomcat/                       Tomcat configuration (context.xml)
                                shared between the simulation
                                containers.

  nginx/                        Local-simulation reverse proxy.

  Spec-*.txt                    The specifications. The repository
                                also carries working notes (executed
                                migration plans, design-space
                                write-ups); the specifications proper
                                are the Spec-*.txt set and are the
                                only documents this spec cites.

Build and deploy notes:

  mvn package -pl <appId> -am   Rebuilds an app JAR but does NOT
                                update the deployed copy under
                                domatar/target/domatar/WEB-INF/apps/.

  mvn package -pl domatar -am   Rebuilds every upstream app JAR
                                that has changed AND copies them
                                all into WEB-INF/apps/. This is
                                the command to use after any
                                source change.

  docker compose restart        Picks up the bind-mounted updated
    tomcat1 tomcat2             WAR contents without rebuilding
                                the container image.

## PART 14 - LOCAL SIMULATION (docker-compose)

The local simulation runs two independent Domatar providers (prv1,
prv2) on one machine, sharing a directory (colocated with prv1).
This exercises every cross-provider code path while keeping the
developer cycle to a single `mvn package` plus `docker compose
restart`.

Topology:

  networks:
    net1        -> tomcat1 + db1   (db1 aliased as `db` on net1)
    net2        -> tomcat2 + db2   (db2 aliased as `db` on net2)
    domatar_net -> tomcat1, tomcat2, nginx

On `domatar_net` tomcat1 wears six DNS aliases corresponding to the
six hosts it represents in the directory:

  domatar       The global directory (peers fetch hst records from
                this name).
  tomcat1       Its own provider role (registered in hst as
                'prv1' → 'tomcat1:8080').
  quippin       The Quippin app's central host.
  login         The Login app's central host.
  navigator     The Navigator app's central host.
  aiagent       The AI Agent app's central host.

tomcat2 likewise wears three central-host aliases for the apps
whose central hosts live on prv2 in the reference simulation
(bookstore, spreadsheet, money).

Services:

  db1, db2      mysql:8.0.20. Each has its own named volume
                (db1_data, db2_data). Both mount ./mySQL into
                /docker-entrypoint-initdb.d read-only, so both
                run the same init scripts on first boot. db1
                publishes on host port 3307, db2 on 3308.

  tomcat1       image: tomcat:10.1-jdk17
                env:    DOMATAR_HSTID=prv1
                        DOMATAR_PRVID=prv1
                        DOMATAR_DOMAIN=quippin:8080
                        DOMATAR_PUBLIC_DOMAIN=domatar.quippin.com
                        DOMATAR_DIRECTORY=domatar:8080
                        DOMATAR_ADMIN_PASSWORD=admin
                        AIAGENT_* (see [AI Agent](apps/AIAgent.md) PART 13.3)
                volume: ./domatar/target/domatar →
                          /usr/local/tomcat/webapps/domatar:ro
                ports:  8080:8080 (HTTP), 5005:5005 (JPDA)
                init:   true (tini reaps zombies)

  tomcat2       Same image, mirror config:
                env:    DOMATAR_HSTID=prv2
                        DOMATAR_PRVID=prv2
                        DOMATAR_DOMAIN=tomcat2:8080
                        DOMATAR_PUBLIC_DOMAIN=domatar.avatarvia.com
                        (other vars as above)
                ports:  8081:8080, 5006:5005

  nginx         Reverse proxy on host port 80 over domatar_net.

After the first /Setup, the directory contains host records for
both providers, all central hosts, the domatar- sub-host for each
provider account, plus per-user sub-hosts for the seed accounts.
A message from a handler on prv1 to a DomId on `quippin-micha@quippin`
is routed: tomcat1 (DOMATAR_HSTID=prv1) looks up the destination,
sees PrvId=prv2, and HTTP-POSTs to `tomcat2:8080/domatar/Msg` over
`domatar_net`. Reply returns the same way.

Browser-side hostname isolation (workstation convenience, not part
of the artefact): add `127.0.0.1 prv1.local prv2.local` to the host
machine's hosts file, then browse to
`http://prv1.local:8080/domatar/` and `http://prv2.local:8081/domatar/`.
Distinct hostnames keep cookies from bleeding across the two
simulated providers.

Direction: an option to scope per-host file storage to a separate
volume such as `/var/domatar/hosts/<hstId>/`, independent of the
WAR, so per-host export/import is just `tar` on one directory.
Today handler file output (where any) is rare; this becomes
relevant when MoveHst is implemented (PART 4 / PART 16).

## PART 15 - INSTALLING ON A REAL SERVER

The same instructions apply to a developer's laptop running the
local two-server simulation and to any single cloud server that
wants to host one Domatar node. Per-server differences come
entirely from environment variables and `provider.config.txt`.

Prerequisites:
  * Java 17 (JDK).
  * Maven 3.8+ (only at build time).
  * Apache Tomcat 10.1 (run time).
  * MySQL 8+ (run time).
  * Docker 24+ with Docker Compose v2 (for the local simulation).

Step 1: get the source.
    git clone <repo-url> domatar
    cd domatar

Step 2: build the artefact.
    mvn package
This produces domatar/target/domatar/ (exploded) and
domatar/target/domatar.war (single file). Both contain
`WEB-INF/apps/<appId>.jar` for every module in the parent POM.

Step 3a: bring the local simulation up.
    docker compose up -d
On first boot, db1 and db2 each run the SQL scripts under ./mySQL,
then tomcat1 and tomcat2 start with the WAR contents bind-mounted
read-only.

Step 3b: deploy to a real server.
    1. Provision Linux + JDK 17 + Tomcat 10.1 + MySQL 8.
    2. Create database `domatar` and load the init scripts under
       mySQL/. On a node that is NOT the directory, you can leave
       the `hst` table empty; the cache populates from the
       directory at first use.
    3. Resolve the JDBC hostname `db` (the literal in web.xml) to
       the local MySQL (via /etc/hosts, Docker DNS, or set
       DOMATAR_DB_URL).
    4. Place `/opt/domatar/provider.config.txt` and
       `/opt/domatar/default-apps-config.txt`, OR set the
       equivalent environment variables (PART 12).
    5. Drop `domatar.war` into Tomcat's `webapps/`.

Step 4: trigger provider install once.
    http://<domain>/domatar/Setup
This calls `PlatformSetupServlet.doSetup`, which runs
`DomatarProviderInstall` and then iterates the loaded apps. The
endpoint short-circuits once provider install has succeeded.

Step 5: log in.
    http://<domain>/domatar/domatar/login.html
Sign in as `<prvId>@<prvId>` with the configured admin password,
land on the provider's Desktop, navigate to the Domatar app to
inspect Hosts / Accounts / Classes / App Catalog.

Step 6: register / update the directory row for this server.
Until `MoveHst` is a first-class operation (PART 4 direction),
the directory's `hst` table is edited via SQL or via
(domatar, hosts).RegisterHst on a server that already trusts this
one:

    UPDATE hst SET Domain='my.example.com:8080', PrvId='self-or-other'
        WHERE HstId='my-host-id';

Resetting the local simulation:
    docker compose down -v       # wipes db1_data and db2_data
    mvn package
    docker compose up -d

## PART 16 - TOWARD DISTRIBUTED INTEROPERABILITY

The present packaging — one Tomcat JVM hosting every app, isolated
by ClassLoader (the model this document calls "Option D") — is
deliberately positioned for a further split into per-app processes
("Option C") without requiring any app code to change.

16.1  Option C — per-app standalone JVMs

  Each app becomes its own process (embedded Jetty or Tomcat-embed),
  with its own port, supervised independently. The directory's `hst`
  table maps each app's hstId to the new domain. The dispatcher does
  not change:
    - Same hstId → same JVM → sendLocal (same as today).
    - Different hstId → HTTP to the resolved domain (same as today;
      the domain just happens to be `quippin.example.com:8081`
      instead of `prv1:8080`).
  ClassLoader-per-JAR and the AppLoader / manifest model already
  isolate apps cleanly; the remaining work is the standalone host
  process. Outline:
    1. New `domatar-host` module producing an executable JAR with
       an embedded servlet container.
    2. `main()` reads `DOMATAR_HOST_APPS_DIR` (or a single
       `DOMATAR_HOST_APP`) and loads JARs the same way AppLoader
       does today.
    3. Registers the same `AppAssetServlet` + Wui servlets + Msg /
       Setup servlets at the configured context path (default "/").
    4. Operators deploy one container per app and update the `hst`
       table; no app source changes.
  Apps require ZERO source changes between Option D and Option C.

16.2  Cross-provider application install

  When the user's Desktop runs on prv1 but the user installed an app
  whose data lives on prv2, prv1's Desktop must still render the
  app's icon. Icons are loaded from the asset origin that has the JAR
  (absolute IconPath / AssetOrigin URLs); see [Icons](platform/Icons.md) PART 7.
  Reference distribution may still ship every JAR everywhere as a
  convenience for relative same-origin URLs. The host record decides who
  runs the handlers; icon display does not require the JAR on the
  page's provider.

  Direction: the AppStore's `InstallApp`, when the target provider
  differs from the user's home provider, may fetch the JAR bytes
  from the target provider via a new `(domatar, catalog) GetAppArtifact`
  operation and write it into the home provider's `WEB-INF/apps/` —
  for installing/running the app locally, NOT for icon display.
  Pulling apps on demand removes the "ship everything everywhere"
  constraint for handlers and local assets.

16.3  Account replication and migration

  An account lives on whichever provider issued or imported it.
  Direction ([Login protocol](apps/Login-Protocol.md) PART 14):
    - Account replication: a user signals "also keep this account on
      provider X"; provider X stores a copy of the account and the
      issuing app's central host trusts both replicas.
    - Account migration: an account moves from prv1 to prv2; the
      issuing app's central host updates its routing.

16.4  Security

  Inter-provider dispatch uses TLS 1.3 when configured
  (Q3, [Security](platform/Security.md) PART 9):

    - Scheme is {@code DOMATAR_WIRE_SCHEME} (default {@code "https"}).
      {@code HttpClient.sendHttp} uses the configured scheme and — when
      {@code "https"} — applies a custom {@link TlsConfig} SSL context
      built from the configured trust store and/or key store.

    - Mutual TLS (mTLS) is enforced for {@code UpdateHst} directory writes
      when {@code DOMATAR_MTLS_REQUIRED=true} (the default in https mode).
      The directory application verifies that the TLS client certificate
      presented by the caller matches the stored provider operational key.

    - Cert provisioning is documented in {@code provider.config.txt}:
      trust-store, key-store, and the OpenSSL commands to generate a
      self-signed P-256 client certificate.  Dev mode: set
      {@code WireScheme=http} to bypass TLS for local simulation.

  Remaining security directions:

    - Per-app secret material out of environment variables and into a vault.
    - Threshold / multiple directory-root signers ([Security](platform/Security.md) PART 14).
    - Device-held ownership keys, revocation, message-level encryption
      ([Security](platform/Security.md) PART 15).

16.5  The class layer

  Direction ([Class](platform/Class.md) PART 9):
    - `GetCls` message on (domatar, cls) for direct schema queries.
    - Type references by class name in the type grammar.
    - An `ImplRef` field in the descriptor that names the Java class
      or remote endpoint implementing the class, so the runtime can
      bind handlers from the descriptor instead of from a manifest.
      The manifest then becomes one possible source of the binding,
      not the only one.

16.6  Hot deployment

  Direction: hot-reload of an individual app JAR (URLClassLoader
  shutdown, re-open) without restarting the platform WAR. Today
  the cycle is `docker compose restart`.

## PART 17 - ANATOMY OF A DOMATAR APPLICATION (THE JAVA IMPLEMENTATION)

The Java anatomy of an application — manifest, hosts, handlers, WUI, install SPI, and the LLM-native facade — lives in [Writing Apps](apps/Writing-Apps.md). Quippin remains a product example in [Quippin](apps/Quippin.md); do not treat that spec as the app-authoring guide.


## PART 18 - OPEN WORK (TODO ROLL-UP)

For convenience, the directional items raised throughout this
document, in one list. Items are grouped by the layer they affect.

Identity / accounts
  * Account replication and migration across providers, so a user
    can keep the same account on more than one host without
    depending on any one of them (PART 2 / [Login protocol](apps/Login-Protocol.md) PART 14).
  * Multiple administrator accounts per provider ([Platform App](platform/Platform-App.md)
    PART 12 T6).

Hosts / directory
  * First-class RegisterHst and MoveHst operations on the directory
    object (PART 4 / [Platform App](platform/Platform-App.md) T1).
  * Server-side emission of "Hst moved" replies; client-side retry
    already exists.
  * Make `hst.hsts` itself a real object instead of a static DomId
    ([Platform App](platform/Platform-App.md) T2).
  * Split the simulation seed so non-directory stores start with an
    empty host cache, exercising the miss-then-fetch path.

Class / service layer
  * Done: the interface / implementation split — immutable service
    objects (domatar, srv) with GetSrv, member-less class objects
    that Implement services, GetCls returning the resolved
    descriptor, and the ClsMap resolution cache (PART 8,
    [Service](platform/Service.md)).
  * Network-wide reverse index of the implementers of a service
    ([Service](platform/Service.md) PART 12).
  * `ImplRef` binding carried in the resolved descriptor so the
    runtime binds handlers from descriptors rather than from app
    manifests, converging ImplMap and ClsMap ([Service](platform/Service.md)
    PART 17).
  * Type references by service name in the type grammar
    ([Class](platform/Class.md) PART 4 / [Service](platform/Service.md) PART 17).
  * Enforcement of service immutability once a publishing mechanism
    exists ([Service](platform/Service.md) PART 16).

Packaging / Option C
  * `domatar-host` module producing a standalone executable JAR
    with embedded servlet container (PART 16.1).
  * Per-host context path stored in the directory, used by
    HttpClient when a peer runs at a non-default mount point.
  * Hot reload of individual app JARs (PART 16.6).

App lifecycle
  * Cross-provider InstallApp: fetch the app JAR from the target
    provider on demand (PART 16.2).
  * Per-provider catalog admin UI (enable / disable / version
    apps without filesystem manipulation).
  * Retire `com.domatar.servlet.LoginMsg` (legacy /LoginMsg
    endpoint; no live caller).

Authorization
  * Richer dynamic policies (owner-match, follower-status,
    ban-list consultation, etc.) declared via class descriptors
    rather than only in Java (PART 6.3 / [Class](platform/Class.md)).
  * Defend per-instance handlers against envelope-class abuse
    (PART 6.2).

Persistence / migration
  * Per-host partitioning of the object / account / link seed; today
    every provider's MySQL receives the same dump for convenience.
  * Object-level migration tooling (export / import of one
    host's objects, accounts, and links plus per-host files), to
    support MoveHst.
  * Per-host data root at `/var/domatar/hosts/<hstId>/`, plumbed
    through Context, so handler-written files do not live under
    the WAR directory.

Security
  * TLS on every inter-provider call.
  * Signing of directory records.
  * Authentication of UpdateHst writes.
  * Per-app secret material out of environment variables and
    into a vault.

# END OF SPEC
