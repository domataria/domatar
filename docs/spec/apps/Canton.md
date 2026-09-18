# DOMATAR - CANTON APP (LEDGER HANDLES)

[IMPLEMENTED — Update-CantonApp.txt]

A Domatar application that is a local face onto Canton. Each Daml
contract this user can see becomes an ordinary object: payload and
stakeholders are attributes, choices are messages. The user lists
those objects and exercises the choices they control. Cross-party
effects travel on Canton, never as Domatar messages to another
party's objects.

Canton is the ledger. Domatar objects are a projection of this
user's active contract set (ACS). Counterparties need not install
this app, need not be Domatar users, and are never addressed by
DomId. A Party on the wire is a Canton Party string.

v1 does not talk to a real participant. Handlers call
`CantonClients.get()` for a `CantonClient`. `MockCanton`
implements it in-process as a mockup of that interface. A later
client to the JSON Ledger API implements the same `CantonClient`;
`MockCanton` is then deleted. The app does not change.

Companion documents:

- [Writing Apps](Writing-Apps.md) — manifest, AppInstall, ObjImpl, WUI, facade
- [Spreadsheet](Spreadsheet.md) PART 7.4 — `[domId]attrName` via Open (GetLnks)
- [Class](../platform/Class.md) — GetCls; Iou service + class at install
- [Navigator](Navigator.md) — Open (v1 read-only: exercise is WUI + agent)
- [Identifiers](../platform/Identifiers.md) — actId as object owner, not as a Party
- [AI Agent](AIAgent.md) — facade tools from GetCls

The Java realisation is `apps/canton/` (Maven module, JAR under
`WEB-INF/apps/`).


## PART 1 — GOALS AND NON-GOALS

1.1  Goals

  G1  This user's visible contracts are Domatar objects, owned by
      this user, browsable in Navigator, readable as Attrs.

  G2  Choices this user controls are messages on those objects.
      Exercising a message submits to `CantonClient`, then refreshes
      this user's projection.

  G3  All communication with other parties is through Canton.
      The app never `send()`s to another account's Canton objects.

  G4  `CantonClient` is the only ledger dependency. `MockCanton` is
      disposable. Replacing it with a real Canton client does not
      change handlers, WUI, install, or class descriptors.

1.2  Non-goals

  * A Daml compiler, DAR upload, LF interpreter, or package
    service beyond `getTemplate` on `CantonClient`.
  * A real participant, synchronizer, or Ledger API gRPC.
    protobuf / gRPC bindings. No new third-party dependencies.
  * Reimplementing Money, or treating Canton objects as the
    legal ledger. Money's accounts ARE balances. These objects
    ARE NOT the contract; they are handles.
  * Requiring counterparties to install this app or to have a
    Domatar account.
  * Shared multi-owner objects. One handle, one actId.
  * Cross-provider mock sharing. Each JVM (each provider
    process) has its own `MockCanton` and its own JSON file.
    Two Tomcats in the sim are two mock networks until a
    real participant exists.
  * Saga / Compensate around a Canton submit. The ledger
    command is already atomic. Saga is for Domatar follow-up
    messages, which this app does not send.
  * Divulgence / disclosed-contract objects, multi-party
    submission, contract keys as addressing.

1.3  Depends on

  * [Writing Apps](Writing-Apps.md) (manifest, AppInstall, ObjImpl, WUI, facade).
  * [Class](../platform/Class.md) / [Service](../platform/Service.md)
    (GetCls; Iou service + class at install).
  * [Navigator](Navigator.md) Open (v1 read-only: exercise is WUI + agent).
  * [Identifiers](../platform/Identifiers.md) actId as object owner. Not as a Party.

1.4  Words this spec does not use

  mismatch     one actId vs many Parties is not a mismatch.
               Each installing stakeholder has their own handle
               (PART 2).
  account      Money's object. Here: handle, contract, Party.
  send         to a counterparty. The verb toward Canton is
               submit.


## PART 2 — MODEL

2.1  Handles, not the contract

  A Canton contract has many stakeholders. A Domatar object has
  one actId. That is not a problem: the object is this user's
  INTERFACE to the contract, not the contract itself.

  For contract C and user U who bound Party P:

    if P is a signatory or observer of C (Canton visibility):
      U has one object
        HstId  = canton-\<U.actId\>
        AppId  = canton
        ActId  = U.actId
        ObjId  = \<ContractId of C\>
    else:
      U has no object for C, no link, no DomId to guess.

  Two stakeholders who both installed the app have two objects.
  Same ObjId string (the ContractId), different ActId. The
  primary key (HstId, AppId, ActId, ObjId) already distinguishes
  them. Correlation across parties is Attr ContractId, never a
  shared DomId.

  Same participant / same JVM does not collapse this. Alice and
  Bob on one mock still have two handles if both installed.

2.2  Canton-only counterparties

  A stakeholder who has no Domatar account, or who has not
  installed this app, still exists as a Party on the contract.
  They appear in Signatories / Observers Attrs as Party strings.
  They have no handle on this provider, and this app does not
  create one for them.

  After Alice Transfers to Bob:

    Canton   archives C, creates C' with Owner=Bob
    Alice    Sync: drop handle C; no handle C' if she is no
             longer a stakeholder (as Iou Issuer she still is;
             as Owner-only she is not)
    Bob      if he bound a Party and installed: his Sync
             upserts handle C'. If he did not: nothing on
             Domatar. He still has C' on Canton.

  There is no RegisterAccount analog, no notify, no guessed
  DomId for "Bob's view of this IOU".

2.3  Source of truth

  ACS (via `CantonClient.queryAcs`) is legal state.
  `obj` + `lnk` are an addressable cache for this user:
  identity (the handle exists), containment (Active links),
  last-seen Attrs. They are not a second ledger.
  The only command that changes legal state is
  `CantonClient.submitCreate` / `submitExercise`.

2.4  Live reads (integration)

  Other apps do not call GetIou or Sync. Spreadsheet
  ([Spreadsheet](Spreadsheet.md) PART 7.4) cites `[domId]attrName`
  by sending Open (GetLnks) to that DomId as the caller and reading
  the named Attr from the response. The AI Agent and
  Navigator do the same. hasRights on the handle is
  therefore the integration gate: if the caller may Open
  this user's handle, they see the payload; if not, #N/A
  / Not authorized. No Canton-specific API for foreign
  apps.

  Those Opens must not return the cached `obj.attrs` row.
  A counterparty can exercise on Canton without this
  user submitting anything, so the row goes stale while
  the ContractId is still live. `ContractImpl` therefore
  REFRESHES FROM CANTON on every Open, GetObj, GetIou,
  and GetContract (PART 6.3):

    1. `queryAcs(boundParty)`, find this ContractId.
    2. If found: map payload + stakeholders to Attrs,
       write-through to the obj row, return those Attrs.
    3. If missing: the contract is archived or no longer
       visible. Fail the read (so Spreadsheet yields
       #N/A). Unlink/delete this handle (same as Sync
       drop). Do not return last-seen Amount.

  Write-through keeps the row equal to the last successful
  live read so a listing that has not Opened yet is only
  as stale as "since last Open or Sync", not a permanent
  fork. Spreadsheet evaluation is always step 1–2, not
  the row.

  Sync (container) remains the bulk projection: after
  this user submits, on Open of Active, on explicit Sync.
  It creates/deletes handles. It is not what keeps a
  cited Amount fresh; the handle's own Open is.

  Do not add a GetAttr message. Open is the platform
  read. Do not push from `MockCanton` into obj rows (D4).

2.5  Hosting

  Home host     hstId = canton
                system account canton@canton (catalog, nothing
                of the mock ledger)

  Per-user      hstId = canton-\<actId\>
                party binding, Active container, handles

  `MockCanton` is not an object and not a host. It is a JVM
  singleton behind `CantonClients.get()` (PART 5).


## PART 3 — THREE PILLARS → OBJECTS

A Daml template has payload, stakeholders, choices — in that
order. The handle maps them as follows.

3.1  Payload (with) → Attrs

  Each payload field is a named Attr on the handle, types from
  [Class](../platform/Class.md) PART 4:

    Party     String   (Canton Party id, never actId)
    Text      String
    Decimal   String   (decimal, Money-style; not binary float)
    Int       Number
    Bool      String "true" / "false"  or Number 0/1 — pick
              String to match other apps' flags
    record    nested { ... }
    list      [type]
    optional  Name?
    ContractId  String (the Canton id). Resolving a handle
                DomId to a ContractId is this user's problem
                when they pass one as a choice argument.

  Identity Attrs always present:

    ContractId    String
    TemplateId    String   (v1: "Iou")
    PackageId?    String   (empty on the mock)

  Workshop demo holdings also carry instrument
  economics as ordinary Attrs (Spreadsheet-citable):

    Nav, AsOf, Constituents, Allocations (JSON list of
    {Name, Weight}), and Weight\<Name\> scalars
    (WeightOpenAI, WeightSpaceX, …). GetLnks / GetObj /
    GetContract return them; `[handle]WeightOpenAI` works
    like `[handle]Amount`.

3.2  Stakeholders (signatory / observer) → Attrs, not Auth

  Signatories    ["String"]   Party ids
  Observers      ["String"]   Party ids

  These describe the CONTRACT. They do not describe who owns
  this object. This object's owner is the installing user
  (`obj.actId`). Other stakeholders do not Open this object;
  they have their own handles, or none.

  Optional display sugar, never wire identity:

    BoundParty    the Party this user submits as

  Do not store SignatoryActIds. Counterparties may have no
  actId.

3.3  Choices (choice / controller) → Msgs

  Each choice on the template is a message of the same name.
  Choice argument record → Parms. Return type → Type (opaque
  String/JSON is enough in v1).

  Auth on that Msg: Owner and controller (PART 7).
  SideEffect: consuming → Destructive; nonconsuming → Write.

  Built-in Archive is a choice if the template has it.

  Generic escape hatch on (canton, contract) only:

    Exercise
      Parms: Choice (String), Argument (JSON object)
      SideEffect: Write
      Auth: Owner (handler still checks controller of Choice)

  Named choice messages are what GetCls, the WUI, and the
  agent use. Exercise is for templates with no typed class
  yet. v1's only template is Iou, which has named messages.

  A consuming choice is not `modifyObj`. Submit, then Sync:
  drop or archive the old handle; upsert successors this
  Party can still see. Nonconsuming: leave the same ObjId;
  refresh Attrs if needed. v1 Iou choices are all consuming.


## PART 4 — OBJECT GRAPH

Follow [Writing Apps](Writing-Apps.md): containers, leaves, Navigator
links, idempotent install.

4.1  Classes

  (canton, app)         facade on the app node. LLM-facing
                        coarse ops (PART 9).

  (canton, party)       ObjId = party
                        Attrs: PartyId (String)
                        This user's bound Canton Party.
                        Submitter for every command.
                        BindParty / GetParty.

  (canton, contracts)   ObjId = active
                        This user's ACS container.
                        Open / Sync upserts child handles.
                        Create submits a create command.

  (canton, iou)         Handle for an Iou contract.
                        ObjId = ContractId
                        Attrs: PART 3 + Issuer, Owner,
                        Amount, Currency
                        Msgs: Transfer, Settle, Archive

  (canton, contract)    Generic handle for any other
                        template. v1 mock never mints one.
                        Attrs: ContractId, TemplateId,
                        Signatories, Observers, Payload
                        (nested). Msgs: Exercise.
                        Same Java handler as (canton, iou).

4.2  Navigator skeleton

  root → app-canton                    (navigator, app)
  app-canton → party                   (navigator, container)
  app-canton → active                  (navigator, container)

  active → each handle                 Tag = (canton, iou)
                                       Val = TemplateId
                                       SeqNum = created millis
                                       or insertion order

  Navigator v1 is read-only. Tree shows handles and Attrs.
  Exercise is the Canton WUI and the agent.

4.3  Install (`CantonInstall`)

  installProvider
    `CatalogInstall.registerInCatalog(prvId, "canton")`
    class + service descriptors for the classes in 4.1
    (Iou service members = PART 10). No mock seed here;
    `MockCanton` constructs itself on first `CantonClients.get()`.
    canton is not a default app; users install via App Store
    InstallApp.

  installUser
    `HstDb.addHstIfMissing("canton-" + actId, ...)`
    objs: app-canton, party (PartyId empty), active
    links: root → app-canton → { party, active }
    ClsInstall / SrvInstall on the user sub-host
    (descriptors travel with the host).

  Idempotent: `addObjIfMissing` / `addLnkIfMissing`.


## PART 5 — CantonClient AND THE MOCK

5.1  The seam

  Package `com.canton.ledger`.

  `CantonClient` is the only type `com.canton.objimpl` may call
  for ledger state. It is a mockup of the Ledger API
  operations this app uses, not a fake Canton network.

  Methods (no others in v1):

    queryAcs(party) → List\<Contract\>
        Active contracts visible to party (signatory or
        observer). Real adapter: JSON API ACS query.

    submitCreate(submitter, templateId, payload) →
        SubmitResult
        Create command. Real adapter: submit-and-wait.

    submitExercise(submitter, contractId, choice,
        argument) → SubmitResult
        Exercise command. Real adapter: submit-and-wait.

    getTemplate(templateId) → TemplateDesc
        Payload fields, signatory field names, observer
        field names, choices (name, controllers as field
        names or "signatory", consuming flag, argument
        fields). Real adapter: package/codegen. Not an
        ACS RPC; still Canton-facing metadata so the app
        does not parse Daml.

    listTemplates() → List\<TemplateId\>
        v1: ["Iou"].

  Types are strings and maps (`ObjAttrs` / JSON). No
  protobuf. That matches JSON Ledger API and this repo's
  "no new third-party dependencies" rule.

  Contract (client DTO, not a Domatar obj):
    contractId, templateId, payload map,
    signatories[], observers[]

  SubmitResult:
    archived[] contract ids, created[] Contract
    (enough for Sync; v1 may ignore and always queryAcs)

  TemplateDesc is data. `ContractImpl` maps whatever
  descriptor it got into Attrs and Msgs. It does not
  switch on "Iou" to know field names, except that
  install wrote an Iou class from the same descriptor
  the mock returns (PART 10).

5.2  MockCanton

  One class, in-memory `Map<ContractId, Contract>` plus a JSON
  text file so the map survives Tomcat `--force-recreate`.
  JVM singleton (one per provider process). Implements
  `CantonClient`.

  create    new id (e.g. "cid-" + counter), store row,
            signatories/observers copied from the payload
            fields TemplateDesc names. Submitter must be
            a signatory or the command fails. Then save().

  exercise  submitter must see the contract and be a
            controller of the choice or the command fails.
            Consuming: remove the row. Iou Transfer:
            insert one successor with Owner = argument
            newOwner (hard-coded in the mock, not a
            choice-body language). Iou Settle / Archive:
            no successor. Then save().

  queryAcs  rows whose signatories or observers contain
            the party. That filter is the whole privacy
            model. Does not write the file.

  getTemplate / listTemplates  Iou (PART 10) plus the
        workshop demo templates in `DemoPortfolio`
        (Amulet, Holding, LockedAmulet, TransferInstruction,
        Dlr Repo). listTemplates remains ["Iou"]; Create
        Iou is unchanged. Non-Iou ACS rows project as
        (canton, contract).

  JSON file (`MockCanton`-only; NOT a `CantonClient` method):

    Default path, next to the provider op-key so the existing
    keys volume keeps it across recreate:

      {parent of DOMATAR_PROVIDER_KEY_PATH}/canton-mock-{prvId}.json

    Override: env `CANTON_MOCK_PATH` (absolute file). JUnit
    passes a temp File to the `MockCanton(File)` constructor;
    File null means memory-only (no I/O).

    Per-prvId filenames matter: sim tomcat1 and tomcat2 share
    the sim_keys volume. One file would merge two mock
    networks. prv1 and prv2 MUST NOT share a store.

    Shape (one object, UTF-8):

      { "nextId": \<int\>,
        "contracts": [
          { "contractId", "templateId",
            "payload": { \<field\>: \<string\>, ... },
            "signatories": [ \<Party\>, ... ],
            "observers":  [ \<Party\>, ... ] },
          ...
        ] }

    Load on construction (missing file → empty map, nextId=1).
    The workshop sim may replace the file with
    `DemoPortfolio.storeJson()` (ten contracts visible to
    `davidb-quippin::1220…`: CC, HECTX, HXAI, HXSPX, HECTO,
    USYC, SBC, LockedAmulet, pending CC transfer, DLR repo).
    Index holdings include Allocations / Weight\<Name\> /
    Nav / AsOf payload Attrs.
    That seed is file contents, not submitCreate. Default
    remains empty until the first Create.
    Save after each successful submitCreate / submitExercise:
    write \<path\>.tmp then replace (no new JSON libraries —
    `JsonHashMap` / `Json.toJson` and the existing parse path).
    Corrupt file: fail construction (do not silently empty).

  Not in MockCanton: participants, synchronizers, Daml,
  DAR, party allocation, completions, MySQL tables,
  snapshot objs, `CantonClient.persistMock`.

  Empty until the first Create. No seed IOU.

  On D1, DELETE the JSON file with MockCanton.

5.3  Replacement

  Handlers obtain the client with `CantonClients.get()` (KD11).
  The `CantonClient` interface stays a pure interface.
  `CantonClients.replaceForTest` is package-private and
  JUnit-only.

  Today:   ContractImpl → CantonClients.get() → MockCanton
  Later:   ContractImpl → CantonClients.get() → JsonLedgerApiCanton
           (HTTP to Canton's JSON Ledger API)

  Then DELETE MockCanton and canton-mock-*.json. No
  handler, WUI, or install change if they only ever
  saw `CantonClient` via `CantonClients.get()`.

  Forbidden (makes replacement a rewrite):

    * Handlers importing MockCanton or reading its map
    * CantonClient methods the JSON API cannot implement
      (IouTransfer, persistMock, syncToMysql, ...)
    * actId on the client interface
    * Template field names hard-coded in ContractImpl
      instead of TemplateDesc


## PART 6 — OPERATIONS

Handlers extend `ObjImpl`. After hasRights, they call
`CantonClients.get()`, then Sync this user when the command wrote.

6.1  PartyImpl  (canton, party)

  hasRights: verified Owner.

  GetParty
    Out: { PartyId }

  BindParty
    In:  PartyId (String, may be empty to unbind)
    Out: { PartyId }
    SideEffect: Write
    Stores PartyId on this object. Does not allocate a
    Canton Party. Mock Parties are any string; real
    Canton will require a Party the participant hosts.
    Realistic ids are `hint::1220` + 64 hex (Daml-LF max
    255). The launcher PartyId field allows 255 chars.

6.2  ContractsImpl  (canton, contracts)

  hasRights: verified Owner.

  Sync
    In:  (none)
    Out: { Count }
    SideEffect: Write  (projection, not ledger)
    party = bound PartyId; empty PartyId → error.
    acs = queryAcs(party) via `HandleSync`
    For each ACS contract: upsert handle obj (4.1 class
    from TemplateId; v1 Iou → (canton, iou)), Attrs from
    payload + stakeholders, lnk from active.
    Handles whose ContractId is not in acs: unlink; delete
    or leave orphaned (prefer delete). Do not create
    handles for other users.

  Open
    Default Open, after Sync so the tree matches ACS.

  Create
    In:  TemplateId, plus payload fields (v1: Issuer,
         Owner, Amount, Currency) or a nested Payload
    Out: { ContractId }
    SideEffect: Write
    submitCreate(boundParty, TemplateId, payload); Sync.
    TemplateId defaults to Iou.

  GetContracts
    In:  (none)
    Out: { Contracts: [ { ContractId, TemplateId,
           ContractDomId, ... } ] }
    Read; may Sync first or read current links.

6.3  ContractImpl  (canton, iou) and (canton, contract)

  One Java class `ContractImpl` registered for both clsIds.

  hasRights: PART 7.

  `HandleSync.refreshOrDrop` (private, every read)
    PART 2.4 steps 1–3. Open (GetLnks) and GetObj MUST
    go through this before returning Attrs; they must
    not use ObjImpl's default, which reads the obj row.
    GetIou / GetContract use the same helper.

  GetIou / GetContract
    Out: identity + payload + Signatories + Observers
    from the live ACS row, not from stored Attrs.

  Transfer     In: NewOwner (Party String)
  Settle       In: (none)
  Archive      In: (none)
  Exercise     In: Choice, Argument   // generic class only

  Each: submitExercise(boundParty, objId, choice, args);
        Sync the owner's Active container (same actId);
        return created/archived ids or the post-Sync Attrs.

  boundParty comes from this user's (canton, party) object
  on the same sub-host, not from the handle Attrs.

6.4  CantonAppImpl  (canton, app)

  Facade ([Writing Apps](Writing-Apps.md) PART 9, [AI Agent](AIAgent.md) PART 16):

    BindParty, GetParty, ListContracts, Sync,
    CreateIou, GetIou, Transfer, Settle

  Coarse ops so the agent does not invent clsIds. Each
  forwards to the objects in 6.1–6.3 on this user's canton
  host. The facade does not send to another actId.


## PART 7 — hasRights

The mock (and later real Canton) enforces visibility and
controllers again. hasRights is the Domatar pre-check and
the UX filter. It does not replace the ledger.

  party object, active container, app node
      verified and req.actId == obj.actId

  handle (iou / contract)
      Read  (Open, GetIou, GetContract):
        verified Owner. This is her handle; another
        stakeholder never Opens it.
      Choice C (Transfer, Settle, Archive, Exercise):
        verified Owner, and bound Party is a controller
        of C according to TemplateDesc + payload
        (Iou Transfer → payload Owner; Settle/Archive →
        payload Issuer). If unbound, deny.

  Do not admit a second stakeholder to this object.
  Do not use Auth "Owner" on a choice without the
  controller check: an observer has a handle they can
  read and cannot exercise.

  GetCls Auth strings:
    reads            Owner
    Transfer         Controller   (app-specific policy name)
    Settle, Archive  Controller
    Exercise         Owner        (handler still checks)


## PART 8 — WUI, NAVIGATOR, AGENT, SPREADSHEET

8.1  WUI

  Translators only ([Writing Apps](Writing-Apps.md) PART 7).

    PartyWui        BindParty / GetParty
    ContractsWui    Sync, Create, list
    IouWui          GetIou, Transfer, Settle, Archive

  Assets: `canton.html` (launcher: bind, create Iou, list
  Symbol/Name/Amount/Owner) and `iou.html?ContractDomId=`
  (every payload Attr, Allocations rendered as a table,
  Transfer / Settle / Archive).
  Party ids are text fields (`hint::1220…`, maxlength 255),
  not actId pickers. LaunchPath `/domatar/canton/canton.html`.

8.2  Navigator

  Tree in PART 4.2. Right pane = Attrs. No exercise in
  Navigator v1.

8.3  Agent

  useApp(canton) → facade tools from GetCls on (canton, app)
  plus GetCls on (canton, iou) when a handle is the target.
  Confirm Write/Destructive as usual ([AI Agent](AIAgent.md)).

8.4  Spreadsheet

  A cell may cite Amount, Nav, Allocations, or WeightOpenAI
  (etc.) of this user's handle
  ([Spreadsheet](Spreadsheet.md) PART 7.4). It cannot cite a
  counterparty's handle; that DomId is not theirs. Live fetch
  still runs hasRights as the spreadsheet owner.


## PART 9 — MODULE LAYOUT

  apps/canton/
    pom.xml                         artifactId canton, finalName
                                    canton, dep domatar-core
    src/main/java/com/canton/
      ledger/CantonClient.java      interface
      ledger/CantonClients.java     CantonClients.get() (KD11)
      ledger/MockCanton.java        v1 implementation
      ledger/Contract.java          DTO
      ledger/SubmitResult.java
      ledger/TemplateDesc.java
      ledger/IouTemplates.java      Iou TemplateDesc constant
      install/CantonInstall.java
      objimpl/CantonAppImpl.java
      objimpl/PartyImpl.java
      objimpl/ContractsImpl.java
      objimpl/ContractImpl.java     (canton, iou) and
                                    (canton, contract)
      objimpl/HandleSync.java       ACS → handles
      webui/PartyWui.java
      webui/ContractsWui.java
      webui/IouWui.java
    src/main/resources/
      META-INF/domatar/app.manifest
      canton/assets/                canton.html, iou.html,
                                    icons/cls/*.svg

  Parent pom module apps/canton. platform/war copies the JAR
  into WEB-INF/apps/ with the other apps.

  Manifest AppId=canton, InstallClass=CantonInstall,
  Handler lines for app, party, contracts, iou, contract,
  Wui lines for the three WUIs.

  How a handler obtains CantonClient: `CantonClients.get()`
  returning the JVM singleton mock (KD11). Later the accessor
  returns JsonLedgerApiCanton from config (participant URL).
  Config is not Mock-specific. `CantonClients.replaceForTest`
  is JUnit-only.


## PART 10 — FIRST TEMPLATE (Iou)

Hard-coded TemplateDesc returned by `MockCanton.getTemplate("Iou")`
and copied into the (canton, iou) service at install. One source
of field names: `IouTemplates`. Install must not drift.

  TemplateId    Iou
  Payload       Issuer : Party
                Owner  : Party
                Amount : Decimal
                Currency : Text
  Signatory     Issuer
  Observer      Owner
  Choices
    Transfer    controller Owner    consuming
                argument NewOwner : Party
                mock body: create Iou { Issuer, Owner:
                NewOwner, Amount, Currency }
    Settle      controller Issuer   consuming
                no argument, no successor
    Archive     controller Issuer   consuming
                no argument, no successor

  Create payload must include Issuer; submitter must be
  Issuer (signatory). Owner may be any Party string,
  including one with no Domatar user.

  After Transfer, Issuer remains a stakeholder of the
  successor and still sees it. The previous Owner does
  not, unless they are still Issuer.


## PART 11 — THIS REALISATION

  App            apps/canton JAR, [Writing Apps](Writing-Apps.md) pattern.

  Ledger         CantonClient + MockCanton (in-memory +
                 canton-mock-{prvId}.json). Swap point for
                 JSON Ledger API. Accessor: CantonClients.get().

  Projection     Sync on (canton, contracts): ACS → this
                 user's handles + lnks. Live Open on the
                 handle (PART 2.4).

  Tests (JUnit in the canton module, no live Canton):
    * queryAcs does not return a contract the party is
      not on
    * create as non-signatory fails
    * Transfer as Owner archives and creates successor
      with new Owner; issuer still sees it; old Owner
      does not if they are no longer observer
    * Transfer as Issuer (not Owner) fails
    * Settle as Issuer archives; as Owner fails
    * two bound users, two handles, same ContractId
      string, different actId (install + Sync test, may
      be handler-level)
    * handlers do not reference MockCanton by type
      (compile-time: only CantonClient via CantonClients)
    * after a counterparty Transfer on the mock, Open
      on the issuer's handle returns the new Owner
      without an intervening Sync of Active
    * Open of an archived ContractId fails (no last-seen
      Amount); the handle is dropped
    * JSON file round-trip: new MockCanton(tmp) after
      create sees the same ACS; nextId continues
    * --force-recreate reloads canton-mock-{prvId}.json;
      handles remain live

  Live / browser (App Store InstallApp, not default-apps):
    bind Party "Alice", Create Iou Issuer=Alice
    Owner=Bob Amount=100 Currency=USD, see handle in
    Active and Navigator. Transfer as Alice (Issuer, not
    Owner) is not authorized. Bind the Owner Party and
    Transfer NewOwner=Bank: that Party's handle for the
    old ContractId is gone; Alice as Issuer still sees
    the successor; no Domatar object for Bank unless Bank
    installed and bound. Recreate Tomcat: remaining
    visible IOUs still Open.


## PART 12 — KEY DECISIONS

  KD1  CantonClient is the only ledger seam. MockCanton is
       a disposable mockup of that interface, not a fake
       network. Replacement is a new CantonClient impl.

  KD2  One handle object per installing user per visible
       contract. ObjId = ContractId, ActId = that user.
       No shared multi-owner row. No handle for parties
       who did not install.

  KD3  All cross-party effects through CantonClient.
       No msgClient.send to another user's Canton objects.
       Counterparties need not be Domatar users. Party on
       the wire is a Canton Party string, never actId.

  KD4  ACS is source of truth. obj/lnk are a cache.
       After submit, Sync this user only. Every Open /
       GetObj / GetIou on a handle refreshes from
       Canton and write-throughs Attrs (PART 2.4).
       Foreign apps (Spreadsheet) integrate by Open;
       they never read a stale row.

  KD5  Payload fields and Signatories/Observers are Attrs.
       Choices are Msgs. Auth is Owner (read) and
       Owner+controller (choice). Other stakeholders do
       not use this object.

  KD6  Client types are strings and maps. Methods are
       queryAcs, submitCreate, submitExercise, getTemplate,
       listTemplates. No protobuf. Later transport is
       JSON Ledger API.

  KD7  MockCanton: in-memory map + JSON text file
       canton-mock-{prvId}.json on the provider-keys
       volume (5.2). Iou Transfer successor hard-coded.
       ACS visibility filter. Two provider JVMs are two
       mock networks and two files. Persistence is not
       on CantonClient.

  KD8  First template Iou (PART 10). Generic (canton,
       contract) exists for later templates. The mock
       Create path still mints only Iou; a workshop JSON
       seed may load DemoPortfolio holdings onto
       (canton, contract).

  KD9  Do not wrap submit in Saga Compensate. Do not
       analogize RegisterAccount / BankTransfer / Credit.

  KD10 Navigator shows; WUI and the agent exercise.

  KD11 Accessor is CantonClients.get() → CantonClient,
       default MockCanton. The interface stays a pure
       interface. CantonClients.replaceForTest is
       package-private and JUnit-only.


## PART 13 — DIRECTION

  D1  JsonLedgerApiCanton: HTTP to a real participant.
      Delete MockCanton and canton-mock-*.json. BindParty
      becomes a Party the participant actually hosts.
      getTemplate from package/codegen.

  D2  Further templates: add TemplateDesc + class/service
      at install (or generate from DAR). Same ContractImpl.
      Unknown templates use (canton, contract) + Exercise.

  D3  Optional Archived container. v1 deletes handles that
      leave the ACS.

  D4  Subscription / push into obj rows so Open need not
      round-trip to Canton. v1 is pull on every handle
      Open (PART 2.4). Active Sync-on-Open stays pull.
