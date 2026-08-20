# DOMATAR - SECURITY SPECIFICATION

This document specifies how Domatar secures inter-object communication:
how a receiving object knows WHO sent a message (the caller's identity),
by WHAT PATH it arrived (the chain of objects it passed through), and how
the bytes are protected ON THE WIRE.

Account identity (ownId, actId, usrId, genesis/ownership keys, binding,
rebind) is [Identifiers](Identifiers.md). This document is the wire:
origin signatures, signed path hops, delegations carried on the message,
and TLS.

The system uses three independent mechanisms:

  Q1  ORIGIN     "Is the caller who it claims to be?"    -> signatures
                                                            (PART 7)
  Q2  PROVENANCE "Did the message really travel this      -> signed
                  path?"                                     hash chain
                                                            (PART 8)
  Q3  WIRE       "Can a network eavesdropper read or       -> TLS
                  alter the bytes in transit?"               (PART 9)

Ownership keys are server-held on signing providers (T1). Device-held
keys, proactive revocation, and message-level encryption are Direction
(PART 15).

## PART 1 - GOALS AND NON-GOALS

1.1  Goals

  G1  A receiving object can verify that a message genuinely originates
      from the account (actId) it names, and that no intermediary
      altered its body. (Q1)

  G2  A receiving object can verify the FULL PATH the message took --
      the ordered chain of objects/providers that relayed it -- and
      detect any inserted, dropped, reordered, or modified hop. (Q2)

  G3  Message bytes are confidential and integrity-protected against
      network eavesdroppers between two communicating providers. (Q3)

  G4  A user may have MULTIPLE redundant home providers and may abandon
      any of them -- including the one on which the account was first
      created -- without any loss of identity, and without any object
      the user owns needing to be rewritten.

  G5  A malicious provider cannot impersonate an account it does not
      control, even if it knows the account's identifiers and claims to
      be that account's home provider.

1.2  Non-goals (for this specification)

  * Consensus / a distributed ledger. Domatar needs unforgeable
    identity and tamper-evident paths, delivered by hash chaining plus
    digital signatures. It does NOT need global agreement on a single
    ordering of all messages, so there is no blockchain, proof-of-work,
    or mining anywhere in this design.

  * Anonymity / traffic-analysis resistance. Full-path provenance (G2)
    is deliberately the OPPOSITE of anonymity: it reveals the route to
    the endpoint (see PART 8.5).

  * Confidentiality against a relaying provider. Transport (per-hop)
    encryption only; a provider that handles a message can read it.
    End-to-end body encryption is Direction (PART 15).

  * Proactive revocation. A provider is de-authorized by letting its
    delegation expire (PART 6), not by a revocation list. A short
    delegation lifetime bounds the exposure; a revocation service is
    Direction (PART 15).

## PART 2 - THREAT MODEL

2.1  Adversaries considered

  A1  A network eavesdropper between providers (passive read, active
      tamper/replay on the wire).

  A2  A malicious or compromised provider that is NOT a delegated home
      provider of the victim account. It can send any bytes, craft any
      identifiers, forge any DomIdPath, and claim to be anyone's home
      provider.

  A3  A malicious relay: a provider that legitimately forwards a message
      but tries to alter it, alter its claimed route, or inject/drop
      hops.

  A4  A replay attacker that captures a valid signed message and
      re-sends it.

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

## PART 3 - IDENTITY ON THE WIRE

Account identifiers are defined in [Identifiers](Identifiers.md). This
part states only what the message path needs.

3.1  actId and usrId

  * actId — permanent account identity, a fingerprint of the genesis
    public key ([Identifiers](Identifiers.md) PART 4). Embedded in every
    DomId and in the signed Origin block (PART 7). Never authorizes by
    itself; the credential chain (PART 6) proves it.

  * usrId — mutable login handle `<localname>@<appId>`
    ([Identifiers](Identifiers.md) PART 2.3;
    [Login protocol](../apps/Login-Protocol.md) PART 3.2). Travels in
    Context.UsrId ([Domatar](../Domatar.md) PART 17.2). Display / routing
    hint only; never an ownership reference; never authorizes.

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

## PART 4 - CRYPTOGRAPHIC PRIMITIVES

  Signatures     Ed25519 (EdDSA over Curve25519). 32-byte public keys,
                 64-byte signatures. Chosen for small keys/signatures,
                 fast verification, and freedom from parameter/padding
                 pitfalls. No RSA.

  Hash           SHA-256. Used for the actId fingerprint
                 ([Identifiers](Identifiers.md) PART 4.1), for body digests,
                 and for hop links (PART 8).

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

  Note the division of labour. The ownership key is needed to GOVERN
  day-to-day delegations. It is NOT needed to OPERATE message-to-message:
  a home provider signs messages with its own provider key (5.2) plus a
  delegation it already holds. Genesis is consulted only at creation and
  rebind.

5.2  Provider operational / identity key  (per provider)

  Each provider has one Ed25519 key pair, published in its directory
  host record (PART 10). One key serves three roles:
    - it signs ORIGIN messages for accounts that have delegated to it
      (PART 7);
    - it signs PATH hops it relays (PART 8);
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

     actId                      the account identity (in the Origin
                                block, PART 7), = fingerprint(GenesisPubKey)
       ^  actId == fingerprint(GenesisPubKey)      [self-certifying;
          version-aware via act.FpVersion / AccountKeys.fingerprintsTo —
          [Identifiers](Identifiers.md); v1 algorithm unchanged]
     GenesisPubKey              carried in the Binding ([Identifiers](Identifiers.md) PART 6)
       ^  signs
     Binding (Version)          "genesis authorizes OwnId as current"
       ^  names OwnPubKey; ownId == fingerprint(OwnPubKey)
     OwnPubKey                  the current operating key, carried in
                                the Delegation
       ^  signs
     Delegation                 "OwnId authorizes provider P until T"
       ^  authorizes P
     provider key of P          from the signed directory host record
                                (PART 10)
       ^  signs
     OriginSig                  P's signature on this specific message
                                (PART 7)

  No lookup of the account is required at any step: Binding and OwnPubKey
  arrive with the message and are proven by the self-certifying checks;
  the only external fetch is provider P's key from the directory, which
  the runtime already caches for routing. See [Identifiers](Identifiers.md) PART 11.

6.2  The delegation certificate

  A delegation is issued by the ownership key and provisioned to a
  home provider. It is self-contained (it carries the operating public
  key so the whole chain verifies from the actId plus the Binding):

    Delegation {
      ActId       : <account ID>                    ([Identifiers](Identifiers.md) PART 4)
      RootPubKey  : <base64 Ed25519 public key>     (32 bytes)
      PrvId       : <prvId authorized to sign for ActId>
      NotAfter    : <expiry, millis-since-epoch>
      Nonce       : <random, >= 16 bytes>           (issuance serial)
    }
    DelegSig    : <base64 Ed25519 sig by RootPubKey over canonical
                   Delegation>

  A verifier ACCEPTS a delegation only if ALL hold:
    (a) SHA-256(RootPubKey)[0..24) encodes to ActId  -- the
        self-certifying check: the delegation proves its own account
        identity;
    (b) DelegSig verifies under RootPubKey            -- the account
        really authorized this;
    (c) NotAfter is in the future (subject to clock skew, PART 13);
    (d) Provider equals the SignerPrv of the Origin block it accompanies
        (PART 7).

  Because of (a) and (b), a delegation needs no external signer or
  registry: a forged delegation either fails the actId check or fails the
  signature check.

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
  which stores it and attaches it to the messages it signs. This is a
  LOCAL act of the ownership-key holder; it involves no directory and no
  global record.

  Renewing. Before NotAfter, the ownership-key holder issues a fresh
  Delegation with a later NotAfter. Providers renew as long as they
  remain authorized.

  Abandoning / removing a home provider. Simply STOP renewing that
  provider's delegation. Its last delegation lapses at NotAfter, after
  which no verifier will accept its signatures for the account. The actId
  is unchanged; no object is rewritten; the abandoned provider may be the
  original creating provider. This realizes G4. A signing provider that
  still holds the ownership key can self-renew until a rebind
  ([Identifiers](Identifiers.md) PART 10).

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

  Desktop-assisted removal (Direction). A future self-synchronizing
  Desktop that knows all of the user's home providers and holds (or
  coordinates) the device ownership key makes COOPERATIVE removal clean:
  it stops issuing delegations to the dropped provider, whose last one
  lapses at NotAfter. Instant network-wide revocation still needs a
  revocation service (PART 15) or a published set (which would
  reintroduce the lookup PART 6.3 removes). Keeping NotAfter short
  (PART 13) bounds the gap.

6.5  Caching (optimization, not required)

  A verifier MAY cache an accepted Delegation by (ActId, Provider) until
  its NotAfter, so senders need not retransmit an unchanged delegation on
  every message. This is a pure optimization; the default, fully
  stateless behaviour is to carry the delegation in each message.

## PART 7 - Q1: ORIGIN AUTHENTICATION ("THE ACCOUNT IS CORRECT")

7.1  What is signed

  The originating home provider attaches an ORIGIN SIGNATURE to the
  message, computed with that provider's operational key (PART 5.2) over
  the canonical form of:

    Origin {
      ActId       : <account ID on whose behalf we send>   ([Identifiers](Identifiers.md) PART 4)
      SrcDomId    : <full source DomId>
      DstDomId    : <full destination DomId>
      BodyHash    : SHA-256( canonical message Body )
      SignerPrvId : <prvId doing the signing>
      Timestamp   : <millis-since-epoch>
      Nonce       : <random, >= 16 bytes>
    }
    OriginSig   : <base64 Ed25519 sig over canonical Origin>

  The message also carries the Delegation + DelegSig (PART 6.2) that
  authorizes SignerPrv to sign for ActId. Together they form the
  credential chain of PART 6.1. All of this travels in the message Head,
  extending the JsonMsg Head of [Domatar](../Domatar.md) PART 17.2 (see PART 11).

7.2  Where signing happens: the browser never signs

  The ownership key lives on the home provider, so the ORIGIN SIGNATURE
  is created at the browser trust boundary (DomatarServlet,
  [Domatar](../Domatar.md) PART 6.1 / 17.4):

    1. The browser authenticates to its home provider with the existing
       cookie/token login (UNCHANGED from [Domatar](../Domatar.md) PART 6).
    2. Having verified the cookie, the home provider signs the outbound
       message with its provider key, asserting the account's actId, and
       attaches its delegation.

  Thus the `(usrId, token)` bearer mechanism SURVIVES on the
  browser->home-provider hop only. Inter-object and inter-provider hops
  are authenticated by signature, not by token. The browser holds no key
  and performs no crypto. Direction (PART 15) would have the device
  sign Origin and issue delegations.

7.3  Verification

  A receiving object (or its provider, at the Msg boundary) accepts the
  origin as authentic iff ALL hold:

    (a) The accompanying Delegation is valid for ActId and names
        SignerPrv as Provider (the PART 6.2 checks a-d).
    (b) SignerPrv's Ed25519 key (from the signed directory record,
        PART 10) verifies OriginSig over the reconstructed Origin.
    (c) BodyHash equals SHA-256 of the received canonical Body.
    (d) Timestamp is within the acceptance window and Nonce is unseen
        (replay defense, PART 13).

  Interpretation: "a provider the account itself delegated to vouched
  for this exact body, to this exact destination, now." A provider with
  no valid delegation (A2), one that tampered with the body (A3), or one
  that replayed an old message (A4) fails (a), (c), or (d) respectively.

7.4  Why A2 (the impersonation attack) fails

  A malicious provider prvEvil crafts a message claiming a victim's
  ActId. To pass 7.3 it must present a Delegation for that ActId naming
  prvEvil as Provider, chained to the victim's current ownId
  ([Identifiers](Identifiers.md) PART 11). prvEvil does not hold the
  ownership key; it cannot forge the ownership signature (b of PART 6.2),
  and it cannot substitute its own key because the self-certifying
  checks would fail (a of PART 6.2). The usrId is never consulted for
  authorization -- only the credential chain to the actId is.
  Impersonation therefore fails cryptographically, not by policy.

7.5  Relationship to "verified" in the core spec

  For cross-provider messages, `Verified=true` means "the origin
  signature verified, and it was made by a provider holding a valid
  delegation from the named account," not "some provider asserted a
  token" ([Domatar](../Domatar.md) PART 6). Authorization (hasRights,
  PART 6.3) is separate: signatures answer WHO; hasRights answers MAY.
  A valid signature proves the caller's actId, not its right to act on
  the destination.

## PART 8 - Q2: PATH PROVENANCE (SIGNED HASH CHAIN)

Q2 is a SEPARATE mechanism from Q1. Q1 proves the origin; Q2 proves the
route. A receiving object that needs to trust the whole path verifies
both.

8.1  What the chain chains

  A Domatar message is not one immutable body forwarded verbatim; each
  handler composes a NEW message to the next object ([Domatar](../Domatar.md)
  PART 17). So `Context.DomIdPath` ([Domatar](../Domatar.md) PART 17.3) is a
  CAUSAL chain of distinct messages. Q2 makes that chain tamper-evident
  by having each hop carry a signed link back to its parent -- the
  hash-chaining idea from blockchains, WITHOUT any consensus layer.

8.2  Hop structure

  Each time a provider performs a send that extends the path, it appends
  a signed hop:

    Hop[i] {
      Seq         : i                         (0 at the origin)
      SignerPrv   : <prvId performing this hop>
      SrcDomId    : <source object of this hop>
      DstDomId    : <destination object of this hop>
      BodyHash    : SHA-256( canonical Body of THIS hop's message )
      PrevHopHash : SHA-256( canonical Hop[i-1] including its HopSig )
                    (empty for Seq 0)
      Timestamp   : <millis>
      Nonce       : <random, >= 16 bytes>
    }
    HopSig[i]   : <base64 Ed25519 sig by SignerPrv's key over
                   canonical Hop[i]>

  PrevHopHash is the chain link: it binds each hop to the exact prior
  hop, so inserting, dropping, reordering, or editing any hop breaks the
  chain.

8.3  Verification at the receiving object

  The receiver accepts the path iff:
    (a) Hop[0] is the origin and its SrcDomId / BodyHash agree with the
        Q1 Origin block (the chain and the origin signature are
        consistent);
    (b) for every i, PrevHopHash == SHA-256(canonical Hop[i-1]) -- the
        chain is unbroken;
    (c) for every i, HopSig[i] verifies under SignerPrv[i]'s directory
        key (PART 10);
    (d) each hop's DstDomId equals the next hop's SrcDomId's object (the
        path is contiguous);
    (e) replay/expiry checks (PART 13) pass for the final hop.

  A receiver requiring only origin authenticity (not full route) MAY
  verify Q1 alone; a receiver requiring provenance verifies the whole
  chain to Seq 0.

8.4  Signing principal: provider, not account

  Path hops are signed by the PROVIDER performing each send (its
  operational key), NOT by the account ownership key. The account attests
  ORIGIN (Q1, via a provider it delegated to); providers attest ROUTE
  (Q2). This is why the two mechanisms are cleanly separable and why a
  hop performed by an object whose account key lives elsewhere is still
  signable: the provider that physically performs the send always has its
  own key.

8.5  Tradeoffs to accept knowingly

  * Size. Each hop adds a Hop block plus a 64-byte signature to the
    envelope (not to stored rows). Deep chains grow the message.
  * Disclosure. Full-path provenance means the endpoint learns the
    entire upstream route (objects and providers). This is inherent to
    G2 and is the opposite of anonymity (PART 1.2). If a boundary must
    NOT reveal its interior, it may terminate a chain and start a new one
    (acting as a fresh origin), at the cost of the endpoint no longer
    seeing past that boundary. Whether/where to allow such pruning is a
    policy decision left to the handler.
  * Cost. Verifying an N-hop chain is N signature verifications versus
    the core spec's single flag read ([Domatar](../Domatar.md) PART 6.3).
    Provider keys are cacheable; per-hop results may be cached.

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
  message-level encryption: Q2 covers path integrity, Q1 covers body
  integrity, and TLS covers per-link confidentiality. A relay provider
  can read a body it handles (accepted, PART 1.2 / T1).

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
         -> provider keys sign     => origin (Q1) and path hops (Q2)
    account genesis / ownership keys  (self-certifying: actId ==
                                   fingerprint(GenesisPubKey);
                                   [Identifiers](Identifiers.md) PART 4)
      -> Binding then Delegation       => a provider is authorized to sign
                                      for the actId (PART 6)

  A receiver trusts exactly one pre-shared key (the directory root) for
  provider identity, and trusts account identity with no pre-shared key
  and no lookup at all (self-certifying, carried in the message).

10.3  Bootstrap carve-outs (avoid the recursion of [Domatar](../Domatar.md) PART 6.4)

  As with the directory exemption of [Domatar](../Domatar.md) PART 6.4, the
  operations needed to VERIFY must themselves be reachable WITHOUT prior
  verification, or dispatch deadlocks:

    * GetHst and provider-key lookup are PUBLIC and require no origin
      signature.
    * A message whose sole purpose is to fetch provider keys/host records
      is exempt from the signed-chain requirement, exactly as directory
      traffic is exempt.

  Account verification needs no such carve-out because it performs no
  lookup: the credential chain is entirely in the message (PART 6.1).

  Provider-key rotation publishes a new hst record Version with an
  overlap window during which both old and new keys verify (PART 13).

## PART 11 - INTEGRATION WITH THE CORE RUNTIME

11.1  Message envelope ([Domatar](../Domatar.md) PART 17.2)

  The JsonMsg Head has an optional security block carrying the full
  credential chain (PART 6.1) plus the provenance path (PART 8):

    Head {
      ... existing fields ...
      Sec {
        Origin     : { ActId, SrcDomId, DstDomId, BodyHash,
                       SignerPrv, Timestamp, Nonce }
        OriginSig  : <base64>
        Delegation : { ActId, RootPubKey, Provider, NotAfter, Nonce }
        DelegSig   : <base64>
        Path       : [ Hop[0], Hop[1], ... ]     (each with HopSig)
      }
    }

  Delegation/DelegSig MAY be omitted when the verifier is known to have
  cached them (PART 6.5); otherwise they are always present.   Absent the
  whole Sec block, a message is UNVERIFIED (Verified=false). Public
  handlers still run for unverified callers ([Domatar](../Domatar.md)
  PART 6.1).

11.2  The two trust boundaries ([Domatar](../Domatar.md) PART 6.1)

  * DomatarServlet (browser entry): after the existing cookie
    verification, STAMP and SIGN the Origin (PART 7.2), attach the
    provider's Delegation, and start the Q2 path with Hop[0].

  * Msg.doAction (cross-prv inbound): verify origin signatures
    (PART 7.3) and, where the handler requires provenance, the path
    chain (PART 8.3). Set Verified accordingly. A prv only believes
    its own checks, never the wire's Verified flag
    ([Domatar](../Domatar.md) PART 6.1) -- it re-verifies signatures
    rather than tokens.

  * HttpClient.sendLocal / sendHttp: sendHttp APPENDS a signed Hop
    (PART 8.2) before dispatch; in-process sendLocal extends the path in
    memory without a network signature but MUST still append a hop record
    so the causal chain is complete for the eventual receiver.

11.3  Unchanged

  Authorization (ObjImpl.hasRights, [Domatar](../Domatar.md) PART 6.3), routing
  by hstId (PART 5), the class envelope and service qualifier (PART 6.2 /
  PART 8), and the obj/lnk persistence shapes (PART 7) are unchanged.
  Verification is signatures + delegations + Binding
  ([Identifiers](Identifiers.md) PART 11). The `act` table holds
  `OwnPrvKey` (PART 5.4) and the `hst` table holds `PubKey` /
  `RecordSig` (PART 10.1).

## PART 12 - OPERATIONS

Account credentials are LOCAL to the ownership-key holder; they are not
directory operations. Host-record operations are authorized by the
directory (mTLS + directory-root signing, PART 9.3 / 10.1).

  Account operations (local to the ownership-key holder; PART 6.4):

    IssueDelegation (Provider, NotAfter) -> Delegation + DelegSig
        Signed by the ownership key. Provisioned to the named home
        provider, which attaches it to the messages it signs. Adding a
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

  * Nonce. Every Origin and every Hop carries a >= 16-byte random Nonce.
    Each verifying provider keeps a sliding-window cache of seen
    (SignerPrv, Nonce) pairs and REJECTS duplicates within the window.

  * Timestamp window. A message is accepted only if its Timestamp is
    within +/- SKEW of local time. SKEW is small (default 120 s). Nonces
    need only be remembered for the window length.

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
  A3 relay tampers body/route   -> BodyHash (Q1) + signed hash chain (Q2)
                                   make edits/insertions/drops detectable
                                   (PART 7.3, 8.3).
  A4 replay                     -> nonce + timestamp window (PART 13).
  Fake usrId                    -> the usrId never authorizes; only the
                                   credential chain to the actId does
                                   (PART 3.4, 7.4).
  Loss of a provider            -> multiple delegated home providers;
                                   abandonment with no identity change
                                   (G4, PART 6.4).

14.2  Residual (accepted)

  * A home provider can act as the user and can re-delegate to itself
    (T1). Rebind ([Identifiers](Identifiers.md) PART 10) evicts a signing
    provider. Device-held ownership keys (PART 15) would remove T1
    between rebinds.
  * De-authorization is expiry-only: a compromised home provider's
    unexpired delegation stays usable until NotAfter (no proactive
    revocation, PART 1.2). Mitigated by a short delegation lifetime; a
    revocation service is Direction (PART 15).
  * A relaying/home provider can read message bodies it handles (no
    end-to-end encryption). Message-level encryption is Direction
    (PART 15).
  * Genesis-key compromise is catastrophic
    ([Identifiers](Identifiers.md) PART 16.3).
  * Weak key-generation entropy would collapse actId uniqueness
    ([Identifiers](Identifiers.md) PART 4.3). Not a problem for the
    current single implementation; a vetted-CSPRNG conformance
    requirement is Direction (PART 15).
  * The directory root key is a single provider-identity trust anchor;
    its compromise forges provider identity (not account identity).
    Threshold/multiple signers are Direction.

## PART 15 - DIRECTION

  Identity (genesis, ownership, Binding, rebind) is
  [Identifiers](Identifiers.md). This PART is remaining security work.

  Device-held ownership key (close T1)
    - The ownership private key moves to the USER'S DEVICE (WebCrypto /
      passkey). The browser then signs the Origin directly and issues
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

## Implementation surface

Origin signatures (PART 7) and delegations (PART 6):

  * act columns Delegation, DelegSig, DelegNotAfter.
  * com.domatar.crypto.Delegation — issue, verify, toJson/fromJson,
    ensureValid.
  * com.domatar.db.ActDb — getDelegationRow, setDelegation.
  * ActManagerImpl issues an initial delegation on sign-up;
    DomatarProviderInstall issues one for the provider account at
    bootstrap. Delegation.ensureValid issues a delegation on first
    outbound send when the row has none yet.
  * com.domatar.crypto.OriginBlock — sign / verify (ProviderKeyStore).
  * JsonMsg.addSec / getSec / hasSec; Sec lives in Head.Sec.
  * HttpClient.send attachSecIfAbsent: when the message has no Sec,
    the context is verified, and actId is a fingerprint, attach
    OriginBlock + Delegation. Failure is non-fatal (no Sec → receiver
    treats as unverified).
  * Msg.verifyAndStamp verifies the credential chain (PART 7.3): parse
    Sec, verify Delegation, fetch signer pubkey and verify OriginSig,
    check BodyHash, timestamp skew, and NonceCache.
  * com.domatar.crypto.NonceCache — sliding-window replay cache keyed
    on (signerPrvId, nonce), TTL = DOMATAR_MSG_SKEW_MS (default 120 s).
  * LoginRemote is the browser-boundary path (DomatarServlet) and the
    local actManager VerifyLogin op — not the cross-provider message
    path.
  * DomatarConfig: getDelegTtlMs (DOMATAR_DELEG_TTL_MS, default 1h),
    getMsgSkewMs (DOMATAR_MSG_SKEW_MS, default 120 s).

Path provenance (PART 8):

  * com.domatar.crypto.Hop — sign, canonicalHash (SHA-256 of the
    canonical hop including HopSig; used as the next hop's PrevHopHash).
  * PathChain.append builds and signs a Hop; PathChain.verify runs
    PART 8.3 checks (a)–(e).
  * SecEnvelope.path: List<Hop>. appendHopToSec adds a hop to
    Head.Sec.Path.
  * HttpClient appendHopToPath on every msgClient.send that carries Sec
    (sendHttp and sendLocal).
  * ObjImpl.requiresPath (default false). Msg.doAction runs
    PathChain.verify before dispatch when the handler requires it; on
    failure, demotes context to verified=false.

Wire confidentiality (PART 9):

  * DomatarConfig: getWireScheme (DOMATAR_WIRE_SCHEME, default
    "https"), trust/key store paths, isMtlsRequired
    (DOMATAR_MTLS_REQUIRED, default true when https).
  * TlsConfig builds SSLSocketFactory from the configured stores;
    cached per process. Null when no stores are configured (JVM
    defaults).
  * HttpClient.sendHttp uses getWireScheme. When https, applies
    TlsConfig.getSocketFactory. Dev: WireScheme=http bypasses TLS.
  * RequestContext holds the TLS client-certificate public key for the
    current request. Msg.doAction populates it from Tomcat's
    X509Certificate attribute.
  * HstsImpl.updateHst, when mTLS is required, rejects UpdateHst
    without a client certificate; if the provider already has a stored
    pubKey, the cert must match.
  * provider.config.txt documents WireScheme, stores, MtlsRequired,
    DelegTtlMs, MsgSkewMs.

  Local two-provider stacks typically set WireScheme=http and
  MtlsRequired=false. Full TLS validation needs provider certificates
  chained to a shared trust anchor.

# END OF SPEC
