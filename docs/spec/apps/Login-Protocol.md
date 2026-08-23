# SPEC: LOGIN - federated identity for Domatar

## PART 1 — PURPOSE

This document specifies how Domatar identifies users, how a user signs
up, signs in, and stays signed in across the whole network.

The model is federated:

  - A user has ONE global account, identified by an opaque actId.
  - A user can sign in to that one account through ANY app on ANY prv,
    using credentials local to that app. Signing in anywhere
    establishes a session that is honoured network-wide.
  - The authoritative password store for a given login lives on the
    central host of the app named in the login's usrId suffix.
  - The actId is internal infrastructure and is NEVER rendered in UI.

## PART 2 — COMMITMENTS

The architecture holds if every part of the system follows the same rules.
Five commitments:

  C1. actId is a 32-character self-certifying fingerprint
      derived from the account's Ed25519 genesis public key
      ([Identifiers](../platform/Identifiers.md) PART 4.1). It is globally unique
      without any cross-host coordination: cryptographic key generation
      ensures uniqueness. The actId has no '@' and encodes no provider
      or app information.

      The actId is minted server-side at sign-up time and returned to the
      client; the client never supplies it for a new account. For cross-app
      linking (PART 5), the client does supply an existing fingerprint
      actId, which the receiving host verifies via the wire context.

      The actId is treated as OPAQUE by the runtime: nothing renders it in
      UI (PART 10). Routing always uses the usrId suffix, never the actId.
      Decoupling actId and usrId is what lets a usrId rename (PART 3.2)
      leave the actId untouched.

  C2. usrId always has the shape "<localname>@<appId>". The "@<appId>"
      suffix is the routing key for verification: it tells every prv
      WHICH app's central host owns this login's password. The
      suffix is therefore immutable for a given account; only the
      <localname> portion is renameable.

  C3. The account for usrId "<localname>@<appId>" lives on the
      central host of <appId>. Every prv that wants to verify or
      mutate that account dispatches a Domatar message to that
      central host. Per-prv local account copies disappear from the
      authentication path; they remain only as caches (PART 7).

  C4. Cross-app linking ("I want to add a Spreadsheet login to my
      existing Quippin actId") is implemented as an AddAct message
      from a verified Domatar session to the new app's central host,
      carrying the existing actId. Trust extension between app
      central hosts is mediated by the same wire-context verification
      that prvs already use.

  C5. World-wide sessions are implemented as TRUE single sign-on via
      an iframe loaded from the verifying app's central host. That
      iframe holds the SSO cookie on the central host's own domain;
      every prv the user lands on reads the SSO ticket via
      postMessage from the iframe and mints its own per-prv session
      from it. Each app sets its own re-verification policy at the
      iframe layer (PART 7).

Sharp edges, all addressed below:

  - First sign-up has no pre-existing actId; the central host of the
    app the user picked GENERATES the actId (PART 4). Generation is
    a per-host local decision under C1.
  - Cross-app linking demands proof-of-actId. The current wire context
    already carries a verified actId after the trust boundary stamps
    it; AddAct on the new app's central host accepts that as the
    proof (PART 5).
  - The verifying app's central host is a hard dependency: if Quippin's
    central host is down, no Quippin-rooted login works anywhere.
    This is the correct behaviour for a federated identity model and
    is mitigated naturally by the multi-app linking story (PART 5):
    a user with two linked logins (e.g. dave@quippin AND
    david@spreadsheet) has TWO independent verifiers - either one
    can establish their actId on any prv. As long as ONE of the
    user's linked apps is up, they can sign in. Apps that the user
    treats as critical can be paired with a backup app login for
    exactly this reason. Replication of any single central host is
    a separate availability concern (PART 14 TODO).
  - Cross-app actId forgery: a rogue or compromised app's central
    host can lie about which actId one of its usrIds belongs to,
    because v1's "linking" rule is enforced only by code paths
    INSIDE that central host. Live-verify (PART 8) does not close
    this gap because the receiver routes verification through the
    same liar. Critically, the targeted user does NOT need to have
    ever linked the rogue app: any registered appId in the
    directory can mount the attack against any actId, since actIds
    are not secret and the receiver routes verification purely on
    parseAppId(wireUsrId). v1 has only an operational mitigation
    (a curated directory restricts which appIds can register); the
    proper protocol fix is asymmetric-key anchoring of actId. PART
    13 specifies the threat model, the proposed protocol changes,
    and the WebAuthn-based implementation lean. Deferred to v2.

Emergent consistency property. A useful free consequence of
"verify at every seam" (PART 9) is that there is no per-prv
zombie-session state. The central host is consulted on every
hop, so:

  - Logout at the central host takes effect EVERYWHERE on the
    very next request, on every prv, in every app the user was
    using. Nothing is "still logged in" anywhere.
  - When a central host goes down, every prv begins failing
    verification uniformly for any usrId rooted at that app.
    There is no half-state where the user is "still signed in
    on prv1 but locked out on prv2" - the failure mode is
    uniform across the network.
  - When a session token is revoked (token theft, password
    rotation, admin action), the same instant-everywhere
    property holds.

This is a v1 design choice (no per-prv session cache; PART 7.3),
not a property of federation in general. Adding a cache later
trades a TTL-sized staleness window for performance; the
spec recommends NOT taking that trade until per-seam latency
is shown to actually matter.

## PART 3 — IDENTITY MODEL

3.1 actId

    The globally-unique, immutable identifier for an account, treated
    as opaque by everything outside the minting host.

    Format: a 32-character self-certifying fingerprint of the genesis
    public key ([Identifiers](../platform/Identifiers.md) PART 4.1). It has no '@' and encodes
    no provider or app.

    Properties:
      - Generated exactly once, at FIRST sign-up, by the app's
        central host (PART 4).
      - Treated as OPAQUE. Routing looks at "@<appId>" off the usrId,
        never off the actId.
      - Carried in the wire Context.actId after a successful
        verification.
      - NEVER shown in UI. Pages, URLs, side menus, and admin tools
        identify users by usrId or usrName.
      - Database FK from any per-app/per-prv obj or session row: the
        actId is the join key that lets us see "all this user's stuff"
        across hosts.

3.2 usrId

    The user's public-facing handle within one app.

    Format: "<localname>@<appId>", where <appId> identifies the app
    whose central host stores this login's password row. The <appId>
    suffix is part of the identity contract: it tells routing where
    to dispatch verification, sign-up, password change, etc.

    Properties:
      - Visible in UI everywhere a user is referred to.
      - Mutable in <localname> only. Renaming "dave@quippin" to
        "davesmith@quippin" is allowed and does NOT touch the actId.
      - The "@<appId>" suffix is immutable. Changing it would mean
        the same login row was now answerable to a different central
        host - effectively a different login.
      - Many usrIds may share one actId (one per app the user has
        linked). One usrId may not span apps.
      - usrId is unique within the scope of its <appId> central host;
        two users on the Quippin central host cannot both be
        "dave@quippin".

3.3 usrName

    Decorative display name. Per-usrId, per-app. Mutable, not used for
    identity. Identical to today's semantics.

3.4 The account (canonical, on each app's central host)

    Stored on the central host of <appId>. This realisation uses
    ActDb / the `act` table, with these field semantics:

      actId         fingerprint                (PART 3.1)
      usrId         "<localname>@<appId>"      (PART 3.2)
      usrName       display name               (PART 3.3)
      hashedPwd     PBKDF2/scrypt/argon2 hash of the per-app password
      salt          per-account
      ...

    Cross-app linked logins (PART 5) share one actId across accounts.
    An account on the Spreadsheet central host that links an existing
    account holds that fingerprint actId alongside
    usrId="david@spreadsheet". The usrId's suffix tells routing
    which central host owns this login's password.

    The account is OWNED by the <appId> central host. No prv stores
    its own copy; per-prv session caches (PART 7) reference the actId
    but never the password.

3.5 Identity invariants (one place to find them)

    INV-1  actId is treated as opaque by everything except the
           central host that minted it; it is never rendered in UI.
    INV-2  usrId rename only changes <localname>; the @<appId> suffix
           is immutable.
    INV-3  An actId may be referenced from multiple accounts on
           multiple app central hosts, but each (appId, usrId) pair
           is unique.
    INV-4  A login row's verifier is unambiguously the central host
           of the appId in its usrId suffix.
    INV-5  The actId is generated exactly once. It does not "promote"
           or "merge" - if the user has two sign-ups that they later
           realise are the same person, that is a manual reconciliation
           outside this spec.

## PART 4 — FIRST SIGN-UP (initial account)

The first sign-up is the one moment in the system where a brand new
actId comes into existence.

Flow:

  1. User visits any prv's signup form, e.g.
     http://prv1.local:8080/quippin/signup
     and picks the app they want as the root of their identity. (For
     v1 there is exactly one app, Quippin, so this is implicit.)

  2. The user enters:
       - localname (e.g. "dave")
       - usrName   (display name)
       - password  (will be sent over TLS in production; cleartext on
                    the local sim)

  3. The prv's signup servlet builds a Domatar AddAct message and
     dispatches it to (<appId>, "act", "act@act", "actManager"). The
     dst.hstId is the appId string — the central host for the app
     shares its name ([Domatar](../Domatar.md) PART 4).

  4. The <appId> central host's ActManagerImpl.addAct:
       a. Validates uniqueness of "<localname>@<appId>" among its
          accounts (within the scope of THIS central host).
       b. Mints a fingerprint actId from a new genesis key
          ([Identifiers](../platform/Identifiers.md) PART 4 / PART 9).
          Uniqueness is cryptographic (PART 3.1 C1); no global
          registry.
       c. Persists the account: (actId, usrId="<localname>@<appId>",
          usrName, hashedPwd, salt, genesis/ownership binding, ...).
       d. Issues a session token (PART 7) and replies with
          { LoggedIn: "True", ActId, UsrId, UsrName, Token }.

  5. The signup prv sets the browser session cookies (token, usrId)
     and redirects to /<context>/desktop ([Desktop](Desktop.md) PART 7).

Where is the signup UI hosted? Anywhere. Any prv's webapp can serve a
signup form; the form's submit handler fires the AddAct message at
the chosen app's central host. The signup UI is just a thin client
over the central host's AddAct primitive - which means any prv can
sign people up against any app, as long as it can reach the app's
central host.

## PART 5 — ADDITIONAL APP LINKING

A user already authenticated as actId X wants to add a per-app login
to their existing identity. Example: Dave has actId X, signed up via
Quippin (usrId dave@quippin). Now he wants to also be able to sign in
as david@spreadsheet, with a Spreadsheet-local password.

Flow:

  1. Dave is signed in. His current prv has stamped his Context with
     actId=X, usrId=dave@quippin, verified=true ([Domatar](../Domatar.md) PART 9).

  2. Dave opens "Add app login" UI. This surface lives in the Login
     app (see [Login](Login.md) PART 8.3) - a Domatar app whose only
     job is identity management. The form collects:
       - appId       (which app to link to; e.g. "spreadsheet")
       - localname   (the new local handle)
       - usrName     (display name in that app, may differ from
                      Dave's existing usrName)
       - password    (new, app-local)

  3. The browser POSTs to a Wui servlet, which dispatches AddAct to
     ("spreadsheet", "act", "act@act", "actManager") with these attrs:

       ActId     = X                        (from Context, not body)
       UsrId     = "<localname>@spreadsheet"
       UsrName
       Pwd

     CRITICAL: the ActId is sourced from the verified wire Context,
     not from any user-submitted form field. The Wui servlet sets it
     after the trust boundary has stamped Context.actId. A client
     forging an ActId in the form is ignored.

  4. The spreadsheet central host's ActManagerImpl.addAct:
       a. Confirms the wire Context is verified (Auth.isVerified) -
          we must NOT permit unauthenticated AddAct calls that claim
          an existing ActId, that would be impersonation.
       b. Confirms <localname>@spreadsheet is not yet taken on this
          central host.
       c. Persists the account, REUSING the supplied actId X. No
          new actId generation in this branch.
       d. Replies success.

  5. From now on, Dave can sign in either as dave@quippin (with his
     Quippin password) or david@spreadsheet (with his Spreadsheet
     password); both yield Context.actId = X.

Trust extension between app central hosts. This is the part that
deserves attention. The spreadsheet central host is being asked to
create a row that claims a foreign actId (X, originally minted by
Quippin). The only check the spreadsheet host runs is:
"is the caller's wire Context verified?". So the security comes from:

  - The caller went through a trust boundary on SOME prv (which ran
    LoginRemote.verifyLogin and stamped verified=true with actId=X).
  - PART 6 below makes that LoginRemote.verifyLogin route to the
    appropriate app central host based on usrId suffix. So any actId
    that ends up in a verified Context was attested by SOME app
    central host as the legitimate identity of this caller.
  - The spreadsheet host trusts that attestation transitively. It
    does NOT independently re-verify the actId, because there is no
    central registry of actIds outside the app hosts themselves.

That trust assumption is acceptable as long as the wire Context is
authenticated end-to-end (PART 9). It is the same trust assumption
the existing /Msg endpoint already makes for cross-prv messages.

## PART 6 — SIGN-IN

Flow:

  1. User on any prv visits /<context>/login. The page loads the
     current login form (login.html / remoteLogin.html iframe).

  2. User enters usrId (e.g. dave@quippin) and password.

  3. The form POSTs to the local prv's login endpoint.

  4. The local prv parses "@<appId>" out of the submitted usrId.
     (For ill-formed input that contains no '@', return "Bad usrId".)

  5. The prv builds a VerifyLogin message and dispatches it to:

       dstDomId = DomId(<appId>, "act", "act@act", "actManager")

     The wire body carries (UsrId, Pwd, Ip).

  6. The <appId> central host's ActManagerImpl.verifyLogin:
       a. Looks up the account by usrId.
       b. Verifies the password against the stored hash + salt.
       c. On success: issues a session token, persists the
          session (token, actId, ip, expiry),
          replies with { LoggedIn: "True", ActId, UsrId, UsrName,
          Token }.
       d. On failure: replies with { LoggedIn: "False" } (no Error -
          this is a normal "no" answer; see [Domatar](../Domatar.md) PART 9 contract).

  7. The local prv receives the reply and:
       a. Sets the browser cookies token / usrId.
       b. Optionally caches the session locally for fast re-verify
          (PART 7).
       c. Redirects the browser to /<context>/desktop.

The browser never speaks to the <appId> central host directly. It
talks only to the local prv; the prv talks to the central host on its
behalf via the Domatar message bus.

Where is sign-in's password actually checked? At the <appId> central
host, NOT at the prv the user is connecting to. This is the federation
point. Every prv routes the same usrId to the same central host, so
the user is signed in identically regardless of which prv they hit.

## PART 7 — WORLD-WIDE SESSION

After sign-in on prv1, the browser holds (token, usrId) cookies on
prv1's domain. The user navigates to prv2. Two layers of "world-wide":

7.1 Cross-prv message verification (the easy half)

When a request handled by prv1 needs to fetch data from prv2, the
existing /Msg cross-prv flow already runs verifyLogin on prv2
([Domatar](../Domatar.md) PART 9). The change here is what verifyLogin DOES on prv2:

  - Today: prv2's ActManagerImpl checks its own local account store.
  - Going forward: prv2 routes verifyLogin by usrId suffix - i.e.
    sends the verification to the <appId> central host, exactly as
    in PART 6. prv2 is no longer authoritative; the central host is.

This is purely a server-side change. The browser is not involved.

7.2 Cross-prv browser sessions (the harder half)

Cookies are scoped to a single domain. If the user signs in on
prv1.local and then types prv2.local into the address bar, prv2.local
has no cookie for them. The target architecture is true SSO via an
iframe loaded from the verifying app's central host.

Concretely:

  a. login.html on prv2.local loads an <iframe> pointing at
     http://<appId>.domatar.com/remoteLogin.html, where <appId> is
     either inferred from the user's earlier choice (cookie hint on
     prv2.local: "you last signed in via Quippin") or, on a clean
     slate, picked by the user from a list of installed apps.
  b. If the iframe's domain has a valid SSO cookie from a prior
     sign-in (e.g. Dave signed in via prv1 yesterday, so the iframe's
     cookie jar still holds an SSO ticket from <appId>.domatar.com),
     the iframe runs the app's chosen re-verification policy
     (PART 7.2.1 below) and postMessages back the token + usrId.
  c. The parent page (prv2.local) accepts the postMessage (origin
     and shape gated as today), mints a per-prv session cookie on
     prv2.local, and proceeds to /<context>/desktop.

The browser ends up with two cookies:

  - On <appId>.domatar.com:    the SSO ticket. Long-lived. Issued
                               once at first sign-in, refreshed by
                               the central host's iframe.
  - On prv2.local (and any
    other prv the user visits): a per-prv session cookie minted
                               from the SSO ticket. Short-lived,
                               cheap to mint, can be silently
                               refreshed by re-running the iframe
                               flow.

Federation is therefore: ONE persistent identity (the SSO ticket on
the central host's domain) plus N short-lived per-prv sessions
derived from it. The central host is the single source of truth for
session validity; prvs are caches.

7.2.1 Per-app re-verification policy

When the iframe finds a valid SSO cookie on the central host's
domain, what should it do? Different apps have different threat
models, so the spec leaves this as a per-app choice.

  - SEAMLESS (default). The iframe immediately postMessages back the
    token + usrId, no prompt, no clicks. The user lands on the new
    prv already signed in. Suitable for the vast majority of apps;
    matches the user expectation that "I am signed in to Domatar,
    not to one prv."

  - CONFIRM. The iframe shows a single button - "Continue as
    dave@quippin" - then postMessages on click. No password.
    Suitable for apps that want a "you are visibly signing in here"
    moment for audit/UX reasons but do not require a fresh
    credential proof.

  - REVERIFY. The iframe shows the full login form and demands the
    password again, even though the SSO ticket would have sufficed.
    Suitable for high-paranoia apps (a Domatar banking app, an
    identity-management app, a password-manager app). Each prv
    visit costs the user a re-entry; in exchange, no SSO-ticket
    theft can be replayed silently.

The policy is set per app, in the central host's auth UI. The prv
does not choose - it accepts whatever the central host's iframe
hands back. This is a property of the central host, not of the
network: the same user moving from prv1 to prv2 on a SEAMLESS app
gets a silent sign-in; the same user moving on a REVERIFY app
re-enters the password each time.

The fallback path. If the SSO cookie is missing or expired, the
iframe always shows the full login form regardless of policy. There
is no "less paranoid than the default" mode - SEAMLESS is the
floor, CONFIRM and REVERIFY add friction on top.

7.2.2 Multi-app fallback (availability mitigation)

If <appId>.domatar.com is unreachable, the iframe will fail to load
and the user will not be able to sign in via that app. PART 2 already
notes that this is the correct behaviour for federation, and that
the natural mitigation is multi-app linking (PART 5): a user who
has dave@quippin AND david@spreadsheet linked can fall back to
spreadsheet.domatar.com when quippin.domatar.com is down.

The login form on the prv must therefore let the user pick WHICH
linked app to sign in via. v1 surfaces this as a usrId text field
the user types ("david@spreadsheet" picks Spreadsheet's central
host, "dave@quippin" picks Quippin's). v2 may surface this as an
explicit "sign in via [Quippin|Spreadsheet|...]" selector once
multiple apps actually exist.

7.3 Session caches (deliberately omitted in v1)

Every credential-bearing request from a prv to a central host
costs a network hop. The natural optimisation is a per-prv
"verified-session" cache keyed by token+usrId, populated on
first successful VerifyLogin and invalidated on TTL.

The spec deliberately does NOT recommend such a cache for v1.
Verifying live at every seam (PART 9) is what gives the system
its consistency properties (PART 2 emergent property): logout
is instant everywhere; an outage of an app's central host is
uniformly expressed across all prvs; a revoked token cannot
be replayed against a stale cache. A per-prv cache trades all
three of those for some saved network hops.

When (and only when) per-seam latency is measured to actually
matter, a cache can be added with the following shape:

  - Keyed by (token, usrId).
  - Populated by the first successful VerifyLogin response from
    the central host.
  - Short TTL (well under a minute) so the staleness window for
    revocations / outages is bounded.
  - Optional central-host-driven invalidation broadcast on
    Logout (PART 8 / PART 14 TODO) so logout still feels
    instant across the network.
  - Only ever a perf optimisation, never a trust extension.

Until then: every seam is a live round-trip to the central
host. This is the right v1 default - it is simpler, more
consistent, and more debuggable than caching, and the
performance cost is bounded by the speed at which the central
host can answer VerifyLogin (a single indexed lookup).

## PART 8 — LOGOUT

Logout is initiated from the prv the user is currently on:

  1. The prv sends Logout to the <appId> central host, which
     deletes the session row from its sessions table. After this
     instant the central host will answer "not verified" to every
     subsequent VerifyLogin for this token.

  2. The central host's SSO cookie (PART 7.2) is also cleared, so
     subsequent iframe checks on any prv fall back to the full
     login form (PART 7.2.1 fallback path) instead of silently
     reissuing a token.

  3. The originating prv clears its own browser cookies (token,
     usrId).

That is the entire logout protocol. There is no "log out
everywhere" sub-procedure to implement, because under v1's
no-cache policy (PART 7.3) every other prv re-verifies live at
its next seam (PART 9), and the central host now says "no" to
all of them. So:

  - Other prvs the user had previously visited remain "logged
    in" only until their NEXT request - which is a hop to the
    central host, which fails verification, which kicks them
    back to login. On the order of one HTTP round-trip after
    Logout, the user is signed out network-wide in every app
    they had open.

  - Cross-prv background traffic (e.g. a news-feed AJAX poll
    on prv2 issued by a page Dave still has open) starts
    failing on the next seam in the same way. The browser's
    JS sees "not authorized" and redirects to login.

This is the same property a central-host outage exhibits: when
the central host is unreachable for ANY reason - logout,
deliberate revocation, password rotation, server crash - every
seam in the network stops accepting that user's tokens. Logout
is just the special case where the unreachability is permanent
and intentional, scoped to one user's sessions.

Logout-vs-outage distinction. Both produce "the user is no
longer logged in anywhere", but for different reasons:

  - LOGOUT  : central host is up, says "this token is gone".
              The user is logged out of EVERY app rooted at
              their actId via this central host.
  - OUTAGE  : central host is down (HTTP timeout / TCP refused).
              The user is effectively logged out of every app
              whose verification path goes through that central
              host - which is, transitively, every app the user
              was using under that signed-in identity. If the
              user has linked logins on a different app
              (PART 5 / PART 7.2.2) whose central host is up,
              they can sign back in through that app
              immediately, and access resumes.

## PART 9 — TRUST BOUNDARIES

[Domatar](../Domatar.md) PART 9 defines two trust boundaries, both calling
LoginRemote.verifyLogin:

  - DomatarServlet.dispatch (browser-facing) - per request from a
    browser, before invoking a handler.
  - Msg.doAction (cross-prv) - per request from another prv, before
    invoking the destination handler.

LoginRemote.verifyLogin routes by usrId suffix:

    appId = parseAppId(usrId);    // chars after the last '@'
    DomId(appId, "act", "act@act", "actManager")

The rest of the contract ([Domatar](../Domatar.md) PART 9: LoggedIn=False is
a valid answer, isSuccess()==false is transport error, defence-in-depth
on actId) is unchanged.

A usrId suffix may be an appId or a provider id (e.g. `prv1@prv1`,
or `dave@quippin` where quippin is both an appId and a prvId in the
sim). Because providers and apps share the host namespace
([Domatar](../Domatar.md) PART 5), a usrId of shape "@<X>" is answerable to
a host named X.

## PART 10 — UI DISPLAY RULES

  - NEVER render actId. Not in body text, not in URLs, not in HTML
    data-* attributes, not in tooltips.
  - Render usrId where the user expects to see "themselves" (login
    bars, settings pages, side menus, follow lists).
  - Render usrName for display-context-only labels (post bylines,
    avatar names).
  - Routing keys in URLs may use usrId because usrId is public.
    The actId stays out of the wire representation that browsers see.

actId and usrId are different strings. Display and routing use
usrId; actId is never shown.

## PART 11 — IMPLEMENTATION SURFACE

11.1 actId generation

  ActManagerImpl mints a fingerprint actId from the genesis public
  key ([Identifiers](../platform/Identifiers.md) PART 4.1). usrId is `<localname>@<appId>`, where appId is the
  central host running addAct (DomatarConfig.getHstId() when that
  tomcat is acting as that app in the directory).

  Uniqueness: act.ActId is unique in that central host's table;
  fingerprint collision is negligible. No global registry.

11.2 LoginRemote.verifyLogin destination

  When usrId is non-null:

        String appId = parseAppId(usrId);
        DomId domId = new DomId(appId, "act", "act@act", "actManager");

  parseAppId returns the substring after the last '@' (or null
  if there is no '@', which causes verifyLogin to return null
  early, treated as "not verified"). LoginRemote.getAct uses the
  same routing.

11.3 Trust-boundary callers

  DomatarServlet and Msg call LoginRemote.verifyLogin and treat
  its return value as the verdict.

11.4 ActWui

  src/main/java/com/domatar/act/ActWui.java routes federated identity
  ops to the central host of the appId in the usrId. Per-user hosts
  use `DomId.subHstId(appId, actId)` (`HOST_SEP` '-'), e.g.
  `quippin-<fingerprint>`.

11.5 Per-app central host in the directory

  Each app's central host is a directory row (`quippin`, `login`,
  `appstore`, …) plus per-user portable hosts `<appId>-<actId>`.
  PART 6 routing depends on the central host name being resolvable.
  In the local sim, tomcat1 wears several aliases (PART 12).

11.6 Login form (post-login redirect)

  login.html / remoteLogin.html send the browser to Desktop after
  sign-in, keeping the `/domatar` WAR context when present
  ([Desktop](Desktop.md) PART 7).

11.7 actId must not leak in the UI

  Web pages and Wui servlets render usrId / usrName, never actId
  (PART 10). A remaining actId in a URL or display is a bug.

11.8 Accounts

  Each account stores a fingerprint ActId and a usrId of shape
  `<localname>@<appId>`. They are never the same string.

11.9 Sessions

  VerifyLogin against the app's central-host account is the
  session check (Token column; match token + usrId + ip). A
  per-provider session cache is Direction (PART 7.3).

## PART 12 — LOCAL SIMULATION

The current docker-compose has tomcat1 (prv1, plus the directory
alias "domatar") and tomcat2 (prv2). PART 6 needs a host called
"quippin" to be reachable - the central host of the Quippin app.

Two options:

  (A) Multi-alias tomcat1. Already serves prv1 and domatar; add a
      "quippin" alias on the same Docker network. Plumbing:

        docker-compose.yml, tomcat1's networks block:

          domatar_net:
            aliases:
              - domatar
              - quippin

        directory seed (mySQL/dump-*.sql):

          INSERT INTO hst VALUES
            ('quippin', 'tomcat1:8080', 'prv1', 1, 0);

      The HttpClient self-dispatch shortcut ([Domatar](../Domatar.md) PART 8) makes
      this safe: when prv1 is itself the central host for Quippin,
      verifyLogin's getHst("quippin") returns Hst(prvId=prv1)
      matching DOMATAR_HSTID, sendLocal fires, no recursive HTTP.

  (B) Third tomcat. Adds a tomcat3 on the domatar_net with its own
      MySQL instance, DOMATAR_HSTID=quippin. More realistic but
      requires more compose plumbing and a new act DB.

For v1 take (A). In this realisation the central host's accounts
live in the same MySQL as prv1, under a different HstId.

## PART 13 — SHARP EDGE: CROSS-APP ACTID FORGERY (DEFERRED)

This section captures a real and unaddressed weakness in the v1
trust model. Nothing in v1 mitigates it; it is documented here so
v2 has a concrete starting point and so the threat is not
rediscovered as a surprise during deployment.

13.1 The threat

The v1 protocol's "linking" rule (PART 5) is:

  "An app's central host promises to write a row binding
   (usrId=<X>@<thatApp>, actId=<Y>) only if the wire Context that
   carried the AddAct was verified as <Y>."

That promise is enforced by a code path inside ActManagerImpl.addAct
(it calls Auth.isVerified(inMsg) and callerOwnsAct in link mode).
A rogue or compromised central host - just another Domatar server
controlled by an attacker - is free to skip that check and write
any (usrId, actId) pair it likes. Per-seam live verification
(PART 8 / PART 9) does NOT close this gap, because the receiver's
verification of "is this user really who they claim to be?" is
itself routed THROUGH the very central host whose honesty is the
question:

  Receiver gets request with usrId="mallory@evil", token T.
  -> LoginRemote.verifyLogin(usrId=mallory@evil, token=T)
     -> dispatches VerifyLogin to evil's central host.
     -> evil happily replies Act(actId=X, ...).
  -> Msg.verifyAndStamp constructs Context(actId=X,
     verified=true).
  -> Every per-user surface (FollowsImpl, LoginsImpl, future
     Desktop / app data) treats Mallory as Dave because all
     owner-match checks read ctx.actId == dst.actId.

The same shape applies to a previously-honest app that turns
malicious: every app a user has ever linked is inside that user's
trust boundary. There is no v1 "revoke an app's right to vouch
for me" mechanism, because the rogue host is itself the source of
truth for its own accounts.

13.2 Why per-seam verification is not enough

PART 8's instant-everywhere logout property gives a strong-feeling
guarantee, but its strength is "the central host's word about its
OWN usrIds is the most current word." It says nothing about a
central host's word about an actId that lives on a DIFFERENT
host's namespace. The link from "mallory@evil" to "dave@quippin"
is a claim by evil, and v1 has no mechanism to require that claim
be backed by anything. Live-verify just re-asks the same liar.

13.3 The fix: pubkey-anchored actIds

The standard cryptographic answer is to bind every actId to a
keypair generated at root sign-up and registered with the actId's
HOME central host (the host named by parseAppId(actId), e.g.
"quippin" for "dave@quippin"). Every later linkage carries a
signature by the root privkey over the new (usrId, actId) pair.
Receivers verify that signature against the registered pubkey
before trusting any cross-app actId claim.

Sketch of the augmented flows. Canonical form C(...) is a
deterministic encoding (e.g. JCS-style canonical JSON over a
fixed key list, or RFC 8785).

  Sign-up (C0: generate the root)
  ------------------------------
    device:  actKey = generateKeyPair()       // privkey stays on device
             rootSig = actKey.sign(C(actId, usrId))
    client:  AddAct(actId, usrId, pubkey=actKey.pub, rootSig)
    home:    central host (the "@<appId>" of actId) verifies
             rootSig against pubkey, persists (actId, pubkey) as
             a write-once trust anchor.

  Link another app (C1: prove ownership)
  -------------------------------------
    device:  linkSig = actKey.sign(C(linkedUsrId, actId))
    client:  AddAct(linkedUsrId, actId=existingActId, linkSig)
                    -> dispatched to <newApp>'s central host
    newApp:  verifies linkSig against pubkey, where pubkey is
             fetched from the home central host (actId's "@<appId>")
             via a new GetActPubKey op. Stores linkSig on the
             new account.

  Receive a request from a linked app (C2: receiver verifies)
  ----------------------------------------------------------
    receiver: VerifyLogin(usrId=linkedUsrId, ...) dispatched to
              newApp's central host as today.
    newApp:   replies Act(actId=existingActId, linkSig, ...) -
              the linkSig stored at link time travels in the
              VerifyLogin response.
    receiver: pubkey = GetActPubKey(actId)     // dispatched to
                                                 parseAppId(actId)
              ok = verify(pubkey, linkSig, C(linkedUsrId, actId))
              if !ok: refuse the actId claim. Either drop to
              an "anonymous" Context.actId=null or stamp a
              degraded actId of shape <linkedUsrId>@<newApp>
              (treat it as a brand-new local identity, not the
              claimed cross-app one).

Properties this gives:

  P1. A rogue evil cannot forge actId=X (Dave's fingerprint). The privkey
      lives only on Dave's device(s); it was never on the wire.
      Without it, no linkSig validates.

  P2. A previously-honest app that turns evil cannot escalate.
      It can only re-present linkSig values the user already
      signed at link time. Worst case it keeps impersonating the
      specific (linkedUsrId, actId) bindings the user explicitly
      authorized - it cannot mint new ones.

  P3. Receivers no longer trust central hosts about identity.
      They still trust each central host to authenticate its own
      usrIds (i.e. "is this the right password / token?"), which
      is fine - that is a strictly local fact.

  P4. The home central host is a vouch point, not a forge point.
      Compromise of the home host leaks identities (it knows the
      pubkeys, the actIds, and the link history) but does NOT let
      the attacker create new bindings, because the attacker still
      does not have the user's privkey.

Recovery / loss of device. Out of scope for v1 even when this
ships, but the natural shape is: the home central host accepts a
"key rotation" message signed by ANY of the user's currently
registered pubkeys (i.e. all linked devices co-sign the next-key
attestation), with a fall-back recovery procedure (e.g. backup
codes printed at sign-up, an admin path on the home host, or
multi-app-quorum recovery using PART 5 linkages as the device
set). This is the same shape Apple iCloud Keychain and
WebAuthn-with-multi-device-passkeys already solve.

13.4 Implementation lean

The crypto itself is one or two lines per operation:

  - Server (Java, JDK 15+): KeyPairGenerator.getInstance("Ed25519"),
    Signature.getInstance("Ed25519"). No external dependency.
    KeyFactory + X509EncodedKeySpec for pubkey serialization.
  - Browser device: window.crypto.subtle.generateKey({ name:"Ed25519" }),
    subtle.sign / subtle.verify.

The genuinely hard part is where the privkey lives on the client.
Three options, in increasing order of seriousness:

  (a) IndexedDB / localStorage. Trivial; one-time generate, store
      forever. Vulnerable to any XSS on a Domatar page - a single
      injection on any prv exfiltrates the key.
  (b) Password-derived (PBKDF2 / Argon2 from the root password).
      No client storage at all; key is reproducible from the
      password. Couples key rotation to password rotation, which
      conflicts with the per-app-password model (PART 3.2) - the
      "root" password is the one tied to the actId's home app, so
      changing it would re-issue the entire identity. Awkward.
  (c) WebAuthn / passkeys. Hardware-backed where available, OS
      keychain everywhere else, sync across the user's devices via
      iCloud Keychain / Google Password Manager / 1Password / etc.
      The browser literally will not release the privkey. Java
      side: webauthn4j (Apache 2.0) or yubico/webauthn-server-java
      (BSD-2). Browser side: navigator.credentials.create() at
      sign-up, navigator.credentials.get() to produce link
      assertions.

WebAuthn is essentially designed for this: each "credential" IS
an actId-bound keypair, an "assertion" is a signed challenge, and
"discoverable credentials" handle the multi-device case cleanly.
The Domatar protocol changes become:

  - AddAct (sign-up): include attestationObject + clientDataJSON
    from navigator.credentials.create().
  - AddAct (link): include an assertion produced by
    navigator.credentials.get() with the canonical (linkedUsrId,
    actId) bytes as the challenge. The home central host's pubkey
    set is the credential set that credentials.get() chooses from.
  - GetActPubKey: returns the credential public-key set for the
    actId.
  - VerifyLogin: returns the stored assertion alongside the
    existing Act fields.
  - Msg.verifyAndStamp: verifies the assertion before stamping
    Context.actId.

13.5 Why deferred from v1

The protocol-shape change is non-trivial:

  - AddAct grows two new attrs (pubkey/attestation at sign-up,
    linkSig/assertion at link).
  - VerifyLogin's response grows linkSig (so Msg.verifyAndStamp
    has something to check).
  - A new GetActPubKey op on every app's central host.
  - A new verify step in Msg.verifyAndStamp's trust boundary.
  - A device-loss / key-rotation recovery story (PART 13.3
    paragraph above).
  - A WebAuthn integration on every prv's web UI - including the
    prvs that today have no JS layer at all, just servlet HTML.

In return for all of that, v1 ships the fast path and v2 layers
the cryptographic anchor on top. PART 14 carries the cross-link
to this section.

It is worth being explicit about what v1 does NOT bound, because
an earlier draft of this section claimed mitigations that turn
out not to hold:

  - The attack does NOT require the targeted user to have linked
    the rogue app. Attacker only needs evil to be a registered
    appId in the directory; evil's account store is then free to
    contain a fabricated binding of any (usrId, actId) pair the
    attacker invents. The receiver routes verifyLogin to evil
    purely on parseAppId(wireUsrId), so the attacker just sets
    usrId=mallory@evil + token=<evil-issued> on their own browser
    and walks into the target's identity. ActIds are not secret -
    they show up in URLs, mentions, follow lists - so picking a
    target is trivial.

  - RemoveLogin ([Login](Login.md) PART 8.8) does not revoke
    this attack. The user cannot delete a row they do not know
    exists, and even if they could, evil's central host owns
    its own table and is not obliged to honour the delete.

  - PART 8's instant-everywhere logout has no leverage here.
    PART 8 invalidates tokens minted by the user's HOME central
    host. The attacker's tokens are minted by evil, which the
    user does not control.

What v1 actually bounds:

  - Operational, not cryptographic. A directory curated by the
    Domatar operator(s) limits which appIds can resolve in the
    first place. This is a deployment posture, not a protocol
    guarantee, and it evaporates the moment the directory is
    opened to outside operators (which is the natural steady
    state for a federation).
  - Password-bearing flows on the home host are unaffected.
    A direct Login at quippin's central host still requires
    Dave's quippin password, which evil does not have. Evil
    only impersonates on per-user surfaces whose authorization
    rule is ctx.actId == dst.actId (Follows, the Login app's
    own logins, future Desktop, any new app's per-user data).
    That is enough to be very bad, but it is not "evil can
    change Dave's quippin password."

## PART 14 — TODO

  - SSO iframe deployment. PART 7.2's true-SSO architecture needs
    the central host to actually be reachable on its own DNS name
    (e.g. "quippin.domatar.com"). The login.html / remoteLogin.html
    postMessage scaffolding is already shaped for this; what's
    missing is the central host's own auth UI and cookie domain.
    Until then, the local sim falls back to the iframe pointing at
    a same-origin URL, which collapses to
    "sign in once per prv" - acceptable for the local sim since
    there is no real cross-domain UX to test against.
  - Per-app re-verification policy enforcement. PART 7.2.1 specifies
    SEAMLESS / CONFIRM / REVERIFY as a per-app choice, but the
    iframe today has no policy field. Add a "ReverifyPolicy" attr
    on the app's central host's act manager and have the iframe
    branch on it.
  - Session caches + revocation broadcasts. Both are deferred
    together (PART 7.3, PART 8). Only relevant if and when per-seam
    latency is measured to be a real bottleneck. If a cache is
    introduced, the central host should broadcast invalidations on
    Logout so the cache does not undo the instant-everywhere
    property the v1 design relies on.
  - Replication of the central host (PART 2 sharp edge):
    multi-master account store for resilience. The user's natural
    mitigation is multi-app linking (PART 5 / PART 7.2.2): a backup
    login on a second app sidesteps any one central host being
    down. Replication is therefore a per-app availability decision,
    not a Domatar-platform requirement.
  - Per-app password policy / 2FA: hooks at ActManagerImpl.verifyLogin
    on each app's central host. Out of v1.
  - Pubkey-anchored actIds (PART 13). Closes cross-app actId
    forgery. Concrete shape: Ed25519 keypair generated at root
    sign-up, registered with the home central host, used to sign
    every cross-app linkage; receivers verify the signature
    against the home host's pubkey before stamping
    Context.actId on cross-app traffic. WebAuthn / passkeys is
    the recommended client-side key custody primitive.
  - The Login app ([Login](Login.md)) is the home of every UI
    described in this protocol: sign-up, sign-in, "Add app login"
    (PART 5), usrId/usrName rename, password change, "Remove
    login", and sign-out. PART 5's note about a Wui+form is fully
    fleshed out in [Login](Login.md) PART 8.3. The v1
    implementation lives at /quippin/signup, /quippin/account,
    and /quippin/AccountsWui (signup is public and goes through
    ActWui; the rest go through AccountsWui which inherits the
    DomatarServlet trust boundary).
  - Per-app central host extensions (UpdateAct, ChangePwd,
    DeleteAct, plus the matching login-<actId> directory side-
    effects): the new ActManagerImpl ops the Login app needs.
    Specified in [Login](Login.md) PART 7 / PART 6.2 and
    implemented in v1 alongside the Login app.
  - "Forgot password" / password change flows: per-app, on the
    app's central host, using a verified-Domatar-context for the
    change-password operation and an app-specific email/SMS dance
    for the forgot-password operation. Out of v1.
  - Auditing: every AddAct, VerifyLogin, and Logout on a central
    host should write a Logs row. (PART 9 trust boundaries are the
    right place to call Log.add.)

## Multiple logins (redundancy)

Membership — which providers host this account — lives on the platform
user substrate `domatar-<actId>-<prvId>`
([Platform App](../platform/Platform-App.md)). Login replicas may
dual-write peers. Shell chrome uses GetShells, not an intrinsic-tile
exemption.

This section specifies how a single Domatar account (one actId) is
replicated across MULTIPLE home providers so the user can sign in
even when one provider is unavailable, and so that no single provider is
a single point of failure for the account's identity.

It is the identity/redundancy half of a pair with [Desktop](Desktop.md)
content-sync, which consults membership to keep launcher tiles aligned.
AttachProvider ([Foreign Provider](../install/Foreign-Provider.md)) is the
login-home path; app hosting without a door is HostProvision.

It builds directly on, and does not replace, the existing identity and
security specifications:

  - [Login protocol](Login-Protocol.md)     (federated identity: actId vs usrId, per-app
                        central hosts, verify-at-every-seam, world-wide
                        session, multi-app linking).
  - [Login](Login.md)  (the Login app UI and its per-user login
                        directory login-<actId>).
  - [Security](../platform/Security.md)  (delegations on the message, origin
                        signatures, path hops, TLS).
  - [Identifiers](../platform/Identifiers.md)    (ownId, actId, usrId, genesis and
                        ownership keys, binding, rebind — cryptographic
                        eviction of a signing provider, PART 10.)

Membership is the account directory. Each login-home provider holds a
replica at login-<actId>-<prvId> with the app-login tile, membership
container, binding obj, and peer rows (PART 14).

## PART 1 - PURPOSE AND SCOPE

1.1  What this provides

  R1  REDUNDANCY, NOT FAILOVER. A user with an account replicated to
      several providers may, if one provider is unavailable, go to
      another provider and sign in there. This is a USER action, not an
      automatic reroute. There is no promotion, no election, no primary,
      and no automatic failover anywhere in this design.

  R2  DURABILITY. Because each attached provider holds a COMPLETE copy of
      the account (credentials, ownership key, membership directory, and -
      via [Desktop](Desktop.md) - the Desktop), the permanent
      loss of any one provider loses nothing: every other provider still
      has a full copy.

  R3  ONE IDENTITY. All replicas share ONE actId
      ([Identifiers](../platform/Identifiers.md) PART 4.1), under one current ownId
      anchored to that actId. Adding a provider never mints a new
      account; it attaches a new per-provider login to the existing actId.

1.2  Non-goals

  * Automatic failover / request rerouting (explicitly rejected; R1).
  * Strong (synchronous) consistency across providers. Eventual
    consistency is sufficient and is all that is offered (PART 10).
  * Cryptographic eviction is NOT done by this spec's removal flow, which
    is COOPERATIVE only (PART 11). Actual eviction of a dishonest signing
    provider is available, but as a REBIND defined in [Identifiers](../platform/Identifiers.md)
    PART 10, not here; between rebinds the accepted limitation is
    [Security](../platform/Security.md) T1.
  * Replication of per-app DATA (quippin-<actId>, money-<actId>, ...).
    Only the account's IDENTITY layer (credentials + ownership key +
    membership + binding) is replicated here; the launcher is replicated by
    [Desktop](Desktop.md). See PART 4.3 for the boundary.

1.3  Terminology (used consistently below)

  * actId       - the account identity, a fingerprint of the GENESIS
                  public key ([Identifiers](../platform/Identifiers.md) PART 4). Immutable,
                  provider-free, never in UI.
  * ownId       - the account's CURRENT operative key identity, a
                  fingerprint of the ownership public key. Rotatable; it,
                  not the actId, is what delegations chain to
                  ([Identifiers](../platform/Identifiers.md) PART 5). Not rendered in UI.
  * usrId       - a login handle "<localname>@<appId>" ([Login protocol](Login-Protocol.md)
                  PART 3.2). The "@<appId>" suffix routes verification to
                  that app's central host.
  * app-login   - one login: a (usrId, password) pair on some app
                  central host, all sharing the same actId. This is what
                  [Login protocol](Login-Protocol.md) PART 5 calls a "linked login".
  * PEER        - an app-login belonging to the account. The set of peers
                  is the MEMBERSHIP (PART 7). A user signs in THROUGH a
                  peer.
  * home prv    - a provider that holds a full replica of the account and
                  a valid delegation for its actId ([Security](../platform/Security.md)
                  PART 6). Also called a REPLICA below.
  * replica set - the set of DISTINCT home providers across the account's
                  peers. Peers map many-to-one onto providers (PART 3.2).

## PART 2 - RELATIONSHIP TO THE FEDERATED-IDENTITY MODEL

This spec is a thin, deliberate extension of three primitives that
already exist:

  (a) Multi-app linking ([Login protocol](Login-Protocol.md) PART 5). "Add another app login"
      already binds a new usrId to an existing actId via an AddAct in
      link mode. Attaching a provider (PART 8) is that same operation
      plus three additions: provision the ownership key to the new
      provider, issue it a delegation, and record it in the membership
      directory.

  (b) The account ownership key + delegations ([Security](../platform/Security.md) PART 5,
      6; [Identifiers](../platform/Identifiers.md) PART 5).
      "Adding a home provider" is ALREADY defined as issuing that
      provider a delegation ([Security](../platform/Security.md) PART 6.4) and realizes
      goal G4 ("multiple redundant home providers ... abandon any ...
      without loss of identity"). This spec gives that mechanism a
      user-facing flow and a durable, user-visible index.

  (c) The Login app's per-user login directory login-<actId>
      ([Login](Login.md) PART 3.2). That directory is generalized here
      into the replicated MEMBERSHIP directory login-<actId>-<prvId>
      (PART 6, PART 7).

Crucially, this spec does NOT reintroduce the published account record
that [Security](../platform/Security.md) PART 6.3 deliberately removed. The membership is
PRIVATE per-account state, replicated ONLY among the account's own
providers. No third-party verifier ever consults it: cross-provider
message verification still works statelessly from the message-carried
delegation ([Security](../platform/Security.md) PART 6.1, PART 7.3). The membership is a
convenience index for the USER and their own providers, not a directory
lookup for the network.

## PART 3 - THE MODEL: PEERS, PROVIDERS, AND REPLICAS

3.1  Two layers, one membership

  There are two kinds of redundancy and they are cleanly separated:

    LOGIN redundancy   (this spec) - the account's credentials and
                                     ownership key exist on several
                                     providers, so the user can sign in
                                     through any of them.
    DESKTOP sync       (companion) - the launcher content is kept
                                     identical across those providers.

  The Login app OWNS the membership: the authoritative answer to "which
  peers/providers does this account have?" The Desktop app does NOT track
  providers; it ASKS the Login app ([Desktop](Desktop.md) PART 5).

3.2  Peers are app-logins; replicas are providers

  A PEER is an app-login (a verifier the user can sign in through). A
  REPLICA is a provider that physically holds a copy of the account.
  These are NOT one-to-one:

    * Two peers may share one provider (e.g. dave@quippin and
      dave@bookstore both hosted on prv1). They are two independent
      verifiers - login redundancy at the app-central-host level - but
      only ONE data replica lives on prv1.

    * Each DISTINCT provider among the peers holds exactly one
      login-<actId>-<prvId> replica and (per the companion spec) one
      desktop-<actId>-<prvId> replica.

  So: attaching a peer on a provider that already hosts a replica adds a
  membership row but creates no new data replica; attaching a peer on a
  NEW provider creates a new replica there (PART 8.4).

3.3  The cryptographic truth vs the convenience index

  The AUTHORITY for "provider P may act for this actId" is the delegation
  P holds, signed by the account ownership key ([Security](../platform/Security.md)
  PART 6.2; [Identifiers](../platform/Identifiers.md) PART 5), which chains to the current ownId.
  The membership directory is a MIRROR of "providers the ownership key
  has delegated to", maintained for the user's benefit. If the two ever
  disagree, the delegation is authoritative for message verification; the
  membership row is authoritative for UI and for the sync peer list. PART
  11 keeps them aligned.

## PART 4 - KEY CUSTODY, AND WHAT IS (AND IS NOT) REPLICATED

4.1  Server-held ownership keys

  Signing providers hold the ownership private key
  ([Identifiers](../platform/Identifiers.md) PART 5; [Security](../platform/Security.md)
  PART 5.4):

    * the OWNERSHIP private key (the operative key that issues
      delegations; [Identifiers](../platform/Identifiers.md) PART 5) is stored server-side
      (act.OwnPrvKey, encrypted at rest) on every SIGNING provider;
    * the GENESIS key (which defines the actId and signs the ownId
      binding; [Identifiers](../platform/Identifiers.md) PART 4, 7) is NOT held by any provider -
      it stays with the user (offline / recovery device).

  Nothing about the account is stored permanently in the browser.

  Consequence, stated plainly: every signing provider can both VERIFY the
  user and SIGN as the account, and can renew its own delegation. This is
  the accepted limitation T1 ([Security](../platform/Security.md) PART 2.2, 6.4). It is
  what makes redundancy simple - any signing provider is a complete,
  independent copy. T1 is ESCAPABLE: because the ownership key is
  rotatable, a rebind
  ([Identifiers](../platform/Identifiers.md) PART 10) can cryptographically evict a dishonest
  signing provider without rewriting objects. Cooperative removal
  (PART 11) remains the routine, no-genesis-key path.

  Attaching a provider therefore PROVISIONS the ownership private key to
  it (PART 8.3). This matches the original intent "set up a new account
  with the existing private key."

4.2  Two-factor authentication is orthogonal (PART 12)

  2FA can be added later WITHOUT changing key custody, because 2FA lives
  in the authentication layer (sign-in) while the ownership key lives in
  the wire-identity layer. See PART 12.

4.3  Replication boundary

  Replicated by THIS spec (the identity layer):
    * the account (usrId, hashedPwd, salt, usrName, ...) - actually one
      account PER peer, on that peer's app central host; see PART 7.4;
    * the ownership private key (act.OwnPrvKey) on each SIGNING provider
      ([Identifiers](../platform/Identifiers.md) PART 5.2); object-only providers do NOT get it;
    * the delegation for each home provider ([Security](../platform/Security.md) PART 6);
    * the membership directory login-<actId>-<prvId> (PART 7);
    * the actId->ownId binding record on each Login peer ([Identifiers](../platform/Identifiers.md)
      PART 6, 12).

  Replicated by the COMPANION spec (the launcher):
    * the Desktop tile set desktop-<actId>-<prvId>.

  NOT replicated here (out of scope, deliberately):
    * per-app DATA sub-hosts (quippin-<actId>, money-<actId>, bookstore-<actId>,
      ...). These stay PORTABLE single-location hosts — `<appId>-<actId>` with
      no -<prvId> suffix ([App Store](AppStore.md) PART 9; UserHostIds). Marketplace
      install chooses which provider hosts that one HstId; it does NOT adopt
      the login/desktop/navigator replica naming. Consequence: after signing
      in on a surviving provider you always get your identity and (via the
      companion spec) your home screen, but clicking a tile whose app data
      lives only on a downed provider still fails. Per-app data redundancy is
      future work (PART 17).

## PART 5 - HOST NAMING (provider-qualified sub-hosts)

5.1  The rule "one hstId -> one location" is preserved

  Replicating a per-user sub-host would break the directory's "one hstId
  resolves to one provider" invariant if every replica shared a name.
  Instead, each replica's host id carries its provider:

    login-<actId>-<prvId>       one Login membership replica per provider
    desktop-<actId>-<prvId>     one Desktop replica per provider (companion)

  Each such host id has exactly one host record and resolves through the
  directory to exactly one provider, unchanged from today's routing
  (HttpClient.dispatch / getHst). Replicas are simply ordinary sub-hosts;
  no directory changes, no provider-directed message bypass.

5.2  Parsing is unambiguous

  The separator is '-' (`DomId.HOST_SEP`, [Login](Login.md) PART 2).
  It is safe because:

    * v1 actIds use the fingerprint alphabet [0-9 A-Z _ a-z ~]
      ([Identifiers](../platform/Identifiers.md) PART 4.1) and therefore contain no '-';
    * appIds ("login", "desktop", ...) contain no '-';
    * prvIds ("prv1", "prv2", ...) contain no '-'.

  So "<appId>-<actId>-<prvId>" splits deterministically: appId up to the
  FIRST '-', prvId after the LAST '-', actId = the middle (width-
  independent; [Identifiers](../platform/Identifiers.md) PART 18.5 / KD7). Public parsers
  still gate on the v1 32-char shape today.

5.3  actId stays provider-free

  The '-<prvId>' suffix is on the HOST id, not on the actId. The actId
  embedded in every object and link remains provider-free and immutable
  ([Identifiers](../platform/Identifiers.md) PART 2.2). Host ids have always mapped to a provider
  (that is what the host directory is), so naming the provider in a replica's
  host id is consistent with the model, not a violation of it.

5.4  Local hot path stays local

  On the provider the user is signed in to, the local replica is
  login-<actId>-<DOMATAR_HSTID>. Reads of the user's own membership are
  therefore in-process (sendLocal); peers are contacted only during
  reconciliation (PART 10). This preserves the [Desktop](Desktop.md) PART 2
  "no cross-prv traffic on the hot path" property for the companion spec
  too.

## PART 6 - MEMBERSHIP DIRECTORY: WHERE IT LIVES

The membership directory generalizes login-<actId> ([Login](Login.md)
PART 3.2) into a per-provider replica:

  Container obj (one per replica)
  -------------------------------
    HstId    = login-<actId>-<prvId>
    AppId    = login
    ActId    = <actId>
    ObjId    = membership              (singleton container)
    ClsAppId = login
    ClsId    = membership
    ObjName  = "Membership"
    ObjDesc  = "Providers and logins linked to this account"
    Attrs    = {
      "Version": "<base64 time>"       (max PeerVersion below; the
                                        changed-since stamp, PART 10.3)
    }

The container lives on EACH home provider, one per replica, and holds one
row per PEER (PART 7). All replicas of the container converge to the same
row set by reconciliation (PART 10).

The Login peer ALSO carries the account's actId->ownId binding record
([Identifiers](../platform/Identifiers.md) PART 6), a sibling obj (ClsId=binding) in the same
sub-host. It replicates by the same LWW machinery as the membership
(PART 10), so every replica - including object-only providers that host
no interactive login ([Identifiers](../platform/Identifiers.md) PART 8) - learns of an ownId
rebind.

## PART 7 - MEMBERSHIP ROW SHAPE

7.1  One row per peer (app-login)

    HstId    = login-<actId>-<prvId>          (the replica this copy is on)
    AppId    = login
    ActId    = <actId>
    ObjId    = peer-<usrId>                    (e.g. peer-dave@quippin)
    ClsAppId = login
    ClsId    = peer
    ObjName  = "<UsrName>"
    ObjDesc  = "Login at <appId> on <prvId>"
    Attrs    = {
      "UsrId":       "<localname>@<appId>",    (the peer's login handle)
      "UsrName":     "<display name>",
      "AppId":       "<appId>",                (verifier: the app central
                                                host that checks this
                                                login's password)
      "PrvId":       "<prvId>",                (home provider hosting this
                                                peer's replica)
      "IsRoot":      "True"|"False",           (True on the original
                                                sign-up peer)
      "AddedAt":     "<base64 time>",
      "PeerVersion": "<base64 time>",          (last-modified stamp for
                                                THIS row; the LWW clock,
                                                PART 10.2)
      "Tombstone":   "True"|"False",           (soft-delete, PART 11.2)
      "FpVersion":   "<int>"                   (fingerprint algorithm that
                                                minted the account actId;
                                                [Identifiers](../platform/Identifiers.md)
                                                PART 3.4; not shown in
                                                ListMembership)
      "IsLoginHome": "True"|"False"            (False = object-only host
                                                peer-prv-<prvId>; omitted
                                                on login-home rows means
                                                True. [Foreign Provider](../install/Foreign-Provider.md)
                                                PART 8)
    }

7.2  Why PrvId is on the row

  The Desktop sync layer needs to know, for each peer, WHICH provider
  holds that peer's replica, so it can derive the peer's Desktop host id
  desktop-<actId>-<PrvId> (companion spec PART 5). The verifier (AppId)
  and the host provider (PrvId) are recorded separately because they are
  different facts ([Login protocol](Login-Protocol.md) PART 3.4 already notes actId-suffix and
  usrId-suffix may diverge; here PrvId is a third, independent axis).

7.3  Deriving the replica set

  The DISTINCT set of PrvId values across non-tombstoned LOGIN-HOME
  peer rows (IsLoginHome !== False; UsrId present) is the sign-in /
  last-login / Desktop-sync replica set (PART 3.2). Object-only peers
  (IsLoginHome=False, ObjId peer-prv-<prvId>) appear in ListMembership
  as "Hosts objects (no sign-in)" but are ignored for sign-in and
  last-login counts ([Foreign Provider](../install/Foreign-Provider.md) PART 8 /
  KD8). Desktop sync still uses login homes only (companion spec
  PART 5), after de-duplicating peers that share a provider.

7.4  Relationship to the per-app accounts

  Each peer still corresponds to a canonical account on its verifier's
  central host ([Login protocol](Login-Protocol.md) PART 3.4), carrying the shared actId. The
  membership object is the account-side INDEX of those logins; it is not
  the password store. Passwords live only on those accounts (hashedPwd),
  never in the membership directory (same rule as [Login](Login.md)
  PART 6.2).

## PART 8 - ATTACH-A-PROVIDER FLOW

AttachProvider creates a LOGIN HOME on N (account, shells, optional
ownership-key copy). Hosting an app on N without a sign-in door is
[Foreign Provider](../install/Foreign-Provider.md) (HostProvision / object-only
peer). Do not use AttachProvider as a side-effect of InstallApp.

This is the user's described flow: "sign in at one provider; go to a
provider where you have no account; choose create; it recognizes you are
already signed in and offers to attach."

Preconditions: the user is signed in somewhere as actId X (Context.actId
verified) and is now visiting a NEW provider N's sign-up page.

  8.1  RECOGNIZE. N's sign-up page loads the SSO iframe ([Login protocol](Login-Protocol.md)
       PART 7.2) pointing at the user's existing verifier central host.
       If that iframe reports a live Domatar session, the page offers:
       "You are signed in as <usrId>. Attach this provider to that
       account?" instead of only "create a brand-new account".

  8.2  CONFIRM + VERIFY. The user confirms and RE-VERIFIES the existing
       account ([Login protocol](Login-Protocol.md) PART 7.2.1 REVERIFY: re-enter the existing
       password, or satisfy 2FA once PART 12 exists). This proves the
       caller controls actId X before N is trusted with a copy.

  8.3  DELEGATE + PROVISION. The current ownership-key holder (the
       user's existing signing provider) does, as one logical
       operation:
         a. issue a Delegation naming N ([Security](../platform/Security.md) PART 6.2, 6.4);
         b. transmit to N, over TLS ([Security](../platform/Security.md) PART 9), the
            ownership private key, the delegation, and the current
            actId->ownId binding ([Identifiers](../platform/Identifiers.md) PART 6);
         c. N stores the ownership key (encrypted at rest), the
            delegation, and the binding.
       N is now a SIGNING provider ([Identifiers](../platform/Identifiers.md) PART 5.4) able to
       verify and sign for X. (A provider where the user only keeps
       objects but never logs in is an OBJECT-ONLY provider: it gets the
       delegation and the binding but NOT the ownership key.)

  8.4  CREATE THE PEER + REPLICA ON N. An AddAct in LINK mode
       ([Login protocol](Login-Protocol.md) PART 5) creates the new app-login on N's verifier
       (usrId = "<localname>@<appId-on-N>", reusing actId X). If N does
       not yet host a replica of this account:
         a. create login-<actId>-N (this membership replica);
         b. create desktop-<actId>-N (companion spec PART 8 bootstrap);
         c. seed both from the attaching provider (PART 8.5).
       If N already hosts a replica (a second login on the same
       provider), skip a-c; only the membership row is added.

  8.5  SEED. Copy the membership row set from the attaching provider's
       login-<actId>-<oldPrv> to login-<actId>-N, then ADD the new peer
       row (with PrvId=N, PeerVersion=now). The Desktop replica is seeded
       by a first reconcile pull (companion spec).

  8.6  RECORD EVERYWHERE. The new peer row is written to N's membership
       replica and propagated (best-effort) to the other replicas; any
       replica that misses the push picks it up at its next reconcile
       (PART 10). IsRoot=False for attached peers.

  8.7  RESULT. The user now has a login on N, a full account copy on N,
       and N appears in the membership on every replica. From now on N is
       a valid sign-in path (PART 9) and a Desktop sync peer.

Trust note. Step 8.2's re-verify is the gate: N is provisioned with the
ownership key only after the caller proves control of X. This is the same
trust extension as [Login protocol](Login-Protocol.md) PART 5, hardened by an explicit
re-verify because the consequence (a full key copy) is heavier than a
plain app link.

## PART 9 - SIGN-IN WITH REDUNDANCY

9.1  Normal sign-in is unchanged

  Sign-in still routes by usrId suffix to the verifier's central host
  ([Login protocol](Login-Protocol.md) PART 6, PART 9). Nothing here changes the per-seam
  verification contract.

9.2  Choosing a provider when one is down (user action, R1)

  If the provider behind the user's usual peer is unreachable, the user
  signs in through a DIFFERENT peer - i.e. types a different usrId
  ("dave@quippin" vs "haya@avatarvia"), exactly the multi-app fallback of
  [Login protocol](Login-Protocol.md) PART 7.2.2, now understood as multi-PROVIDER fallback.
  There is no automatic selection: the user picks the surviving peer.

9.3  After sign-in, everything is local

  Because the provider the user landed on holds a full replica, its
  login-<actId>-<thisPrv> and desktop-<actId>-<thisPrv> are present
  locally. The membership list and the Desktop render from local data
  (PART 5.4); peers are contacted only to reconcile (PART 10).

## PART 10 - MEMBERSHIP REPLICATION AND RECONCILIATION

10.1  Eventual consistency, no failover

  Replicas converge; they are never required to agree synchronously. A
  provider that is down during a change simply reconciles later. This is
  sufficient for R1/R2 and is all that is promised (PART 1.2).

10.2  Last-writer-wins per peer row

  Each peer row carries PeerVersion (a timestamp). When two replicas hold
  different versions of the same row (same ObjId peer-<usrId>), the higher
  PeerVersion wins; the older is discarded. Membership changes (attach,
  rename, tombstone) are rare and almost never concurrent, so LWW is
  more than adequate. The merge key is (ActId, ObjId); HstId differs per
  replica and is ignored in the comparison.

  Clock note: LWW relies on comparable timestamps. Providers already keep
  sane clocks for the message freshness window ([Security](../platform/Security.md)
  PART 13); no new requirement.

10.3  The changed-since version stamp

  The container's Version attr (PART 6) is the maximum PeerVersion across
  its rows. A reconciler first compares container Versions; if a peer's
  Version is not newer than what it last saw, it skips that peer with no
  row transfer. Only a newer Version triggers a full row pull + merge.

10.4  When reconciliation runs

  Membership reconciliation runs opportunistically:
    * on ATTACH/RENAME/REMOVE - best-effort fan-out push to reachable
      replicas immediately (for timely durability, R2);
    * on DISPLAY - when the Login "account" page ([Login](Login.md)
      PART 4) or the Desktop (companion spec) renders, it pulls from the
      replica set and merges. In practice this is "shortly after each
      sign-in", which is the dominant case.

  Fan-out failures are non-fatal; the display-time pull repairs anything
  a push missed. There is no background daemon.

10.5  Authorization of membership writes

  A membership write is accepted only from a verified Context whose actId
  owns the sub-host (owner-match; [Login](Login.md) PART 6.1). Because
  cross-replica pushes are signed by a provider holding a valid
  delegation for the actId ([Security](../platform/Security.md) PART 7.3), a replica accepts
  a peer-row push only if the credential chain verifies AND the row's
  actId matches the sub-host's actId. A provider with no delegation for X
  cannot inject a peer into X's membership.

## PART 11 - REMOVING A PROVIDER (COOPERATIVE)

11.1  What removal means here

  Removal is a COOPERATIVE, user-initiated cleanup, matched to R1: "I no
  longer want to use provider N." It is NOT cryptographic revocation of a
  compromised provider (out of scope, PART 1.2).

11.2  Procedure

    a. TOMBSTONE the peer row(s) for N: set Tombstone=True and a fresh
       PeerVersion, so the delete wins under LWW and is not resurrected by
       an older copy on another replica (PART 10.2). Tombstones are
       retained for a purge window, then physically removed.
    b. STOP RENEWING N's delegation ([Security](../platform/Security.md) PART 6.4). N's last
       delegation lapses at NotAfter; after that no verifier accepts N's
       signatures for the actId.
    c. Optionally ask N to delete its local replica (login-<actId>-N,
       desktop-<actId>-N, accounts, OwnPrvKey). An honest N complies.
    d. Refuse to remove the LAST peer (that would lock the user out),
       mirroring [Login](Login.md) PART 8.8.

11.3  Cooperative removal vs cryptographic eviction

  Because N was provisioned with the ownership private key (PART 4.1), a
  DISHONEST N that ignores step (c) still holds a usable copy of that key
  and could renew its own delegation ([Security](../platform/Security.md) PART 6.4
  governance caveat). Cooperative removal ALONE cannot prevent that.

  What CAN evict such an N is a REBIND ([Identifiers](../platform/Identifiers.md) PART 10): the user
  rotates to a fresh ownership key, and once the new actId->ownId binding
  propagates, N's self-issued delegations under the OLD ownership key no
  longer chain to the current ownId and are rejected - without rewriting
  any object. A rebind requires the genesis key (held by the user, not by
  N), so N cannot block it. The one residual is the binding-propagation
  window ([Identifiers](../platform/Identifiers.md) PART 16.2).

  Plain removal is "stop using" (cooperative, no genesis key needed).
  "Evict / revoke" is a rebind. Device-held ownership keys and a
  revocation service ([Security](../platform/Security.md) PART 15) remain
  Direction.

## PART 12 - TWO-FACTOR AUTHENTICATION (FORWARD-LOOKING)

12.1  Why it composes cleanly with server-held keys

  2FA lives in a different layer than the ownership key:

    Layer 1 - AUTHENTICATION ("may this person start a session as this
              usrId?"): the password check at the verifier
              ([Login protocol](Login-Protocol.md) PART 6). 2FA is a SECOND FACTOR added here.
    Layer 2 - WIRE IDENTITY ("is this message really from actId X?"): the
              ownership key + delegation ([Security](../platform/Security.md) PART 6, 7;
              [Identifiers](../platform/Identifiers.md) PART 11), server-held in this design.

  2FA sits entirely in Layer 1; Layer 2 never sees it. So 2FA can be
  added at any time without moving keys to the client and without
  touching the actId/ownId/delegation machinery.

12.2  Consistent with "no permanent data in the browser"

  An additional AUTHENTICATION DEVICE (phone authenticator, email/SMS
  code) is not the browser storing the account identity:
    * email / SMS one-time code - stores nothing on the client;
    * TOTP authenticator - seed lives on the phone and on the verifier;
      the browser stores nothing;
    * a passkey/security key used as a second factor - lives in the
      phone/hardware authenticator, not the browser.
  All are consistent with this design's no-client-storage rule. (The
  thing that rule excludes is a browser-held OPERATIVE key - the
  [Security](../platform/Security.md) PART 15 / [Identifiers](../platform/Identifiers.md) device-held ownership key -
  which is a different mechanism. The cold GENESIS key on a recovery
  device, [Identifiers](../platform/Identifiers.md) PART 7, is also not browser storage.)

12.3  Where it slots in

  2FA is a gate at the moment a real sign-in mints the session / SSO
  ticket ([Login protocol](Login-Protocol.md) PART 6, PART 7). Once a session exists, derived
  per-prv sessions and reconciliation ride on it without re-prompting
  (unless an app's REVERIFY policy asks again, [Login protocol](Login-Protocol.md) PART 7.2.1).

12.4  The one design choice it will raise (deferred)

    * PER-LOGIN 2FA - each verifier stores its own second factor; the
      user enrols per peer. Isolated and simple; enrol N times.
    * ACCOUNT-LEVEL 2FA - one shared second factor referenced by all
      logins. More convenient, but the enrolment becomes account-scoped
      state that must be REPLICATED among providers alongside the
      membership (PART 7), and is a shared point of compromise.

  No decision is forced now. Account-level 2FA would add one more small
  replicated dataset with the same LWW treatment as membership.

12.5  What 2FA does and does not fix

  2FA hardens Layer 1 against password/token theft. It does NOT address
  T1 (a signing provider holding the ownership key can act as the user);
  that is a Layer 2 property unchanged by 2FA. T1 is addressed instead by
  a rebind (PART 11.3, [Identifiers](../platform/Identifiers.md) PART 10).

## PART 13 - AUTHORIZATION SUMMARY

  Public                     Verified + owner-match
  ------                     ----------------------
  (recognize via SSO iframe) AttachProvider (after re-verify, PART 8.2)
                             ListMembership
                             AddPeer / TombstonePeer / UpdatePeer
                             (cross-replica pushes, PART 10.5)

  "Owner-match" means Context.actId equals the sub-host's actId
  ([Login](Login.md) PART 6.1). Cross-replica pushes additionally require
  a valid credential chain for that actId (PART 10.5).

## PART 14 - IMPLEMENTATION SURFACE

Handlers / actions:

  com.login.objimpl.MembershipImpl   (login, membership) -> handler
      ListMembership, ListPeersFull, AddPeer, TombstonePeer, UpdatePeer,
      GetMembershipVersion, ReconcileMembership, GetBinding, SetBinding.

  com.login.webui.AccountsWui:
      Action=ListMembership, ReconcileMembership, AttachProvider,
      RemoveProvider; ImportLogin writes AddPeer; account page reads the
      LOCAL membership replica only.

  com.domatar.act.ActManagerImpl:
      AttachProvision; recordPeer (AddPeer + fan-out); updateAct/deleteAct
      UpdatePeer/TombstonePeer; rebind publishes binding via MembershipFanout.

  Helpers: DomId.subHstId(app, actId, prvId); DirectoryRegister;
      MembershipReplica.ensure; MembershipFanout.push / publishBinding.

  ImplMap: (login, membership) -> MembershipImpl.
  K11: app-login lives on login-<actId>-<prvId>; Login/Navigator/Desktop
  are per-provider and exempt from desktop tile content-sync
  ([Desktop](Desktop.md) PART 4.4).

Directory / seed: host records for login-<actId>-<prvId> per replica (PART 5).

## PART 15 - LOCAL SIMULATION

The two-provider sim has prv1 (tomcat1) and prv2 (tomcat2). To exercise
multi-provider
redundancy for one user:

  * Give the user a peer on each provider (e.g. dave@quippin on prv1 and
    dave@avatarvia on prv2), both carrying the same actId fingerprint.
  * Seed membership replicas:
      login-<actId>-prv1  on tomcat1
      login-<actId>-prv2  on tomcat2
    each with two peer rows (PrvId=prv1, PrvId=prv2).
  * Seed Desktop replicas per the companion spec.

  Attach can be exercised live: sign in on prv1, visit prv2's signup,
  attach, and confirm a login-<actId>-prv2 replica and a peer row appear
  on both providers after reconcile.

  In the sim, TLS is bypassed ([Security](../platform/Security.md) PART 9); the
  ownership-key transfer in PART 8.3 travels over plain http between
  tomcats. This is acceptable for the sim only. The GENESIS key stays out
  of the tomcats, in a sim-only user vault ([Identifiers](../platform/Identifiers.md) PART 15).

## PART 16 - SECURITY CONSIDERATIONS AND RESIDUAL RISKS

  * Membership is private per-account state, not a network directory; no
    third-party lookup (PART 2). Verification stays stateless
    ([Security](../platform/Security.md) PART 6.3 property preserved).
  * A provider cannot inject itself into an account's membership without a
    valid delegation chaining to the current ownId for that actId
    (PART 10.5; [Identifiers](../platform/Identifiers.md) PART 11), so the
    [Login protocol](Login-Protocol.md) PART 13 cross-app forgery threat is not widened by this
    spec.
  * T1 (narrowed): every signing provider holds the ownership key and can
    act as the user, and cooperative removal alone cannot evict a
    dishonest one. This is now ESCAPABLE by a REBIND (PART 11.3,
    [Identifiers](../platform/Identifiers.md) PART 10), which invalidates the old ownId without
    rewriting objects; the residual is the binding-propagation window
    ([Identifiers](../platform/Identifiers.md) PART 16.2). Device-held keys and
    revocation remain Direction ([Security](../platform/Security.md) PART 15).
  * The attach step 8.3 transmits the ownership private key between
    providers; it MUST be over TLS ([Security](../platform/Security.md) PART 9). Compromise
    of that channel leaks the operative key (recoverable by a rebind, but
    still to be avoided). The genesis key never travels this path.
  * Re-verify (PART 8.2) is the sole gate before provisioning a heavy
    (full-key) copy; it should be rate-limited and logged.

## PART 17 - TODO

  - Per-app DATA redundancy. Marketplace apps keep portable
    `<appId>-<actId>` hosts ([App Store](AppStore.md) PART 9) — do not solve
    redundancy by appending -<prvId>. A future replica pattern for app
    data is separate from login/desktop/navigator (PART 4.3 / PART 5).
  - Two-phase / idempotent attach. PART 8 spans several writes (delegate,
    provision, create peer, seed, record). Make it re-drivable so a
    mid-attach failure leaves no half-attached provider; surface drift on
    the account page (mirrors [Login](Login.md) PART 13).
  - Account-level 2FA replication (PART 12.4) if that option is chosen.
  - Cryptographic provider eviction is now provided for SIGNING providers
    by rebind ([Identifiers](../platform/Identifiers.md) PART 10); wire the rebind flow into the
    account page and shrink the propagation window ([Identifiers](../platform/Identifiers.md)
    PART 16.2). Device-held ownership keys remain Direction
    ([Security](../platform/Security.md) PART 15).
  - Membership-change notification instead of pure display-time pull, if
    convergence latency is ever shown to matter (analogous to
    [Login protocol](Login-Protocol.md) PART 7.3's cache discussion).
  - Navigator tree content is not merged across providers
    ([Desktop](Desktop.md) PART 4.4). Login/Navigator/Desktop are
    per-provider and exempt from tile content-sync.

# END OF SPEC
