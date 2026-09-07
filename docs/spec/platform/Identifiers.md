# Identifiers

This document is the identity spec: ownId, actId, usrId, the genesis and
ownership keys, the actId→ownId binding, rebind, and fingerprint
versioning (`act.FpVersion`).

  - ownId  - the account's OPERATIVE cryptographic identity. Rotatable.
             The top of the day-to-day authority chain. Never seen by
             the user; appears only in a few binding records, never in
             objects.
  - actId  - the account's PERMANENT identity. Immutable. Stamped into
             every object. Visible in the navigator, never in ordinary
             apps. A fingerprint of the genesis public key (PART 4).
  - usrId  - a login handle "<localname>@<appId>". Mutable label.

Splitting name (actId / genesis key) from operation (ownId / ownership key)
lets an account rotate its operative key — and evict a dishonest provider —
without rewriting the identifier baked into stored objects.

[Security](Security.md) is the wire: origin signatures, signed path hops,
delegations on the message, TLS. This document is who the account is.
[Login protocol](../apps/Login-Protocol.md) replicates the identity layer
across providers; membership carries the ownId binding. [Desktop](../apps/Desktop.md)
keys on actId, which never changes here.

## PART 1 - PURPOSE AND SCOPE

1.1  Why three identifiers

  Naming an account and operating it have opposite requirements. Naming
  wants permanence: the actId is written into every stored object and
  cannot rotate. Operating wants replaceability: a dishonest home
  provider that holds the operating key must be evictable without
  discarding the account. A single key cannot be both permanent and
  replaceable, so the model splits them.

  The ownership private key is server-held on signing providers
  ([Security](Security.md) T1, PART 5). Between rebinds that provider can
  act as the account and renew its own delegation. Rebind (PART 10)
  rotates the ownId so those self-issued delegations no longer chain.

1.2  What the model provides

  O1  ROTATABLE OPERATIVE KEY. The genesis key (PART 4) never rotates
      and defines the immutable actId. The ownership key (PART 5)
      defines a rotatable ownId and issues delegations. Rotating the
      ownId re-keys operative authority WITHOUT touching the actId or
      any object.

  O2  CRYPTOGRAPHIC EVICTION. Because delegations chain to the ownId, a
      new ownId (a rebind, PART 10) invalidates every delegation a
      dishonest provider self-issued under the old ownId, once the new
      binding has propagated (PART 12). Cooperative-only removal in
      [Login protocol](../apps/Login-Protocol.md) PART 11 cannot do this.

  O3  STABLE OBJECTS. Objects carry actId (PART 2.2). Rotation changes
      only the binding records (PART 6), of which there are few — one
      per Login peer — never the objects.

  O4  UNIVERSAL TRUST WITHOUT A REGISTRY. The actId→ownId binding is
      SELF-AUTHENTICATING: it is signed by the genesis key, and actId is
      the fingerprint of the genesis public key, so ANY provider can
      verify the current ownId from the actId alone, with no third-party
      lookup ([Security](Security.md) PART 6.3), including providers where
      the user never logs in (PART 8).

1.3  Non-goals

  * A user-visible ownId. The ownId is machinery; the user never sees or
    types it (PART 2.4).
  * Rotating the actId. The actId is permanent by construction (PART 4).
    Eviction rotates the ownId, not the actId.
  * Strong consistency of the binding across providers. Rotation takes
    effect as the new binding propagates; eventual consistency (PART 12).
  * Replacing 2FA or per-app data redundancy (see the sibling specs).

1.4  Terminology

  * genesis key   - a cold Ed25519 key pair, held by the USER offline
                    (PART 7). Defines the actId and signs bindings. Used
                    only at account creation and at rebind/recovery.
  * actId         - fingerprint(genesis public key). Permanent account
                    identity, stamped into every object (PART 4).
  * ownership key - a hot Ed25519 key pair. Defines the ownId and issues
                    delegations. Server-held on signing providers
                    (PART 5). Rotatable.
  * ownId         - fingerprint(ownership public key). The account's
                    current operative identity (PART 5).
  * binding       - the genesis-signed record "actId -> current ownId"
                    (PART 6). Versioned; latest wins.
  * usrId         - a login handle, as in [Login protocol](../apps/Login-Protocol.md) PART 3.2.
  * rebind        - minting a new ownership key and publishing a new,
                    higher-versioned binding (PART 10). This is a
                    re-key / eviction, NOT a change of actId.
  * fpVersion     - which fingerprint algorithm minted this account's
                    actId and ownId (PART 18). v1 is the shipped
                    algorithm (PART 4.1).

## PART 2 - THE THREE IDENTIFIERS

2.1  ownId - the operative identity (rotatable)

  ownId = fingerprint(ownership public key), derived the same way an
  actId is derived from a public key (PART 4.1):

      ownId = Base64Encoder.encode( SHA-256( ownPublicKeyBytes )[0 .. 24) )

  It is the top of the account's DAY-TO-DAY authority chain: the
  ownership private key signs the delegations that let providers act
  (PART 11). It is deliberately EASY TO CHANGE: a rebind mints a new
  ownership key pair and a new ownId, leaving the actId untouched
  (PART 10).

  It is stored only in the binding records (PART 6) — "a few places" —
  and NEVER embedded in objects.

2.2  actId - the permanent identity (immutable)

  actId = fingerprint(genesis public key) (PART 4). It is the identifier
  written into every DomId and therefore into every object and link
  ([Domatar](../Domatar.md) PART 2), and it NEVER changes for the life of
  the account. It is provider-free.

2.3  usrId - the login handle

  usrId is a mutable "<local>@<appId>" label that routes verification
  to that app's home host and authorizes nothing on its own
  ([Login protocol](../apps/Login-Protocol.md) PART 3.2,
  [Domatar](../Domatar.md) PART 4.1.1). `@` is forbidden in `<local>`
  and in `appId`, so a usrId contains exactly one `@`. `.` is forbidden
  in every DomId field. Nothing authorizes on the usrId; authorization
  is always against the actId via the credential chain
  ([Security](Security.md) PART 7).

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

  Naming wants permanence (it is in every object). Operating wants
  replaceability (to evict a bad provider). actId is the permanent NAME,
  ownId is the replaceable OPERATOR, and a genesis-signed binding
  (PART 6) ties them together.

## PART 3 - RELATIONSHIP TO [Security](Security.md)

This document is the identity model. [Security](Security.md) is how a
message proves origin, path, and wire protection (Q1 / Q2 / Q3).

3.1  Two keys, not one

    GENESIS key   (cold)  - defines the actId and signs the actId→ownId
                            binding. Never rotates. User-held offline
                            (PART 7).
    OWNERSHIP key (hot)   - issues delegations. Rotatable. Server-held
                            on signing providers (PART 5).

  [Security](Security.md) T1 applies to the ownership key between rebinds:
  a signing provider that holds it can act as the account and renew its
  own delegation. Direction in [Security](Security.md) PART 15 moves the
  ownership key to the user's device; that is orthogonal to genesis.

3.2  Objects carry actId only

  The actId on every object and link is the permanent name (PART 2.2).
  Rebind never rewrites objects (O3). PART 10.5 covers any leftover
  account that still fingerprints a single operating key: declare that
  key genesis and publish an initial binding.

3.3  The credential chain

  A verified message is checked bottom-up against the actId (PART 11;
  [Security](Security.md) PART 6.1):

    actId -> GenesisPubKey -> Binding -> OwnPubKey -> Delegation
          -> provider key -> OriginSig

  Origin signatures, path hops, and TLS stay in [Security](Security.md)
  PART 7 / 8 / 9. The binding hop is the identity-anchoring step.

## PART 4 - THE GENESIS KEY AND actId

4.1  Derivation

  At account creation the user mints a GENESIS Ed25519 key pair. The
  actId is the fingerprint of its public key (algorithm version 1;
  PART 18):

      actId = Base64Encoder.encode( SHA-256( genesisPublicKeyBytes )[0 .. 24) )

  i.e. the first 24 bytes (192 bits) of the SHA-256 digest of the 32-byte
  Ed25519 genesis public key, encoded with the platform's URL-safe
  Base64Encoder (com.domatar.util.Base64Encoder). 24 bytes is a multiple
  of 3, so the encoding is exactly 32 characters with no padding, using
  only the alphabet [0-9 A-Z _ a-z ~]. That alphabet contains neither '.'
  (the DomId separator) nor '@' (the usrId separator) nor '-'
  (`DomId.HOST_SEP`), so an actId is safe as a DomId component, in a host
  id suffix ([Login protocol](../apps/Login-Protocol.md) PART 5.2), and in a
  URL without escaping.

  Example shape (illustrative, not a real key):
      qK3nZ8pMvB2rT9wLxF4hJ7dScA1yE6gU

  Self-certification is version-aware via act.FpVersion /
  AccountKeys.fingerprintsTo (v1 unchanged, PART 18).

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

  Because actId = fingerprint(genesis public key), uniqueness comes from
  key randomness, not from a namespace authority:

    * Two distinct key pairs yield distinct public keys, hence distinct
      actIds (barring hash collision).
    * At 192 bits, an accidental collision (birthday bound ~2^96) is
      negligible. A deliberate second-preimage (~2^192) is infeasible,
      and even a collision would not grant impersonation without the
      matching private key.
    * A malicious host cannot issue YOUR actId to a different user: to
      produce a valid binding for actId (PART 6) one must sign with the
      genesis key whose fingerprint IS that actId. A binding not so
      signed is rejected by every verifier (PART 11).
    * First-come-first-served registration is NOT load-bearing for
      identity: even if two providers both claim to host actId X, only
      the one presenting a genesis-signed binding is believed.
    * An account is globally unique the moment its genesis key pair
      exists, minted offline, with no registry.

  WARNING — key-generation entropy. All of the above assumes the genesis
  key is generated with a vetted CSPRNG. A weak RNG collapses effective
  entropy and can duplicate private keys. The current implementation
  generates keys correctly; a conformance requirement for multiple
  independent implementations is Direction ([Security](Security.md) PART 15).
  Increasing the actId length would NOT help.

## PART 5 - THE OWNERSHIP KEY AND ownId

5.1  Role

  The OWNERSHIP key is the operating key: its private half signs
  DELEGATIONS authorizing providers to act for the account
  ([Security](Security.md) PART 6.2), and ownId = fingerprint(its public key)
  is the identity those delegations chain to (PART 11). Genesis names
  the account; ownership operates it.

5.2  Custody

  The ownership private key is SERVER-HELD on the account's SIGNING
  providers (5.4), encrypted at rest, in act.OwnPrvKey.
  A signing provider is a complete, independent copy that can verify
  and sign without the user's password
  ([Login protocol](../apps/Login-Protocol.md) PART 4.1).

5.3  T1 persists between rebinds, but is escapable

  Because a signing provider holds the ownership private key, it can
  still act as the account and renew its own delegation (T1,
  [Security](Security.md) PART 2.2 / 5.4). The escape hatch is REBIND to a
  fresh ownership key (PART 10), after which the dishonest provider's
  copy of the OLD ownership key is worthless — its self-issued
  delegations no longer chain to the current ownId. T1 lasts until the
  next rebind propagates.

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

  The binding is stored with the account (this realisation: columns
  GenesisPubKey / OwnPubKey / BindingVersion / BindingNotBefore /
  BindingSig on `act`) and travels with each signed message in the
  `Sec=` envelope ([Security](Security.md) PART 11.1). Login-peer
  storage and replication are
  [Login protocol](../apps/Login-Protocol.md).

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

  Note this is NOT the [Security](Security.md) Direction "device signs every
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

  On an object-only host there is NO local account. HostProvision
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
  membership objects when there is no local account (HostProvision). It does
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
  Binding (which carries GenesisPubKey and Version), as
  [Security](Security.md) PART 6.1 carries the delegation — plus the binding
  as one extra self-contained object. The only external fetch remains
  provider P's key from the directory (already cached for routing). No
  account lookup.

11.3  The freshness rule

  A verifier accepts a Binding only if its Version is >= the highest
  Version it has seen for that actId (PART 6.4). It caches the highest
  Version per actId. A message whose delegation chains to an ownId from a
  SUPERSEDED binding is rejected. This is the sole state a verifier keeps
  about an account, and it is monotonic and self-correcting: a newer
  binding always wins.

11.4  Wire mechanisms are separate

  Origin signature, path provenance, and TLS are [Security](Security.md)
  PART 7 / 8 / 9. The binding hop is the identity-anchoring step
  (PART 6.3) and does not alter per-hop or per-message signing.

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

  The binding lives with the account and in the `Sec=` envelope
  ([Security](Security.md) PART 11.1); this realisation stores account
  fields on `act`. ActManagerImpl / OwnIdsRebind perform rebind;
  Msg.doAction / Provenance.verify checks the binding hop + local
  Version freshness. MembershipImpl GetBinding / SetBinding replicate
  it ([Login protocol](../apps/Login-Protocol.md)).

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
  per-actId highest-Version check against the local account (PART 11.3
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
    delegation but no local account) and confirm it receives the t1 binding and
    correctly identifies the new ownId with no login (PART 8).

  As in the sibling sim, TLS is bypassed; the ownership-key transfer of
  PART 10.2.c travels over plain http between tomcats. Sim only.

## PART 16 - SECURITY CONSIDERATIONS AND RESIDUAL RISKS

16.1  What the model buys

  * Cryptographic eviction of a signing provider via rebind (O2, PART 10),
    which cooperative removal ([Login protocol](../apps/Login-Protocol.md) PART 11) could not
    do - achieved WITHOUT rewriting objects (O3).
  * Universal, registry-free trust in the current ownId, even on no-login
    providers (O4, PART 8), with no account-record lookup
    ([Security](Security.md) PART 6.3).

16.2  Propagation window (residual)

  A rebind is effective at a verifier only after the new binding reaches
  it (PART 12.3). During the window a not-yet-updated verifier may still
  accept the evicted provider's old-ownId delegations. Fan-out on rebind
  bounds this. It is the residual risk for signing-provider eviction,
  not a permanent inability to evict.

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
    * It reintroduces an allocator/registry and a squatting race
      (PART 4.3) — a malicious host could register your actId to someone
      else, and honest providers would have no self-contained way to tell
      who is right.
    * Bindings could not be self-authenticating (6.3): trust would again
      require a lookup, reversing [Security](Security.md) PART 6.3.
  Anchoring actId to the genesis key keeps the actId permanent AND makes
  ownership unforgeable by construction, at no cost to objects (they still
  carry an opaque tag).

16.5  Ownership-key compromise between rebinds

  A stolen ownership private key lets the thief act until a rebind
  propagates (PART 10.3). Detection-to-rebind latency is the exposure.
  Without a rotatable ownId that theft would be permanent.

16.6  Layering with 2FA

  2FA ([Login protocol](../apps/Login-Protocol.md) PART 12) sits in the authentication layer
  and is orthogonal to all three identifiers here. It hardens "may this
  person start a session"; it does not sign bindings or delegations.

## PART 17 - DIRECTION

  * Genesis custody default for the product (PART 7.3): recovery device
    + backup codes; sign-up UX that shows codes and the "loss is final"
    warning (PART 7.5).
  * Formalize PART 10.5 as a one-shot install step if any dumps still
    lack a binding.
  * A short signed revocation hint a rebind can push so verifiers
    drop a superseded ownId cache entry, shrinking the PART 16.2 window.
  * Object-only third-provider sim (PART 15).

## PART 18 - FINGERPRINT VERSIONING (implemented)

The actId is self-certifying: a verifier re-derives the fingerprint from
the presented key and compares it to the stored actId (crypto.Binding,
crypto.Delegation). That derivation is VERSIONED so a future algorithm
can be added without rewriting identifiers already minted.

There is exactly ONE shipped algorithm (v1), PART 4.1:

    actId = Base64Encoder.encode( SHA-256(pubKey)[0 .. 24) )   → 32 chars

(com.domatar.crypto.AccountKeys.deriveId(..., 1)). v1 does not change.
Each account keeps forever the algorithm that minted it. New accounts
mint under the current default version. A future change is "add v2;
accept v1 and v2; default new mints to v2" — never recompute existing
actIds.

Unlike `act.Encryption` (password hash, upgradable in place on next
login), an actId IS the identity and is never upgraded in place.
Re-identification (old actId → new actId) is a separate, rare migration
(18.8), not the normal path. ownId uses the SAME version as the
account's actId.

18.1  Storage: act.FpVersion

  `FpVersion` int NOT NULL DEFAULT 1 — fingerprint algorithm that minted
  this ActId (and its OwnId). 1 = v1 (PART 4.1).

  A missing/NULL value reads as 1. Legacy non-fingerprint ActIds
  (archaeology: `name@appId`, `act@act`) SHOULD carry FpVersion=0;
  `DomId.isFingerprintActId` skips self-certification for them. Fresh
  DBs have no such rows.

  FpVersion travels with the account on membership / peer replicas
  ([Login protocol](../apps/Login-Protocol.md)). A missing inbound FpVersion
  defaults to 1.

18.2  Algorithm registry

  All fingerprinting goes through com.domatar.crypto.AccountKeys:

    static String  deriveActId(byte[] pubKey)     // deriveId(pubKey, 1)
    static String  deriveOwnId(byte[] pubKey)     // deriveId(pubKey, 1)
    static String  deriveId(byte[] pubKey, int version)
    static boolean fingerprintsTo(byte[] pubKey, String id, int version)
    static int     defaultVersion()               // 1 unless config says otherwise

  deriveId: version 1 is PART 4.1; version 2 is reserved; otherwise throw.
  New mints use deriveId(pub, defaultVersion()). Re-derivation of a known
  account uses that account's FpVersion. No call site outside the
  registry encodes SHA-256/24/Base64 directly.

  `DomId.isFingerprintActId(actId, version)` knows the shape for that
  version (v1 ⇒ 32 chars of the fingerprint alphabet). The zero-arg form
  is the v1/legacy gate only.

18.3  Self-certification

  Binding and Delegation verify "presented key fingerprints to stored id"
  under the account's FpVersion (KD2: Binding.verify / Delegation.verify
  also try every registered version so a self-authenticating record
  still validates with no lookup, PART 6.3). An unknown/unsupported
  version is REJECTED (fail closed).

  FpVersion at verify time, in order: local account; membership/peer
  record; default 1. This realisation reads `act.FpVersion` first.

18.4  Minting, reading, routing

  Account creation records FpVersion = defaultVersion() next to the
  actId just produced. An existing actId is NEVER re-minted on login.

  Routing that distinguishes fingerprint actIds from a string containing
  '@' is version-agnostic (all fingerprint versions share the '@'-free
  alphabet). UI and logs treat actId as an opaque string; do not assume
  length 32.

18.5  Replica-host parsing (KD7)

  Provider-qualified replica hosts are `<app>-<actId>-<prvId>`
  ([Login protocol](../apps/Login-Protocol.md) PART 5). Parsing MUST NOT assume
  a fixed 32-char actId. prvId values contain no HOST_SEP (`-`). Parse:

    - appPrefix = substring before the FIRST HOST_SEP
    - prvId     = substring after the LAST HOST_SEP
    - actId     = everything between them

  Existing v1 host names round-trip. Existing hosts are not renamed.

18.6  Direction: a future v2

  defaultVersion() stays 1 until a deliberate v2. Adding v2: implement
  deriveId(pub, 2); dual-accept v1 and v2 on every node BEFORE any node
  mints v2; then flip FpDefaultVersion / DOMATAR_FP_DEFAULT_VERSION to 2.
  No data migration of existing actIds.

  Changing an EXISTING account's actId (re-identification) would rewrite
  every object, host name, lnk, peer/binding, and cookie. Out of scope;
  versioning exists to avoid it.

18.7  Implementation (KD1–KD7)

  KD1  Registry lives in AccountKeys (deriveId / fingerprintsTo /
       defaultVersion / registeredVersions). deriveActId/deriveOwnId
       remain thin v1 delegators.
  KD2  Binding.verify() / Delegation.verify(Binding) are version-tolerant
       (try every registered version). The credential chain uses
       verify(fpVersion) with the stored version.
  KD3  ActDb.getFpVersion(actId) returns 1 when the row is missing/NULL.
  KD4  AccountKeys.fromPrivKey(bytes) stays pinned to v1;
       fromPrivKey(bytes, version) is the versioned overload.
  KD5  defaultVersion() reads DomatarConfig.getFpDefaultVersion()
       (FpDefaultVersion / DOMATAR_FP_DEFAULT_VERSION), falling back to 1.
  KD6  Schema includes FpVersion in act CREATE TABLE.
  KD7  Replica-host parsing is delimiter-based (first/last HOST_SEP), not
       fixed-width (+ 32). Public parsers still gate on the v1 shape.
