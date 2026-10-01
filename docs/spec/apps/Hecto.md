# DOMATAR - HECTO APP (FUND SUBSCRIPTIONS)

[IMPLEMENTED — Update-HectoApp.txt]

Hecto runs funds on Canton. A subscription adds Delta to a
holding Hecto administers and draws 25 credits, payable to
the Hecto home user. The caller's own Canton app stays free:
Adjust there submits the choice and does not draw
([Canton](Canton.md)).

There is no second ledger. Fund contracts remain Canton
contracts. Subscribe submits Adjust as the caller's bound
Party, posts that user's Hecto booking, and draws once.

Companion documents:

- [Canton](Canton.md) — handles, free Adjust, live Open
- [Spreadsheet](Spreadsheet.md) PART 7.4 — `[domId]path` via Open
- [Writing Apps](Writing-Apps.md) — manifest, AppInstall, facade

The Java realisation is `apps/hecto/`.


## PART 1 — SHAPE

App id `hecto`. Home host `hecto`. Home user `hecto@hecto`,
created when `hecto` is an offered host. The provider that
owns that host creates the desk. In the two-provider sim
that provider is prv1, the same JVM as host canton, so
Subscribe and the workshop holdings share one `MockCanton`.

A user who installs Hecto gets a sub-host and:

```
root → app-hecto → booking
```

The desk is not in that tree. One desk serves every Hecto
user on that provider. Each user has a separate booking
and a separate credit balance with the desk's owner.


## PART 2 — CLASSES

(hecto, app)
  Facade on the user's app node. The WUI and the agent
  call this object. It forwards to the desk. Subscribe
  declares Cost 25 so the agent can see the price.
  Facade `rights()` stays ADMIT, so the facade does not
  draw. The desk draws once.

(hecto, desk)
  ObjId = desk, on host hecto, owned by `hecto@hecto`.
  Msgs: Quote, Credits, Subscribe (Cost 25, Compensates
  ReverseSubscribe), ReverseSubscribe, ApplyTwice, Echo,
  Refill.

(hecto, booking)
  ObjId = booking, on the user's sub-host.
  Attrs: NetDelta, LastDelta, and, after a recorded
  Subscribe, LastContextId, LastMsgName.
  Msgs: GetBooking, Post (Compensates Unpost), Unpost.
  Post denies when `alreadyEntered()` is true.


## PART 3 — WHICH HOLDINGS

Subscribe accepts a workshop holding whose InstrumentAdmin
is the Hecto registrar party. That is HECTX, HXAI, HXSPX,
and HECTO. Canton Coin, cash, locked coin, the pending
transfer, and the repo are refused with "Not a Hecto
holding", and that refusal does not draw. The caller must
be the bound Party that controls Adjust (Owner).

The page lists only those Hecto holdings. It loads the
caller's Canton contracts and keeps the ones Hecto
administers.


## PART 4 — MESSAGES

4.1  Subscribe

  In:  ContractId, Delta
  Out: { ContractId, Amount, Symbol, NetDelta, Remaining }
  SideEffect: Write
  Cost: 25 credits. Compensates: ReverseSubscribe.

  The facade is the object the WUI and the agent call, so
  the visit's caller is that facade. The facade sends to
  the desk. Undo sends Compensate to the desk from that
  same facade.

  The desk submits Adjust as the caller's bound Party,
  then Post on that user's booking with RecordUndo. If
  Post fails, the desk submits the opposite delta and
  does not attach a saga slot. On success it attaches
  slot saga on the desk visit with effect
  { ActId, ContractId, Delta, OrigContextId }.

  Delta may be negative (a redemption). Delta 0 is
  rejected. The page defaults Delta to 500.

4.2  The other desk messages

  Quote
    In:  ContractId
    Out: { ContractId, Amount, Symbol, Cost }
    Read. No draw. Refuses a non-Hecto holding.

  Credits
    Out: { Remaining, Cost }
    Read. Seeds 100 when this payer has no row with
    the desk owner.

  ReverseSubscribe
    In:  the saga effect.
    No Compensates. Idempotent when the booking's
    reverse key equals OrigContextId. Otherwise
    submit Adjust with the negated delta and Sync.
    A failed draw is not a visit, so there is nothing
    to reverse.

  ApplyTwice
    In:  ContractId, Delta
    Out: { Amount, FirstPost, SecondPost }
    No Cost. One subscription, one Adjust, then the
    booking is posted twice, with no RecordUndo.
    The second post is the duplicate confirmation of
    that same order. It is refused. The holding and
    the booking each move once. It is not a second
    subscription, so it is not charged again.

  Echo
    Out: { Stopped: "cycle" }
    A callback on this same path calls the desk again.
    The second arrival is denied (`inOwnPath`, and
    `alreadyEntered`). No draw, no holding change.

  Refill
    Out: { Remaining }
    Credits the shortfall up to 100. No Cost.

  The desk owner calling its own desk is admitted
  without a draw. Every other verified caller of
  Subscribe is priced, after the holding check. A
  non-Hecto ContractId is admitted so the handler can
  refuse it without a draw.

4.3  Booking

  hasRights: the installing user, for that user's booking.

  Post adds Delta to NetDelta and sets LastDelta. When
  RecordUndo is set, it stores the context id and the
  undo message name, and attaches saga effect
  { Delta, OrigContextId } with Compensates Unpost.

  Unpost subtracts Delta. A second Unpost for the same
  OrigContextId returns the current NetDelta and does
  not subtract again.

  `rights()` denies Post when `alreadyEntered()` is true.
  That is this class refusing a second delivery of the
  same message in one operation. A later operation, with
  a new ContextId, may Subscribe again and is charged
  again.


## PART 5 — PAYMENT, SAGA, RE-ENTRY

5.1  Payment

  Cost on the facade Subscribe descriptor is the list
  price the agent reads. The draw happens only because
  the desk's `rights()` returns priced. PayeeActId is
  the Hecto home user. PayerActId is the caller.
  Declaring Cost on the facade does not draw.

  A failed draw is a deny. It is not a visit and is not
  compensated. When a balance row already exists and
  Remaining is below 25, the facade reports that and
  does not send.

5.2  Saga

  The Canton submit is not wrapped in Saga. The desk
  message submits, then posts. Compensate walks the
  visit's children and then invokes ReverseSubscribe.
  The booking Post compensates with Unpost.
  ReverseSubscribe does not itself declare Compensates.

5.3  Re-entry

  Booking Post is the refuse-on-reentry probe. Desk
  Echo is the cycle probe. The agent iteration cap does
  not cover a cycle inside one tool call, which is why
  Echo exists.


## PART 6 — SURFACES

6.1  Page

  LaunchPath `/domatar/hecto/hecto.html`.
  The launcher and the `(hecto, app)` tile use the Hecto
  mark at `icons/app.svg` and `icons/cls/app.svg`.
  Shows the caller's Hecto holdings, then the priced
  strip: credits, price 25, amount, booking net,
  Subscribe, Cancel, Duplicate order, Callback, Refill.
  Duplicate order sends ApplyTwice. Callback sends Echo.
  Cancel sends Undo. Use on a row fills ContractId,
  highlights that row, and names the holding. Delta
  defaults to 500.

6.2  Navigator

  Canton is `root → app-canton → party, active`
  ([Canton](Canton.md)).
  Hecto is `root → app-hecto → booking`.
  The desk is absent from the investor's tree.
  Navigator v1 stays read-only.

6.3  Spreadsheet

  Amount, Nav, allocations, and weights stay citations
  of the user's Canton handle. NetDelta and LastDelta
  are

    hecto-\<actId\>.hecto.\<actId\>.booking

  ([Spreadsheet](Spreadsheet.md) PART 7.4). Parse of
  either citation is a read and is free. After
  Subscribe, Amount and NetDelta both move. After Undo,
  both return.

6.4  Agent

  Canton tools do not advertise Cost on Adjust. Hecto
  Subscribe's description carries Cost: 25 credit.
  Undo, ApplyTwice, and Echo are facade tools. The
  agent does not call the desk or the booking directly.


## PART 7 — DEMO

David holds the Hectocorn Index (HECTX). He buys more of
it from Hecto. He does not edit that fund on the Canton
page. Log in as David on prv1.

  1. His own app, free. On Canton, bind his party and
     Sync. Use Canton Coin, not the index. Adjust by
     500. The coin Amount rises, the ContractId stays,
     and no credits move. This is his coin, so Hecto
     is not involved.

  2. The sheet. Cite the HECTX handle's Amount, and
     cite NetDelta on the Hecto booking. Parse. The
     index quantity is unchanged. NetDelta is 0.

  3. The subscription. On Hecto, Use HECTX. Delta stays
     500. Credits show 100, price 25. Subscribe. He
     pays Hecto 25. HECTX Amount is 500 higher.
     NetDelta becomes 500. Parse the sheet: both cells
     moved. ContractId of the index is unchanged.

  4. Cancel that subscription. On Hecto, Cancel. The
     500 shares come off, NetDelta returns to 0, and
     the 25 credits return. The Canton Coin adjust
     from step 1 is still there. Parse: the index
     cells are back to step 2.

  5. One order, confirmed twice. Press Duplicate order
     on HECTX. That single press submits the shares
     once and tries to book them twice. The index and
     the booking move once. The second booking is
     refused. It is not a second subscription, so the
     25 is not charged. Then press Callback once: a
     call back into Hecto on this same path. The loop
     is stopped. No shares, no fee.

  Refill restores a spent balance to 100. Subscribe on
  Canton Coin is refused. Hecto does not take a fee for
  a holding it does not run.
