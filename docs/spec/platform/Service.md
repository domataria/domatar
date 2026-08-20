# SERVICE OBJECTS — SPECIFICATION

This document specifies service objects: persistent, immutable interface
descriptors (attributes and messages) that classes implement. A class is a
concrete implementation: handler binding, presentation, and policy. One
class may implement many services; services may extend other services.
Applications interoperate by speaking the same service, regardless of which
app, host, or account owns the implementing class.

The type language and the Attrs/Msgs sub-structures are defined in
[Class](Class.md) PART 3 / PART 4 and are not restated here. GetCls on a
class object returns those structures resolved from the services the class
implements, overlaid with the class's Auth / SideEffect policy.

## PART 1 — PURPOSE

A service is the shared contract: what attributes an object has and what
messages it understands. A class is local: which Java handler runs, which
icon is shown, how instances are named and contained, and what
authorization and side-effect policy applies.

Interoperability between two applications is agreement on an interface.
That interface is a service object (domatar, srv). The implementing class
object (domatar, cls) stays on the owning application. Any two classes that
implement the same service are interoperable through it.

## PART 2 — TWO DESCRIPTOR KINDS

There are two descriptor kinds, both ordinary objects:

  Service descriptor   (domatar, srv)
      Holds the INTERFACE: the attribute and message declarations. Immutable
      once published. Shared: a service defined by one application is
      implemented and referenced by many. Carries no implementation, no
      authorization, no side-effect policy, no instance conventions.

  Class descriptor     (domatar, cls)
      Holds the IMPLEMENTATION: the list of services the class implements,
      the handler binding, the icon, the instance conventions, and the
      per-message authorization / side-effect policy. Carries NO attribute
      or message declarations of its own (PART 7).

The governing rule is: every piece of information lives in exactly one
place. Interface declarations live only in services. Implementation, policy,
and presentation live only in classes. Nothing is duplicated between them.

Classes are always LOCAL: a class describes a class of the application that
owns it, so its ClsAppId is always that application's own appId. Sharing
across applications is via services, not via foreign class descriptors
([Class](Class.md) PART 5).

Dispatch: a message reaches an object, the runtime finds the object's class
(clsAppId, clsId), and ImplMap routes to that class's handler. Services are
the schema and interoperability layer, resolved on read (PART 11), not a
second dispatch axis.

## PART 3 — SERVICE OBJECT IDENTITY

A service object is an object whose own class is (domatar, srv).
Its DomId fields are:

  HstId   : the sub-host on which this service descriptor lives (the same
            sub-host on which class descriptors live — the installing
            account's navigator-<actId> sub-host; [Class](Class.md) PART 5).

  AppId   : the SrvAppId — the application that DEFINES the service. This is
            defining app (the application that published the interface).
            See the dedup note below.

  ActId   : the account the descriptor was installed for.

  ObjId   : "<SrvId>Srv" — the SrvId followed by the literal suffix "Srv".
            The suffix distinguishes a service descriptor from a same-named
            class descriptor ("<ClsId>Cls") and from same-named data
            objects. Example: the service (bookservice, book) has
            ObjId "bookSrv".

  ClsAppId: "domatar"
  ClsId   : "srv"

  ObjName : "<SrvId>Srv"  (same as ObjId)
  ObjDesc : a short human-readable description of the service.

A service is identified throughout the system by the pair (SrvAppId, SrvId).
Written as a reference it is "<SrvAppId>.<SrvId>" (e.g. "bookservice.book").

Collision freedom. The obj primary key is (HstId, AppId, ActId, ObjId).
Because AppId is the SrvAppId and ObjId carries the "Srv" suffix, a service
descriptor never collides with (a) a service of a different defining app
(different AppId), (b) the same app's class descriptor of the same id
(ObjId "bookCls" vs "bookSrv"), or (c) a data object named "book".

Dedup of copies. Because the obj AppId is the defining app's id and services
are immutable (PART 16), every reference to (SrvAppId, SrvId) resolves to the
SAME DomId on a given sub-host. When several installed applications each
"copy" a service onto a user's sub-host for convenience or availability
(PART 16), the idempotent ObjDb.addObjIfMissing coalesces them into one
canonical row. There is one descriptor per service identity per sub-host, no
matter how many applications reference it.

## PART 4 — SERVICE OBJECT ATTRS

The Attrs JSON of a service object contains the interface definition:

  {
    "SrvAppId"    : "<the SrvAppId of this service>",
    "SrvId"       : "<the SrvId of this service>",
    "Description" : "<multi-line prose summary of the interface>",
    "Extends"     : [ "<srvAppId>.<srvId>", ... ],
    "Attrs"       : [
      { "Name" : "<attrName>", "Type" : <type>, "Description" : "<short>" },
      ...
    ],
    "Msgs"        : [
      {
        "Name"        : "<msgName>",
        "Description" : "<sentence or short paragraph>",
        "Type"        : <type>,
        "Parms"       : [
          { "Name" : "<parmName>", "Type" : <type>, "Description" : "<short>" },
          ...
        ]
      },
      ...
    ]
  }

Fields:

  SrvAppId / SrvId   The service's identity (PART 3).

  Description        (optional) Natural-language summary of the interface.

  Extends            (optional) A list of service references this service
                     inherits from (PART 6). Absent or empty means the
                     service has no parent.

  Attrs              The attribute declarations of the interface. Same entry
                     shape as [Class](Class.md) PART 3 (Name, Type, optional
                     Description). Order is informational.

  Msgs               The message declarations of the interface. Each entry
                     has Name, optional Description, return Type, and Parms,
                     using the type language of [Class](Class.md) PART 4.

What a service does NOT contain. A service carries no SideEffect and no Auth
on its messages: those are implementation policy and live on the class
(PART 10). A service carries no Conventions (ObjId / Owner / Lifecycle /
Containment): those describe concrete instances and live on the class
(PART 7). A service carries no ImplRef and no icon. The service is interface
only.

## PART 5 — MEMBER IDENTITY, QUALIFICATION, AND OVERLOADING

Every attribute and every message in the system is identified by the triple

  (SrvAppId, SrvId, Name)

— the service that DEFINES the member, plus the member's Name. There are no
class-local members, so there is no other kind of member identity (PART 7).

Inheritance preserves the defining service. When service B Extends service A,
A's members appear in B's resolved interface but RETAIN A's identity:
A's "GetPrice" is (A, GetPrice), never (B, GetPrice). A re-declaration of the
same Name under B is simply a different member, (B, GetPrice), that coexists.
This is why flattening never produces a conflict (PART 11): identity carries
the defining service, so "same name, different service" are distinct members
by construction, and "same name, same service" reached by two inheritance
paths (a diamond) are the identical member and dedup to one.

Overloading. A single class may implement two services that both declare a
message named "GetBook". These are two distinct members, (srvA, GetBook) and
(srvB, GetBook). Both are simply added to the resolved class (PART 11); the
single class handler implements both. The platform does not treat this as an
error and does not attempt to choose between them — it dispatches by Name as
it does today, and the handler resolves the overlap however it wishes
(PART 9). Frequently the handler does nothing special, because two services
that share a name often intend the same thing; when they do not, the handler
branches on the service identity carried in the envelope.

Attribute names are handled the same way for identity, with an additional
storage question answered in PART 8.

## PART 6 — SERVICE INHERITANCE (Extends)

A service may extend zero or more other services by listing them in Extends.
The resolved interface of a service is the union of its own Attrs/Msgs and
the resolved interfaces of every service it extends, with members keeping
their defining service's identity (PART 5).

  * Multiple inheritance is allowed: a service may extend several parents.

  * Diamonds are well-defined: if S extends both B and C, and both B and C
    extend A, then A's members appear once — deduplicated by their identity
    (A, Name) — because both paths contribute the identical member.

  * There is no override: re-declaring an inherited Name under a descendant
    creates a new, coexisting member under the descendant's identity, not a
    replacement. Because services hold only interface (never implementation),
    there is nothing to override — the single class handler is the only
    implementation and it sees every declared member.

Backward compatibility is expressed with Extends. A later, compatible
version of an interface is published as a NEW service that Extends the older
one (PART 13). A class implementing the newer service therefore implements
the older one too, transitively (PART 12), so consumers written against the
older interface continue to work.

## PART 7 — THE REVISED CLASS OBJECT

A class object remains a row in obj with ClsAppId="domatar", ClsId="cls",
and ObjId "<ClsId>Cls" ([Class](Class.md) PART 6, as implemented in
ClsInstall.addClsObj). Its Attrs JSON is revised to hold implementation,
policy, and presentation only:

  {
    "ClsAppId"    : "<this application's appId>",
    "ClsId"       : "<the class id>",
    "Description" : "<what this class is, optional>",
    "Conventions" : {
      "ObjId" : "...", "ObjName" : "...", "ObjDesc" : "...",
      "Owner" : "...", "Lifecycle" : "...", "Containment" : "..."
    },
    "Implements"  : [ "<srvAppId>.<srvId>", ... ],
    "ImplRef"     : "<fully-qualified handler class>",
    "MsgPolicy"   : [
      { "Srv" : "<srvAppId>.<srvId>", "Name" : "<msgName>",
        "SideEffect" : "Read|Write|Destructive", "Auth" : "<policy>" },
      ...
    ],
    "AttrStorage" : [
      { "Srv" : "<srvAppId>.<srvId>", "Name" : "<attrName>",
        "StorageKey" : "<key>" },
      ...
    ]
  }

Fields:

  Implements   The services this class implements, by reference. The class's
               full interface is the union of these services' resolved
               interfaces (PART 6, PART 11). A class declares no attributes
               or messages of its own; if an application needs a bespoke
               member, it publishes a service for it (possibly a service that
               only this one class ever implements) and implements that.

  ImplRef      (optional) The handler class that implements this class. The
               app manifest ([Domatar](../Domatar.md) PART 11) supplies the same
               binding today; ImplRef lets the descriptor carry it too, the
               direction noted in [Class](Class.md) PART 9.

  Conventions  (optional) Instance naming / ownership / lifecycle /
               containment hints. These describe concrete instances of THIS
               class and are therefore implementation, not interface — so
               they live here, not on the service.

  MsgPolicy    (optional) Per-message authorization and side-effect policy
               (PART 10), keyed by member identity (Srv, Name). Entries are
               needed only where the policy differs from the defaults;
               omitted messages take the defaults (SideEffect "Write",
               Auth "Verified"; [Class](Class.md) PART 3.1).

  AttrStorage  (optional) Per-attribute storage-key overrides (PART 8), keyed
               by member identity (Srv, Name). Omitted attributes store under
               their bare Name.

The icon is not a field: it is the file convention
/domatar/<appId>/icons/cls/<clsId>.svg ([Domatar](../Domatar.md) PART 11.5),
unchanged.

## PART 8 — ATTRIBUTE STORAGE: SAME OR DIFFERENT

Instance attributes are stored as a flat JSON map of key → value in the obj
row's Attrs column (com.domatar.util.ObjAttrs). The interoperability question
is whether two attributes named the same by two different services occupy the
same stored slot (they mean the same thing) or different slots (they merely
share a name).

The rule:

  * Default — SAME. An attribute's storage key is its bare Name. If services
    A and B both declare "Title", by default both read and write the single
    stored key "Title". This is the interoperability-friendly default and the
    common case: a reader using A's interface sees what a writer using B's
    interface wrote.

  * Opt out — DIFFERENT. When a class knows that two same-named attributes
    from two services are NOT the same, it splits them with an AttrStorage
    entry (PART 7) that maps the secondary service's attribute to a qualified
    storage key, conventionally "<srvAppId>.<srvId>.<Name>". The other
    attribute keeps the bare Name, so existing data is undisturbed.

The decision lives on the CLASS, because only the class implements both
services and can know whether the names coincide by meaning or by accident.
Services, being pure interface, cannot know about one another.

The resolved class descriptor (PART 11) exposes, for every attribute, the
StorageKey actually used (defaulting to the bare Name). Handlers and foreign
readers — which already resolve the class on read — use StorageKey to
address the obj Attrs map, so the flat-map storage model is untouched; only
the key changes.

Resolution-time warning. If two services contribute the same attribute Name
with the same default StorageKey but DIFFERENT Type, that is a strong signal
the names coincide by accident; resolution should surface a warning so the
class author can split them. Same Name with the same Type defaults to shared
silently.

## PART 9 — MESSAGE DISPATCH AND THE ENVELOPE

Dispatch is unchanged from [Domatar](../Domatar.md) PART 5 / PART 6: a message
reaches an object, the runtime determines the object's class (clsAppId,
clsId) — from the envelope class fields or from the object — and ImplMap
routes to that class's single handler. The operation field carries the bare
message Name, exactly as today (e.g. "GetBook"). Existing operations
(GetObj, Open, GetLnks, ListBook, …) are unaffected.

The message envelope gains two OPTIONAL fields:

  SrvAppId, SrvId   The service the caller intends. Optional. When present,
                    they identify which interface's message the caller means;
                    the handler may consult them. When absent, dispatch and
                    behaviour are exactly as today.

The handler resolves overlap. Because there is exactly one handler per class,
the message always reaches it regardless of the envelope's service fields.
The platform never has to choose between implementations and never raises an
"ambiguous message" error. The handler decides:

  * If the class implements two services that declare the same message Name
    and treats them identically (they intend the same thing), the handler
    ignores the service fields and runs one code path.

  * If the class must behave differently per service, the handler branches on
    (SrvAppId, SrvId) from the envelope.

The platform does NOT perform structural / signature-based dispatch (it does
not inspect the body to pick an overload). It dispatches by Name and hands
the handler the optional service fields; any finer resolution is the
handler's own logic. Within a single service, message Names are unique;
duplicate Names occur only ACROSS services and are disambiguated, when
needed, by the service fields.

Polymorphic addressing. A caller that wants "the book interface's GetBook"
on an object sends operation "GetBook" with SrvAppId/SrvId = the book
service. Because "implements" is transitive through Extends (PART 12), this
works whenever the object's class implements that service or any descendant
of it — the one handler has the code either way.

## PART 10 — AUTHORIZATION AND SIDE EFFECTS

Authorization (Auth) and side-effect classification (SideEffect) are
properties of the IMPLEMENTATION and the object, not of the interface. They
live strictly on the class (in MsgPolicy, PART 7) and are enforced where the
object lives, in the handler's hasRights ([Domatar](../Domatar.md) PART 6.3).
Services never declare Auth or SideEffect.

This is deliberate: two different implementations of the same service message
may legitimately differ in who may call them or whether they write. The
interface fixes the shape of the call; the implementation fixes its policy
and effect.

Defaults are unchanged ([Class](Class.md) PART 3.1): a message with no MsgPolicy
entry is treated as SideEffect "Write" and Auth "Verified". A class annotates
only the messages whose policy departs from the defaults.

The resolved class descriptor (PART 11) carries SideEffect and Auth on every
message — sourced from the class's MsgPolicy (or the defaults) and merged
onto the service-declared message. Consumers that read policy from a
descriptor (notably the AI Agent's side-effect classification and
consent gate, [AI Agent](../apps/AIAgent.md)) read the merged view.

## PART 11 — RESOLUTION AND ClsMap

The resolved class descriptor is the union of the interfaces of the services
a class implements, with the class's policy and storage decisions overlaid.
It has the same shape that a class descriptor has today ([Class](Class.md)
PART 3), so every existing consumer — the Navigator, the UI builder, and the
AI Agent's GetCls / MsgsSchemaBuilder path — sees an unchanged structure.

Resolution algorithm for class (clsAppId, clsId):

  1. Flatten Implements through service Extends (PART 6) into the set of
     resolved members, each tagged with its defining service identity.
     Diamonds dedup by member identity.

  2. For each attribute, set StorageKey from the class's AttrStorage, or to
     the bare Name by default (PART 8).

  3. For each message, overlay SideEffect and Auth from the class's MsgPolicy,
     or the defaults (PART 10).

  4. Compute ImplementsClosure: the set of all service identities the class
     satisfies — Implements plus every service reachable through Extends
     (PART 12).

  5. Emit a descriptor with merged "Attrs" and "Msgs" (each entry additionally
     carrying its "Srv", and attributes carrying "StorageKey"), plus
     "ImplementsClosure". The additive fields are ignored by consumers that
     do not need them.

ClsMap. To avoid re-resolving on every GetCls, the runtime caches resolved
class descriptors in ClsMap, an in-memory registry analogous to ImplMap
(com.domatar.core). v1 keys ClsMap globally by (clsAppId, clsId); this is a
runtime concern and the key may be narrowed (e.g. host-scoped) later without
affecting the model.

ClsMap differs from ImplMap in one important way: ImplMap maps to code fixed
at startup and never changes, whereas ClsMap caches DATA derived from obj
rows. Coherence is, however, cheap here because services are IMMUTABLE
(PART 16): a service definition, once fetched, never needs refreshing — even
a foreign one fetched from another host can be cached indefinitely. The only
thing that changes is a class's OWN descriptor (its Implements / policy /
storage), which is a local edit; invalidate the affected ClsMap entry when
that local class descriptor is written.

## PART 12 — DISCOVERY: THE IMPLEMENTS CLOSURE

A class C implements a service S if and only if S is in the transitive
closure of C.Implements followed by service Extends edges. This relation is
the basis of interoperability and polymorphism:

  * "Does this object satisfy interface S?" is answered by testing S against
    the resolved class's ImplementsClosure (PART 11), available from GetCls.

  * Polymorphic addressing (PART 9) relies on the closure: addressing an
    object through a base service works whenever the object's class
    implements that service or any descendant of it.

  * Versioning (PART 13) relies on the closure: because a new version Extends
    the old, implementers of the new version are discoverable as implementers
    of the old.

v1 supports the local membership test (against a class resolved on a host).
A network-wide reverse index — "find every object that implements S" — is
future work; the per-class ImplementsClosure is the unit such an index would
aggregate.

## PART 13 — VERSIONING

Services are immutable (PART 16), so a "new version" is always a new,
distinct artifact. Two encodings of that fact were considered:

  * A Version FIELD on the service, making identity the triple
    (SrvAppId, SrvId, Version). Rejected: the version dimension would have to
    propagate everywhere the identity is used — the envelope, ClsMap keys,
    Implements, and overloading — and version-range matching would
    reintroduce exactly the kind of resolution choice the design keeps out of
    the platform. With immutability, a version field is only a label on a
    distinct artifact, which a distinct NAME already provides.

  * The version in the NAME / id. Adopted: a new version is a new service
    identity (a new SrvId, e.g. "book2", or a new SrvAppId.SrvId), and
    nothing in the model changes — identity stays the pair (SrvAppId, SrvId).

Backward compatibility is then expressed structurally with Extends (PART 6):
when a new version is a compatible superset of the old, publish it as a new
service that Extends the old one. Because "implements" is transitive through
Extends (PART 12), a class implementing the new version also implements the
old, so version-agnostic consumers — which should be written against the
stable BASE service — keep working. A breaking change simply does not Extend
the old service; being an unrelated identity is the correct semantics for an
incompatible interface.

Design guidance: put the long-lived, stable surface in a base service, and
put volatile or extended members in versioned services that Extend the base.
Checks that do not care about version key off the base service, which never
changes.

## PART 14 — QUERYING SERVICES

A service descriptor is an ordinary obj, so GetObj returns its full row. In
addition, a GetSvc message on (domatar, srv) mirrors GetCls on (domatar, cls)
([Class](Class.md) PART 8): it accepts (SrvAppId, SrvId) and returns the
service's Attrs document, located on the host by the ObjId convention
"<SrvId>Srv" with obj AppId = SrvAppId.

The (domatar, srv) class is self-describing, exactly as (domatar, cls) is:
there is a service-descriptor object that describes service-descriptor
objects, created by the core install routine alongside (domatar, cls).

Resolving a class's full interface combines the two: GetCls returns the
resolved class descriptor (PART 11), which the runtime builds by reading the
class's Implements and fetching each referenced service via GetSvc (locally,
or from the defining host with the directory-cache fallback of
[Domatar](../Domatar.md) PART 4.3 when a service is foreign), then flattening and
merging. ClsMap caches the result.

## PART 15 — PUBLISHING A SERVICE FOR A CLASS

To expose a class's interface as a service:

  1. Create a service descriptor whose Attrs/Msgs are the interface, and
     whose identity is SrvAppId.SrvId (typically the class's own
     clsAppId.clsId).

  2. On the class descriptor: Implements = [ that service ]; keep ImplRef /
     Conventions / icon; put per-message SideEffect and Auth in MsgPolicy
     where they depart from defaults (PART 7).

  3. GetCls merges interface (from the service) and policy (from the class)
     into the PART 11 document. Navigator, WUI, and the AI Agent consume
     that shape.

Classes are always local; a class's ClsAppId is always the owning
application's appId.

  * An application interoperates with another's objects by implementing the
    other's SERVICE with its OWN local class. Objects carry the implementing
    app's (clsAppId, clsId) and interoperate because ImplementsClosure
    contains the shared service.

  * To use another app's own objects, send ordinary messages to them.
    Do not create objects of another app's class in order to reach that
    app's handlers — implement the shared service with your own handler
    instead.

## PART 16 — IMMUTABILITY

A published service never changes. Its identity (SrvAppId, SrvId) denotes one
interface definition, forever. To evolve an interface, publish a new service
(PART 13). Implementing applications MAY copy a service descriptor onto their
own sub-hosts — for convenience, or so the interface remains available if the
defining application becomes unreachable or is discontinued — and because the
definition never changes, copies cannot diverge and dedup safely by identity
(PART 3).

Immutability is, for now, a design intent rather than an enforced rule: there
is no publishing mechanism in the system yet, so no enforcement is specified.
When a publishing / distribution mechanism is introduced, the natural seam to
enforce immutability is the add-only creation path (services are created with
the idempotent addObjIfMissing and never modified in place); an optional
content hash would let copies be verified against the origin.

## PART 17 — RELATION TO ImplMap AND FUTURE WORK

Implemented model:

  * Services hold interface; classes hold implementation, policy, and
    presentation; classes have no members of their own; interoperability is
    by implementing shared services.

  * Identity of every member is (SrvAppId, SrvId, Name). The wire carries a
    bare operation plus optional (SrvAppId, SrvId); the single class handler
    resolves any overlap.

  * GetCls returns the resolved class descriptor; GetSvc returns a service;
    ClsMap caches resolved descriptors globally.

Future work:

  * Network-wide reverse index of implementers of a service (PART 12).

  * Convergence with ImplMap: a ClsMap entry already aggregates the per-class
    resolved schema; carrying the ImplRef binding alongside it would let the
    runtime bind handlers from descriptors, subsuming the ImplMap-replacement
    direction of [Domatar](../Domatar.md) PART 8 / [Class](Class.md) PART 9.

  * Type references by service name in the type grammar ([Class](Class.md)
    PART 4): a parameter or attribute Type of "<srvAppId>.<srvId>" referring
    to that service's attribute shape.

  * Enforcement of service immutability once a publishing mechanism exists
    (PART 16).

# END OF SPEC
