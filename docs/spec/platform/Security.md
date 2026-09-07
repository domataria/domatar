# DOMATAR - SECURITY SPECIFICATION

[IMPLEMENTED — Update-Security-New-Architecture.txt]

This document specifies how Domatar secures inter-object communication:
how a receiving object knows WHO sent a message (the caller's identity),
by WHAT PATH it arrived (the chain of objects it passed through), and how
the bytes are protected ON THE WIRE.

Account identity (ownId, actId, usrId, genesis/ownership keys, binding,
rebind) is [Identifiers](Identifiers.md). This document is the wire:
origin signatures, signed path hops, delegations carried with the
message, and TLS.

Security is a platform operation. Apps compose bodies and call `send`.
They do not construct `Context`, do not attach provenance, and do not
opt into verification. An app that does the wrong thing can fail its
own work; it cannot become the source of truth for who called or which
path the call took.

The system uses three independent mechanisms:

  Q1  ORIGIN     "Is the caller who it claims to be?"    -> signatures
                                                            (PART 7)
  Q2  PROVENANCE "Did the message really travel this      -> signed
                  path?"                                     hash chain
                                                            (PART 8)
  Q3  WIRE       "Can a network eavesdropper read or       -> TLS
                  alter the bytes in transit?"               (PART 9)

Ownership keys are server-held on signing providers (T1). Device-held
keys, proactive revocation, message-level encryption, and defenses
against a *correctly signing but dishonest* registered provider (A5)
are Direction (PART 15).


## PART 1 - GOALS AND NON-GOALS

1.1  Goals

  G1  A receiving object can verify that a message genuinely originates
      from the account (actId) it names, and that no intermediary
      altered the body of the origin hop. (Q1)

  G2  A receiving object can verify the FULL PATH the message took --
      the ordered chain of objects/providers that relayed or composed
      it -- and detect any inserted, dropped, reordered, or modified
      hop. (Q2)

  G3  Message bytes are confidential and integrity-protected against
      network eavesdroppers between two communicating providers. (Q3)

  G4  A user may have MULTIPLE redundant home providers and may abandon
      any of them -- including the one on which the account was first
      created -- without any loss of identity, and without any object
      the user owns needing to be rewritten.

  G5  A malicious provider cannot impersonate an account it does not
      control, even if it knows the account's identifiers and claims to
      be that account's home provider.

  G6  Provenance is produced and verified by the platform. It does not
      depend on an application supplying, preserving, or requesting a
      security envelope.

1.2  Non-goals (for this specification)

  * Consensus / a distributed ledger. Domatar needs unforgeable
    identity and tamper-evident paths, delivered by hash chaining plus
    digital signatures. It does NOT need global agreement on a single
    ordering of all messages, so there is no blockchain, proof-of-work,
    or mining anywhere in this design.

  * Anonymity / traffic-analysis resistance. Full-path provenance (G2)
    is deliberately the OPPOSITE of anonymity: it reveals the route to
    the endpoint (see PART 8.13).

  * Confidentiality against a relaying provider. Transport (per-hop)
    encryption only; a provider that handles a message can read *that*
    hop's body. It does not receive upstream bodies (PART 8.4).
    End-to-end body encryption is Direction (PART 15).

  * Proactive revocation. A provider is de-authorized by letting its
    delegation expire (PART 6), not by a revocation list. A short
    delegation lifetime bounds the exposure; a revocation service is
    Direction (PART 15).

  * Preventing a registered provider from lying about its own interior.
    A provider is the only witness to its own objects. Q2 proves that
    named providers attested a route; it does not prove those providers
    were honest about objects they host (A5, PART 2.1 / PART 14.2).


## PART 2 - THREAT MODEL

2.1  Adversaries considered

  A1  A network eavesdropper between providers (passive read, active
      tamper/replay on the wire).

  A2  A malicious or compromised provider that is NOT a delegated home
      provider of the victim account. It can send any bytes, craft any
      identifiers, forge any path, and claim to be anyone's home
      provider.

  A3  A malicious relay: a provider that legitimately forwards or
      composes a message but tries to alter it, alter its claimed
      route, or inject/drop hops.

  A4  A replay attacker that captures a valid signed message and
      re-sends it.

  A5  A registered provider that signs correctly and lies in the
      claims nobody else can check: fabricated internal routes,
      truncated chains presented as roots, doctored class descriptors,
      success returned for work not done. A2 and A3 fail
      cryptographically when the chain is platform-held. A5 passes
      every signature check we have. (Direction for additional
      mitigations: PART 15.)

  A6  A malicious or buggy *application* running in-process on an
      otherwise honest provider. It can compose any body and call
      `send`. It must not be able to mint, truncate, or substitute
      provenance, nor to raise its own trust level (PART 7.5).

2.2  Trusted, by design (accepted limitations)

  T1  A user's OWN home providers are trusted to act as the user. The
      ownership private key lives on each signing provider
      ([Identifiers](Identifiers.md) PART 5; this PART 5.4) and is usable
      without the user present, so a home provider can sign as the
      account AND can issue delegations (PART 6.4). This is the price of
      server-held keys. Rebind ([Identifiers](Identifiers.md) PART 10)
      evicts a dishonest signing provider. Device-held ownership keys
      (PART 15) would remove T1 between rebinds.

  T2  The directory's provider/host records (PART 10) are trusted for
      PROVIDER identity once signed. Accounts do NOT trust the directory
      for account identity — an account's identity is self-certifying
      and travels with each message ([Identifiers](Identifiers.md),
      PART 6).

2.3  Explicitly out of scope

  Endpoint compromise of a user's device; malware on a provider that
  already holds the keys; side-channel attacks on the crypto library;
  denial of service. These are real but not addressed here.

2.4  Self-certifying vs provider-asserted

  Every claim on the wire is one or the other:

    Self-certifying     checkable without trusting a provider.
                        actId is a fingerprint of GenesisPubKey;
                        Binding and Delegation prove themselves
                        against that actId.

    Provider-asserted   true only if the signer is honest.
                        Hop SrcDomId / DstDomId inside the signer's
                        namespace; "this operation began here";
                        class-descriptor fields (SideEffect, Cost,
                        Compensates); "the compensating write
                        succeeded."

  Q1 and the credential chain exist so that *account identity* is
  self-certifying. Q2 exists so that *route attestation* is
  attributable to named providers. No authorization decision may rest
  on a provider-asserted claim without the policy knowing it is one.
  Confused-deputy rules that treat the path as a cryptographic proof
  of caller *class* are treating a provider-asserted claim as
  self-certifying; they hold only as far as each signing provider on
  that segment is honest.


## PART 3 - IDENTITY ON THE WIRE

Account identifiers are defined in [Identifiers](Identifiers.md). This
part states only what the message path needs.

3.1  actId and usrId

  * actId — permanent account identity, a fingerprint of the genesis
    public key ([Identifiers](Identifiers.md) PART 4). Embedded in every
    DomId and covered by `hop[0].HopSig` (PART 7). Never authorizes by
    itself; the credential chain (PART 6) proves it.

  * usrId — mutable login handle `<localname>@<appId>`
    ([Identifiers](Identifiers.md) PART 2.3;
    [Login protocol](../apps/Login-Protocol.md) PART 3.2). Travels in
    Context as a display / routing hint only; never an ownership
    reference; never authorizes.

  There is no global usrId-to-actId resolution step: the binding is
  asserted per message, and the actId half is cryptographically proven
  (PART 7).

3.2  Immutability

  The actId is written into every object the account creates and cannot
  change, or a home-provider switch would require rewriting all of the
  user's rows. It encodes no provider. Uniqueness is key-derived
  ([Identifiers](Identifiers.md) PART 4.3), not a namespace allocator.

3.3  How the actId is derived

  See [Identifiers](Identifiers.md) PART 4.1 (v1 fingerprint algorithm)
  and PART 18 (`act.FpVersion`). ownId uses the same registry.

3.4  usrId uniqueness

  Local name unique within a provider, times the globally-unique
  provider / app id. Moving home providers may change the usrId while
  the actId is invariant. Authorization is always against the actId via
  a signature (PART 7).

3.5  prvId

  prvId is a host identifier (an hstId), not a key fingerprint
  ([Domatar](../Domatar.md) PART 3–4). SignerPrv on each hop names this
  id. Q1 uses path[0].signerPrv; there is no separate Origin signer
  field. The matching operational public key comes from the signed
  directory host record (PART 10).


## PART 4 - CRYPTOGRAPHIC PRIMITIVES

  Signatures     Ed25519 (EdDSA over Curve25519). 32-byte public keys,
                 64-byte signatures. Chosen for small keys/signatures,
                 fast verification, and freedom from parameter/padding
                 pitfalls. No RSA.

  Hash           SHA-256. Used for the actId fingerprint
                 ([Identifiers](Identifiers.md) PART 4.1), for body
                 digests, for hop links (PART 8), and for ContextId
                 (PART 8.6).

  Text encoding  com.domatar.util.Base64Encoder (URL-safe alphabet, no
                 padding) for all binary-in-string values: actIds, public
                 keys, signatures, digests, nonces.

  Canonical      All signatures are computed over a CANONICAL byte
  form           serialization, never over "whatever JSON happened to be
                 produced." JSON key order and whitespace are not stable,
                 so a signer and verifier that disagree on bytes will
                 disagree on validity. The canonical form is:
                   - object keys sorted lexicographically by UTF-16 code
                     unit;
                   - no insignificant whitespace;
                   - UTF-8 output;
                   - the signature field(s) excluded from the bytes being
                     signed.
                 (Equivalent to RFC 8785 JCS. An implementation MAY
                 instead sign over the exact received bytes, provided it
                 stores those bytes verbatim for re-verification.)

  TLS            Standard TLS 1.3 for the wire (PART 9).


## PART 5 - KEYS AND PRINCIPALS

Three kinds of key exist. Keep them distinct; they answer different
questions and have different lifetimes.

5.1  Account keys (genesis + ownership)

  Two Ed25519 key pairs ([Identifiers](Identifiers.md)):

    * GENESIS key (cold, user-held): defines the permanent actId
      (= fingerprint of GenesisPubKey). Signs the actId->ownId Binding.
      Never stored on a provider after account creation / rebind
      ([Identifiers](Identifiers.md) PART 4, 7).
    * OWNERSHIP key (hot, server-held in act.OwnPrvKey): defines the
      rotatable ownId and issues delegations
      ([Identifiers](Identifiers.md) PART 5).

  The actId NEVER rotates (rotating genesis would change identity). The
  ownership key DOES rotate via rebind ([Identifiers](Identifiers.md) PART 10).

  The ownership key is needed to GOVERN day-to-day delegations. It is
  NOT needed to OPERATE message-to-message: a home provider signs
  messages with its own provider key (5.2) plus a delegation it already
  holds. Genesis is consulted only at creation and rebind.

5.2  Provider operational / identity key  (per provider)

  Each provider has one Ed25519 key pair, published in its directory
  host record (PART 10). One key serves three roles:
    - it signs ORIGIN messages for accounts that have delegated to it
      (PART 7);
    - it signs PATH hops it performs (PART 8);
    - it authenticates the provider in TLS (PART 9).
  It may rotate; rotation is a directory record update (PART 10.3) with
  an overlap window.

5.3  Directory root key  (provider trust anchor only)

  Signs directory host records so that provider keys can be trusted
  (PART 10). It is the trust anchor for PROVIDER identity, NOT for
  account identity. Distributed out of band / pinned in provider
  configuration. This is the ONLY pre-shared trust root, and it vouches
  only for "which key belongs to which provider," never for accounts.

5.4  Key storage

  Two private keys are held on the provider, in two different places:

    * The ACCOUNT OWNERSHIP private key is per account and is stored in
      `act.OwnPrvKey` ([Domatar](../Domatar.md) PART 7 /
      [Identifiers](Identifiers.md) PART 5), encrypted at
      rest under a provider master key. Only the private key is stored;
      the public key is derivable from it. The actId is the fingerprint
      of the *genesis* public key, not of this key
      ([Identifiers](Identifiers.md) PART 4). (This is distinct from
      `act.Encryption`, which versions the password-hashing method.)

    * The PROVIDER operational private key is per provider, not per
      account, and lives in a provider-level key store (its public half
      is published in the directory host record, PART 10). It is NOT in
      the `act` table.

  Both are usable by the provider WITHOUT the user's password, so the
  provider can sign (and issue delegations) for background and relayed
  sends. This is T1: a home provider can both act as the account and
  re-delegate to itself. Direction (PART 15) moves the ownership private
  key to the user's device, after which a provider can only act with a
  device-issued, expiring delegation and no longer stores
  `act.OwnPrvKey`.


## PART 6 - DELEGATION AND THE CREDENTIAL CHAIN

There is NO shared "account record" and NO account directory. Everything
a verifier needs to authenticate a message travels WITH the message and
is checked against the self-certifying actId plus the (separately signed)
provider directory. This is possible because Desktop synchronization and
automatic failover are explicitly NOT required (a client that cannot
reach the user's current home provider simply fails), so there is no need
to publish an account's set of home providers anywhere.

6.1  The credential chain

  A verified message carries a chain that a receiver checks bottom-up
  against the one thing it inherently trusts about the account -- the
  actId itself. The chain includes the genesis-signed Binding
  ([Identifiers](Identifiers.md) PART 6 / PART 11):

     actId                      the account identity (`hop[0].ActId`,
                                PART 7), = fingerprint(GenesisPubKey)
       ^  actId == fingerprint(GenesisPubKey)      [self-certifying;
          version-aware via act.FpVersion / AccountKeys.fingerprintsTo —
          [Identifiers](Identifiers.md); v1 algorithm unchanged]
     GenesisPubKey              carried in the Binding ([Identifiers](Identifiers.md) PART 6)
       ^  signs
     Binding (Version)          "genesis authorizes OwnId as current"
       ^  names OwnPubKey; ownId == fingerprint(OwnPubKey)
     OwnPubKey                  the current operating key, carried in
                                the Binding
       ^  signs
     Delegation                 "OwnId authorizes provider P until T"
       ^  authorizes P
     provider key of P          from the signed directory host record
                                (PART 10)
       ^  signs
     hop[0].HopSig              P's signature on hop 0, whose signed
                                bytes include ActId (PART 7)

  No lookup of the account is required at any step: Binding and OwnPubKey
  arrive with the message and are proven by the self-certifying checks;
  the only external fetch is provider P's key from the directory, which
  the runtime already caches for routing. See [Identifiers](Identifiers.md) PART 11.

6.2  The delegation certificate

  A delegation is issued by the ownership key and provisioned to a
  home provider. The *signed* form is self-contained (it names the
  account, the operating public key, and the provider):

    Delegation (canonical, what DelegSig covers) {
      ActId       : <account ID>                    ([Identifiers](Identifiers.md) PART 4)
      OwnPubKey   : <base64 Ed25519 public key>     (32 bytes)
      PrvId       : <prvId authorized to sign for ActId>
      NotAfter    : <expiry, millis-since-epoch>
      Nonce       : <random>
    }
    DelegSig    : <base64 Ed25519 sig by the ownership key over
                   canonical Delegation>

  The delegation is carried WHOLE. Every field above travels on the
  message and is stored in the same shape (ActDb keeps the canonical
  JSON). There is ONE representation of a Delegation, so `verify` needs
  no `Context` and a credential can be checked in isolation — in a test,
  in a migration, or in a log. Re-deriving ActId / OwnPubKey / PrvId at
  verify time from their neighbours would save roughly 120 bytes on the
  wire and cost a second shape per type, a verifier that cannot run
  standalone, and the diagnosability of every mismatch below.

  A verifier ACCEPTS a delegation only if ALL hold:
    (a) the accompanying Binding verifies under the account's genesis
        key and names this OwnPubKey as current
        ([Identifiers](Identifiers.md) PART 6 / 11);
    (b) `Delegation.ActId` == `Binding.ActId` == `hop[0].ActId`;
    (c) `Delegation.OwnPubKey` == `Binding.OwnPubKey`;
    (d) `Delegation.PrvId` == `hop[0].SignerPrv` — the delegation
        authorizes the provider that actually signed the origin hop;
    (e) DelegSig verifies under OwnPubKey over canonical Delegation
        excluding DelegSig — the ownership key really authorized this
        provider;
    (f) NotAfter is in the future (subject to clock skew, PART 13).

  Because of (a) and (e), a forged delegation either fails the Binding
  chain to the actId or fails the ownership signature. A delegation
  issued to prvA cannot ride on a hop signed by prvB: check (d) rejects
  it, and says so — the failure names the mismatched provider instead of
  surfacing as an opaque bad signature. There is no delegation registry.

6.2a  Binding on the message

  The Binding's canonical signed form is unchanged
  ([Identifiers](Identifiers.md) PART 6):

    Binding (canonical, what GenesisSig covers) {
      ActId, GenesisPubKey, OwnId, OwnPubKey, Version, NotBefore
    }

  As with the Delegation, the Binding is carried WHOLE and stored in the
  same shape (actId is the persistence primary key). `Binding.verify()`
  therefore takes no `Context`: it re-derives ownId from OwnPubKey
  ([Identifiers](Identifiers.md) PART 5.1), checks
  actId == fingerprint(GenesisPubKey) under the account's stored
  FpVersion, and checks GenesisSig. The shipped
  `com.domatar.crypto.Binding` and `Delegation` classes already have
  exactly these fields and this self-contained `verify`; this design
  leaves both classes alone.

6.3  No account record, no lookup

  Contrast with a published allow-list of home providers: that would
  require a stored, replicated, cacheable record and a fetch per unknown
  account. It is unnecessary here. The only reasons to publish such a set
  would be (i) authorization, which the carried delegation already
  provides; (ii) Desktop synchronization, which the Desktop app now
  handles itself; and (iii) failover discovery, which is explicitly a
  non-goal (we prefer to fail). With all three gone, the set is never
  published: authorization rides in the message, and no party ever needs
  to enumerate an account's providers.

6.4  Issuing, renewing, and abandoning delegations

  Issuing / adding a home provider. The ownership-key holder (a signing
  provider today; the user's device in PART 15 Direction) signs a
  Delegation naming the new provider and provisions it to that provider,
  which stores it and attaches it to the origin hops it signs. This is a
  LOCAL act of the ownership-key holder; it involves no directory and no
  global record.

  Renewing. Before NotAfter, the ownership-key holder issues a fresh
  Delegation with a later NotAfter. Providers renew as long as they
  remain authorized.

  Abandoning / removing a home provider. Simply STOP renewing that
  provider's delegation. Its last delegation lapses at NotAfter, after
  which no verifier will accept its origin signatures for the account.
  The actId is unchanged; no object is rewritten; the abandoned provider
  may be the original creating provider. This realizes G4. A signing
  provider that still holds the ownership key can self-renew until a
  rebind ([Identifiers](Identifiers.md) PART 10).

  De-authorization latency is therefore bounded by the delegation
  lifetime (PART 13), not instantaneous. There is no proactive
  revocation (a non-goal, PART 1.2): choose a NotAfter short enough that
  lapse-based removal is timely for the deployment.

  Governance caveat (T1). The ownership key lives on the home
  provider(s), so a provider that holds it can renew its OWN delegation
  indefinitely -- it cannot be forcibly abandoned while it holds that
  key, except by rebind. Direction (PART 15) moves the ownership key to
  the user's device: providers then depend on device-issued, expiring
  delegations, so ceasing to renew genuinely de-authorizes them.

6.5  Caching (optimization, not required)

  A verifier MAY cache an accepted Delegation by (ActId, Provider) until
  its NotAfter, so senders need not retransmit an unchanged delegation on
  every message. This is a pure optimization; the default, fully
  stateless behaviour is to carry the delegation with each message.


## PART 7 - Q1: ORIGIN AUTHENTICATION ("THE ACCOUNT IS CORRECT")

7.1  What is signed

  There is NO separate origin signature and NO Origin object. The
  account assertion is a FIELD OF HOP 0, inside the bytes that hop 0's
  `HopSig` already covers (PART 8.3):

    hop[0].ActId : <fingerprint actId asserted by hop[0].SignerPrv>
                   absent when no account is being asserted

  So the provider's one signature on hop 0 says both "I relayed this
  route" and "I assert this account for it." The envelope additionally
  carries the Delegation + DelegSig (PART 6.2) authorizing
  `hop[0].SignerPrv` to sign for that ActId, and the Binding chaining
  OwnPubKey to the actId. Together they form the credential chain of
  PART 6.1.

  Why one signature, not two. Separability is a property of the CHECKS,
  not of the signatures. Q1 asks "is an account asserted here and
  authorized?"; Q2 asks "is the chain intact?"; both remain independently
  answerable with ActId inside hop 0. One canonical map, one signature.

  ActId appears at index 0 ONLY. A hop at i > 0 carrying an ActId is
  rejected (PART 8.9 check (h)): an intermediary does not get to assert
  an account mid-chain. Later hops are authenticated by Q2, not by
  re-asserting the origin. A receiver that needs "this account started
  this operation" verifies Q1. A receiver that needs "this body
  travelled this object path" verifies Q2.

7.2  Where signing happens: the browser never signs

  The ownership key lives on the home provider, so hop 0 — and with it
  the account assertion — is signed at the browser trust boundary
  (DomatarServlet, [Domatar](../Domatar.md) PART 6.1):

    1. The browser authenticates to its home provider with the existing
       cookie/token login (UNCHANGED from [Domatar](../Domatar.md) PART 6).
    2. Having verified the cookie, the home provider roots the
       operation (PART 8.7): it mints hop 0 with `ActId` set, signs it
       with its provider key, and attaches Binding and Delegation.

  Thus the `(usrId, token)` bearer mechanism SURVIVES on the
  browser->home-provider hop only. Inter-object and inter-provider hops
  are authenticated by signature, not by token. The browser holds no key
  and performs no crypto. Direction (PART 15) would have the device sign
  hop 0 and issue delegations.

7.3  Verification

  A receiving provider, at the Msg HTTP boundary, accepts the origin as
  authentic iff ALL hold:

    (a) `hop[0].ActId` is present and is a fingerprint actId (PART 8.8).
    (b) `hop[0]` verifies as a hop (PART 8.9) under `hop[0].SignerPrv`'s
        Ed25519 key from the signed directory record (PART 10). Since
        ActId is inside those signed bytes, this is also the account
        assertion. Replay of the origin is replay of hop 0; there is no
        second nonce.
    (c) The accompanying Binding and Delegation are valid for that ActId
        and name `hop[0].SignerPrv` as PrvId (the PART 6.2 checks).

  Interpretation: "a provider the account itself delegated to vouched
  for this hop 0, now." A provider with no valid delegation (A2) fails
  (c); one that swapped in a different hop 0 (A3) or replayed one (A4)
  fails (b) or the hop-0 nonce check.

  An absent `hop[0].ActId` does not mean the path is unsigned. Hop 0 is
  still present and provider-signed (PART 8.8). It means no *account* is
  being asserted, which is a distinct and visible provenance level
  (`Trust.PATH`, PART 7.5), not a failure.

7.4  Why A2 (the impersonation attack) fails

  A malicious provider prvEvil crafts a message claiming a victim's
  ActId. To pass 7.3 it must present a Delegation for that ActId naming
  prvEvil as PrvId, chained to the victim's current ownId
  ([Identifiers](Identifiers.md) PART 11). prvEvil does not hold the
  ownership key; it cannot forge the ownership signature ((e) of
  PART 6.2), and it cannot substitute its own key because the
  self-certifying checks would fail ((a) of PART 6.2). Nor can it reuse
  the victim's genuine delegation on a hop of its own: that delegation
  names the victim's real home provider, and (d) of PART 6.2 compares
  PrvId against `hop[0].SignerPrv`. The usrId is never consulted for
  authorization -- only the credential chain to the actId is.
  Impersonation therefore fails cryptographically, not by policy.

7.5  Trust levels: "verified" is not a boolean

  PART 8.13 requires that an unattributed message be a VISIBLE
  provenance level for `hasRights` and charging, "not a silent absence."
  A boolean cannot express that: it collapses "provider-signed route,
  no account asserted" into the same value as "no usable provenance at
  all," at exactly the call sites that must tell them apart. So the
  stamped verdict is three-valued:

    Trust.ACCOUNT   Q1 passed (PART 7.3). An actId is proven, and a
                    provider the account delegated to vouched for it.
    Trust.PATH      the hop chain verified (PART 8.9) but hop 0
                    asserts no account. The ROUTE is attributable to
                    named providers; no account bears consequence.
    Trust.NONE      no usable provenance: the chain is missing, broken,
                    unsigned, or its signers are unknown.

  For source compatibility `isVerified()` is a derived accessor meaning
  `trust == ACCOUNT`, so `Auth.isVerified` and the existing `hasRights`
  overrides keep their present meaning ([Domatar](../Domatar.md)
  PART 6): "the origin hop verified, and it was signed by a provider
  holding a valid delegation from the named account," not "some provider
  asserted a token."

  Authorization (hasRights, [Domatar](../Domatar.md) PART 6.3) stays
  separate: signatures answer WHO, hasRights answers MAY. A valid
  signature proves the caller's actId, not its right to act on the
  destination.

  `trust`, `actId`, and `contextId` are stamped by the receiving
  platform from its OWN verification verdict (PART 16, `Verdict`). They
  are never taken from the wire.


## PART 8 - Q2: PATH PROVENANCE (SIGNED HASH CHAIN)

Q2 is a SEPARATE mechanism from Q1. Q1 proves the origin account; Q2
proves the route. The path is a causal chain of distinct messages:
each handler composes a NEW body to the next object
([Domatar](../Domatar.md) PART 17). Q2 makes that chain tamper-evident
by having each hop carry a signed link back to its parent — the
hash-chaining idea from blockchains, WITHOUT any consensus layer.

8.1  Custody: the platform, not the message

  The signed path does not live in the application `JsonMsg`. Apps
  compose bodies. The platform owns provenance.

  In memory the chain and its credentials live in a `Provenance` object
  (PART 8.2) held privately by `HttpClient` and by `Msg`. `HttpClient`
  is the only code that roots or extends it. On the wire it travels in a
  platform slot *beside* the app message — a second POST parameter
  (PART 11.1) — never inside `JsonMsg.Head`. `JsonMsg` has no Sec API.

  Provenance is NOT reachable from anything a handler receives. A
  handler gets a `DomatarMsgClient` (a capability that can only extend
  the chain it was given) and a `Context` that carries the stamped
  verdict but no crypto. Consequently:

    * An app that does `new JsonMsg()` before `send` cannot restart
      the chain. There is nowhere to put a forged envelope.
    * `HttpClient.send` always builds the outbound envelope from its
      own private `Provenance`, and ignores any bytes an app might
      have smuggled into the body that look like provenance.
    * No hop array is exposed as a public field anywhere. `final` on a
      Java array protects the reference, not the contents; a public
      `Hop[]` on a handler-visible object would BE somewhere to put a
      forged envelope, and would alias mutably across fan-out
      branches. The chain is wrapped (`Path`, PART 16) and hands out
      only copies and projections.
    * There is one lineage. The object list is a projection of the
      verified path (PART 8.5).

  App JARs run in-process and could in principle call
  `ProviderKeyStore` directly. That is T1/A5, not A6. G6 is the claim
  that the *default* path through the API cannot be used as a
  provenance oracle. Reachability, not documentation, is what makes
  that true: apps never import `com.domatar.crypto` at all (PART 16).

8.2  Context and Provenance

  Two objects, because they have different audiences and different
  serialization rules. Mixing both in one handler-visible type is how
  the envelope became an app-facing surface.

  `Context` — WHO and WHERE. Handler-visible, informational plus the
  stamped verdict (`trust`, `actId`, `contextId`). Constructed only by
  the platform (`DomatarServlet`, `Msg`, `HttpClient`);
  apps never call `new Context(...)`. Its informational fields (usrId,
  usrName, usrIp, token, httpHeaders) travel in `Head.Context` for
  logging and cookie continuity and do not authorize. `trust`, `actId`,
  and `contextId` are stamped from a local `Verdict` at a trust
  boundary and are never read from the wire.

  `Provenance` — the CHAIN and the CREDENTIALS: a `Path` plus the
  per-operation Delegation and Binding. Platform-only, never handed to
  a handler, never a field of `Context` or of `JsonMsg`. The Delegation
  and Binding are per-operation and sit here once, not on every hop; a
  non-root send carries them through unchanged and appends one `Hop`.

  Members of both: PART 16.

8.3  Hop

  One element of the path is one send. *n* sends, *n* elements. All
  fields are final; elements are immutable after creation and are
  shared by reference across fan-out branches, which is safe precisely
  because they are immutable and because the enclosing array is never
  published (PART 8.1).

  The class keeps its shipped name and package,
  `com.domatar.crypto.Hop`. The ARRAY is the path and the ELEMENT is a
  hop. A signed value type that calls `ProviderKeyStore` belongs beside
  the crypto helpers that canonicalize it. Members: PART 16.

  `domId` is not a stored field. It is `dstDomId`: the current object,
  the destination of the hop that arrived here.

    hop[i].SrcDomId == hop[i-1].DstDomId     for i > 0
    hop[0].SrcDomId == the UI id             (PART 8.5)
    hop[i].DstDomId == the object that receives hop i

  Both `SrcDomId` and `DstDomId` belong in the signed bytes of every
  hop. `DstDomId` binds the destination of the in-flight hop: `BodyHash`
  covers the body only, and `Head.DstId` is not in the body, so omitting
  dest from the signature is a redirect. `SrcDomId` names which object
  on the signing provider sent; a provider hosts many objects. Once dest
  is in a hop's signed bytes it stays there: `PrevHopHash` covers the
  previous hop including its signature, so dest cannot be stripped from
  earlier hops when they cease to be last.

  `BodyHash` covers the Body ONLY, never the Head. The servlet and
  `addRequestHead` mutate heads around the signing point, so a hash over
  the whole message would be invalidated by ordinary platform work. The
  destination is bound instead by `DstDomId` being a signed field, which
  is why omitting it would license a redirect.

  There is NO `Message` / retained-body field on a hop. Check (d) of
  PART 8.9 hashes the RECEIVED body against `bodyHash`. Where the
  canonical body is wanted for logging or for a returned causal tree, it
  belongs on the log record keyed by `contextId`, not inside the signed
  type. `Hop.sign` takes the canonical bytes as a parameter and retains
  nothing.

  There is no `Seq` field either. Position is pinned transitively:
  `PrevHopHash` covers the previous hop INCLUDING its signature, index 0
  is identified by an empty `PrevHopHash`, and hop 0 is anchored by
  Q1 or by explicit unattribution (PART 8.13). The array index is the
  sequence.

8.4  One form, and what is NOT in it

  A hop has ONE shape. The in-memory object and the wire map carry the
  same fields, and `HopSig` is computed over the canonical map excluding
  `HopSig` itself. Signed field set (canonical order):

    ActId (index 0 only, when an account is asserted), BodyHash,
    ContextId, DstDomId, Nonce, PrevHopHash, SignerPrv, SrcDomId,
    Timestamp

  Absent fields are omitted from the canonical map rather than encoded
  as null, so "no account asserted" is a well-defined signing input
  without a special case (PART 4).

  Bodies are NOT in it. A provider that handles hop *i* can read hop
  *i*'s body (accepted, PART 1.2). It cannot read hop *i−1*'s body from
  the envelope. Carrying bodies forward would leak hop-2 payment data to
  a hop-6 shipping provider, grow the envelope with the sum of bodies
  (quadratic in depth if recorded messages nested their own paths,
  exponential), and contradict the redaction required of a returned
  causal tree (the saga / compensation work, Direction, PART 15).

  REDACTION IS NOT POSSIBLE ON A SIGNED HOP, and this constrains later
  work. `HopSig` covers `SrcDomId` and `DstDomId`, so dropping either
  one to hide a callee's interior breaks the signature: a "redacted
  hop" cannot be a hop. Any future partial disclosure (the saga /
  compensation work, Direction, PART 15, wants signed opaque handles
  for a returned causal tree) must therefore either be a SEPARATE signed
  object, or rely on
  commitments placed in the signed set BEFORE the format is frozen —
  e.g. a salted `DstDomIdCommit` signed in place of the plaintext, with
  the plaintext carried as an unsigned adjunct that a redactor drops.
  This design does NOT add such commitments, because the salting, the
  contiguity check (PART 8.9 (c)) and the handle-redemption semantics
  are the Saga spec's to settle, not this one's. The decision is
  recorded here because it cannot be retrofitted without an envelope
  version bump (PART 11.1).

8.5  The UI hop, and the object-list projection

  The first message of a user operation comes from the UI, outside
  Domatar. The browser is not an object and cannot sign.
  `DomatarServlet` is the trust boundary that roots the chain.

  Hop 0's `SrcDomId` is a synthetic UI id minted by the servlet, of
  the existing shape `(prvHstId, "ui", actId, "uiObj")`. The browser
  does not supply it. A client-supplied source is forgeable.

  That UI id is NOT a path element. `path[0].DstDomId` is the first
  real Domatar object — the thing the UI addressed. The UI id exists
  only as hop 0's signed `SrcDomId`.

  The object list formerly stored as `Context.domIdPath` is the
  projection

    [ path[0].SrcDomId, path[0].DstDomId, path[1].DstDomId, ... ]

  i.e. UI, then every real object. `LogsImpl` and any other reader of
  the old array consume this projection. It is derived, never
  independent state.

  Typing. A `Hop` stores `srcDomId` / `dstDomId` as STRINGS, because
  that is what gets canonicalized and signed. `Context.domIdPath` was
  `DomId[]`, and that is what `LogsImpl` consumes. So the projection
  re-parses: `Path.domIdPath()` returns `DomId[]`, parsing each signed
  string once, and returns a fresh array per call so no caller can
  mutate shared state. Callers that only log may take the string form.

8.6  ContextId

  `contextId` is the identity of the operation lineage: the same value
  on every hop of one user action, including every branch of a fan-out,
  and a different value from every other action.

  It is not a login session. The act table's token slots are sessions;
  two devices of the same person are two sessions and many ContextIds.
  It is not `actId`. It is not a Java-object identity.

  MINTED, NOT DERIVED. A root send mints `ContextId` as >= 16 random
  bytes; every appended hop copies it verbatim. It is a SIGNED FIELD of
  every hop (PART 8.4), so every hop's `HopSig` covers it. A signed
  field is verifiable at every hop independently, including a redacted
  or partial chain; "all hops of one operation share a lineage" is a
  CHECKED invariant (PART 8.9 check (i)); there is no formula that must
  work both before and after signing.

  It also makes prefix-verification caching safe (PART 8.11).

  TRUST RULE. At each HTTP trust boundary `contextId` is stamped onto
  `Context` from the verified `Path`, never from a wire `Context` field.
  An id copied from a wire Context field is forgeable by any relay; one
  read out of a verified hop is not. The member on `Context` is a
  convenience for handlers and need not be serialized at all.

  `Context.getContextId()` is the handler-facing accessor. Saga /
  charging / re-entry work (Direction, PART 15) keys off this
  value; PART 8.14 states what that work must supply, because this
  design deliberately persists no hop.

8.7  Root and send

  Extending the chain is correct both when a relay passes the same body
  onward and when a handler composes a new one. Starting a chain is a
  different operation. The distinction is an explicit API, never
  inferred from whether an app reused a `JsonMsg`.

    root(dst, msg)      mint hop 0, minting ContextId and setting
                        ActId. Platform only (DomatarServlet,
                        bootstrap, directory bring-up); not a member
                        of `DomatarMsgClient`. An app that can root
                        can launder provenance. Rejected if the
                        client already holds a non-empty chain.

    send(dst, msg)      append a hop, copying ContextId. This is what
                        apps call, through `DomatarMsgClient`. The
                        client a handler receives is a capability that
                        can only extend the chain it was given.
                        Rejected if the chain is empty.

  There is no separate `forward`. A relay hop is exactly one where
  `hop[i].BodyHash == hop[i-1].BodyHash`, which any receiver can observe
  without being told. Relay is a derived predicate over the verified
  path.

  `HttpClient.derive(Context)` with an arbitrary context is not an app
  API and is removed. Its shipped caller is `ActManagerImpl.addAct`,
  which hand-builds a whole `Context` with a new `actId`, `verified` set
  true by app-tier code, and an EMPTY path — i.e. it restarts the chain,
  which is the very bug this PART exists to close. A token-only copier
  cannot serve that caller, because the caller legitimately needs a new
  identity: a just-created account asserting itself for the first time.
  That is a new ROOT, not a derived context. It is served by

    rootAs(actId, dst, msg)   platform only, restricted to the
                              account-creation flow: mints a fresh
                              hop 0 and ContextId under the newly
                              created actId and attaches that
                              account's binding and delegation.

  and by a narrow `withToken(String)` on the client for the genuine
  token-stamping case, which carries `actId`, `trust`, `contextId` and
  the chain through unchanged and can alter nothing else.

8.8  Hop always; the account assertion conditional

  Every send produces a hop. The provider key signs it, so a hop can
  exist without an account. `hop[0].ActId`, Binding, and Delegation are
  present only when the root names a real fingerprint actId and a
  binding and delegation are available.

  Synthetic actIds (`act@act`, `login@act`) therefore still carry a
  provider-signed hop 0. They are not "internal, so unsigned":
  AccountsWui sends `AddAct` / `AttachProvision` to another provider,
  carrying passwords, delegations, bindings, and sometimes the
  ownership private key. The hop is the message-level evidence of
  which provider sent it, and it is the `contextId` for the one flow
  that moves keys between providers.

  `Path.verify` skips the Q1 checks when `hop[0].ActId` is absent and
  returns `Trust.PATH`: an account-less root verifies as far as the
  hops go. `Trust.ACCOUNT` requires Q1 (PART 7.5).

8.9  Verification

  Verification is a platform operation at an HTTP trust boundary
  (`Msg.doAction`). It is not gated on `ObjImpl.requiresPath()`.
  That hook, defaulting to false and overridden nowhere, is why Q2
  never ran and why the chain-restart bug survived.

  Every HTTP receive verifies the path and records the verdict.
  `requiresPath()` remains as *policy only*: whether a `Trust.NONE` or
  `Trust.PATH` verdict is fatal for that handler (reject rather than
  run), not whether verification happens.

  In-process sends (`sendLocal`) do not re-verify. The chain is
  platform-held Java objects this process just built. They still
  *sign* a hop, because the eventual HTTP receiver needs a contiguous
  object-level chain.

  Verification returns a `Verdict` (PART 16) — a `Trust` level plus the
  proven `actId`, the `contextId`, and a reason string for logs. It is
  the ONLY route from wire bytes to a trusted `actId`. The receiver
  accepts the path iff:

    (a) For every i > 0, PrevHopHash == SHA-256(canonical path[i-1]
        including HopSig) — the chain is unbroken. Index 0 has empty
        PrevHopHash. The array index is the sequence; it is not a
        stored field.
    (b) For every i, HopSig verifies under SignerPrv[i]'s directory
        key (PART 10). A hop with a null HopSig fails this check.
    (c) For every i > 0, path[i].SrcDomId == path[i-1].DstDomId
        (contiguous). path[0].SrcDomId is the UI id (PART 8.5).
    (d) SHA-256 of the received canonical Body equals path[last].bodyHash.
    (e) Replay / expiry checks (PART 13) pass for the FINAL hop.
    (f) Timestamps are non-decreasing: path[i].Timestamp >=
        path[i-1].Timestamp for every i > 0, and
        path[last].Timestamp - path[0].Timestamp does not exceed the
        configured maximum chain age. Without this, a fresh final hop
        can carry an arbitrarily old prefix past check (e), which
        checks only the last hop.
    (g) Depth does not exceed the platform cap (PART 8.11).
    (h) ActId appears on path[0] only. A hop at i > 0 carrying an ActId
        is rejected (PART 7.1).
    (i) Every hop carries the same ContextId (PART 8.6).

  Then, if path[0].ActId is present, the Q1 checks of PART 7.3 decide
  between `Trust.ACCOUNT` and rejection; if it is absent, the verdict
  is `Trust.PATH`. A path that fails any check above yields
  `Trust.NONE`.

  An HTTP message whose path is missing, empty, or unsigned is
  rejected (PART 8.10), except the directory-lookup carve-out
  (PART 10.3).

8.10  Unsigned root (pre-bootstrap only)

  True pre-bootstrap, before `/Setup` mints a provider key, may
  produce an unsigned hop 0 so bring-up is never blocked by the
  absence of a key.

    * In-process only. An HTTP message with an unsigned root is
      REJECTED. Otherwise "unsigned is allowed" is a downgrade:
      strip the signature, claim pre-bootstrap. Pre-bootstrap work
      is local by definition — by the time `/Setup` publishes the
      PubKey, the key exists.
    * Never verified, never authoritative. Check (b) of PART 8.9
      already fails a hop with a null HopSig, so an unsigned root can
      exist and can never pass. That is its only remaining function:
      it lets bring-up traffic have a lineage id without granting it
      any trust.
    * `contextId` still works, and needs no special case: it is a
      minted random field (PART 8.6), not a hash of signed bytes.

8.11  Depth cap and verification cost

  Bytes per hop are small (hashes, not bodies). The real cost is
  verification: a receiver at depth *d* performs *d* signature checks.
  The platform enforces a hard maximum hop depth
  (provider-configurable). This is a safety cap, not an app budget;
  agent iteration caps (AIAgent.md) are a different axis and do not
  cover a cycle inside a single tool call.

  The honest total. Verifying an N-hop chain is N signature checks at
  each HTTP boundary it crosses, so an operation that crosses a
  boundary at every hop costs O(N^2) across the operation, not O(N).
  The mitigation is a verified-prefix cache keyed on
  (ContextId, PrevHopHash): a provider re-entered on a lineage it has
  already verified re-checks only the new tail. This is sound BECAUSE
  `ContextId` is a signed field (PART 8.6) — with a derived id the
  cache key would itself be unverifiable. The cache is an optimization;
  correctness never depends on a hit. It is NOT implemented; it remains
  the named mitigation for the O(N^2) cost.

  Cost of a non-root send: one Hop signature, no database. Binding /
  Delegation work happens at the root only. Ordinary sends are one
  signature; they do not re-read Binding / Delegation or sign a second
  origin object.

8.12  Requests only

  The hash chain covers request hops, not responses. A chain whose
  Src/Dst reverse halfway loses the contiguity property that makes
  PART 8.9 simple.

  A returned causal tree (the saga / compensation work, Direction,
  PART 15) is a signed attachment on the reply, not more hops.
  Redaction of that tree is specified there.

8.13  Tradeoffs

  * Size. Each hop adds a signed element to the envelope (not to
    stored rows). Deep chains grow the message; the depth cap bounds
    this.
  * Disclosure. The endpoint learns the upstream *route* (objects
    and providers), not upstream *bodies* (PART 8.4). This is
    inherent to G2 and the opposite of anonymity (PART 1.2).
  * Interior honesty. A provider can sign hops with any SrcDomId /
    DstDomId in its own namespace (A5). The chain is a sequence of
    per-provider attestations.
  * Truncation. Silently terminating a chain and starting a new one
    is provenance laundering: a receiver cannot distinguish a
    laundered short chain from a genuinely short one. A root hop
    MUST either carry `ActId` (naming an account that bears the
    consequence) or be explicitly unattributed. Unattributed is a
    visible provenance level (`Trust.PATH`, PART 7.5) for `hasRights`
    and charging, not a silent absence. Handler-level "pruning as
    policy" is not permitted.
  * Cost. O(N) signature verifications per HTTP boundary, O(N^2) over
    an operation that crosses a boundary at every hop (PART 8.11).

8.14  The chain has no durable home

  Stated plainly because dependent work assumes otherwise. `Hop` and
  `Path` are the only security objects in this design with NO
  persistence: they exist in the heap for one request and on the wire
  for one POST. Bindings, delegations, provider keys and nonce windows
  all have durable homes (PART 5.4, PART 10.1); the path does not. The
  only part that reaches storage is the PART 8.5 object-list
  projection, through the log.

  Consequences:

    * There is no after-the-fact audit of a route. Once a request
      returns, the proof that a message travelled a given path is
      gone unless something recorded it deliberately.
    * No party ever holds the TREE. At a fan-out the branching object
      is the only one that knows both branches; PART 8.12 makes the
      chain request-only, so nothing comes back. Each receiver holds
      exactly its own root-to-here BRANCH.
    * Therefore effect rollback (the saga / compensation work,
      Direction, PART 15) cannot be built on this PART as it stands.
      It needs, and must specify for
      itself: durable per-participant records keyed by `contextId`
      (what I did, for whom, and whom I called), and either a returned
      causal tree or a cascading protocol that needs only each node's
      own out-edges. This design supplies the correlation key
      (`contextId`, verifiable at every hop, PART 8.6) and the
      per-message granularity, and nothing else.


## PART 9 - Q3: WIRE CONFIDENTIALITY (TLS)

9.1  Transport encryption

  Every inter-provider dispatch ([Domatar](../Domatar.md) PART 5, the HTTP POST
  to `http(s)://<domain>/domatar/Msg`) uses TLS 1.3 when
  `DOMATAR_WIRE_SCHEME=https` (the default). Local simulation may set
  `WireScheme=http` ([Login protocol](../apps/Login-Protocol.md) PART 15).

9.2  Why one hop is enough here

  TLS protects a single network hop. That is sufficient for Domatar
  because routing is point-to-point: a sender's provider resolves the
  destination's provider and POSTs DIRECTLY to it ([Domatar](../Domatar.md)
  PART 5); messages are not onion-routed through a chain of relays at the
  network layer. The logical multi-hop path (Q2) is about
  object-to-object causality, and its INTEGRITY is already protected
  end-to-end by the signed hash chain (PART 8). There is no
  message-level encryption: Q2 covers path integrity, Q1 covers origin
  integrity, and TLS covers per-link confidentiality. A relay provider
  can read the body of a hop it handles (accepted, PART 1.2). It does
  not receive other hops' bodies from the envelope (PART 8.4).

9.3  Mutual TLS (optional, for provider-to-provider trust)

  Where a provider must authenticate the PEER provider -- notably to
  authenticate directory writes (`UpdateHst`, [Domatar](../Domatar.md) PART 4.2
  / 16.4) -- use mTLS, with the provider operational key (PART 5.2) as
  the client certificate identity. This gives the directory a
  cryptographic basis for "who is allowed to update this host record."
  Cross-replica membership/binding writes ([Login protocol](../apps/Login-Protocol.md)) and
  cross-replica Desktop reconcile (PullApps / MergeApps;
  [Desktop](../apps/Desktop.md)) are authorized by the existing credential
  chain (PART 6) plus owner-match on the Desktop handler; no new published
  directory. A rebind ([Identifiers](Identifiers.md) PART 10) cuts off an evicted
  provider's Desktop access and is tile-neutral ([Desktop](../apps/Desktop.md)
  PART 11). Attach transmits the ownership key over TLS (this PART); the
  sim may bypass TLS ([Login protocol](../apps/Login-Protocol.md) PART 15).

9.4  Bootstrap

  The directory endpoint and provider certificates chain to the directory
  root (PART 5.3 / PART 10), pinned in provider configuration. This is
  the out-of-band trust anchor that lets a fresh provider verify its
  first peer.


## PART 10 - KEY DISTRIBUTION AND TRUST ANCHORS

  Host records carry PubKey / RecordSig. HstsImpl.GetHst returns them;
  UpdateHst accepts PubKey and signs the record when DirectoryRootPrivKey
  is configured. HttpClient.getRemoteHst verifies RecordSig against the
  pinned DirectoryRootPubKey before caching. Bootstrap publishes the
  provider operational public key via
  DomatarProviderInstall.publishProviderKey()
  (ProviderKeyStore.getOrCreate()). DirectoryTrust.signRecord() /
  canonicalHstRecord() produce the signed form. provider.config.txt
  documents DirectoryRootPubKey / DirectoryRootPrivKey.

10.1  Two distribution channels

  * ACCOUNT keys: self-certifying and MESSAGE-CARRIED. The genesis and
    ownership public keys travel inside the Binding and Delegation
    (PART 6.1; [Identifiers](Identifiers.md) PART 6 / 11) and are proven
    against the actId; there is no account key distribution service and
    no account certificate authority.

  * PROVIDER keys: distributed via the directory host record, which
    carries a public-key column and is signed by the directory root key
    (PART 5.3; [Domatar](../Domatar.md) PART 4.2). The directory remains
    the authority for PROVIDER identity and host routing only.

  hst record ([Domatar](../Domatar.md) PART 4.1 / PART 7):

    hst ( HstId, Domain, PrvId, Version, FetchedAt,
          PubKey      -- provider Ed25519 public key (base64)
          RecordSig ) -- directory-root signature over the canonical row

10.2  Trust chain summary

    directory root key            (pinned, out of band)
      -> signs hst records        => provider PubKeys are trusted
         -> provider keys sign     => path hops, hop 0 of which also
                                      carries the account assertion
                                      (Q2 and Q1)
    account genesis / ownership keys  (self-certifying: actId ==
                                   fingerprint(GenesisPubKey);
                                   [Identifiers](Identifiers.md) PART 4)
      -> Binding then Delegation       => a provider is authorized to
                                      assert the actId on hop 0
                                      (PART 6)

  A receiver trusts exactly one pre-shared key (the directory root) for
  provider identity, and trusts account identity with no pre-shared key
  and no lookup at all (self-certifying, carried in the message).

10.3  Bootstrap carve-outs (avoid the recursion of [Domatar](../Domatar.md) PART 6.4)

  As with the directory exemption of [Domatar](../Domatar.md) PART 6.4, the
  operations needed to VERIFY must themselves be reachable WITHOUT prior
  verification, or dispatch deadlocks:

    * GetHst and provider-key lookup are PUBLIC and require no origin
      signature and no signed path.
    * A message whose sole purpose is to fetch provider keys/host records
      is exempt from the signed-chain requirement, exactly as directory
      traffic is exempt.

  Account verification needs no such carve-out because it performs no
  lookup: the credential chain is entirely in the message (PART 6.1).

  Provider-key rotation publishes a new hst record Version with an
  overlap window during which both old and new keys verify (PART 13).


## PART 11 - INTEGRATION WITH THE CORE RUNTIME

11.1  Envelope

  The application message remains a `JsonMsg` with Head (Method, SrcId,
  DstId, TimeStamp, informational Context) and Body. Provenance is not
  in the Head.

  The platform envelope, carried beside the message:

    {
      Ver         : 1
      Delegation  : { ActId, OwnPubKey, PrvId, NotAfter, Nonce,
                      DelegSig }              // whole, PART 6.2
      Binding     : { ActId, GenesisPubKey, OwnId, OwnPubKey,
                      Version, NotBefore, GenesisSig }
                                              // whole, PART 6.2a
      Path        : [ Hop[0], Hop[1], ... ]   // PART 8.4; always
                                              // present on HTTP
    }

  There is no envelope-level `ActId` and no `OriginSig`: the account
  assertion is `Path[0].ActId`, inside hop 0's signed bytes (PART 7.1).
  When it is absent the path still authenticates the route and the
  caller is `Trust.PATH`, not a verified account. Delegation / Binding
  MAY be omitted when the verifier is known to have cached them
  (PART 6.5).

  `Ver` is mandatory and is the FIRST field. Every other versioned
  artifact in the platform has one — `hst.Version`, `Binding.Version`,
  `act.FpVersion` — and this envelope is versioned so providers can
  upgrade independently (`Seq` removed from hops, `ContextId` and
  `ActId` added to hop bytes, no separate origin signature). A receiver
  rejects an envelope whose `Ver` it does not implement, and rotation
  across a version boundary gets the same overlap treatment as
  provider-key rotation (PART 13).

  Transport. `sendHttp` posts two parameters to
  `<scheme>://<domain>/domatar/Msg`: `Msg=` with the URL-encoded
  `JsonMsg` (Head + Body) and `Sec=` with the URL-encoded envelope
  above. The envelope sits beside the message, not inside Head.

11.2  The trust boundaries ([Domatar](../Domatar.md) PART 6.1)

  * DomatarServlet (browser entry): after cookie verification, `root`
    the operation (PART 8.7): mint hop 0 from the synthetic UI source
    to the addressed object with `ContextId` minted and `ActId` set
    when asserting an account, sign it, attach Binding / Delegation,
    and stamp `Context` with `Trust.ACCOUNT`.

  * Msg.doAction (HTTP inbound): parse `Sec=`, always verify the path
    (PART 8.9), and take the resulting `Verdict`. Stamp `Context` from
    that verdict only — `actId` and `trust` from Q1, `contextId` from
    the verified Path. Never trust a wire `Verified` flag, `ActId`,
    `contextId`, or object list. A prv only believes its own checks.

  * MsgHandler (in-process entry): the class was dead code (its whole
    body commented out, no caller) and was DELETED (KD7). The lesson
    remains: an in-process entry point that parses a Context off a
    message would be a trust boundary.

  * HttpClient.root / send / sendLocal / sendHttp: append a signed
    `Hop` from the client's own private `Provenance` before dispatch.
    `sendHttp` serializes the envelope as the `Sec=` parameter.
    `sendLocal` passes the Java objects through and does not re-verify
    — but it MUST construct the local handler's client from the
    EXTENDED provenance. The shipped `sendLocal` passes the
    un-extended `srcContext`, which drops in-process hops from the
    chain; that is the same chain-restart bug this PART exists to
    close, and it is one line.

  * Directory lookups are exempt (PART 10.3) and the exemption is a
    NAMED method (`sendDirectory`), not an artifact of `getRemoteHst`
    happening to call `sendHttp` directly. Naming it makes the
    carve-out auditable and stops a later refactor from silently
    widening or losing it.

11.3  Handler-facing surface

  A handler receives a `DomatarMsgClient` that can `send` and cannot
  `root`, plus a `Context` that carries `trust`, `actId`, `contextId`
  and the informational fields — and no crypto. The path projection is
  readable via the client as `domIdPath()`. `ObjImpl.hasRights` is unchanged in role
  ([Domatar](../Domatar.md) PART 6.3). `ObjImpl.requiresPath()` no
  longer gates verification; it only says whether a `Trust.NONE` /
  `Trust.PATH` verdict is fatal for this class.

  Apps never set the envelope. That rule is in
  [Writing-Apps](../apps/Writing-Apps.md).

11.4  Unchanged

  Authorization (ObjImpl.hasRights), routing by hstId, the class
  envelope and service qualifier, and object/link persistence are
  unchanged. This realisation stores `OwnPrvKey` on `act` (PART 5.4)
  and `PubKey` / `RecordSig` on `hst` (PART 10.1). v1 actId / ownId
  math, existing identifiers, and host names are unchanged.


## PART 12 - OPERATIONS

Account credentials are LOCAL to the ownership-key holder; they are not
directory operations. Host-record operations are authorized by the
directory (mTLS + directory-root signing, PART 9.3 / 10.1).

  Account operations (local to the ownership-key holder; PART 6.4):

    IssueDelegation (Provider, NotAfter) -> Delegation + DelegSig
        Signed by the ownership key. Provisioned to the named home
        provider, which attaches it to the origin hops it signs. Adding a
        home provider is exactly issuing it a delegation; renewing is
        re-issuing before NotAfter.

    (Removal has no operation: stop renewing, and the last delegation
     lapses at NotAfter -- PART 6.4.)

  Host / provider-key operations (directory):

    GetHst (HstId) -> { ..., PubKey, RecordSig }
        PUBLIC (10.3). Extends [Domatar](../Domatar.md) PART 4.2 with the key.

    UpdateHst / RegisterHst / MoveHst (HstId, ...)
        Authenticated by mTLS against an authorized provider identity and
        re-signed by the directory root (PART 9.3). Directory writes
        require a matching client certificate when mTLS is on
        ([Domatar](../Domatar.md) PART 16.4).

  There is deliberately NO GetAccountRecord / PutAccountRecord /
  RegisterAccount: an account publishes nothing and is looked up by no
  one (PART 6.3). Its identity and authorization travel in each message.


## PART 13 - REPLAY, EXPIRY, AND CLOCK SKEW

  * Nonce. Every Hop carries a random Nonce (>= 16 bytes). Each
    verifying provider keeps a sliding-window cache of seen
    (SignerPrv, Nonce) pairs and REJECTS duplicates within the window.
    Q1 has no nonce of its own: replaying the account assertion is
    replaying hop 0.

    SCOPE. "Provider" here must mean the DEPLOYMENT, not the process.
    This deployment is a single node per provider. The `NonceCache` is
    a per-JVM `LinkedHashMap` singleton; a Tomcat restart forgets up
    to SKEW seconds of nonces. A provider running more than one node
    MUST share the window (a common store, or sticky routing per
    SignerPrv).

  * Timestamp window. A message is accepted only if the *final hop's*
    Timestamp is within +/- SKEW of local time. SKEW is small (default
    120 s). Nonces need only be remembered for the window length.

  * Chain age and ordering. Because the freshness check applies to the
    final hop only, a fresh last hop can otherwise carry an
    arbitrarily old prefix. Hop timestamps must be non-decreasing and
    the total chain age is capped (PART 8.9 (f)). Both checks are
    free; the cap is provider configuration alongside SKEW.

  * Replay protection is NOT idempotency. These checks stop the same
    signed message being accepted twice. They say nothing about the
    same EFFECT being applied twice: a legitimately retried operation
    is re-signed with a fresh nonce and a fresh timestamp, and will
    pass. Effect-level exactly-once, where it is needed, is the
    handler's problem keyed on `contextId` — see the saga /
    compensation work (Direction, PART 15), where idempotent
    compensation depends on exactly this distinction.

  * Delegation expiry. Delegation.NotAfter bounds how long an
    authorization -- including one for a provider you have stopped
    renewing (abandoned) -- remains valid (PART 6.4). It is the sole
    de-authorization mechanism (no proactive revocation, PART 1.2).
    Recommended values depend on who holds the ownership key:

      - Server-held ownership key (T1, now): NotAfter is NOT a security
        lever against a compromised home provider, which can self-renew
        (PART 6.4). The lifetime only sets how fast a VOLUNTARILY dropped
        provider stops working, or how soon a rebind's leftover
        delegations lapse. Default ~1 hour (3600 s).

      - Device-held ownership key (PART 15 Direction): NotAfter IS the
        compromise-exposure window, since providers can no longer
        self-renew. Default ~24 hours, renewed by the device at ~50% of
        lifetime. A shorter exposure window than device availability
        allows needs the revocation service (PART 15) rather than
        shrinking NotAfter into lockout territory.

    Keep this delegation window DISTINCT from the per-message freshness
    window above (Timestamp SKEW ~120 s + the nonce cache): the latter
    stops replay of individual messages and stays short regardless of
    delegation lifetime.

  * Provider-key overlap. On rotation, both keys verify during an overlap
    window (PART 10.3) so in-flight messages are not rejected.

  These windows are provider configuration ([Domatar](../Domatar.md) PART 12).


## PART 14 - THREATS ADDRESSED AND RESIDUAL RISKS

14.1  Addressed

  A1 eavesdrop/tamper on wire   -> TLS 1.3 (PART 9).
  A2 provider impersonates acct -> the origin signature must be backed by
                                   a delegation chained to the account's
                                   current ownId; an outside provider holds
                                   no such delegation and cannot forge it
                                   (PART 6.2, 7.4).
  A3 relay tampers body/route   -> last-hop BodyHash (Q2) + ActId inside
                                   hop 0's signed bytes (Q1)
                                   + signed hash chain make
                                   edits/insertions/drops detectable
                                   (PART 7.3, 8.9). A relay cannot rewrite
                                   Head.DstId without breaking the current
                                   hop's DstDomId signature (PART 8.3).
  A4 replay                     -> nonce + timestamp window, plus
                                   non-decreasing timestamps and a chain
                                   age cap (PART 13, 8.9 (f)). Not the
                                   same as effect idempotency (PART 13).
  A6 app-supplied provenance    -> the chain is not in JsonMsg and not on
                                   Context; it lives in a platform-only
                                   Provenance that no handler can reach,
                                   behind a wrapper that publishes no
                                   array; send cannot root; verification
                                   is not an app opt-in
                                   (PART 8.1, 8.7, 8.9). G6.
  Fake usrId                    -> the usrId never authorizes; only the
                                   credential chain to the actId does
                                   (PART 3.4, 7.4).
  Loss of a provider            -> multiple delegated home providers;
                                   abandonment with no identity change
                                   (G4, PART 6.4).
  Visible-but-uncertified path  -> one lineage; the object list is a
                                   projection of verified path (PART 8.5).

14.2  Residual (accepted)

  * A home provider can act as the user and can re-delegate to itself
    (T1). Rebind ([Identifiers](Identifiers.md) PART 10) evicts a signing
    provider. Device-held ownership keys (PART 15) would remove T1
    between rebinds.
  * De-authorization is expiry-only: a compromised home provider's
    unexpired delegation stays usable until NotAfter (no proactive
    revocation, PART 1.2). Mitigated by a short delegation lifetime; a
    revocation service is Direction (PART 15).
  * A relaying/home provider can read the body of a hop it handles (no
    end-to-end encryption). It cannot read other hops' bodies from the
    envelope (PART 8.4). Message-level encryption is Direction
    (PART 15).
  * Genesis-key compromise is catastrophic
    ([Identifiers](Identifiers.md) PART 16.3).
  * Weak key-generation entropy would collapse actId uniqueness
    ([Identifiers](Identifiers.md) PART 4.3). Not a problem for the
    current single implementation; a vetted-CSPRNG conformance
    requirement is Direction (PART 15).
  * The directory root key is a single provider-identity trust anchor;
    its compromise forges provider identity (not account identity).
    Threshold/multiple signers, and an append-only log of hst records,
    are Direction.
  * A5: a registered provider that signs correctly can lie about its
    own objects, return success for work it did not do, and serve a
    doctored class descriptor. Q2 makes the lie *attributable*. It
    does not make it impossible. Truncation-as-new-root is constrained
    by PART 8.13 (`ActId` or explicit unattributed); the remaining A5
    surface is Direction (PART 15).
  * Nothing in this design persists a hop (PART 8.14), so there is no
    after-the-fact audit of a route and no party holds the fan-out
    tree. Dependent work (effect rollback, charging) must supply its
    own durable records keyed on `contextId`.


## PART 15 - DIRECTION

  Identity (genesis, ownership, Binding, rebind) is
  [Identifiers](Identifiers.md). This PART is remaining security work.

  Device-held ownership key (close T1)
    - The ownership private key moves to the USER'S DEVICE (WebCrypto /
      passkey). The browser then signs hop 0 directly and issues
      delegations from the device; the home provider no longer holds
      `act.OwnPrvKey`, so ceasing to renew a delegation genuinely
      de-authorizes a provider (PART 6.4).

  Hardening
    - Message-level confidentiality (JWE-style) for bodies that must be
      opaque to relaying providers.
    - Proactive delegation revocation (a revocation list / status
      service) if expiry-only proves insufficient.
    - Key-generation assurance once multiple implementations exist:
      vetted CSPRNG, optional same-actId / different-key detection
      ([Identifiers](Identifiers.md) PART 4.3).
    - Threshold / multiple directory-root signers (PART 14.2).
    - Optional self-certifying PROVIDER ids (hstId derived from the
      provider key); providers currently stay on the hstId scheme with
      keys in the directory (PART 10.1).

  Malicious implementations (A5)
    - Publisher-signed class descriptors, digest registered in the App
      Store listing, so a provider serving a modified SideEffect /
      Cost / Compensates is detectable. Needed before those fields
      drive consent or billing.
    - Append-only log of hst records with gossiped signed heads
      (certificate-transparency shape): does not prevent a forged
      record; makes equivocation provable. Cheaper than threshold
      signing for the realistic directory-root attack.
    - Verifiable misbehavior proofs: two conflicting signed statements
      under one provider key, or a signed receipt contradicted by a
      signed record. Anyone checks them offline. Local allow/deny
      lists consume proofs; there is no central scoring service.
    - Conformance suite plus an optional certified-build claim on the
      hst record, meaningful once lying about it is provable.
    - All of the above depend on PART 8 having already made
      decision-relevant data signed and platform-held.


## PART 16 - JAVA TYPES (DATA MEMBERS)

  Methods are named in PARTs 6–11; this PART is the shape. `Hop` keeps
  its name and package (PART 8.3) and carries `contextId` / `actId`;
  `JsonMsg` has no Sec members. `Delegation` and `Binding` are shipped
  classes and are NOT modified.

  Nullability: `actId` is set on hop 0 only and only when an account is
  asserted (PART 7.1). `delegation` and `binding` are null exactly when
  `hop[0].actId` is null. `hopSig` is null only on an in-process
  unsigned pre-bootstrap root (PART 8.10). There is no field that is
  null for positional reasons.

```
package com.domatar.crypto;

/** One signed link in the chain. Immutable; this IS the wire shape. */
public final class Hop
{
  public final String contextId;   // lineage id; minted at root, identical on every hop
  public final String actId;       // index 0 only: account asserted by signerPrv; else null
  public final String signerPrv;   // prvId whose operational key signed this hop
  public final String srcDomId;    // sender; hop 0 = servlet-minted UI id
  public final String dstDomId;    // receiver = current object (domId)
  public final String bodyHash;    // SHA-256 of this hop's canonical Body (Body only)
  public final String prevHopHash; // SHA-256 of previous hop incl. HopSig; "" at index 0
  public final long   timestamp;   // millis; non-decreasing along the chain
  public final String nonce;       // >= 16 bytes; replay cache key with signerPrv
  public final String hopSig;      // sig over canonical(this) excluding HopSig
}

/** The whole chain. Immutable, platform-constructed; the array is never published. */
public final class Path
{
  private final Hop[] hops;        // never exposed, not even as a copy-on-read field
}

/** Chain plus credentials. Platform-only; never handed to a handler. */
public final class Provenance
{
  private final Path       path;
  private final Delegation delegation;  // whole (PART 6.2); null iff no account
  private final Binding    binding;     // whole (PART 6.2a); null iff no account
}

/** The outcome of verification, and the ONLY route from wire bytes to a trusted actId. */
public final class Verdict
{
  public final Trust  trust;       // NONE | PATH | ACCOUNT (PART 7.5)
  public final String actId;       // non-null iff trust == ACCOUNT
  public final String contextId;   // from the verified chain
  public final String reason;      // why it is not ACCOUNT; for logs, never for policy
}
```

```
package com.domatar.core;

/** Trust level lives in core, next to Context, so no app ever imports com.domatar.crypto. */
public enum Trust { NONE, PATH, ACCOUNT }

public final class Context
{
  // WHO. Stamped from a Verdict at a trust boundary, or at root. Never from the wire.
  public final Trust   trust;        // PART 7.5
  public final String  actId;        // fingerprint account id; non-null iff trust == ACCOUNT
  public final String  contextId;    // lineage id (PART 8.6); not a wire field

  // Informational. Travels in Head.Context; never authorizes.
  public final String  usrId;        // login handle
  public final String  usrName;      // display name
  public final String  usrIp;        // caller IP
  public final String  token;        // browser cookie token; home hop only
  public final JsonMap httpHeaders;  // inbound HTTP headers

  // Source-compatible with the shipped boolean; 47 hasRights overrides keep working.
  public boolean isVerified()  { return trust == Trust.ACCOUNT; }
}

public class HttpClient implements DomatarMsgClient
{
  private final DomId      srcDomId;            // object this client sends FROM
  private final String     srcDomain;           // this provider's domain
  private final String     srcContextPath;      // servlet context path
  private final String     srcContextRealPath;  // exploded-WAR filesystem path
  private final Context    srcContext;          // WHO; handler-visible, no crypto
  private final Provenance prov;                // the chain; private, platform-only
}
```

```
package com.domatar.util;

public class JsonMsg
{
  private final JsonMap jsonMap;  // Head + Body only; no Sec envelope
}
```

  Notes on the shape:

  * No public array anywhere. `Path` wraps `Hop[]` and hands out only
    `domIdPath()` (a fresh `DomId[]`, PART 8.5), `contextId()`,
    `depth()`, `last()`, and the wire map. This is what closes the A6
    hole an exposed `public final Hop[] path` would open (PART 8.1).
  * `Path` is a value object, not a static helper. `root` and `append`
    return a new `Path`; `verify` returns a `Verdict`. Copy-on-append
    and the depth cap live in `Path`.
  * `Hop.sign` takes the canonical body BYTES and retains nothing
    (PART 8.3); the caller keeps them if it wants them for a log.
  * `Delegation` and `Binding` keep every field they ship with —
    `actId`, `ownPubKeyB64`, `prvId` on the former; `actId`, `ownId` on
    the latter — so `verify` stays self-contained and needs no
    `Context`. ActDb persists and returns the same shape it transmits;
    there is one representation per type.
  * Failing to sign is FATAL to the send. An unsigned HTTP message is
    rejected by the receiver, so failing open only converts a local,
    diagnosable error into a remote, opaque one.


## Implementation surface

  This section names the realisation that implements this spec.
  Fields are PART 16. The `Sec=` envelope sits beside the message;
  verification is unconditional at every HTTP boundary.

  * `com.domatar.crypto.Hop` — MODIFY: add `contextId`, `actId`; drop
    `seq`. `sign`, `canonicalHash`, `toMap` / `fromMap` (one shape).
    `getDomId()` returns `dstDomId`.
  * `com.domatar.crypto.Path` — NEW. `root`, `append`, `verify` ->
    `Verdict`, `contextId`, `depth` (cap enforced here), `domIdPath`,
    `toWire` / `fromWire`.
  * `com.domatar.crypto.Provenance` — NEW. `Path` + whole Delegation
    and Binding. Held by `HttpClient` and `Msg` only.
  * `com.domatar.crypto.Verdict` — NEW.
  * `com.domatar.crypto.SecWire` — NEW. Envelope `encode` / `decode`;
    a receiver rejects a `Ver` outside [SecVerMin, SecVerMax].
  * `com.domatar.crypto.ProviderKeyResolver` — NEW. Lookup of a
    provider's operational public key for hop verification.
  * `com.domatar.install.DirectoryKeyResolver` — NEW. Directory-root
    implementation of `ProviderKeyResolver` (PART 10.3 carve-out).
  * `com.domatar.core.Trust` — NEW enum, in `core` deliberately: it is
    the one crypto-derived value a `hasRights` implementor names, so
    apps never import `com.domatar.crypto`. That is a grep-checkable
    invariant, not a paragraph.
  * `com.domatar.core.Context` — MODIFY: `trust` / `actId` /
    `contextId` plus informational fields; `isVerified()`;
    platform-only constructors; no crypto members.
  * `com.domatar.core.HttpClient` — MODIFY: `root` and
    `rootAs(actId, …)` (platform, not on the interface), `send`
    (apps), `withToken`; `derive(Context)` removed; one private
    `dispatchWith` produces the outbound `Provenance` unconditionally;
    `sendLocal` passes the EXTENDED provenance to the local handler's
    client; `sendHttp` serializes the `Sec=` parameter;
    `sendDirectory` is the named PART 10.3 carve-out; never reads
    provenance from `JsonMsg`.
  * `com.domatar.util.DomatarMsgClient` — `send`, `getSrcId`,
    `withToken`, `domIdPath`. No `root`, no `forward` (PART 8.7). `HttpClient` is
    its only implementer, so the interface change is cheap.
  * `com.domatar.servlet.DomatarServlet` — cookie verify, mint UI
    SrcDomId, `root`.
  * `com.domatar.servlet.Msg` — parse `Sec=`, always verify the path,
    stamp `Context` from the `Verdict`; reject unsigned HTTP roots and
    unknown `Ver`.
  * `com.domatar.util.JsonMsg` — DELETE `SecEnvelope`, `addSec`,
    `getSec`, `hasSec`, `appendHopToSec`. Head.Context carries
    informational fields only.
  * `com.domatar.util.ObjImpl.requiresPath` — fatal-if-untrusted
    policy, not a verify gate.
  * `com.domatar.core.MsgHandler` — DELETE (dead code; PART 11.2).
  * Unchanged: `Delegation`, `Binding`, `AccountKeys`, `CanonicalJson`,
    `KeyOps`, `ProviderKeyStore`, `DirectoryTrust`, `MasterKey`,
    `GenesisVault`, `TlsConfig`. `NonceCache` unchanged in role; this
    deployment is a single node per provider (PART 13).

  Config (existing): DOMATAR_DELEG_TTL_MS, DOMATAR_MSG_SKEW_MS,
  DOMATAR_WIRE_SCHEME, DOMATAR_MTLS_REQUIRED. New (getter / env /
  provider.config.txt key / default):

    getMaxHopDepth()    DOMATAR_MAX_HOP_DEPTH    / MaxHopDepth    = 16
    getMaxChainAgeMs()  DOMATAR_MAX_CHAIN_AGE_MS / MaxChainAgeMs  = 300000
    getSecVerMin()      DOMATAR_SEC_VER_MIN      / SecVerMin      = 1
    getSecVerMax()      DOMATAR_SEC_VER_MAX      / SecVerMax      = 1

# END OF SPEC
