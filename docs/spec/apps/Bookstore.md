# BOOKSTORE APP — SPECIFICATION

A distributed second-hand and new-book marketplace built on the Domatar
platform. Sellers publish book listings to their own account; a central catalog
aggregates those listings so any user can browse and buy. Purchased books land
as objects in the buyer's own library.

The Bookstore's central host runs on prv2 (tomcat2 / db2).

## PART 1 — OVERVIEW

Goals
-----
  * A user can list books for sale by supplying title, author, ISBN, price,
    condition, and an optional description.
  * Any user can browse the global catalog and purchase a listed book.
  * Purchasing creates a permanent copy of the book in the buyer's library.
  * Every object lives under its owner's account; nothing is stored centrally
    except cross-prv index links in the catalog.

Non-goals (deferred)
--------------------
  * Payment processing — purchases are recorded but no money changes hands.
  * Inventory quantities — each listing is a single-unit "one copy for sale";
    it disappears from the catalog once bought.
  * Search / filtering — v1 returns the full catalog; search is a future PART.
  * Seller notifications — the seller is not notified when their book sells.
  * Ratings and reviews.

## PART 2 — PROVIDER AND HOSTING

2.1  Central host
-----------------
  hstId  : bookstore
  prvId  : prv2
  domain : domatar.avatarvia.com
  address: tomcat2:8080   (on domatar_net)

  tomcat2 must expose the alias "bookstore" on domatar_net so that cross-prv
  HttpClient dispatches from prv1 can reach it.  Add to docker-compose.yml:

    tomcat2:
      networks:
        domatar_net:
          aliases:
            - tomcat2
            - bookstore          ← add this

2.2  Per-user sub-hosts
-----------------------
  Each user who installs Bookstore gets a personal sub-host:

    hstId : bookstore-<actId>
    prvId : <the prv the user is on when they install>
    domain: same as the prv's domain (e.g. domatar.avatarvia.com for prv2,
            domatar.quippin.com for prv1 — users share the same Tomcat)

  The sub-host is created by BookstoreInstall (see PART 5).

2.3  Nginx routing
------------------
  Add a server block in nginx/nginx.conf for the Bookstore domain:

    server {
        listen 80;
        server_name domatar.avatarvia.com;
        location / { proxy_pass http://tomcat2:8080; }
    }

  (All bookstore pages and WUI servlets are served from tomcat2.)

2.4  System account
-------------------
  Account owning the central catalog object (not a real user):

    actId  : bookstore@bookstore
    usrId  : bookstore@bookstore
    usrName: Bookstore
    password: "123"  (bcrypt hash: see PART 9)

## PART 3 — DATA MODEL

3.1  Object classes
-------------------

  (bookstore, forsale)
    One per user. The container for all books the user has listed for sale.
    ObjId : forsale
    HstId : bookstore-<actId>

  (bookstore, listing)
    One per listed book. Lives on the seller's sub-host.
    ObjId : listing-<base64Timestamp>   (IdGen.createId("listing", timestamp))
    HstId : bookstore-<actId>
    Attrs : {
      Title       : string
      Author      : string
      ISBN        : string (optional)
      Price       : string (e.g. "12.50")
      Condition   : string ("New" | "Like New" | "Good" | "Fair" | "Poor")
      Description : string (optional)
      SellerActId : string (actId of the seller)
      SellerName  : string (display name of the seller)
      ListedAt    : string (ISO-8601 timestamp)
      Sold        : string ("false" | "true")
    }

  (bookstore, library)
    One per user. The container for all books the user has purchased.
    ObjId : library
    HstId : bookstore-<actId>

  (bookstore, book)
    One per purchased copy. Lives on the buyer's sub-host.
    ObjId : book-<base64Timestamp>   (IdGen.createId("book", timestamp))
    HstId : bookstore-<actId>
    Attrs : {
      Title        : string
      Author       : string
      ISBN         : string (optional)
      Price        : string (price paid)
      Condition    : string
      Description  : string (optional)
      SellerActId  : string
      SellerName   : string
      PurchasedAt  : string (ISO-8601 timestamp)
      ListingDomId : string (DomId of the original listing, for reference)
    }

  (bookstore, catalog)
    Singleton on the central bookstore host, owned by bookstore@bookstore.
    ObjId : catalog
    HstId : bookstore
    Attrs : {}   (no stored content; behavior is entirely in CatalogImpl)

3.2  Link types
---------------

  forsale  →  listing    Tag=(bookstore, listing)   Val=<actId of seller>
                         SeqNum=<millisecond timestamp at list time>

  library  →  book       Tag=(bookstore, book)       Val=<sellerActId>
                         SeqNum=<millisecond timestamp at purchase time>

  catalog  →  listing    Tag=(bookstore, listing)    Val=<actId of seller>
  (cross-prv)            SeqNum=<millisecond timestamp at list time>
                         LnkObjName=<Title>
                         LnkObjDesc=<Author>

  app-bookstore → forsale   Tag=(navigator, container)  SeqNum=1
  app-bookstore → library   Tag=(navigator, container)  SeqNum=2

## PART 4 — NAVIGATOR INTEGRATION

BookstoreInstall adds the following nodes to the user's Navigator tree
(identical pattern to QuippinInstall):

  navigator-<actId> / navigator / <actId> / root
    └─ [navigator/app] app-bookstore                     seqNum=2 (after Quippin)
         ├─ [navigator/container] bookstore-<actId>/forsale    seqNum=1
         └─ [navigator/container] bookstore-<actId>/library    seqNum=2

The seqNum=2 on the root→app-bookstore link means Bookstore appears as the
second app icon in the Navigator root (after Quippin at seqNum=1).

## PART 5 — INSTALL ROUTINE  (BookstoreInstall.java)

Package: com.bookstore.install

Static method:
  public static void install(String actId,
                             String usrId,
                             String usrName,
                             String domain,
                             String prvId,
                             DomatarMsgClient msgClient)

Steps (all idempotent via addObjIfMissing / addLnkIfMissing):

  1. Create container objects on bookstore-<actId>:
       - (bookstore, forsale)  ObjId=forsale   "For Sale"    "Books you are selling"
       - (bookstore, library)  ObjId=library   "Library"     "Books you have bought"

  2. Create app-bookstore on navigator-<actId>:
       - (navigator, app)      ObjId=app-bookstore  "Bookstore"  "Buy and sell books"

  3. Add root → app-bookstore link  (navigator/app, seqNum=2)

  4. Add app-bookstore → forsale link  (navigator/container, seqNum=1)
     Add app-bookstore → library link  (navigator/container, seqNum=2)

  5. Register this user in the Bookstore catalog:
       Send Register JsonMsg to bookstore/bookstore/bookstore@bookstore/catalog
       Body attrs: { ActId, UsrId, UsrName }
       The catalog does not create a per-user object — it only stores an
       actId→domain mapping so that cross-prv catalog links can be resolved.
       (This step is a no-op in v1 since the catalog uses cross-prv links
        that carry the full LnkHstId; it is included as a hook for future
        directory-style optimisations.)

ActManagerImpl changes:
  Call BookstoreInstall.install(actId, usrId, usrName, domain, prvId, sideClient)
  from addAct(), after QuippinInstall (sideClient already available).

## PART 6 — OBJECT IMPLEMENTATIONS

6.1  ForSaleImpl   (com.bookstore.objimpl.ForSaleImpl)
------------------------------------------------------
ImplMap: put("bookstore", "forsale", new ForSaleImpl())

hasRights: Auth.isVerified(inMsg)

Operations:

  ListBook
    In:  Title, Author, ISBN(opt), Price, Condition, Description(opt)
    Out: { ListingId: <objId> }

    Steps:
      a. Generate objId = IdGen.createId("listing", IdGen.getCurTimeBase64())
         (produces "listing-<base64ts>")
      b. Build Attrs map from input + SellerActId (from context.actId),
         SellerName (from context.usrName), ListedAt (ISO-8601 now), Sold="false"
      c. ObjDb.addObj(listing obj on bookstore-<actId>)
      d. LnkDb.addLnk(forsale → listing, seqNum=currentTimeMillis)
      e. Send Register JsonMsg to the central catalog:
           dst = DomId("bookstore", "bookstore", "bookstore@bookstore", "catalog")
           body attrs = { ListingDomId, Title, Author, Price, Condition,
                          SellerActId, SellerName }
         (Use msgClient; the message is stamped with the authenticated context.)

  DelistBook
    In:  ListingId
    Out: { Delisted: "True" }

    Steps:
      a. Mark listing as Sold="delisted" (ObjDb.modifyObj) — keeps the row for
         audit; do NOT delete so cross-prv links remain resolvable.
      b. LnkDb.deleteLnks(forsale → listing, bookstore, listing)
      c. Send Deregister JsonMsg to the central catalog with { ListingDomId }.

  GetForSale
    In:  (none)
    Out: { Listings: [ {DomId, Title, Author, Price, Condition, Sold}, ... ] }

    Steps:
      Read all lnks from forsale with Tag=(bookstore, listing).
      For each lnk, fetch the listing obj (local) and return its key attrs.

6.2  LibraryImpl   (com.bookstore.objimpl.LibraryImpl)
------------------------------------------------------
ImplMap: put("bookstore", "library", new LibraryImpl())

hasRights: Auth.isVerified(inMsg)

Operations:

  BuyBook
    In:  ListingDomId, Title, Author, ISBN(opt), Price, Condition,
         Description(opt), SellerActId, SellerName
    Out: { BookId: <objId> }

    Steps:
      a. Generate objId = IdGen.createId("book", IdGen.getCurTimeBase64())
      b. Build Attrs from input + PurchasedAt (ISO-8601 now) + ListingDomId
      c. ObjDb.addObj(book obj on bookstore-<actId>)
      d. LnkDb.addLnk(library → book, seqNum=currentTimeMillis)
      e. Send MarkSold JsonMsg to the seller's forsale object:
           dst = ForSaleImpl on bookstore-<sellerActId>
           body = { ListingId: <last segment of ListingDomId> }
         (ForSaleImpl.MarkSold sets Sold="true" and removes the catalog link.)

  GetLibrary
    In:  (none)
    Out: { Books: [ {DomId, Title, Author, Price, PurchasedAt, SellerName}, ... ] }

    Read all lnks from library with Tag=(bookstore, book), fetch local objs.

6.3  CatalogImpl   (com.bookstore.objimpl.CatalogImpl)
------------------------------------------------------
ImplMap: put("bookstore", "catalog", new CatalogImpl())

hasRights: Auth.isVerified(inMsg)

Operations:

  Register
    In:  ListingDomId, Title, Author, Price, Condition, SellerActId, SellerName
    Out: { Registered: "True" }

    Parse the ListingDomId into a DomId object (target of the cross-prv lnk).
    LnkDb.addLnk(catalog → listing,
                 LnkClsAppId="bookstore", LnkClsId="listing",
                 LnkObjName=Title, LnkObjDesc=Author,
                 Tag=(bookstore, listing), Val=SellerActId,
                 SeqNum=currentTimeMillis)

  Deregister
    In:  ListingDomId
    Out: { Deregistered: "True" }

    Parse the ListingDomId into a DomId.
    LnkDb.deleteLnks(catalog → listing, "bookstore", "listing")

  GetCatalog
    In:  (none) — v1 returns all available listings (Sold != "true")
    Out: { Listings: [ {DomId, Title, Author, Price, Condition,
                        SellerActId, SellerName, ListedAt}, ... ] }

    Read all lnks from catalog with Tag=(bookstore, listing).
    For each lnk, attempt to fetch the remote listing obj via msgClient.send
    (Open on the listing DomId). Include only those whose Sold attr != "true".
    If a remote fetch fails (seller offline), include the lnk metadata
    (Title, Author from LnkObjName/LnkObjDesc) and mark as { Available: "unknown" }.

  MarkSold   (called by LibraryImpl after a purchase)
    In:  ListingDomId
    Out: { MarkedSold: "True" }

    Fetch the listing obj (local, since MarkSold is sent to the seller's forsale).
    Set Attrs.Sold = "true" (ObjDb.modifyObj).
    LnkDb.deleteLnks(forsale → listing, "bookstore", "listing").
    Send Deregister to the central catalog.

    NOTE: MarkSold is handled by ForSaleImpl (not CatalogImpl) because it
    modifies objects on the seller's sub-host. The catalog update is a side-effect.

## PART 7 — WEB UI SERVLETS

7.1  ForSaleWui    (com.bookstore.webui.ForSaleWui)
---------------------------------------------------
@WebServlet("/ForSaleWui/*")

Routes messages to the caller's forsale object:
  dst = DomId("bookstore-" + srcAct.actId, "bookstore", srcAct.actId, "forsale")

Supported actions: ListBook, DelistBook, GetForSale
  (parameters forwarded directly from HTTP request into ObjAttrs)

7.2  LibraryWui    (com.bookstore.webui.LibraryWui)
---------------------------------------------------
@WebServlet("/LibraryWui/*")

Routes messages to the caller's library object:
  dst = DomId("bookstore-" + srcAct.actId, "bookstore", srcAct.actId, "library")

Supported actions: BuyBook, GetLibrary

7.3  CatalogWui    (com.bookstore.webui.CatalogWui)
---------------------------------------------------
@WebServlet("/CatalogWui/*")

Routes messages to the central catalog:
  dst = DomId("bookstore", "bookstore", "bookstore@bookstore", "catalog")

Supported actions: GetCatalog
  (Register and Deregister are internal, called server-to-server only)

## PART 8 — FRONTEND PAGES

8.1  bookstore.html   — Main Bookstore page
-------------------------------------------
URL: http://domatar.avatarvia.com/domatar/bookstore
Shows:
  - A "For Sale" section listing the current user's active listings
    (calls ForSaleWui?Action=GetForSale on load)
  - Buttons: [List a Book] → opens newListing.html
             [Browse Catalog] → opens catalog.html
  - A "My Library" section listing purchased books
    (calls LibraryWui?Action=GetLibrary on load)
  - Each listing row has a [Delist] button that calls DelistBook.

8.2  newListing.html  — List a book for sale
--------------------------------------------
URL: http://domatar.avatarvia.com/domatar/newListing
Form fields:
  - Title*         (text input)
  - Author*        (text input)
  - ISBN           (text input, optional)
  - Price*         (text input, e.g. "12.50")
  - Condition*     (select: New / Like New / Good / Fair / Poor)
  - Description    (textarea, optional)
Submit → POST ForSaleWui?Action=ListBook → on success, redirect to bookstore.html

8.3  catalog.html     — Browse catalog and buy
----------------------------------------------
URL: http://domatar.avatarvia.com/domatar/catalog
On load: POST CatalogWui?Action=GetCatalog → renders table of available books.
Each row shows: Title, Author, Price, Condition, Seller name.
[Buy] button on each row:
  - Opens a confirmation dialog.
  - On confirm: POST LibraryWui?Action=BuyBook with listing details
    (passed from the catalog response row: ListingDomId, Title, Author, ISBN,
     Price, Condition, Description, SellerActId, SellerName).
  - On success: refresh the catalog (the bought listing is now Sold="true"
    and will no longer appear) and show a success banner.
  - On error: show alert with error message.
A user cannot buy their own listings (client hides [Buy] for rows where
SellerActId equals the current user's actId cookie; server enforces the same).

## PART 9 — IMPLMAP ENTRIES

In ImplMap.java, add inside the static initialiser:

  put("bookstore", "catalog",  new CatalogImpl());
  put("bookstore", "forsale",  new ForSaleImpl());
  put("bookstore", "library",  new LibraryImpl());

  // listing and book use the default ObjImpl (Open, GetObj).
  // No entry needed for (bookstore, listing) or (bookstore, book).

## PART 10 — DATABASE SEED

File: mySQL/dump-2024-01-xx-bookstore.sql
Applied to: both db1 and db2 (the script uses INSERT IGNORE so it is safe
to run on either; only the relevant rows exist on each).

10.1  System account (db2 / prv2 only meaningful, but harmless on db1)
-----------------------------------------------------------------------
INSERT IGNORE INTO act (ActId, UsrId, UsrName, Pwd)
VALUES ('bookstore@bookstore', 'bookstore@bookstore', 'Bookstore',
        '<bcrypt-hash-of-123>');

10.2  Host rows (db1 and db2)
-------------------------------
-- Central bookstore host (lives on prv2)
INSERT IGNORE INTO hst (HstId, Domain, PrvId, AppId)
VALUES ('bookstore', 'domatar.avatarvia.com', 'prv2', 'bookstore');

-- Per-user sub-host for dave (on prv1)
INSERT IGNORE INTO hst (HstId, Domain, PrvId, AppId)
VALUES ('bookstore-dave@quippin', 'domatar.avatarvia.com', 'prv1', 'bookstore');

-- Per-user sub-host for micha (on prv2)
INSERT IGNORE INTO hst (HstId, Domain, PrvId, AppId)
VALUES ('bookstore-micha@quippin', 'domatar.avatarvia.com', 'prv2', 'bookstore');

10.3  Catalog object (db2)
---------------------------
INSERT IGNORE INTO obj
  (HstId, AppId, ActId, ObjId, ClsAppId, ClsId, ObjName, ObjDesc, Attrs)
VALUES
  ('bookstore', 'bookstore', 'bookstore@bookstore', 'catalog',
   'bookstore', 'catalog', 'Catalog', 'Global Bookstore catalog', '{}');

10.4  Per-user container objects
---------------------------------
For each existing user (dave, micha) insert forsale and library objects
on their respective sub-hosts, and add Navigator links.
(Exact SQL in the migration file; pattern identical to Quippin containers.)

10.5  docker-compose.yml changes
---------------------------------
Add the "bookstore" alias to tomcat2 on domatar_net:

  tomcat2:
    networks:
      domatar_net:
        aliases:
          - tomcat2
          - bookstore    ← new

Update the tomcat2 environment comment to document the new role.

## PART 11 — SECURITY NOTES

11.1  Self-purchase prevention
  ForSaleImpl.ListBook stamps SellerActId = context.actId.
  LibraryWui (and LibraryImpl.BuyBook) must verify that the authenticated
  caller's actId != the listing's SellerActId before executing a purchase.
  The client also hides the [Buy] button for own listings (UI convenience only).

11.2  MarkSold race condition
  Two simultaneous buyers could both receive the same listing from the catalog
  before either purchase completes. In v1, the second purchase succeeds anyway
  (the buyer gets a library copy, the seller's listing is already Sold="true").
  A future version should use an optimistic locking flag or a dedicated
  reservation step to prevent double-sale.

11.3  Cross-prv trust
  Register, Deregister, and MarkSold are server-to-server calls made with
  sideClient (the authenticated msgClient). The receiving ImplMap handler
  verifies Auth.isVerified as usual; no special catalog-admin privilege is
  required beyond being a logged-in user.

## PART 12 — FUTURE WORK

  * Catalog search / filtering by title, author, ISBN, price range, condition.
  * Multiple copies per listing (Quantity field).
  * Seller rating and buyer reviews.
  * Payment integration.
  * Seller notification on purchase (push or polling).
  * Bookstore directory (discover sellers, follow sellers).
  * Book categories / genres.
  * Cover image upload (binary object store).
  * Wishlist container (user saves listings to buy later).
