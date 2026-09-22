# CLASS OBJECTS — SPECIFICATION

This document specifies class descriptors: persistent objects that describe
the IMPLEMENTATION of a class (handler binding, icon, instance conventions,
and per-message authorization / side-effect policy). The INTERFACE of a
class — its attributes and messages — lives on service objects
([Service](Service.md)). The type language in PART 4 is shared: service
descriptors use it for Attrs/Msgs; GetCls returns a resolved document in
the PART 3 shape (service interface merged with class policy).

A class is always local: its ClsAppId is the appId of the application that
owns it. Cross-application reuse is by implementing a shared service, not
by declaring a foreign class. If Bookstore defines a (bookservice, book)
service, a Library app interoperates by implementing that service with its
own local class (library, book).

## PART 1 — PURPOSE

Every Domatar class is identified by the pair (ClsAppId, ClsId). Every
class an application defines is stored as a self-describing
class object. GetCls returns the resolved schema (attributes and messages
from the implemented services, plus the class's policy) so a runtime, a UI
builder, or another application can learn the class without reading Java.

The in-memory ImplMap still locates the Java handler for (ClsAppId, ClsId)
at dispatch time ([Domatar](../Domatar.md) PART 6 / PART 11). Class objects
do not replace ImplMap; Direction is to carry ImplRef on the resolved
descriptor so the two registries converge ([Service](Service.md) PART 17).

## PART 2 — CLASS OBJECT IDENTITY

A class object is an ordinary Domatar object. Its DomId fields are
(as implemented in com.domatar.install.ClsInstall and looked up by
com.domatar.cls.ClsImpl.GetCls):

  HstId   : the sub-host on which this class object lives (the installing
             account's navigator-<actId>-<prvId> sub-host; PART 5).

  AppId   : the ClsAppId — the application that defines the class. GetCls
             locates the descriptor with AppId = the requested ClsAppId, so
             the obj AppId column IS the ClsAppId. Because classes are always
             local, this is also the owning application.

  ActId   : the account that installed the application (the current account).

  ObjId   : <ClsId>Cls
             the ClsId followed by the literal suffix "Cls". The suffix
             distinguishes a class descriptor from a same-named data object
             ("followsCls" vs "follows") and from a service descriptor
             ("<SrvId>Srv"). No app-prefix is needed because the obj AppId
             column already carries the ClsAppId.
             Example: "listingCls", "quipCls", "clsCls".

The class-descriptor objects are themselves instances of the class
(domatar, cls):

  ClsAppId : "domatar"
  ClsId    : "cls"

  ObjName  : same as ObjId, i.e. "<ClsId>Cls"
  ObjDesc  : a short human-readable description of the described class.

Example: the class object for (bookstore, listing) installed for an
account would be the row:

  HstId   : navigator-<actId>-<prvId>
  AppId   : bookstore
  ActId   : <actId>
  ObjId   : listingCls
  ClsAppId: domatar
  ClsId   : cls
  ObjName : listingCls
  ObjDesc : A book listed for sale in the Bookstore

## PART 3 — RESOLVED CLASS DOCUMENT (GetCls)

GetCls returns a JSON document with this shape. Attribute and message
*declarations* live on service objects ([Service](Service.md)); the stored
class object holds Implements, ImplRef, Conventions, icon, and per-message
Auth / SideEffect. The runtime merges those into the document below so
Navigator, WUI builders, and the AI Agent see one schema per class.

  {
    "ClsAppId"    : "<the ClsAppId of the described class>",
    "ClsId"       : "<the ClsId of the described class>",
    "Description" : "<multi-line prose summary of what this class is>",
    "Conventions" : {
      "ObjId"       : "<pattern / rule for ObjId values>",
      "ObjName"     : "<pattern / rule for ObjName values>",
      "ObjDesc"     : "<pattern / rule for ObjDesc values>",
      "Owner"       : "<who owns instances; which actId holds them>",
      "Lifecycle"   : "<when instances are created and destroyed>",
      "Containment" : "<parent/child lnk relationships>"
    },
    "Attrs"    : [
      { "Name" : "<attrName>", "Type" : <type>, "Description" : "<short string>" },
      ...
    ],
    "Msgs"     : [
      {
        "Name"        : "<msgName>",
        "Description" : "<sentence or short paragraph>",
        "SideEffect"  : "Read" | "Write" | "Destructive",
        "Compensates" : "<msgName>",
        "Auth"        : "Public" | "Verified" | "Owner" | "Follower" | "<policy>",
        "Type"        : <type>,
        "Parms" : [
          { "Name" : "<parmName>", "Type" : <type>, "Description" : "<short string>" },
          ...
        ]
      },
      ...
    ]
  }

Fields:

  ClsAppId     The application that defines the class (the namespace owner).
               This is the ClsAppId used on the object and in ImplMap.

  ClsId        The class identifier within that application's namespace.

  Description  (optional) Multi-line natural-language summary of what this
               class represents and how it is used.

  Conventions  (optional) Free-text conventions for how this class's instances
               are named and structured. All sub-fields are optional. Intended
               for both human readers and AI agents that need to address objects
               correctly (see PART 3.1).

  Attrs        The persistent attributes stored on the object (Attrs JSON
               for every instance of this class. Order is informational only.
               Each entry's "Description" (optional) gives a one-line summary
               of that attribute's meaning.

  Msgs         The messages this class understands. Each message entry has:

                 Name        The operation name as it appears in the Domatar
                             message envelope (the "Action" or operation field).

                 Description (optional) Prose for the message's purpose, side
                             effects, and key preconditions.

                 SideEffect  (optional) One of:
                               "Read"        — the operation is read-only; it
                                              produces no lasting change.
                               "Write"       — the operation creates or modifies
                                              persistent state.
                               "Destructive" — the operation deletes or
                                              irrevocably transforms data.

                 Compensates (optional) The MsgName on this class that
                             semantically undoes this message. Absent means
                             the message is irreversible. The named target
                             MUST NOT itself declare Compensates (a
                             compensation is a leaf; Credit has none).
                             The string is provider-asserted. It is not a
                             consent signal until descriptors are
                             publisher-signed ([Security](Security.md)
                             PART 15). Compensate reads it after children
                             and invokes that Msg in-process
                             ([Domatar](../Domatar.md) PART 6.3).

                 Auth        (optional) The minimum authorization level required:
                               "Public"    — no authentication needed.
                               "Verified"  — caller must be authenticated.
                               "Owner"     — caller must own the target object.
                               "Follower"  — caller must follow the owner.
                               <other>     — app-specific policy name.

                 Type        The type of the message's return value (the shape
                             of the Success response body).

                 Parms       The input parameters the message accepts. Each
                             entry's "Description" (optional) gives a one-line
                             summary of that parameter's meaning. Order is
                             informational; names are the binding. A "?" suffix
                             on a parameter name marks it as optional.

All Name values are strings. Types use the type language defined in PART 4.

## PART 3.1 — AGENT-FRIENDLY FIELDS

The fields Description, Conventions, and per-Msg Description / SideEffect /
Auth / per-Parm Description are optional extensions that benefit AI agents
and tooling (see [AI Agent](../apps/AIAgent.md) PART 16). Descriptors without them are
still valid; the runtime and the Navigator ignore the extra fields. Agents
apply conservative defaults when the fields are absent:

  Field missing             Agent default
  ─────────────────────     ─────────────────────────────────────────────
  Class Description         Agent uses ObjDesc as the class summary.
  Msg Description           Agent uses Msg Name as the description.
  Msg SideEffect            "Write" — agent treats the message as a write
                            and requires user confirmation before
                            dispatching it in Agent mode.
  Msg Auth                  "Verified" — agent assumes the caller must be
                            authenticated.
  Attr / Parm Description   Agent uses the Name as the description.

Conventions sub-fields guide the agent when it needs to construct a DomId
to address an object. The most important sub-fields are ObjId (naming
pattern), Owner (whose actId appears in the DomId), and Containment (which
container to Open to find instances). All are free-text; the agent reads
them as prose, not as code.

Recommended practice for new classes:
  1. Write a Description of 1–5 sentences.
  2. Fill in Conventions.ObjId, Conventions.Owner, and
     Conventions.Containment.
  3. For every Msg, set SideEffect and Auth explicitly; set
     Compensates when the message is reversible; add a Description
     of 1–2 sentences.
  4. For Parms that might be ambiguous, add a one-line Description.

## PART 4 — TYPE LANGUAGE

A type describes the exact shape of a JSON value. The grammar is:

  type
    = "String"
    | "Number"
    | [ <type>, ... ]
    | { "<AttrName>" : <type>, ... }

Rules:

  "String"
      A JSON string value.

  "Number"
      A JSON number value (integer or floating-point).

  [ <type>, ... ]
      A JSON array. All elements have the same type. The single type inside
      the brackets is the element type.
      Example:  ["String"]  — an array of strings.
      Example:  [{ "Name" : "String", "Value" : "String" }]
                — an array of objects each having Name and Value fields.

  { "<AttrName>" : <type>, ... }
      A JSON object with a fixed set of named fields. Each entry names a
      field and gives its type. The object may contain exactly these fields
      (and no others, unless the field name ends in "?" to mark it optional).

Optional fields
      A field name ending in "?" is optional: the field may be absent from
      the JSON object entirely.
      Example:  { "Title" : "String", "ISBN?" : "String" }
      means Title is required and ISBN may be omitted.

Type references (future)
      A type name that is not one of the literals above and is not a JSON
      array or object literal is a reference to another class's schema.
      Example:  "bookstore.listing"  — the Attrs shape of that class.
      This is reserved for future use; v1 inlines all shapes explicitly.

## PART 5 — INSTALL-TIME CREATION

When an application is installed for an account on a host, its install
routine (e.g. BookstoreInstall.java, QuippinInstall.java) creates one class
object for every class the application DEFINES, and one service object
([Service](Service.md)) for every service it defines or implements (copying
foreign service descriptors it depends on; [Service](Service.md) PART 16).

The install routine uses addObjIfMissing so that repeated installs are
idempotent. The object is created on the installing account's sub-host.

Creation parameters (passed to ObjDb.addObj or addObjIfMissing):

  HstId    : the account's sub-host  (e.g. "bookstore-<actId>")
  AppId    : the installing app      (e.g. "bookstore")
  ActId    : the installing account
  ObjId    : "<ClsId>Cls"            (e.g. "listingCls")
  ClsAppId : "domatar"
  ClsId    : "cls"
  ObjName  : "<ClsId>Cls"
  ObjDesc  : <short description>
  Attrs    : Implements, ImplRef, Conventions, icon, MsgPolicy
             (interface Attrs/Msgs live on the companion service objects)

Classes are always local: an application's install routine creates class
descriptors only for classes the application itself defines, and the obj
AppId equals that application's appId (= the ClsAppId). An application does
NOT create a class descriptor for a class defined by another application.
To interoperate with another application, an app implements that
application's SERVICE with its own local class and creates a copy of the
service descriptor instead ([Service](Service.md) PART 3 / PART 15).

## PART 6 — QUERYING A CLASS DEFINITION

Any object can be queried to determine its class definition. The standard
GetObj message (handled by ObjImpl) returns the full object including
ClsAppId, ClsId, and Attrs for any object, including a class object.

To retrieve the class definition for a given (ClsAppId, ClsId) pair on a
given host/app/account, send GetObj to the DomId:

  HstId  : <the host where that application is installed for that account>
  AppId  : <the application>
  ActId  : <the account>
  ObjId  : <ClsId>Cls

The response Attrs contain the full class schema (PART 3).

To retrieve a class definition without knowing its DomId, use the GetCls
message on any (domatar, cls) object, passing ClsAppId and ClsId as
parameters. GetCls is defined in PART 8 and implemented in ClsImpl
(com.domatar.cls.ClsImpl). The agent catalogue ([AI Agent](../apps/AIAgent.md) PART 16)
uses GetCls to walk all class descriptors on a host at runtime.

This enables:
  * A UI to render an editor for any object type without hardcoded knowledge.
  * An external application to discover what messages a class understands.
  * Interoperability checks between two applications that claim to use the
    same class.
  * An AI agent to build a tool catalogue automatically from class descriptors
    without any hardcoded knowledge of the installed applications.

## PART 7 — EXAMPLE: BOOKSTORE CLASS OBJECTS

The following class objects are created by BookstoreInstall for each user.
All have ClsAppId="domatar", ClsId="cls" on the object itself.

The examples below use the full agent-friendly shape (PART 3.1) so that app
authors can see every optional field populated at least once.

7.1  (bookstore, listing)
--------------------------
ObjDesc: A book listed for sale in the Bookstore

Attrs:
{
  "ClsAppId"    : "bookstore",
  "ClsId"       : "listing",
  "Description" : "A single book listing created by a seller. Contains the
                   book's metadata, asking price, and current sold status.
                   Read by any authenticated user; modified only by the
                   seller who owns it.",
  "Conventions" : {
    "ObjId"       : "listing-<base64-millis>",
    "ObjName"     : "first 40 chars of Title",
    "ObjDesc"     : "Title by Author",
    "Owner"       : "DomId.actId is the seller's actId",
    "Lifecycle"   : "created by forsale.ListBook; marked sold by forsale.MarkSold; deleted when the seller delists",
    "Containment" : "child of the seller's (bookstore, forsale) container; linked by tag=(bookstore, listing)"
  },
  "Attrs" : [
    { "Name" : "Title",       "Type" : "String", "Description" : "Book title as it appears on the cover." },
    { "Name" : "Author",      "Type" : "String", "Description" : "Author(s) full name." },
    { "Name" : "ISBN",        "Type" : "String", "Description" : "ISBN-10 or ISBN-13; may be empty for older books." },
    { "Name" : "Price",       "Type" : "String", "Description" : "Asking price as a decimal string, e.g. '12.50'." },
    { "Name" : "Condition",   "Type" : "String", "Description" : "One of: New, Like New, Good, Acceptable, Poor." },
    { "Name" : "Description", "Type" : "String", "Description" : "Free-text description from the seller." },
    { "Name" : "SellerActId", "Type" : "String", "Description" : "actId of the seller." },
    { "Name" : "SellerName",  "Type" : "String", "Description" : "Display name of the seller at listing time." },
    { "Name" : "ListedAt",    "Type" : "String", "Description" : "Unix-ms timestamp when the listing was created." },
    { "Name" : "Sold",        "Type" : "String", "Description" : "'True' once the book is sold; 'False' otherwise." }
  ],
  "Msgs" : [
    {
      "Name"        : "GetObj",
      "Description" : "Return all attributes of this listing.",
      "SideEffect"  : "Read",
      "Auth"        : "Verified",
      "Type"  : {
        "Title"       : "String",
        "Author"      : "String",
        "ISBN?"       : "String",
        "Price"       : "String",
        "Condition"   : "String",
        "Description?": "String",
        "SellerActId" : "String",
        "SellerName"  : "String",
        "ListedAt"    : "String",
        "Sold"        : "String"
      },
      "Parms" : []
    }
  ]
}

7.2  (bookstore, book)
-----------------------
ObjDesc: A book purchased by a user in the Bookstore

Attrs:
{
  "ClsAppId"    : "bookstore",
  "ClsId"       : "book",
  "Description" : "A record of a book purchase. Created in the buyer's
                   library when a transaction completes. Read-only after
                   creation.",
  "Conventions" : {
    "ObjId"       : "book-<base64-millis>",
    "ObjName"     : "first 40 chars of Title",
    "Owner"       : "DomId.actId is the buyer's actId",
    "Lifecycle"   : "created by library.BuyBook; never deleted",
    "Containment" : "child of the buyer's (bookstore, library) container"
  },
  "Attrs" : [
    { "Name" : "Title",         "Type" : "String", "Description" : "Book title." },
    { "Name" : "Author",        "Type" : "String", "Description" : "Author(s) full name." },
    { "Name" : "ISBN",          "Type" : "String", "Description" : "ISBN-10 or ISBN-13." },
    { "Name" : "Price",         "Type" : "String", "Description" : "Price paid." },
    { "Name" : "Condition",     "Type" : "String", "Description" : "Condition at time of purchase." },
    { "Name" : "Description",   "Type" : "String", "Description" : "Seller's description at time of purchase." },
    { "Name" : "SellerActId",   "Type" : "String", "Description" : "actId of the seller." },
    { "Name" : "SellerName",    "Type" : "String", "Description" : "Display name of the seller." },
    { "Name" : "PurchasedAt",   "Type" : "String", "Description" : "Unix-ms timestamp of the purchase." },
    { "Name" : "ListingDomId",  "Type" : "String", "Description" : "DomId of the original listing." }
  ],
  "Msgs" : [
    {
      "Name"        : "GetObj",
      "Description" : "Return all attributes of this purchase record.",
      "SideEffect"  : "Read",
      "Auth"        : "Owner",
      "Type"  : {
        "Title"        : "String",
        "Author"       : "String",
        "ISBN?"        : "String",
        "Price"        : "String",
        "Condition"    : "String",
        "Description?" : "String",
        "SellerActId"  : "String",
        "SellerName"   : "String",
        "PurchasedAt"  : "String",
        "ListingDomId" : "String"
      },
      "Parms" : []
    }
  ]
}

7.3  (bookstore, forsale)
--------------------------
ObjDesc: Container for books a user has listed for sale

Attrs:
{
  "ClsAppId"    : "bookstore",
  "ClsId"       : "forsale",
  "Description" : "Per-user container holding all active and past book
                   listings. Use ListBook to create a new listing,
                   GetForSale to browse, MarkSold to close a sale, and
                   DelistBook to remove a listing.",
  "Conventions" : {
    "ObjId"       : "forsale",
    "ObjName"     : "For Sale",
    "Owner"       : "DomId.actId is the seller",
    "Lifecycle"   : "created at Bookstore install time; never deleted",
    "Containment" : "holds (bookstore, listing) children"
  },
  "Attrs"    : [],
  "Msgs" : [
    {
      "Name"        : "ListBook",
      "Description" : "Create a new book listing and add it to the global catalog.",
      "SideEffect"  : "Write",
      "Auth"        : "Owner",
      "Type"  : { "ListingId" : "String" },
      "Parms" : [
        { "Name" : "Title",       "Type" : "String", "Description" : "Book title." },
        { "Name" : "Author",      "Type" : "String", "Description" : "Author(s) full name." },
        { "Name" : "ISBN",        "Type" : "String", "Description" : "ISBN-10 or ISBN-13." },
        { "Name" : "Price",       "Type" : "String", "Description" : "Asking price as a decimal string." },
        { "Name" : "Condition",   "Type" : "String", "Description" : "Condition: New, Like New, Good, Acceptable, or Poor." },
        { "Name" : "Description", "Type" : "String", "Description" : "Free-text seller description." }
      ]
    },
    {
      "Name"        : "DelistBook",
      "Description" : "Remove a listing from sale and from the global catalog.",
      "SideEffect"  : "Destructive",
      "Auth"        : "Owner",
      "Type"  : { "Delisted" : "String" },
      "Parms" : [
        { "Name" : "ListingId", "Type" : "String", "Description" : "ObjId of the listing to remove." }
      ]
    },
    {
      "Name"        : "GetForSale",
      "Description" : "Return all listings currently for sale by this user.",
      "SideEffect"  : "Read",
      "Auth"        : "Verified",
      "Type"  : {
        "Listings" : [{
          "DomId"     : "String",
          "Title"     : "String",
          "Author"    : "String",
          "Price"     : "String",
          "Condition" : "String",
          "Sold"      : "String"
        }]
      },
      "Parms" : []
    },
    {
      "Name"        : "MarkSold",
      "Description" : "Mark a listing as sold. The listing remains visible but Sold='True'.",
      "SideEffect"  : "Write",
      "Auth"        : "Owner",
      "Type"  : { "MarkedSold" : "String" },
      "Parms" : [
        { "Name" : "ListingDomId", "Type" : "String", "Description" : "Full DomId string of the listing." }
      ]
    }
  ]
}

7.4  (bookstore, library)
--------------------------
ObjDesc: Container for books a user has purchased

Attrs:
{
  "ClsAppId"    : "bookstore",
  "ClsId"       : "library",
  "Description" : "Per-user container holding all books purchased through
                   the Bookstore. Use BuyBook to purchase a listing and
                   GetLibrary to browse owned books.",
  "Conventions" : {
    "ObjId"       : "library",
    "ObjName"     : "Library",
    "Owner"       : "DomId.actId is the buyer",
    "Lifecycle"   : "created at Bookstore install time; never deleted",
    "Containment" : "holds (bookstore, book) children"
  },
  "Attrs"    : [],
  "Msgs" : [
    {
      "Name"        : "BuyBook",
      "Description" : "Purchase a listed book. Creates a (bookstore, book) record in this library and marks the listing sold.",
      "SideEffect"  : "Write",
      "Auth"        : "Owner",
      "Type"  : { "BookId" : "String" },
      "Parms" : [
        { "Name" : "ListingDomId",  "Type" : "String", "Description" : "Full DomId string of the listing to purchase." },
        { "Name" : "Title",         "Type" : "String", "Description" : "Book title (denormalised)." },
        { "Name" : "Author",        "Type" : "String", "Description" : "Author(s) (denormalised)." },
        { "Name" : "ISBN",          "Type" : "String", "Description" : "ISBN (denormalised)." },
        { "Name" : "Price",         "Type" : "String", "Description" : "Price paid (denormalised)." },
        { "Name" : "Condition",     "Type" : "String", "Description" : "Condition (denormalised)." },
        { "Name" : "Description",   "Type" : "String", "Description" : "Seller description (denormalised)." },
        { "Name" : "SellerActId",   "Type" : "String", "Description" : "Seller actId (denormalised)." },
        { "Name" : "SellerName",    "Type" : "String", "Description" : "Seller display name (denormalised)." }
      ]
    },
    {
      "Name"        : "GetLibrary",
      "Description" : "Return a summary of all books purchased by this user.",
      "SideEffect"  : "Read",
      "Auth"        : "Owner",
      "Type"  : {
        "Books" : [{
          "DomId"       : "String",
          "Title"       : "String",
          "Author"      : "String",
          "Price"       : "String",
          "PurchasedAt" : "String",
          "SellerName"  : "String"
        }]
      },
      "Parms" : []
    }
  ]
}

7.5  (bookstore, catalog)
--------------------------
ObjDesc: Global catalog of all books listed for sale across all users

Attrs:
{
  "ClsAppId"    : "bookstore",
  "ClsId"       : "catalog",
  "Description" : "Singleton global catalog of all active book listings on
                   this provider. An agent can call GetCatalog to discover
                   books for sale; Register and Deregister are called
                   internally by forsale.ListBook and forsale.DelistBook.",
  "Conventions" : {
    "ObjId"       : "catalog",
    "ObjName"     : "Catalog",
    "Owner"       : "shared; owned by the bookstore app sub-host",
    "Lifecycle"   : "created at Bookstore provider install time",
    "Containment" : "not navigated directly; use GetCatalog"
  },
  "Attrs"    : [],
  "Msgs" : [
    {
      "Name"        : "Register",
      "Description" : "Add a listing to the global catalog. Called internally by forsale.ListBook.",
      "SideEffect"  : "Write",
      "Auth"        : "Verified",
      "Type"  : { "Registered" : "String" },
      "Parms" : [
        { "Name" : "ListingDomId", "Type" : "String", "Description" : "Full DomId string of the listing." },
        { "Name" : "Title",        "Type" : "String", "Description" : "Book title." },
        { "Name" : "Author",       "Type" : "String", "Description" : "Author(s)." },
        { "Name" : "Price",        "Type" : "String", "Description" : "Asking price." },
        { "Name" : "Condition",    "Type" : "String", "Description" : "Condition." },
        { "Name" : "SellerActId",  "Type" : "String", "Description" : "Seller's actId." },
        { "Name" : "SellerName",   "Type" : "String", "Description" : "Seller's display name." }
      ]
    },
    {
      "Name"        : "Deregister",
      "Description" : "Remove a listing from the global catalog. Called internally by forsale.DelistBook.",
      "SideEffect"  : "Write",
      "Auth"        : "Verified",
      "Type"  : { "Deregistered" : "String" },
      "Parms" : [
        { "Name" : "ListingDomId", "Type" : "String", "Description" : "Full DomId string of the listing to remove." }
      ]
    },
    {
      "Name"        : "GetCatalog",
      "Description" : "Return all active listings in the global catalog across all sellers.",
      "SideEffect"  : "Read",
      "Auth"        : "Verified",
      "Type"  : {
        "Listings" : [{
          "DomId"       : "String",
          "Title"       : "String",
          "Author"      : "String",
          "Price"       : "String",
          "Condition"   : "String",
          "SellerActId" : "String",
          "SellerName"  : "String",
          "ListedAt"    : "String"
        }]
      },
      "Parms" : []
    }
  ]
}

## PART 8 — EXAMPLE: THE (domatar, cls) CLASS OBJECT ITSELF

The (domatar, cls) class is self-describing: there is a class object that
describes class objects. It is created by the Domatar core install routine.

ObjId   : clsCls   (following the convention clsId + "Cls")
ObjDesc : Describes the attributes and messages of a Domatar class

Attrs:
{
  "ClsAppId"    : "domatar",
  "ClsId"       : "cls",
  "Description" : "A class descriptor object. Each installed application
                   creates one instance per class it uses, storing the
                   class's attribute and message schema as a persistent
                   object. The (domatar, cls) class is self-describing:
                   this object describes class-descriptor objects.",
  "Conventions" : {
    "ObjId"       : "<ClsId>Cls — the 'Cls' suffix distinguishes class descriptors from same-named data objects",
    "ObjName"     : "Same as ObjId",
    "Owner"       : "The installing application on this sub-host",
    "Lifecycle"   : "Created idempotently at application install time; updated when the descriptor schema evolves",
    "Containment" : "Child of the app's (domatar, clss) Classes container"
  },
  "Attrs" : [
    { "Name" : "ClsAppId",    "Type" : "String",
      "Description" : "The application namespace that defines the described class." },
    { "Name" : "ClsId",       "Type" : "String",
      "Description" : "The class identifier within ClsAppId's namespace." },
    { "Name" : "Description", "Type" : "String",
      "Description" : "Optional multi-line prose summary of the described class." },
    { "Name" : "Conventions", "Type" : "String",
      "Description" : "Optional JSON object with ObjId/ObjName/Owner/Lifecycle/Containment hints." },
    {
      "Name" : "Attrs",
      "Type" : [{ "Name" : "String", "Type" : "String", "Description" : "String" }],
      "Description" : "Array of attribute definitions; each entry has Name, Type, and optional Description."
    },
    {
      "Name" : "Msgs",
      "Type" : [{
        "Name"        : "String",
        "Description" : "String",
        "SideEffect"  : "String",
        "Compensates" : "String",
        "Auth"        : "String",
        "Type"        : "String",
        "Parms"       : [{ "Name" : "String", "Type" : "String", "Description" : "String" }]
      }],
      "Description" : "Array of message definitions; each entry has Name, Type, Parms, and optional Description/SideEffect/Compensates/Auth."
    }
  ],
  "Msgs" : [
    {
      "Name"        : "GetObj",
      "Description" : "Return the full class descriptor for this object.",
      "SideEffect"  : "Read",
      "Auth"        : "Public",
      "Type"  : {
        "ClsAppId"    : "String",
        "ClsId"       : "String",
        "Description?": "String",
        "Conventions?": "String",
        "Attrs"       : [{ "Name" : "String", "Type" : "String", "Description?" : "String" }],
        "Msgs"        : [{
          "Name"        : "String",
          "Description?": "String",
          "SideEffect?" : "String",
          "Compensates?": "String",
          "Auth?"       : "String",
          "Type"        : "String",
          "Parms"       : [{ "Name" : "String", "Type" : "String", "Description?" : "String" }]
        }]
      },
      "Parms" : []
    },
    {
      "Name"        : "GetCls",
      "Description" : "Return the class descriptor for the (ClsAppId, ClsId) pair on this host, looked up by the descriptor object's ObjId convention (<ClsId>Cls). Does not require the caller to know the full DomId of the descriptor.",
      "SideEffect"  : "Read",
      "Auth"        : "Public",
      "Type"  : {
        "ClsAppId" : "String",
        "ClsId"    : "String",
        "ObjName"  : "String",
        "ObjDesc"  : "String",
        "Attrs"    : {}
      },
      "Parms" : [
        { "Name" : "ClsAppId", "Type" : "String",
          "Description" : "The defining application's appId." },
        { "Name" : "ClsId",    "Type" : "String",
          "Description" : "The class identifier within ClsAppId's namespace." }
      ]
    }
  ]
}

NOTE: In the self-description above, the Type fields for Attrs and Msgs entries
are given as "String" for the agent-friendly fields (Description, SideEffect,
Auth, Conventions) because the type language itself is expressed as a JSON
value (string, array, or object), and a fully recursive type-of-a-type
description is not needed in v1. A future version may introduce a formal
"Type" meta-type.

## PART 9 — RELATION TO ImplMap

Class objects plus GetCls are how the running system *describes* a class.
Dispatch still uses ImplMap (keyed by (ClsAppId, ClsId)) to locate Java
handler instances ([Domatar](../Domatar.md) PART 6 / PART 11).

GetCls returns the resolved descriptor: implemented services' Attrs/Msgs
merged with the class's SideEffect / Auth / Conventions, cached in ClsMap
([Service](Service.md)). Navigator, WUI builders, and the AI Agent consume
that document.

Direction ([Service](Service.md) PART 17 / [Domatar](../Domatar.md) PART 8):
carry ImplRef on the resolved descriptor so the runtime can bind handlers
from descriptors rather than from the app manifest, converging ImplMap and
ClsMap.

Implemented in this version:

  * Agent-friendly fields: Description, Conventions, per-Attr Description,
    per-Msg Description / SideEffect / Compensates / Auth, per-Parm
    Description (PART 3 / PART 3.1). Used by the AI Agent tool catalogue
    ([AI Agent](../apps/AIAgent.md) PART 16). Compensates is metadata
    for Compensate ([Domatar](../Domatar.md) PART 6.3); consent stays
    on SideEffect until publisher-signed descriptors
    ([Security](Security.md) PART 15).

  * GetCls on (domatar, cls) returns the resolved Attrs document
    (ClsImpl; [AI Agent](../apps/AIAgent.md) PART 16.1).

Further Direction:

  * ImplRef on the class descriptor so the runtime can load handlers
    without a compile-time ImplMap entry ([Service](Service.md) PART 7 / PART 17).

  * Type references by service name (e.g. "bookservice.book") instead of
    inlining a schema ([Service](Service.md) PART 17).

  * Index class objects so that "list all classes used by application X on
    host Y" can be answered efficiently.

  * Enrich the class descriptors of all existing applications (navigator,
    login, desktop, appstore, quippin, bookstore, money, spreadsheet) with
    the agent-friendly fields. Deferred until descriptors stabilise after
    the AI Agent agent-loop implementation ([AI Agent](../apps/AIAgent.md) PART 18.4).

# END OF SPEC
