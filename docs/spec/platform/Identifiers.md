# Identifiers

This document is the identity spec: the three-level ownId / actId / usrId
model, and actId fingerprint versioning.

  - ownId  - the account's OPERATIVE cryptographic identity. Rotatable.
             The top of the day-to-day authority chain. Never seen by
             the user; appears only in a few binding records, never in
             objects.
  - actId  - the account's PERMANENT identity. Immutable. Stamped into
             every object. Visible in the navigator, never in ordinary
             apps. A fingerprint of the genesis public key
             ([Security](Security.md) PART 3.3).
  - usrId  - a login handle "<localname>@<appId>". Mutable label.

Splitting name (actId / genesis key) from operation (ownId / ownership key)
lets an account rotate its operative key — and evict a dishonest provider —
without rewriting the identifier baked into stored objects.

[Login protocol](../apps/Login-Protocol.md) replicates the identity layer
across providers; membership carries the ownId binding. [Desktop](../apps/Desktop.md)
keys on actId, which never changes here.

## PART 1 - PURPOSE AND SCOPE

1.1  The problem this solves

  In the two-level model, actId = fingerprint(root public key), and that
  ONE key both NAMES the account (defines the actId) and OPERATES it
  (issues delegations to providers). The two jobs are fused, with one
  hard consequence spelled out in [Security](Security.md) PART 5.1: the root
  key can NEVER rotate, because rotating it would change the actId - the
  identifier already written into every stored object - and force a
  rewrite of all of the user's rows.

  That fusion is exactly what makes a dishonest home provider
  unevictable. In Phase 1 the root key is server-held ([Security](Security.md)
  PART 5.4, T1), so a home provider can renew its own delegation forever;
  the only key that could disown it is the root key, which cannot rotate
  without discarding the account's identity.

1.2  What this provides

  O1  ROTATABLE OPERATIVE KEY. Separate the "name the account" job from
      the "operate the account" job into two keys. The name-defining key
      (GENESIS, PART 4) never rotates and defines the immutable actId.
      The operating key (OWNERSHIP, PART 5) defines a rotatable ownId and
      issues delegations. Rotating the ownId re-keys the account's
      operative authority WITHOUT touching the actId or any object.

  O2  CRYPTOGRAPHIC EVICTION. Because delegations chain to the ownId, a
      new ownId (a rebind, PART 10) invalidates every delegation a
      dishonest provider self-issued under the old ownId, once the new
      binding has propagated (PART 12). This is the eviction that the
      cooperative-only removal of [Login protocol](../apps/Login-Protocol.md) PART 11 could
      not achieve.

  O3  STABLE OBJECTS. Objects keep carrying actId, exactly as today
      ([Security](Security.md) PART 3.2). Rotation changes only the binding
      records (PART 6), of which there are few - one per Login peer -
      never the objects, of which there are many.

  O4  UNIVERSAL TRUST WITHOUT A REGISTRY. The actId->ownId binding is
      SELF-AUTHENTICATING: it is signed by the genesis key, and actId is
      the fingerprint of the genesis public key, so ANY provider can
      verify the current ownId from the actId alone, with no third-party
      lookup - preserving [Security](Security.md) PART 6.3's no-account-registry
      property even for providers where the user never logs in (PART 8).

1.3  Non-goals

  * A user-visible ownId. The ownId is machinery; the user never sees or
    types it (PART 2.4).
  * Rotating the actId. The actId is permanent by construction (PART 4).
    Eviction rotates the ownId, not the actId.
  * Strong consistency of the binding across providers. Rotation takes
    effect as the new binding propagates; eventual consistency, as in the
    sibling specs (PART 12).
  * Replacing 2FA or per-app data redundancy (unchanged; see the sibling
    specs).

1.4  Terminology

  * genesis key   - a cold Ed25519 key pair, held by the USER offline
                    (PART 7). Defines the actId and signs bindings. Used
                    only at account creation and at rebind/recovery.
  * actId         - fingerprint(genesis public key). Permanent account
                    identity, stamped into every object (PART 4).
  * ownership key - a hot Ed25519 key pair. Defines the ownId and issues
                    delegations. Server-held in Phase 1 (PART 5).
                    Rotatable.
  * ownId         - fingerprint(ownership public key). The account's
                    current operative identity (PART 5).
  * binding       - the genesis-signed record "actId -> current ownId"
                    (PART 6). Versioned; latest wins.
  * usrId         - a login handle, as in [Login protocol](../apps/Login-Protocol.md) PART 3.2.
  * rebind        - minting a new ownership key and publishing a new,
                    higher-versioned binding (PART 10). This is a
                    re-key / eviction, NOT a change of actId.

## PART 2 - THE THREE IDENTIFIERS

2.1  ownId - the operative identity (rotatable)

  ownId = fingerprint(ownership public key), derived the same way an
  actId is derived from a public key ([Security](Security.md) PART 3.3):

      ownId = Base64Encoder.encode( SHA-256( ownPublicKeyBytes )[0 .. 24) )

  It is the top of the account's DAY-TO-DAY authority chain: the
  ownership private key signs the delegations that let providers act
  (PART 11). It is deliberately EASY TO CHANGE: a rebind mints a new
  ownership key pair and a new ownId, leaving the actId untouched
  (PART 10).

  It is stored only in the binding records (PART 6) - "a few places" -
  and NEVER embedded in objects.

2.2  actId - the permanent identity (immutable)

  actId = fingerprint(genesis public key) (PART 4). It is the identifier
  written into every DomId and therefore into every obj/lnk row
  ([Domatar](../Domatar.md) PART 2; [Security](Security.md) PART 3.2), and it NEVER
  changes for the life of the account. It is provider-free.

  This is the SAME slot and the SAME shape as today's actId; what changes
  is only WHICH key it fingerprints (the cold genesis key, not the hot
  operating key). Existing objects need no change (PART 3.2).

2.3  usrId - the login handle (unchanged)

  usrId keeps its [Login protocol](../apps/Login-Protocol.md) PART 3.2 meaning and rules: a mutable
  "<localname>@<appId>" label that routes verification to an app central
  host and authorizes nothing on its own.

2.4  Visibility ladder (who sees what)

    usrId   - the user types it and sees it everywhere (login).
    actId   - the user can see it in the NAVIGATOR if they look; it never
              appears in the user interface of ordinary apps.
    ownId   - the user REALLY never sees it; only a programmer doing
              deliberate investigation would. It is pure machinery.

  This ladder is intentional: the durable public handle (actId) is the
  one baked into objects, while the powerful, rotatable key identity
  (ownId) is kept out of sight precisely so it can be replaced quietly.

2.5  Why three, not two

  The two jobs that [Security](Security.md) PART 5.1 fused - NAME and OPERATE -
  have opposite requirements. Naming wants permanence (it is in every
  object). Operating wants replaceability (to evict a bad provider).
  A single key cannot be both permanent and replaceable, so the model
  splits it: actId is the permanent NAME, ownId is the replaceable
  OPERATOR, and a genesis-signed binding (PART 6) ties them together.

## PART 3 - RELATIONSHIP TO [Security](Security.md) (WHAT THIS REVISES)

3.1  The single root key becomes two keys

  [Security](Security.md) PART 5.1 defines ONE "account root key" that both
  defines the actId and issues delegations, and states it never rotates.
  This spec REPLACES that single key with two:

    GENESIS key   (cold)  - takes over "defines the actId" and adds "signs
                            the actId->ownId binding". Never rotates. User-
                            held offline (PART 7).
    OWNERSHIP key (hot)   - takes over "issues delegations". IS rotatable.
                            Server-held in Phase 1 (PART 5), exactly where
                            [Security](Security.md) PART 5.4 puts the root key
                            today.

  The server-held operating key is the OWNERSHIP key (rotatable,
  identified by ownId). A cold GENESIS key sits above it to own the
  actId and authorize re-keys.

3.2  Objects are unaffected

  The actId slot in every obj/lnk row is unchanged in shape and meaning
  (PART 2.2). An account whose actId is fingerprint of a single
  operating key is upgraded by DECLARING that key to be the genesis
  key and publishing an initial binding to a freshly minted ownership
  key (PART 10.5). No object is rewritten. This is the whole point (O3).

3.3  The credential chain gains one link

  [Security](Security.md) PART 6.1's chain
    actId -> RootPubKey -> Delegation -> provider key -> OriginSig
  becomes (PART 11)
    actId -> GenesisPubKey -> Binding -> OwnPubKey -> Delegation
          -> provider key -> OriginSig
  i.e. one extra, self-authenticating hop (the genesis-signed binding)
  is inserted between the actId and the operating key.

3.4  Phases still apply

  Phase 1 keeps the OWNERSHIP key server-held (T1 still holds for signing
  providers between rebinds; PART 5.3). The GENESIS key is user-held from
  the start (it is the [Security](Security.md) PART 15 "user-held offline
  recovery key", promoted to a first-class part of the model). Phase 2's
  device-held operating key ([Security](Security.md) PART 15) is then the
  ownership key on the device, orthogonal to the genesis key.

## PART 4 - THE GENESIS KEY AND actId

4.1  Derivation

  At account creation the user mints a GENESIS Ed25519 key pair. The
  actId is the fingerprint of its public key, using the identical formula
  as [Security](Security.md) PART 3.3 (fingerprint algorithm version 1;
  [Identifiers](Identifiers.md)):

      actId = Base64Encoder.encode( SHA-256( genesisPublicKeyBytes )[0 .. 24) )

  So the actId is a fixed 32-character string over [0-9 A-Z _ a-z ~],
  provider-free, with no '.', '@' or '-'. It is safe as a DomId component
  and in a host id suffix ([Login protocol](../apps/Login-Protocol.md) PART 5.2). Self-
  certification is version-aware via act.FpVersion /
  AccountKeys.fingerprintsTo (v1 unchanged).

4.2  actId is arbitrary in USE but anchored in ORIGIN

  From the point of view of objects and apps the actId is just an opaque,
  permanent tag - "arbitrary", carrying no operational key. But it is NOT
  arbitrarily CHOSEN: it is the fingerprint of the genesis key. That
  anchoring is what makes the binding self-authenticating (PART 6.3) and
  makes squatting infeasible (4.3). The alternative - a genuinely
  arbitrary, non-key-derived actId defended only by first-come-first-
  served registration - is possible but weaker; it is discussed and
  rejected in PART 16.4.

4.3  No squatting, no allocator

  Because actId = fingerprint(genesis public key), the uniqueness and
  anti-forgery arguments of [Security](Security.md) PART 3.4 carry over intact:

    * A malicious host cannot issue YOUR actId to a different user: to
      produce a valid binding for actId (PART 6) one must sign with the
      genesis key whose fingerprint IS that actId, which the attacker
      does not hold. A binding not so signed is rejected by every
      verifier (PART 11).
    * Therefore first-come-first-served registration is NOT load-bearing
      for identity (as in [Security](Security.md) PART 3.4): even if two
      providers both claim to host actId X, only the one presenting a
      genesis-signed binding is believed.
    * An account is globally unique the moment its genesis key pair
      exists, minted offline, with no registry.

  The [Security](Security.md) PART 3.4 key-entropy WARNING applies unchanged:
  all of this assumes the genesis key is generated with a vetted CSPRNG.

## PART 5 - THE OWNERSHIP KEY AND ownId

5.1  Role

  The OWNERSHIP key is the operating key: its private half signs
  DELEGATIONS authorizing providers to act for the account
  ([Security](Security.md) PART 6.2), and ownId = fingerprint(its public key)
  is the identity those delegations chain to (PART 11). It is what
  [Security](Security.md) PART 5.1 calls the root key, minus the identity-naming
  job (which moved to genesis) and minus the never-rotate constraint.

5.2  Custody (Phase 1)

  In Phase 1 the ownership private key is SERVER-HELD on the account's
  SIGNING providers (5.4), encrypted at rest, in act.OwnPrvKey.
  A signing provider is a complete, independent copy that can verify
  and sign without the user's password
  ([Login protocol](../apps/Login-Protocol.md) PART 4.1).

5.3  T1 persists between rebinds, but is now escapable

  Because a signing provider holds the ownership private key, it can
  still act as the account and renew its own delegation (T1,
  [Security](Security.md) PART 5.4). What is NEW is the escape hatch: the user
  can REBIND to a fresh ownership key (PART 10), after which the dishonest
  provider's copy of the OLD ownership key is worthless - its self-issued
  delegations no longer chain to the current ownId. T1 is thus downgraded
  from "permanent" to "until the next rebind propagates".

5.4  Signing providers vs object-only providers

  Two provider roles, distinguished by what they hold:

    SIGNING provider (custodial) - holds the ownership PRIVATE key. Can
        issue/renew delegations and operate the account autonomously.
        These are the interactive-login home providers of
        [Login protocol](../apps/Login-Protocol.md). Keep their number small; each is a T1
        exposure until a rebind.

    OBJECT-ONLY provider - hosts the user's OBJECTS and a Login peer with
        the binding (PART 8), and holds a DELEGATION so it can act on
        those objects, but does NOT hold the ownership private key. It can
        VERIFY (read the binding, check chains) and OPERATE its delegated
        objects, but cannot govern the account or renew its own
        delegation.

  Consequence for eviction (PART 10.4):
    * An object-only provider is evicted simply by NOT renewing its
      delegation - it never had the power to renew itself. This is clean
      cryptographic eviction with no rebind.
    * A signing provider is evicted by a REBIND, which invalidates the old
      ownId its self-renewals depend on.

## PART 6 - THE BINDING RECORD (actId -> ownId)

6.1  Shape

  The binding is the genesis-signed statement of the account's CURRENT
  operating key. It is self-contained, so the whole chain verifies from
  the actId alone (PART 11):

    Binding {
      ActId         : <actId>                    (= fingerprint(GenesisPubKey))
      GenesisPubKey : <base64 Ed25519 public key>   (32 bytes)
      OwnId         : <ownId>                     (= fingerprint(OwnPubKey))
      OwnPubKey     : <base64 Ed25519 public key>   (32 bytes)
      Version       : <monotonic long>           (latest wins; often ms epoch)
      NotBefore     : <ms since epoch>           (issuance time)
      GenesisSig    : Ed25519( GenesisPrvKey,
                        CanonicalJson(Binding excluding GenesisSig) )
                      // signed bytes are the
                      // CanonicalJson encoding of the binding map with
                      // GenesisSig omitted (not a hand-concatenated field list).
    }

6.2  Where it lives

  A copy of the binding lives in EVERY Login peer's membership directory
  ([Login protocol](../apps/Login-Protocol.md) PART 6/7), i.e. on every provider that hosts
  the account's identity layer - which, by the PART 8 invariant, is every
  provider that hosts any of the account's objects. It replicates by the
  same LWW machinery as the membership (PART 12).

  The binding is stored on the account's act row (GenesisPubKey /
  OwnPubKey / BindingVersion / BindingNotBefore / BindingSig) and
  travels with each signed message in Head.Sec.Binding. Login-peer
  storage and replication are [Login protocol](../apps/Login-Protocol.md).

6.3  Why it is self-authenticating

  A verifier that knows only the actId can validate a binding with NO
  external lookup:
    1. Check GenesisPubKey fingerprints to ActId
       (actId == SHA-256(GenesisPubKey)[0..24), PART 4.1). This proves the
       binding was issued under the RIGHT genesis key.
    2. Check GenesisSig over (ActId,OwnId,OwnPubKey,Version) with
       GenesisPubKey. This proves the genesis-key holder authorized THIS
       ownId.
    3. Check OwnPubKey fingerprints to OwnId.
  No registry, no third party. This is what lets even an object-only,
  no-login provider trust a re-key (O4, PART 8).

6.4  Version is the anti-rollback defence

  A dishonest provider might replay an OLD binding (pointing at a revoked
  ownId it still controls). Version defeats this: verifiers and replicas
  keep only the HIGHEST-Version binding for an actId (PART 12), and a
  rebind always increments Version. A replayed old binding loses the LWW
  comparison and is discarded. The residual exposure is only the
  propagation window (PART 10.6, PART 16.2).

## PART 7 - GENESIS KEY CUSTODY (WHO KEEPS IT)

7.1  Not any provider - and that is the point

  The genesis key MUST live outside every provider's routine reach. If a
  provider held it, that provider could forge bindings and re-key the
  account at will, recreating T1 one level up and destroying eviction
  (O2). So the custodian is the USER. This is the single non-negotiable
  custody rule of the model.

7.2  It is a COLD key

  The genesis private key is used at only two moments: account CREATION
  (PART 4) and REBIND/RECOVERY (PART 10). It is never used for normal
  login or normal message signing - those use passwords (Layer 1) and the
  ownership/provider keys (Layer 2). So its custody can be offline and
  mildly inconvenient; day to day it sits untouched.

  Note this is NOT the [Security](Security.md) Phase 2 "device signs every
  message" model. The device (or paper) holds only the COLD genesis key,
  consulted rarely; the browser still stores nothing and the signing
  providers still do all routine signing. "No permanent data in the
  browser" ([Login protocol](../apps/Login-Protocol.md) PART 4.1) is preserved.

7.3  Custody options

  a. RECOVERY DEVICE (recommended primary). The genesis key lives in the
     user's phone / passkey / hardware authenticator (WebAuthn-style),
     platform-synced for durability, unlocked by biometric for a rebind.
     No provider holds it. Fits the stated preference that client storage
     "makes sense for phones, not browsers".

  b. OFFLINE BACKUP CODES / RECOVERY PHRASE (recommended fallback).
     Generated at sign-up, shown once, kept on paper or in a password
     manager, imported transiently only during a rebind. Zero
     infrastructure. The cost is the wallet failure mode: lose it with no
     copy and the strong recovery path is gone (7.5).

  c. THRESHOLD-SPLIT ACROSS THE USER'S PROVIDERS (alternative, "keep
     nothing"). Secret-share the genesis key M-of-N across the account's
     providers so no single one holds it and a threshold must co-sign a
     rebind. Uses the redundancy as custody. Caveats: a colluding
     majority could forge; evicting a share-holding provider needs
     re-sharing without it; and it introduces threshold crypto the
     project otherwise avoids. Recorded as an option, not the default.

  RECOMMENDATION: (a) as primary custodian with (b) as the lost-device
  fallback - the belt-and-suspenders pattern of mainstream platforms -
  and (c) offered only to users who refuse to hold anything.

7.4  Two-tier recovery

  The genesis key upgrades recovery from soft to strong:

    STRONG (genesis key present) - the user signs a new, higher-versioned
        binding directly (PART 10). Any provider verifies it against the
        actId with no lookup (6.3); it propagates safely to every Login
        peer, including object-only ones; it cryptographically evicts a
        dishonest signing provider. This is the clean path.

    SOFT (genesis key absent) - a provider MAY still let the user
        re-establish local control via its OWN proof of ownership
        (recovery email, support, backups). But a soft-recovered control
        canNOT produce a genesis-signed binding, so it does NOT propagate
        with authority to other providers and canNOT evict a provider
        holding an old ownership key. It is a per-provider convenience,
        not a re-key.

  The genesis key is precisely what makes recovery universal and
  propagating rather than local and advisory.

7.5  The unavoidable trade

  The power to rebind and the power to lock oneself out are the same
  power. Losing the genesis key with NO backup means the account keeps
  working (existing delegations stay valid) but can never be re-keyed -
  if an ownership key is later compromised, there is no strong recovery.
  This finality is the price of "no provider can forge your identity" and
  MUST be stated in user-facing wording at sign-up.

## PART 8 - THE LOGIN-PEER-EVERYWHERE INVARIANT

8.1  Invariant

  EVERY provider that hosts ANY of the account's objects MUST also host
  a verifiable binding and a membership peer for that actId. This holds
  even where the user has NO interactive login (an object-only provider,
  PART 5.4).

  On a login home: login-<actId>-<prvId> ([Login protocol](../apps/Login-Protocol.md) PART 5)
  plus the domatar substrate, carrying the current binding (PART 6).

  On an object-only host there is NO act row. HostProvision
  ([Foreign Provider](../install/Foreign-Provider.md)) is the install-time way the
  peer appears: binding and delegation live on the domatar binding
  object (domatar-<actId>-<prvId>), and membership holds
  `peer-prv-<prvId>` (IsLoginHome=False).

8.2  Why

  The future is full of providers where the user keeps objects - because
  the provider has the right infrastructure for an app - but does not log
  in. Such a provider still must be able to answer "who is the REAL owner
  of these objects right now?" after a rebind. The Login peer is how it
  learns: rebinds propagate to it as a new binding version (PART 12), so
  it always knows the current ownId even with no user session and no
  ownership private key.

8.3  What the Login peer holds on an object-only provider

  Only PUBLIC, verifiable data: the binding (genesis-signed), the
  membership index ([Login protocol](../apps/Login-Protocol.md) PART 7), and the delegation
  issued TO that provider. Stored on the domatar substrate binding /
  membership objects when there is no act row (HostProvision). It does
  NOT hold the ownership private key (PART 5.4). So installing objects
  on a new provider extends the binding's reach without extending the
  T1 key exposure.

8.4  Interaction with sign-in

  An object-only provider offers no interactive sign-in (no local act
  row / password) but is a full participant in binding replication. The
  set of providers a user can sign in THROUGH ([Login protocol](../apps/Login-Protocol.md)
  PART 9) is therefore a subset of the providers hosting a membership
  peer. HostProvision does not create a sign-in door; AttachProvider
  does.

## PART 9 - ACCOUNT CREATION (INITIAL BINDING)

  At sign-up:
    1. Mint the GENESIS key pair; derive actId = fingerprint(genesis pub)
       (PART 4). Hand the genesis key to the user's chosen custody
       (PART 7); show backup codes.
    2. Mint the OWNERSHIP key pair; derive ownId (PART 5).
    3. Build the initial Binding (Version = now) and sign it with the
       genesis key (PART 6).
    4. Provision the ownership PRIVATE key to the first signing provider
       (PART 5.2); issue that provider a delegation under the ownership
       key ([Security](Security.md) PART 6.2).
    5. Write the binding into that provider's Login peer (PART 8).

  From here the account operates entirely through ownId + delegations; the
  genesis key goes cold until a rebind (PART 7.2).

## PART 10 - REBIND (RE-KEY / EVICTION)

10.1  When

  The user rebinds to evict a compromised or dishonest SIGNING provider
  (O2), or as precautionary key rotation. It requires the genesis key
  (PART 7) and is therefore a rare, user-authorized event.

10.2  Procedure (strong path)

    a. With the genesis key available, mint a NEW ownership key pair ->
       newOwnId.
    b. Build a new Binding with Version = now (strictly greater than the
       current one), signed by the genesis key (PART 6).
    c. Provision the NEW ownership private key to the signing providers
       the user STILL trusts (not the evicted one).
    d. Re-issue delegations to those trusted providers, signed by the new
       ownership key.
    e. Propagate the new binding to every Login peer (PART 12), including
       object-only providers (PART 8).
    f. Stop renewing the evicted provider's delegation; optionally ask it
       to delete its replica (as in [Login protocol](../apps/Login-Protocol.md) PART 11.2).

10.3  Effect

  Once the new binding has propagated to a verifier, that verifier keeps
  only newOwnId (PART 6.4). Any delegation the evicted provider self-
  issued under the OLD ownership key no longer chains to the current ownId
  and is REJECTED. The evicted provider still holds stale object copies
  and the old ownership key, but cannot get up-to-date verifiers to accept
  it, and - lacking the genesis key - cannot rebind to reassert itself.

10.4  Object-only providers need no rebind to evict

  To drop an object-only provider, simply stop renewing its delegation
  (PART 5.4); it cannot renew itself. Rebind is reserved for evicting a
  provider that holds the ownership private key.

10.5  Accounts minted with a single operating key

  An account whose actId is fingerprint of a single root key is
  upgraded in place: DECLARE that key to be the genesis key
  (its fingerprint already equals the actId), then run 10.2.b-e once to
  publish an initial binding to a fresh ownership key. No object changes
  (PART 3.2). After this the old root key is treated as cold genesis
  custody and SHOULD be moved to PART 7 storage.

10.6  Asymmetry in the user's favour

  Only the genesis holder (the user) can rebind; a dishonest signing
  provider cannot (no genesis key), so it can neither lock the user out
  nor block its own eviction. The only residual is timing: eviction is
  effective per-verifier only after the new binding reaches it
  (PART 16.2).

## PART 11 - VERIFICATION WITH THE THREE-LEVEL CHAIN

11.1  The extended credential chain

  A verified message is checked bottom-up against the one thing inherently
  trusted about the account - the actId:

     actId               the account identity (in the Origin block),
                         = fingerprint(GenesisPubKey)
       ^  actId == fingerprint(GenesisPubKey)        [self-certifying]
     GenesisPubKey        carried in the Binding
       ^  signs
     Binding (Version)    "genesis authorizes OwnId as current, at Version"
       ^  names OwnPubKey; ownId == fingerprint(OwnPubKey)
     OwnPubKey            the current operating key, carried in the
                         Delegation
       ^  signs
     Delegation           "OwnId authorizes provider P until T"
       ^  authorizes P
     provider key of P    from the signed directory host record
       ^  signs
     OriginSig            P's signature on this message

11.2  What travels with the message

  The message carries the Delegation (which carries OwnPubKey) and the
  Binding (which carries GenesisPubKey and Version), exactly as
  [Security](Security.md) PART 6.1 carries the delegation today - one extra
  self-contained object. The only external fetch remains provider P's key
  from the directory (already cached for routing). No account lookup.

11.3  The freshness rule

  A verifier accepts a Binding only if its Version is >= the highest
  Version it has seen for that actId (PART 6.4). It caches the highest
  Version per actId. A message whose delegation chains to an ownId from a
  SUPERSEDED binding is rejected. This is the sole state a verifier keeps
  about an account, and it is monotonic and self-correcting: a newer
  binding always wins.

11.4  Compatibility

  Everything [Security](Security.md) PART 7/8/9 does (origin signature, path
  provenance, TLS) is unchanged; the binding hop is inserted purely at the
  identity-anchoring step (PART 6.3) and does not alter per-hop or
  per-message signing.

## PART 12 - REPLICATION OF THE BINDING

12.1  Rides on the membership replication

  The binding is replicated by the SAME machinery as the membership
  ([Login protocol](../apps/Login-Protocol.md) PART 10): stored in each Login peer, compared
  by a monotonic Version, latest-writer-wins, propagated best-effort on
  change and pulled on display. No new transport.

12.2  Merge rule

  The merge key is ActId; the winner is the binding with the highest
  Version (PART 6.4). Because a binding is genesis-signed and self-
  authenticating (PART 6.3), a replica ACCEPTS a higher-versioned binding
  from ANY peer only after verifying the genesis signature and the actId
  fingerprint - so a dishonest peer cannot inject a forged binding (it
  lacks the genesis key), and cannot roll one back (Version is monotonic).

12.3  Propagation and the eviction window

  A rebind is effective at a given verifier once the new binding has
  reached it. Propagation is eventual ([Login protocol](../apps/Login-Protocol.md) PART 10.1);
  to bound the window, a rebind SHOULD fan-out-push the new binding to all
  reachable Login peers immediately (as PART 10.4 of the sibling does for
  membership), with display-time pulls repairing any that were missed.

## PART 13 - AUTHORIZATION SUMMARY

  Requires the GENESIS key (user, offline)   Requires only an ownId
  -----------------------------------------   delegation (a provider)
  Mint / rotate the ownId (rebind, PART 10)   Issue/renew per-provider
  Sign a new binding (PART 6)                 delegations to itself
  Evict a signing provider (PART 10.3)        Sign origin messages (PART 11)
                                              Operate delegated objects

  Requires only membership ownership (Context.actId owner-match, as
  [Login protocol](../apps/Login-Protocol.md) PART 10.5): read the current binding; accept a
  higher-versioned, genesis-verified binding push; derive the current
  ownId.

  Note the deliberate gap: providers can OPERATE (right column) but cannot
  RE-KEY (left column). Re-keying is the user's alone, gated by the
  genesis key.

## PART 14 - IMPLEMENTATION SURFACE (indicative)

Names indicative; follow [Code Style](CodeStyle.md).

  The binding lives on the act row and in Head.Sec; ActManagerImpl /
  OwnIdsRebind perform rebind; Msg.verifyCredentialChain checks the
  binding hop + local Version freshness. MembershipImpl GetBinding /
  SetBinding replicate it ([Login protocol](../apps/Login-Protocol.md)).

  Binding record: a (login, binding)
  obj on each membership replica login-<actId>-<prvId>, alongside the
  membership container. Attrs carry GenesisPubKey, OwnId, OwnPubKey,
  Version, NotBefore, GenesisSig (PART 6.1). MembershipImpl GetBinding /
  SetBinding + MembershipFanout.publishBinding complete the multi-provider
  binding the single-provider OwnIds plan deferred.

  com.domatar.act.ActManagerImpl gains:
      MintGenesis / DeriveActId        - creation (PART 9).
      MintOwnership / DeriveOwnId      - creation and rebind (PART 10.2).
      SignBinding (genesis)            - offline/at-device; the server
                                         side only STORES the result.
      Rebind                           - drive PART 10.2 (single-provider
                                         subset via OwnIdsRebind + ActWui
                                         Action=Rebind).

  Verifier (Msg.verifyCredentialChain): binding hop (PART 11.1) and the
  per-actId highest-Version check against the local act row (PART 11.3
  single-provider form).

  Delegation: unchanged in shape ([Security](Security.md) PART 6.2), but now
  signed by the OWNERSHIP key and validated against the ownId named in the
  current binding rather than directly against the actId.

## PART 15 - LOCAL SIMULATION

Building on the two-provider sim ([Login protocol](../apps/Login-Protocol.md) PART 15):

  * At seed time, generate a genesis key per account, derive its actId,
    generate an ownership key, and write an initial binding (Version = t0)
    into each Login peer.
  * Keep the genesis PRIVATE key OUT of the tomcat key stores - store it
    in a sim-only "user vault" file to stand in for the recovery device
    (PART 7). Only the ownership private key goes into act.OwnPrvKey on
    signing providers.
  * Exercise rebind: from the user vault, mint a new ownership key, sign a
    binding at Version = t1, provision it to prv1 only, fan-out the new
    binding to both Login peers, and confirm that a message signed under
    prv2's OLD delegation is now REJECTED once prv2's verifier has the t1
    binding (PART 10.3, PART 11.3).
  * Add an object-only provider (a third tomcat with a Login peer and a
    delegation but no act row) and confirm it receives the t1 binding and
    correctly identifies the new ownId with no login (PART 8).

  As in the sibling sim, TLS is bypassed; the ownership-key transfer of
  PART 10.2.c travels over plain http between tomcats. Sim only.

## PART 16 - SECURITY CONSIDERATIONS AND RESIDUAL RISKS

16.1  What the model buys

  * Cryptographic eviction of a signing provider via rebind (O2, PART 10),
    which cooperative removal ([Login protocol](../apps/Login-Protocol.md) PART 11) could not
    do - achieved WITHOUT rewriting objects (O3).
  * Universal, registry-free trust in the current ownId, even on no-login
    providers (O4, PART 8), preserving [Security](Security.md) PART 6.3.

16.2  Propagation window (residual)

  A rebind is effective at a verifier only after the new binding reaches
  it (PART 12.3). During the window a not-yet-updated verifier may still
  accept the evicted provider's old-ownId delegations. Fan-out on rebind
  bounds this; it is the same eventual-consistency caveat as the sibling
  specs, now the ONLY residual for signing-provider eviction rather than a
  permanent inability.

16.3  Genesis key is the crown jewel (residual)

  Compromise of the genesis key is catastrophic: the holder can re-key the
  account and lock the legitimate user out. This concentrates risk in
  PART 7 custody, which is why the genesis key is cold, offline, and never
  on a provider. Loss with no backup is unrecoverable for re-keying
  (PART 7.5). These are accepted, clearly-communicated properties.

16.4  Why not a purely arbitrary actId + FCFS (rejected)

  One could make actId a genuinely arbitrary tag (not key-derived) and
  defend ownership by first-come-first-served registration. Rejected
  because:
    * It reintroduces an allocator/registry and the squatting race
      [Security](Security.md) PART 3.4 eliminated - a malicious host could
      register your actId to someone else, and honest providers would have
      no self-contained way to tell who is right.
    * Bindings could not be self-authenticating (6.3): trust would again
      require a lookup, reversing [Security](Security.md) PART 6.3.
  Anchoring actId to the genesis key keeps the actId permanent AND makes
  ownership unforgeable by construction, at no cost to objects (they still
  carry an opaque tag).

16.5  Ownership-key compromise between rebinds

  A stolen ownership private key lets the thief act until a rebind
  propagates (PART 10.3). This is strictly better than the two-level model
  (where the equivalent theft was permanent, since the key could not
  rotate). Detection-to-rebind latency is the exposure.

16.6  Layering with 2FA (unchanged)

  2FA ([Login protocol](../apps/Login-Protocol.md) PART 12) sits in the authentication layer
  and is orthogonal to all three identifiers here. It hardens "may this
  person start a session"; it does not sign bindings or delegations.

## PART 17 - TODO / RECONCILIATION WITH SIBLING SPECS

  - Reconcile terminology in [Login protocol](../apps/Login-Protocol.md) and
    [Desktop](../apps/Desktop.md) with this spec:
      * "the account root key" ([Login protocol](../apps/Login-Protocol.md) PART 4.1, 8.3,
        11.3, 16) -> "the ownership key"; note it is now ROTATABLE.
      * "binds the usrIds to one actId" phrasing -> "binds the usrIds to
        one ownId, anchored to the permanent actId".
      * PART 11.3's "cannot evict a dishonest provider" -> "cooperative
        removal alone cannot; rebind ([Identifiers](Identifiers.md) PART 10) can, for
        signing providers".
  - Replicate the binding in the membership container as a first-class
    row (PART 14) and add GetBinding/PutBinding to the sim seed.
  - Decide genesis custody default for the product (PART 7.3): recovery
    device + backup codes recommended; wire the sign-up UX for showing
    codes and the "loss is final" warning (PART 7.5).
  - Formalize PART 10.5 as a one-shot install step if any dumps still
    lack a binding.
  - Consider a short signed "revocation hint" a rebind can push so that
    verifiers proactively drop a superseded ownId cache entry, shrinking
    the PART 16.2 window.

# END OF SPEC

## ActId versioning

This section specifies how Domatar records WHICH fingerprint algorithm
produced a given actId (and ownId), so the derivation algorithm can be
changed in the future WITHOUT rewriting the identifiers already minted
under the old algorithm.

Today there is exactly ONE algorithm (call it "v1"):

    actId = Base64Encoder.encode( SHA-256(rootPubKey)[0 .. 24) )   → 32 chars

([Security](Security.md) PART 3.3; [Identifiers](Identifiers.md) PART 2.1;
com.domatar.crypto.AccountKeys.deriveId(..., 1)). This spec does NOT
change that algorithm. It introduces the machinery to add a "v2" later
with a controlled, node-by-node rollout and no mass re-identification.

It sits alongside, and defers to, the existing identity specs:

  - [Security](Security.md)        : self-certifying actIds, the account root
                               key, the credential chain.
  - [Identifiers](Identifiers.md)          : actId = fingerprint(genesis pub);
                               ownId = fingerprint(ownership pub); the
                               actId is PERMANENT and stamped into every
                               object.
  - [Login protocol](../apps/Login-Protocol.md)  : provider-qualified replica hosts
                               <app>-<actId>-<prvId>; peer records carry
                               FpVersion; host parsing is width-independent.
  - [Desktop](../apps/Desktop.md) : keys on actId; unaffected in
                               mechanism.

The parenthetical "(v1)" everywhere refers to the single shipped
algorithm above. Implementation refinements (KD1–KD7) are recorded in
PART 12 of this section.

## PART 1 - PURPOSE AND SCOPE

1.1  The problem this solves

  An actId is self-certifying: it IS the fingerprint of a public key, and
  every verifier RE-DERIVES the fingerprint from the presented key and
  compares it to the stored actId ([Identifiers](Identifiers.md) PART 8; crypto.Binding,
  crypto.Delegation). That check hard-codes ONE derivation:
  SHA-256[0..24) → Base64Encoder → 32 chars. If we ever need a different
  hash, a different truncation length, or a different encoding, every
  such check would silently reject every existing account, because the
  presented key no longer fingerprints to the stored id under the new
  rule.

  The actId is also PERMANENT by design ([Identifiers](Identifiers.md) PART 1): it is
  written into the ObjDb rows of every object the account ever created,
  into provider-qualified host names (login-<actId>-<prvId>,
  desktop-<actId>-<prvId>, navigator-<actId>-<prvId>), into peer/binding
  records, and into cookies. It CANNOT be recomputed in place.

  Therefore the algorithm must be VERSIONED, not upgraded:

    - Each account's actId keeps forever the algorithm that minted it.
    - The verifier learns the version and applies the matching algorithm.
    - New accounts are minted under the current default version.
    - A future algorithm change is "add v2; accept v1 and v2; default new
      mints to v2" — never "recompute existing actIds".

1.2  Analogy to act.Encryption (and where it differs)

  The act table already versions the password hash via the `Encryption`
  column: `encrypt(pwd, encryption)` dispatches on an integer
  (ActDb.encrypt; encryption==1 → Domatar-Base64 SHA-1). A new password
  scheme is "add encryption==2; verify against the stored code; optionally
  re-hash on next successful login."

  actId versioning mirrors the DISPATCH idea: store a small integer,
  branch on it, apply the right algorithm. It DIFFERS in one crucial way:

    - A password hash is an opaque SIDE field. It can be upgraded in place
      on next login (same ActId/UsrId, new hash) — see PART 6.2.
    - An actId IS the identity. It can NEVER be upgraded in place, because
      that would change the identifier stamped across the whole system.
      The version of a SPECIFIC account is fixed at creation for life.

  So: new accounts adopt the new version; existing accounts keep theirs.
  Re-identification (old actId → new actId) is a separate, rare,
  explicitly-triggered migration (PART 9), NOT the normal path.

1.3  In scope

  - A stored actId-version code on each account (PART 3).
  - A single derivation/validation chokepoint that takes a version
    (PART 4).
  - Version-aware self-certification in the credential chain (PART 5).
  - Rules for minting, reading, and routing by version (PART 6).
  - Making replica-host parsing version-tolerant (PART 7).
  - A conservative default and a rollout discipline for adding v2
    (PART 8).

1.4  Out of scope

  - Choosing a specific v2 algorithm. v2 is hypothetical here; this spec
    only guarantees v2 can be added safely.
  - Changing v1. The shipped algorithm and all existing 32-char actIds
    remain byte-for-byte valid.
  - ownId rotation mechanics ([Identifiers](Identifiers.md)). ownId versioning follows
    the SAME algorithm registry as actId (PART 4.4), but rotation policy
    is unchanged.

## PART 2 - TERMINOLOGY

  fingerprint algorithm
      A total function (pubKeyBytes → id string). v1 is
      SHA-256[0..24) → Base64Encoder → 32 chars.

  actId version (fpVersion)
      A small positive integer naming the fingerprint algorithm that
      minted an actId. v1 == 1. Stored per account (PART 3).

  default version
      The version new accounts are minted under. Today == 1. Bumped only
      by the rollout in PART 8.

  self-certification
      The check "does the presented public key fingerprint to this actId
      under actId's version?" (PART 5).

  non-fingerprint actId
      A "name@appId" ActId that is not a key fingerprint. Treated as
      version 0 (PART 3.3); never minted for new accounts.

## PART 3 - STORAGE: act.FpVersion

3.1  New column

  Add an integer column to the act table, mirroring `Encryption`:

    `FpVersion` int NOT NULL DEFAULT 1
        COMMENT 'Fingerprint algorithm version that minted this ActId
                 (and its OwnId). 1 = SHA-256[0..24)->Base64->32ch
                 ([Security](Security.md) PART 3.3). See [Identifiers](Identifiers.md).'

  Schema is added by a stored-procedure-guarded ALTER, in the same style
  as the OwnPrvKey/Delegation additions (mySQL/dump-2024-01-22b-*.sql):
  idempotent, safe on fresh and live volumes.

3.2  Default backfill

  Every existing account was minted under v1, so the column DEFAULT of 1
  correctly labels all current rows with no data migration. The migration
  MAY additionally set FpVersion=1 explicitly for any row where it is NULL
  (belt-and-suspenders for older dumps).

3.3  Legacy (non-fingerprint) actIds

  Rows whose ActId is NOT a fingerprint (legacy "name@appId", or the
  system token act@act) predate self-certification. They SHOULD carry
  FpVersion=0 ("none"), and self-certification (PART 5) MUST be skipped
  for them exactly as today (DomId.isFingerprintActId gates those paths).
  A fresh dev DB has no such rows; this clause is for archaeology only.

3.4  The column travels with the account

  Because Login-Multiple replicates the account across providers, the
  FpVersion is part of the account's canonical membership state. When a
  peer row / membership replica is materialised on another provider
  ([Login protocol](../apps/Login-Protocol.md)), the FpVersion MUST be carried alongside the
  actId, so every provider derives/validates that account identically.
  A missing FpVersion on an inbound record defaults to 1 (v1).

## PART 4 - THE ALGORITHM REGISTRY (single chokepoint)

4.1  One place to derive, one place to validate

  All fingerprinting MUST go through a single registry so a future v2 is
  one edit, not a repo-wide hunt. Concretely, in
  com.domatar.crypto.AccountKeys (or a new Fingerprint class it delegates
  to):

    // Current signatures (v1 only) — RETAINED, redefined as "v == 1":
    static String deriveActId(byte[] pubKey)              // = deriveId(pubKey, 1)
    static String deriveOwnId(byte[] pubKey)              // = deriveId(pubKey, 1)

    // New version-aware chokepoint:
    static String deriveId(byte[] pubKey, int version)
    static boolean fingerprintsTo(byte[] pubKey, String id, int version)
    static int     defaultVersion()                        // returns 1 today

  deriveId dispatches on version:

    version == 1 : SHA-256(pubKey)[0..24) → Base64Encoder.encode → 32 ch
    version == 2 : (reserved; not defined here)
    otherwise    : throw DomatarException("Unsupported FpVersion " + v)

  This is the EXACT structural analogue of ActDb.encrypt(pwd, encryption).

4.2  Existing call sites delegate, they do not branch

  Call sites that mint identity (AccountKeys.generate, .fromPrivKey,
  ownId derivation) call deriveId(pub, defaultVersion()) for NEW mints,
  and deriveId(pub, account.fpVersion) when RE-DERIVING a known account's
  id. No call site outside the registry may encode SHA-256/24/Base64
  directly.

4.3  Validation is version-driven, not length-driven

  DomId.isFingerprintActId today asserts "length == 32 and alphabet ok".
  That stays valid for v1, but new code MUST NOT treat 32 as the
  definition of "is an actId". Introduce:

    static boolean isFingerprintActId(String actId, int version)

  which knows the shape produced by `version` (v1 ⇒ 32 chars of the
  fingerprint alphabet). The zero-arg form remains as "looks like a v1
  fingerprint" for legacy gating only (PART 3.3), and SHOULD be treated
  as deprecated for new logic.

4.4  ownId shares the registry

  ownId = fingerprint(ownership pub) under the SAME version as the
  account ([Identifiers](Identifiers.md) PART 2.1). deriveOwnId(pub) == deriveId(pub,
  account.fpVersion). An account never mixes versions between its actId
  and ownId.

## PART 5 - SELF-CERTIFICATION IN THE CREDENTIAL CHAIN

5.1  The check becomes version-aware

  Everywhere the credential chain verifies "presented key fingerprints to
  stored id" it MUST use the account's version:

    - Binding verification (crypto.Binding): GenesisPubKey fingerprints to
      ActId, and OwnPubKey fingerprints to OwnId, BOTH under the account's
      FpVersion.
    - Delegation verification (crypto.Delegation): actId ==
      fingerprint(rootPubKey) under FpVersion.
    - Msg / Auth seams that re-establish trust (servlet.Msg PART on
      self-cert; core.Auth) pass the FpVersion through.

  Replace any hard-coded deriveActId(pub).equals(actId) with
  fingerprintsTo(pub, actId, version).

5.2  Where the version comes from at verify time

  The verifier already has the actId in hand. It obtains the matching
  FpVersion from, in order:

    1. The local act row (ActDb) when the account is local.
    2. The membership replica / peer record when cross-provider
       ([Login protocol](../apps/Login-Protocol.md)), which carries FpVersion per PART 3.4.
    3. Default 1 if a record predates the column (PART 3.2 guarantees this
       is correct for all existing accounts).

5.3  Security invariant unchanged

  Self-certification still proves possession of the key that the id names.
  Versioning only selects the derivation; it never weakens the check. A
  request whose actId version is unknown/unsupported MUST be REJECTED
  (fail closed), never accepted under a guessed algorithm.

## PART 6 - MINTING, READING, ROUTING

6.1  Minting (new accounts)

  Account creation (AccountKeys.generate → ActDb.addAct) records
  FpVersion = defaultVersion() in the act row, alongside the actId it just
  produced with that same version. addAct's INSERT gains the FpVersion
  column (today it writes the literal 1 for Encryption; it should write
  defaultVersion() for FpVersion).

6.2  Reading (existing accounts) — NO in-place upgrade

  Unlike passwords, an actId is NEVER re-minted on login. There is no
  "re-fingerprint on next login" step. The FpVersion is read and used;
  it is not advanced. (Password upgrade-on-login via `Encryption` remains
  independent and unaffected.)

6.3  Routing

  Routing that today distinguishes fingerprint actIds from legacy
  "name@appId" (e.g. AppstoreWui, HttpClient) continues to key on
  "has '@' ⇒ legacy; else fingerprint". That test is version-agnostic and
  needs no change: all fingerprint versions share the '@'-free alphabet.

6.4  Display

  actId length/appearance may differ across versions (a future v2 could be
  longer). UI and logs MUST treat actId as an opaque string of variable
  length, never assume 32, and never parse meaning out of its characters.

## PART 7 - REPLICA-HOST PARSING MUST BE VERSION-TOLERANT

7.1  The current rigidity

  Provider-qualified replica hosts are <app>-<actId>-<prvId>
  ([Login protocol](../apps/Login-Protocol.md) PART 5). DomId.replicaActId / replicaPrvId
  parse them by slicing a FIXED 32-char actId (actStart + 32) and
  checking the next char is '-'. This hard-codes v1's length into the
  host grammar and would break for a longer v2 actId.

7.2  The rule

  Replica-host parsing MUST NOT assume a fixed actId width. Because prvId
  values contain no HOST_SEP ('-') (they are simple tokens like "prv1",
  [Login protocol](../apps/Login-Protocol.md)) and app prefixes are known, parse structurally:

    - appPrefix = substring before the FIRST HOST_SEP.
    - prvId     = substring after the LAST HOST_SEP.
    - actId     = everything between them.
    - Validate actId with isFingerprintActId(actId, versionFor(actId))
      — or, when version is not yet known, accept any known fingerprint
      shape (v1 today) and defer strict validation to the account lookup.

  This makes the host grammar independent of actId length while remaining
  exactly compatible with every existing v1 host name.

7.3  Non-goal

  This spec does NOT require renaming existing hosts or changing HOST_SEP.
  It only removes the "+ 32" assumption from parsing so v2 needs no host
  grammar change.

## PART 8 - ROLLOUT DISCIPLINE FOR A FUTURE v2

8.1  Conservative default

  defaultVersion() stays 1 until a deliberate decision to introduce v2.
  Adding the column and the registry (this spec) does NOT change any
  minted id.

8.2  Adding v2 later (the intended future edit)

  a. Implement deriveId(pub, 2) in the registry (PART 4.1) and the shape
     for isFingerprintActId(id, 2).
  b. Teach every verifier nothing new — they already pass version through
     (PART 5). They simply now accept version 2 as well.
  c. DUAL-ACCEPT PERIOD: all nodes must recognise v1 AND v2 before ANY
     node starts minting v2. Recognition ships first; minting second.
  d. Flip defaultVersion() to 2 (config-gated is preferable to a code
     constant, so providers can roll forward independently). New accounts
     now mint v2; existing accounts keep v1 forever.
  e. No data migration of existing actIds. Ever, as routine.

8.3  Why recognition-before-minting

  A v2 actId minted on an updated node would be rejected by a not-yet-
  updated node's self-certification (unknown version ⇒ fail closed,
  PART 5.3). Shipping recognition everywhere first prevents a partial
  fleet from locking out new accounts.

8.4  Config surface (recommended)

  Expose the default as provider.config.txt "FpDefaultVersion" (read by
  DomatarConfig), defaulting to 1. This lets the fleet enable v2 minting
  by config flip after code recognition is universal, without a rebuild.

## PART 9 - RE-IDENTIFICATION (explicitly NOT the normal path)

9.1  When it would be needed

  Changing an EXISTING account's actId (e.g. deprecating v1 for a
  compromised hash) is a full identity migration: mint a new actId, then
  rewrite every object row, host name, lnk, peer/binding record, and
  cookie that embeds the old actId, with a binding that proves old→new
  continuity.

9.2  Status

  Re-identification is out of scope for this spec and is expected to be
  rare-to-never. The whole point of versioning is to AVOID it: v1
  accounts stay v1, and only NEW accounts adopt v2. If re-identification
  is ever required, it gets its own Spec/Update pair modelled on the
  Security/OwnIds migrations, and reuses the delegation machinery to
  attest the linkage.

## PART 10 - IMPLEMENTATION SURFACE

  Schema:
    - mySQL guarded ALTER adding act.FpVersion int NOT NULL DEFAULT 1
      (style of dump-2024-01-22b-security-schema.sql).

  Core:
    - com.domatar.crypto.AccountKeys: add deriveId(pub, version),
      fingerprintsTo(pub, id, version), defaultVersion(); redefine
      deriveActId/deriveOwnId as the v==1 delegators; keep the v1 math in
      exactly one branch.
    - com.domatar.util.DomId: add isFingerprintActId(actId, version);
      rewrite replicaActId/replicaPrvId to parse by delimiters, not
      "+ 32" (PART 7.2); keep the zero-arg isFingerprintActId as the
      deprecated v1/legacy gate.
    - com.domatar.crypto.Binding, Delegation: verify via
      fingerprintsTo(pub, id, version) instead of a hard-coded derive.
    - com.domatar.db.ActDb: SELECT/INSERT FpVersion; expose it on the Act
      value holder; addAct writes defaultVersion().
    - Login-Multiple membership/peer records: carry FpVersion (PART 3.4).
    - DomatarConfig (optional): FpDefaultVersion, default 1 (PART 8.4).

  No change required to: the v1 algorithm, existing actIds, existing host
  names, Desktop/Navigator sync mechanism, password Encryption handling.

## PART 11 - ACCEPTANCE

  Live:

  * act.FpVersion exists, defaults to 1, and every existing account reads
    back as version 1 with no data migration.
  * All fingerprint derivation and self-certification flow through the
    registry; no call site outside it encodes SHA-256/24/Base64 directly.
  * Self-certification passes the account's FpVersion and fails closed on
    an unknown/unsupported version.
  * Replica-host parsing round-trips every existing v1 host name WITHOUT a
    fixed-width (+ 32) assumption.
  * A hypothetical v2 can be added by (a) one registry branch, (b) a shape
    rule, (c) a default-version flip — with existing v1 accounts untouched
    and a recognition-before-minting rollout.
  * Passwords (act.Encryption) are unaffected; actId versioning never
    re-mints an existing account's id in place.

## PART 12 - IMPLEMENTATION REFINEMENTS (KD1–KD7)

  Key decisions:

  KD1  Registry lives in AccountKeys (deriveId / fingerprintsTo /
       defaultVersion / registeredVersions). deriveActId/deriveOwnId
       remain as thin v1 delegators.

  KD2  Binding.verify() / Delegation.verify(Binding) are version-tolerant
       (try every registered version) so self-authenticating records still
       validate with no external lookup ([Identifiers](Identifiers.md) PART 6.3). The
       credential chain uses verify(fpVersion) with the stored version.

  KD3  ActDb.getFpVersion(actId) returns 1 when the row is missing/NULL.

  KD4  AccountKeys.fromPrivKey(bytes) stays pinned to v1; fromPrivKey(bytes,
       version) is the versioned overload.

  KD5  defaultVersion() reads DomatarConfig.getFpDefaultVersion()
       (FpDefaultVersion / DOMATAR_FP_DEFAULT_VERSION), falling back to 1.

  KD6  Schema: mySQL/dump-2024-01-22d-fpversion-schema.sql (fresh volumes)
       + live guarded ALTER; canonical restore point includes FpVersion in
       act CREATE TABLE (canonical-*-actidver-db{1,2}.sql).

  KD7  Replica-host parsing is delimiter-based (first/last HOST_SEP), not
       fixed-width (+ 32). Public parsers still gate on the v1 shape.
