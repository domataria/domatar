# SPEC: FOREIGN-PROVIDER INSTALLATION — host an app on a provider where the user has no interactive login

A user signed in on home provider H may install an application onto
ANY offering provider N that lists an Active offer for that app,
including N where the user has no local account, no password, and no
sign-in handle. N becomes an OBJECT-ONLY host for that account
([Identifiers](../platform/Identifiers.md) PART 5.4 / PART 8), not a login home.

Companion documents:

- [App Store](../apps/AppStore.md) — marketplace InstallApp, offers, portable hosts
- [Identifiers](../platform/Identifiers.md) — object-only providers; binding on every object-hosting provider (PART 8)
- [Login protocol](../apps/Login-Protocol.md) — AttachProvider = login-home attach. Distinct from InstallApp
- [Platform App](../platform/Platform-App.md) — `domatar-<actId>-<prvId>` substrate; object-only N gets membership + binding only (no shells)
- [Installation](Installation.md) — InstallUser / UserInstallDispatch
- [Security](../platform/Security.md) — delegations, credential chain, TLS
- [Desktop](../apps/Desktop.md) / [Icons](../platform/Icons.md) — home-side launcher; remote LaunchPath / IconPath (PublicDomain)

Present-tense statements describe the live system.

## PART 1 - PURPOSE

1.1  Hosting is not login

  The App Store lets the user pick an app and then pick a provider that
  OFFERS it (GetListing → Active Offers → InstallApp with PrvId).

  Those are two different jobs:

    * HOSTING an app's data (portable sub-host on N)
    * SIGNING IN through N (account, password, shells, optional
      ownership-key copy)

  Hosting does not require a door. A user on prv1 can put Bookstore on
  prv2 because prv2 offers Bookstore, not because they created
  `…@login` on prv2.

1.2  Goals

  G1  Any Active offer is choosable. InstallApp(AppId, PrvId=N) succeeds
      when N offers AppId, the catalog probe passes, and the session is
      verified — whether or not the user can sign in on N.

  G2  Hosting ≠ login. Foreign install creates OBJECT-ONLY presence on
      N. It does NOT AddAct, does NOT set a password, does NOT copy the
      ownership private key, does NOT InstallUser login/desktop/navigator
      on N.

  G3  OwnIds 8.1 holds. Every provider that stores objects for actId X
      holds a verifiable binding (and a membership peer) for X, so a
      later rebind can reach N.

  G4  Launcher stays on the login home. The userApps / Desktop entry is
      written on H (the session's provider). LaunchPath / IconPath /
      HostPrvId point at N. The user does not need a Desktop replica on
      N to use the app.

  G5  AttachProvider stays the login-home path. Wanting a sign-in door
      on N remains an explicit attach, not a side-effect of picking an
      offer.

1.3  Non-goals

  * Minting a usrId on N (`localname@login`, `@avatarvia`, …) as part of
    InstallApp.
  * Copying the ownership private key to N (T1 expansion).
  * Creating login/desktop/navigator hosts or shell bindings on N.
  * Baking PrvId into portable app host ids ([App Store](../apps/AppStore.md) PART 9).
  * Moving an already-installed portable host from H to N (still
    refuse "Already installed on provider …" while the launcher is live;
    after UninstallApp, InstallApp restores the tile on the existing host.
    MoveInstall is Direction).
  * Changing offer discovery (SearchApps / RegisterOffer).
  * Multi-install of the same AppId on two providers under two HstIds.

## PART 2 - CONCEPTS

2.1  Home provider H

  The provider of the current verified session — where the user signed
  in, where GetUserApps / GetShells for this session are local
  ([Platform App](../platform/Platform-App.md)).

2.2  Offering / hosting provider N

  The provider chosen in InstallApp.PrvId. It must have an Active offer
  for the app and a local catalog entry. After a successful foreign
  install it owns the portable user sub-host
  (`<appId>-<actId>`, [App Store](../apps/AppStore.md) PART 9.2) and serves the app runtime.

  H and N MAY be the same (local install; unchanged).

2.3  Login home

  A provider that holds an interactive login for the account: an act
  row (usrId + password), a full domatar substrate including shells,
  and (if signing) the sealed ownership key. The user can open that
  provider's PublicDomain and sign in. Created by sign-up or
  AttachProvider — NEVER by InstallApp.

2.4  Object-only host

  A provider that stores some of the account's OBJECTS and can operate
  them under a Delegation, but:

    * has NO local account / password for this actId
    * does NOT hold the ownership private key
    * is NOT a sign-in path ([Identifiers](../platform/Identifiers.md) PART 8.4)

  It DOES hold: the genesis-signed Binding, a Delegation naming this
  prvId, a membership peer marked object-only, and a MINIMAL domatar
  substrate (PART 7.2).

  The set of login homes is a subset of providers that hold a
  membership peer. Object-only hosts are the remainder.

## PART 3 - INVARIANTS

3.1  actId on objects ≠ local account

  The object's ActId is the global owner fingerprint. The local
  account store is the login/password directory for THIS node (and,
  for verification, the usrId suffix's central host —
  [Login protocol](../apps/Login-Protocol.md) C3). An object need not
  have a matching local account.

  Foreign install MAY create objects on N whose ActId has no matching
  local account on N. That is required, not accidental. The account still
  has a login on at least one login home. Identity on N is the
  Binding object, not a local account.

  This realisation stores objects in `obj` and accounts in `act`;
  there is no foreign key from `obj` to `act`. Code that calls
  `ActDb.getAct(actId)` / `ActDb.getOwnPrvKey(actId)`
  on N MUST treat a missing row as object-only, not as "account does
  not exist".

3.2  No T1 expansion on foreign install

  N does not receive RawOwnPrvKey. Compromise of an object-only host
  does not yield the ownership key. Eviction is "stop renewing the
  delegation" ([Identifiers](../platform/Identifiers.md) PART 5.4), not a rebind.

3.3  One portable host per (user, AppId)

  Unchanged from [App Store](../apps/AppStore.md) PART 9. If `<appId>-<actId>` already
  lives on a different provider, refuse. Do not mint
  `<appId>-<actId>-<prvId>` to dodge the collision.

3.4  Session usrId is the home handle

  InstallUser still carries the session's UsrId / UsrName (the handle
  the user signed in with on H). That is a label for app install
  (display names, InstallUser's session match). It is NOT an instruction
  to create that usrId on N.

## PART 4 - USER FLOW

  1. User is signed in on H. Opens App Store (home origin).
  2. SearchApps / listing list. User chooses an app.
  3. GetListing returns Active Offers (PrvId, Domain, PublicDomain, …).
     The UI lists EVERY Active offer. It MUST NOT filter to providers
     where the user already has a login. It MAY annotate:
       "You can sign in here"   — membership peer with IsLoginHome=True
       "Will host the app only" — no login home on that PrvId
  4. User selects a provider N and confirms Install.
  5. Home InstallApp runs PART 5. On success the home launcher shows
     the app; LaunchPath targets N's PublicDomain (or wire Domain).
  6. The user is NOT offered a new usrId, is NOT asked to pick an app
     suffix (`@login` / `@quippin`), and is NOT asked to re-enter a
     password solely because N is foreign.

     (AttachProvider still re-verifies, because it copies the ownership
     key when IsSigning. Foreign install does not.)

## PART 5 - InstallApp (HOME NODE)

Unchanged request: `{ AppId, PrvId }` plus optional Domain /
PublicDomain / PrvActId from the offer picker
([App Store](../apps/AppStore.md) PART 7.4 / KD10).

Behaviour when PrvId == H: today's local path. No HostProvision.

Behaviour when PrvId == N ≠ H (FOREIGN):

  1. Verified session; ctx.actId is the installing user.
  2. Resolve Active offer for (AppId, N); else "No active offer…".
  3. Catalog probe on N (HasCatalogEntry); else refuse.
  4. Resolve portable HstId (PART 9). If already registered to a
     different PrvId, refuse. If already on N, skip InstallUser
     (idempotent) but still ensure the home userApps row.
  5. PRESENCE. If membership (home replica) has no non-tombstoned
     peer with PrvId=N — neither login-home nor object-only —
     call HostProvision (PART 6) BEFORE InstallUser.
     If a peer already exists (user attached N earlier, or a previous
     foreign install), skip HostProvision.
  6. Directory-publish the portable HstId → N's Domain, then
     UserInstallDispatch.sendInstallUser to N (unchanged transport).
  7. Upsert home userApps (UserAppRegistry) with HostPrvId=N,
     HstId, absolute LaunchPath / IconPath ([Icons](../platform/Icons.md) / LaunchPaths
     remote policy). Do NOT write a Desktop tile as authority
     (Mandatory-App-Rewrite KD5).
  8. Respond { Status: "Installed", AppId, PrvId, HstId }.

This REPLACES App Store KD5. The error
"Attach a login on provider N before installing there" is deleted.
New failures: HostProvision failed (binding/delegation), InstallUser
failed on N, catalog miss — same PART 10.3 spirit.

InstallUser authorization on N remains `Auth.isVerified` plus
ctx.actId match (`AppUserInstallHandler`). A verified cross-provider
message from H is sufficient; N does not look up `act` for this
actId.

## PART 6 - HostProvision PROTOCOL

6.1  Why a new operation

  AttachProvision today REQUIRES a local account ("run link-mode AddAct
  first"), stores binding/delegation/own key ON `act`, AddPeer with a
  usrId, then Mandatory KD8: full substrate + default shell InstallUser
  on N.

  That is the login-home attach. Foreign install must not use it as-is.

  HostProvision is the object-only provision: binding + delegation +
  minimal substrate + object-only peer. No AddAct. No OwnPrvKey. No
  shells.

6.2  Driven from H (like AttachProvider)

  The SOURCE is the current signing login home (holds OwnPrvKey). It
  issues Delegation naming N ([Security](../platform/Security.md) PART 6.2 / 6.4) and
  sends HostProvision to N's actManager:

    dst = (hstId=N's provider act host, appId=act, actId=act@act,
           objId=actManager)
    // same routing as AttachProvider's AddAct / AttachProvision

  Body (HostProvision on ActManagerImpl; HostProvisionDriver.ensure
  on H issues the Delegation and sends the Msg):

    ActId
    BindingActId, BindingGenesisPubKey, BindingOwnId, BindingOwnPubKey,
    BindingVersion, BindingNotBefore, BindingSig
    Delegation        (JSON; prvId = N)
    Domain, PrvId     (N's wire domain and prvId)
    // NO NewUsrId, NO Pwd, NO RawOwnPrvKeyB64, NO IsSigning=True

  Auth: verified session; callerOwnsAct; binding.verify();
  delegation.verify(binding) and delegation.prvId == N.

  Presence on H uses HostPresence.hasPeer. Object-only peer ObjId is
  ObjectOnlyPeers.objId(prvId) → peer-prv-<prvId>.

6.3  What N does

  1. Do NOT require ActDb.getAct(actId). Do NOT AddAct. Do NOT write
     act.OwnPrvKey / act.Pwd / act.UsrId.
  2. UserSubstrateInstall.ensureObjectOnlySubstrate (domatar-<actId>-N;
     membership + binding; no shells — PART 7.2).
  3. Persist Binding and Delegation on the substrate binding object
     (NOT on a local account). GetBinding / SetBinding already exist on
     that graph (Mandatory-App-Rewrite).
  4. MembershipMigrator.upsertObjectOnlyPeer (PART 8) on N's membership;
     fan-out so H's replica also lists N (MembershipFanout / AddPeer on
     H as well — H should AddPeer locally even if N's fan-out is
     best-effort).
  5. Respond Success. Idempotent if substrate + object-only peer
     already exist.

6.4  Re-verify

  AttachProvider re-verifies the password because it may copy the
  ownership key. HostProvision does not copy that key. The existing
  verified session on H is the gate. Do not add a password prompt to
  InstallApp.

6.5  TLS

  Delegation (and binding) travel H→N. Production: TLS
  ([Security](../platform/Security.md) PART 9). Sim: plain http between tomcats, same
  exception as Login-Multiple PART 15. Genesis key never travels.

## PART 7 - WHAT IS CREATED WHERE

7.1  On H (login home, unchanged jobs)

  * userApps row for the app (HostPrvId=N, LaunchPath remote)
  * membership peer for N (object-only) after HostProvision
  * NO second copy of the app's portable data host

7.2  On N (object-only host) — MINIMAL substrate

  Host id: domatar-<actId>-<prvId>  (same naming as login homes)

  Objects required:

    membership     + object-only peer (PART 8)
    binding        + Delegation-to-N stored with it
    (userApps on N is OPTIONAL and MUST NOT be treated as the
     launcher of record — that is H. N may have an empty userApps
     container if ensureUserSubstrate creates one as a unit; N MUST
     NOT get shell-* rows or InstallUser of login/desktop/navigator.)

  Login homes get shells ([Platform App](../platform/Platform-App.md)).
  Object-only hosts get membership + binding only (this PART).
  Bookstore (and other portable apps) may live on a non-login
  provider; this PART is how N gets presence without becoming a
  login home.

7.3  On N — the app

  * host record: portable `<appId>-<actId>` (or hook extension), PrvId=N,
    Domain=N's domain; directory-registered
  * InstallUser side-effects (forsale, library, …) owned by ctx.actId

7.4  Explicitly NOT on N

  * local account for this user
  * OwnPrvKey
  * login-<actId>-N / desktop-<actId>-N / navigator-<actId>-N
    as a new login home (those exist only after AttachProvider)
  * a usrId such as bethb@login

## PART 8 - OBJECT-ONLY MEMBERSHIP PEER

Login-home peers stay `peer-<usrId>` with UsrId, AppId, PrvId,
IsRoot, IsSigning implied by attach (Mandatory KD2).

Object-only peers MUST NOT reuse `peer-<usrId>`: there is no new
usrId, and stuffing the home handle (`bethb@quippin`) onto a prv2
peer would look like a login that does not exist.

  ObjId   : peer-prv-<prvId>
  Attrs   :
    PrvId       : N
    IsLoginHome : False
    IsSigning   : False
    AddedAt / PeerVersion / FpVersion  (same LWW clocks as today)
    // UsrId omitted (or empty). ListMembership-for-sign-in ignores
    // these rows. Account "Providers" UI MAY show them as
    // "Hosts objects (no sign-in)".

AddPeer / TombstonePeer / reconcile treat `peer-prv-*` like other
peers for LWW merge. Sign-in and AttachProvider listing of "your
logins" filter IsLoginHome=True (or presence of UsrId).

HostPresence.hasPeer is the presence check: a non-tombstoned peer
with matching PrvId, whether `peer-<usrId>` or `peer-prv-<prvId>`.

## PART 9 - LAUNCH AND SESSION

  * LaunchPath / IconPath: AppUrl on the userApps row — app-owned URL
    if set, else inherit the offering provider's BrowserOrigin, else
    PublicDomain front door. Do not use wire Domain as a browser URL.
    [App Store](../apps/AppStore.md) PART 11.2 / [Icons](../platform/Icons.md) PART 7.2.
  * The user still authenticates on H. Opening the app on N's origin
    uses [Login protocol](../apps/Login-Protocol.md) world-wide session / SSO iframe — NOT a new
    usrId on N.
  * Navigator Open of objects on the portable host runs on N (Msg
    routing by HstId). PrvId in the Open response is N
    (Navigator details pane).

If SSO-on-foreign-origin is incomplete in the sim, that is a Login
bug/gap to close; it is not solved by minting `…@login` on N.

## PART 10 - UNINSTALL, DETACH, LATER ATTACH

10.1  UninstallApp

  Unchanged data-retention: hide launcher / navigator inventory; do
  not delete app objects ([Installation](Installation.md) PART 5.5). Does not
  WithdrawOffer. Does NOT automatically TombstonePeer the object-only
  host (other apps may still live on N). Direction: GC `peer-prv-N`
  when N holds no remaining portable hosts for this actId.

10.2  Promote object-only N to a login home

  User runs AttachProvider (existing flow) targeting N: AddAct,
  AttachProvision with IsSigning as chosen, shells, usrId. Tombstone
  `peer-prv-N` then AddPeer login-home `peer-<newUsrId>` (KD7 — two
  ObjIds, do not upgrade in place). Existing portable app hosts on N
  stay; they already use actId.

10.3  Detach login home that also hosts apps

  Cooperative RemoveProvider tombstones the LOGIN peer. If portable
  app hosts remain on that prvId, an object-only `peer-prv-*` MUST
  remain (or be created) so OwnIds 8.1 still holds. Do not delete
  app data as a side-effect of dropping a sign-in door.

## PART 11 - SECURITY

  * HostProvision is authorized by the existing credential chain
    (verified session on H + binding + delegation to N). No new
    public membership directory.
  * N cannot inject itself into membership without a delegation that
    verifies under the current ownId (same as AttachProvider).
  * Object-only N cannot renew its own delegation (no OwnPrvKey).
  * Foreign install does not widen T1. AttachProvider (IsSigning)
    still does, and still requires re-verify.
  * InstallUser on N must keep ctx.actId == body ActId so a verified
    caller cannot provision someone else's portable host.

## PART 12 - RELATED SPECS

- [App Store](../apps/AppStore.md) — Foreign InstallApp requires presence (login-home OR object-only peer); if missing, HostProvision then InstallUser. Do not require a linked login. Do not AddAct inside InstallApp. Default presence is HostProvision, not AttachProvider.
- [Platform App](../platform/Platform-App.md) — Substrate replicas exist on every object-hosting provider; shells only on login homes. This spec is how N gets presence without becoming a login home.
- [Login protocol](../apps/Login-Protocol.md) — PART 8 remains AttachProvider (login home). App hosting on N without a door is this document. ListMembership sign-in views ignore object-only peers.
- [Identifiers](../platform/Identifiers.md) — HostProvision is the install-time way an object-only Login/domatar peer appears. Binding and delegation live on the substrate, not a local account, when there is no local account.

## PART 13 - SIM ACCEPTANCE

Fixture: bethb@quippin on prv1 only (no peer on prv2). Bookstore
offered on prv2. Password 123.

  A1  App Store on prv1 lists prv2 as an offer for bookstore. Offer
      is selectable (not greyed out for "no login").
  A2  InstallApp(bookstore, prv2) as bethb → Success. HstId
      bookstore-<bethb-actId> on db2, PrvId=prv2.
  A3  db2 `act` has NO row for bethb's actId / no bethb@login.
  A4  db2 has domatar-<actId>-prv2 with binding + peer-prv-prv2
      (IsLoginHome=False). No login/desktop/navigator hosts for her
      on prv2.
  A5  prv1 userApps / Desktop shows Bookstore with HostPrvId=prv2
      and an absolute LaunchPath (avatarvia or tomcat2).
  A6  Navigator (signed in on prv1) Open of her bookstore cart shows
      PrvId=prv2.
  A7  Sign-in as bethb@quippin on prv1 still works. There is no
      bethb@login (or similar) to type on prv2.
  A8  Repeat InstallApp → Success, no duplicate host.
  A9  A prv1-only user installing onto prv1 (local) is unchanged
      (no HostProvision).
  A10 davidb (already dual login) installing onto prv2 skips
      HostProvision (presence already exists) and still succeeds.

Negative:

  B1  InstallApp to a PrvId with no Active offer → clear error; no
      HostProvision; no hst.
  B2  Second AppId onto the same N reuses presence (one
      peer-prv-prv2); does not AddAct.

## PART 14 - DIRECTION

  * Garbage-collect object-only presence when N holds no remaining
    hosts for the actId.
  * Account UI: show object-only providers separately from sign-in
    providers; "Attach as a login" affordance (→ AttachProvider).
  * MoveInstall (export host H → import N) as an alternative to
    first-time foreign install when the portable host already exists.
  * Object-only third tomcat ([Identifiers](../platform/Identifiers.md) PART 8 Direction) as a
    dedicated host-only sim node, not required for v1 of this spec
    (prv2 as N is enough).
)
