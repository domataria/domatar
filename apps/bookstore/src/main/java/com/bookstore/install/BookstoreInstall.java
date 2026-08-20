/*
 * Copyright (c) 2024 Domatar
 */

package com.bookstore.install;

import com.domatar.db.HstDb;
import com.domatar.db.LnkDb;
import com.domatar.db.ObjDb;
import com.domatar.install.AppInstall;
import com.domatar.install.CatalogInstall;
import com.domatar.install.ClsInstall;
import com.domatar.install.SrvInstall;
import com.domatar.util.JsonMsg;
import com.domatar.util.Lnk;
import com.domatar.util.ObjAttrs;
import com.domatar.util.DomId;
import com.domatar.util.DomatarException;
import com.domatar.util.DomatarMsgClient;

/**
 * Install routine for the Bookstore app (Spec-Bookstore.txt PART 5).
 *
 * Creates the per-user bookstore~<actId> container objects (forsale, library),
 * the app-bookstore node in the Navigator, and the Navigator skeleton links.
 * Also sends a Register message to the central catalog (no-op in v1).
 *
 * Every step is idempotent: a second call is a no-op.
 */
public class BookstoreInstall implements AppInstall
{
  private static final String CAT_HST_ID = "bookstore";
  private static final String CAT_APP_ID = "bookstore";
  private static final String CAT_ACT_ID = "bookstore@bookstore";
  private static final String CAT_OBJ_ID = "catalog";

  @Override
  public void installProvider(final String prvId, final String domain) throws DomatarException
  {
    CatalogInstall.registerInCatalog(prvId, "bookstore");
  }

  @Override
  public void installUser(final String actId,
                          final String usrId,
                          final String usrName,
                          final String prvId,
                          final String domain,
                          final DomatarMsgClient msgClient) throws DomatarException
  {
    install(actId, usrId, usrName, domain, prvId, msgClient);
  }

  private static void install(final String actId,
                              final String usrId,
                              final String usrName,
                              final String domain,
                              final String prvId,
                              final DomatarMsgClient msgClient) throws DomatarException
  {
    final String bsHstId  = DomId.subHstId("bookstore", actId);
    final String navHstId = DomId.subHstId("navigator", actId, prvId);

    // 0. Per-user bookstore sub-host.
    if (HstDb.getHst(bsHstId) == null)
      HstDb.addHst(bsHstId, domain, prvId);

    final DomId rootId    = new DomId(navHstId, "navigator", actId, "root");
    final DomId appBsId   = new DomId(bsHstId,  "bookstore", actId, "app-bookstore");
    final DomId forSaleId = new DomId(bsHstId,  "bookstore", actId, "forsale");
    final DomId libraryId = new DomId(bsHstId,  "bookstore", actId, "library");
    final DomId cartId    = new DomId(bsHstId,  "bookstore", actId, "cart");

    // 1. Container objs on bookstore~<actId>
    ObjDb.addObjIfMissing(forSaleId, "bookstore", "forsale", "For Sale",
                          "Books you are selling");
    ObjDb.addObjIfMissing(libraryId, "bookstore", "library", "Library",
                          "Books you have bought");
    ObjDb.addObjIfMissing(cartId, "bookstore", "cart", "My Shopping Cart",
                          "Books saved for purchase");

    // 2. app-bookstore obj on bookstore~<actId>
    ObjDb.addObjIfMissing(appBsId, "bookstore", "app", "Bookstore",
                          "Buy and sell books");
    ObjDb.reclassObj(appBsId, "bookstore", "app");

    // 3. root -> app-bookstore lnk  (seqNum=6: after Quippin/Login/Desktop/Navigator/AiAgent)
    addLnkIfMissing(rootId, appBsId,
                    "bookstore", "app",
                    "Bookstore", "Buy and sell books",
                    "navigator", "app", null, 6);

    // 4. app-bookstore -> container lnks
    addLnkIfMissing(appBsId, forSaleId,
                    "bookstore", "forsale",
                    "For Sale", "Books you are selling",
                    "bookstore", "forsale", null, 1);

    addLnkIfMissing(appBsId, libraryId,
                    "bookstore", "library",
                    "Library", "Books you have bought",
                    "bookstore", "library", null, 2);

    addLnkIfMissing(appBsId, cartId,
                    "bookstore", "cart",
                    "My Shopping Cart", "Books saved for purchase",
                    "bookstore", "cart", null, 4);

    // 5. Services container + class container + service descriptors (interface only) and
    //    slim class descriptors (Implements + MsgPolicy). See Spec-Service.txt PART 5/7.
    //    For already-installed accounts, run /Setup?migrate=services to split
    //    the existing legacy inline class rows into service + slim class rows.
    SrvInstall.ensureSrvsContainer(appBsId,
        "Service descriptors for Bookstore", "bookstore", 3);
    ClsInstall.ensureClssContainer(appBsId,
        "Class descriptors for Bookstore", "bookstore", 5);

    // ---- Services (interface only; SideEffect / Auth live on the class) ----

    SrvInstall.upsertSrvObj(appBsId, "bookstore", "app",
        "Bookstore app entry point service interface",
        bsAppSrvJson());

    SrvInstall.addSrvObj(appBsId, "bookstore", "forsale",
        "Container of books listed for sale by one user",
        bsForsaleSrvJson());

    SrvInstall.addSrvObj(appBsId, "bookstore", "listing",
        "A single book-for-sale listing object",
        bsListingSrvJson());

    SrvInstall.addSrvObj(appBsId, "bookstore", "library",
        "Container of books purchased and owned by one user",
        bsLibrarySrvJson());

    SrvInstall.addSrvObj(appBsId, "bookstore", "book",
        "A purchased book record in a user library",
        bsBookSrvJson());

    SrvInstall.addSrvObj(appBsId, "bookstore", "cart",
        "Shopping cart of listings saved for purchase by one user",
        bsCartSrvJson());

    SrvInstall.addSrvObj(appBsId, "bookstore", "cartitem",
        "A single listing saved in a user shopping cart",
        bsCartItemSrvJson());

    SrvInstall.addSrvObj(appBsId, "bookstore", "catalog",
        "Shared central catalog of all available bookstore listings",
        bsCatalogSrvJson());

    // ---- Slim classes (Implements + MsgPolicy; no inline Attrs / Msgs) ----

    ClsInstall.upsertClsImplementing(appBsId, "bookstore", "app",
        "Bookstore app entry point (LLM-native operations)",
        bsAppClsJson());

    ClsInstall.upsertClsImplementing(appBsId, "bookstore", "forsale",
        "Container of books listed for sale by one user",
        bsForsaleClsJson());

    ClsInstall.upsertClsImplementing(appBsId, "bookstore", "listing",
        "A single book-for-sale listing object",
        bsListingClsJson());

    ClsInstall.upsertClsImplementing(appBsId, "bookstore", "library",
        "Container of books purchased and owned by one user",
        bsLibraryClsJson());

    ClsInstall.upsertClsImplementing(appBsId, "bookstore", "book",
        "A purchased book record in a user library",
        bsBookClsJson());

    ClsInstall.upsertClsImplementing(appBsId, "bookstore", "cart",
        "Shopping cart of listings saved for purchase by one user",
        bsCartClsJson());

    ClsInstall.upsertClsImplementing(appBsId, "bookstore", "cartitem",
        "A single listing saved in a user shopping cart",
        bsCartItemClsJson());

    ClsInstall.upsertClsImplementing(appBsId, "bookstore", "catalog",
        "Shared central catalog of all available bookstore listings",
        bsCatalogClsJson());

    // 6. Register this user with the central catalog (no-op hook in v1).
    final DomId catDomId = new DomId(CAT_HST_ID, CAT_APP_ID, CAT_ACT_ID, CAT_OBJ_ID);
    final JsonMsg regMsg = new JsonMsg();
    final ObjAttrs regAttrs = new ObjAttrs();
    regAttrs.addAttr("ActId",   actId);
    regAttrs.addAttr("UsrId",   usrId);
    regAttrs.addAttr("UsrName", usrName);
    regMsg.addRequestBody("UserRegister", regAttrs);
    regMsg.addClsId(CAT_APP_ID, "catalog");
    msgClient.send(catDomId, regMsg);
  }

  // ---------------------------------------------------------------------------
  // Service document builders — interface only (no SideEffect / Auth)
  // ---------------------------------------------------------------------------

  /** bookstore.app — structured format with natural-language descriptions for LLM clarity. */
  private static String bsAppSrvJson()
  {
    return "{"
        + "\"SrvAppId\":\"bookstore\","
        + "\"SrvId\":\"app\","
        + "\"Description\":\"Bookstore app entry point. Use these LLM-native operations to browse"
        +   " the user's listings, library, shopping cart, and the global catalog.\","
        + "\"Msgs\":["
        +   "{\"Name\":\"GetCart\","
        +    "\"Description\":\"Get the shopping cart items for the current user. "
        +      "Returns each item's title, author, price, and availability.\","
        +    "\"SideEffect\":\"Read\"},"
        +   "{\"Name\":\"GetForSale\","
        +    "\"Description\":\"Get all books the current user has listed for sale. "
        +      "Returns listing details including price, condition, and ISBN.\","
        +    "\"SideEffect\":\"Read\"},"
        +   "{\"Name\":\"GetLibrary\","
        +    "\"Description\":\"Get all books the current user has purchased. "
        +      "Returns book details from the user's personal library.\","
        +    "\"SideEffect\":\"Read\"},"
        +   "{\"Name\":\"GetCatalog\","
        +    "\"Description\":\"Get all books available for purchase in the global catalog. "
        +      "Returns listings from all sellers including price and condition.\","
        +    "\"SideEffect\":\"Read\"}"
        + "],"
        + "\"Attrs\":\"DisplayName, IconPath, LaunchPath\""
        + "}";
  }

  private static String bsForsaleSrvJson()
  {
    return "{"
        + "\"SrvAppId\":\"bookstore\","
        + "\"SrvId\":\"forsale\","
        + "\"Attrs\":[],"
        + "\"Msgs\":["
        +   "{\"Name\":\"ListBook\","
        +    "\"Parms\":[{\"Name\":\"Title\",\"Type\":\"String\"},{\"Name\":\"Author\",\"Type\":\"String\"},"
        +               "{\"Name\":\"Price\",\"Type\":\"String\"},{\"Name\":\"Condition\",\"Type\":\"String\"},"
        +               "{\"Name\":\"SellerActId\",\"Type\":\"String\"},{\"Name\":\"SellerName\",\"Type\":\"String?\"},"
        +               "{\"Name\":\"ISBN\",\"Type\":\"String?\"},{\"Name\":\"Description\",\"Type\":\"String?\"}],"
        +    "\"Type\":{\"ListingId\":\"String\"}},"
        +   "{\"Name\":\"DelistBook\","
        +    "\"Parms\":[{\"Name\":\"ListingId\",\"Type\":\"String\"}],"
        +    "\"Type\":{\"Delisted\":\"String\"}},"
        +   "{\"Name\":\"GetForSale\","
        +    "\"Type\":{\"Listings\":[{\"DomId\":\"String\",\"ListingId\":\"String\",\"Title\":\"String\","
        +                             "\"Author\":\"String\",\"Price\":\"String\",\"Condition\":\"String\","
        +                             "\"ListedAt\":\"String\",\"Sold\":\"String\"}]}},"
        +   "{\"Name\":\"MarkSold\","
        +    "\"Parms\":[{\"Name\":\"ListingId\",\"Type\":\"String\"},{\"Name\":\"ListingDomId\",\"Type\":\"String\"}],"
        +    "\"Type\":{\"MarkedSold\":\"String\"}}"
        + "]}";
  }

  private static String bsListingSrvJson()
  {
    return "{"
        + "\"SrvAppId\":\"bookstore\","
        + "\"SrvId\":\"listing\","
        + "\"Attrs\":["
        +   "{\"Name\":\"Title\",\"Type\":\"String\"},{\"Name\":\"Author\",\"Type\":\"String\"},"
        +   "{\"Name\":\"ISBN\",\"Type\":\"String?\"},{\"Name\":\"Price\",\"Type\":\"String\"},"
        +   "{\"Name\":\"Condition\",\"Type\":\"String\"},{\"Name\":\"Description\",\"Type\":\"String?\"},"
        +   "{\"Name\":\"SellerActId\",\"Type\":\"String\"},{\"Name\":\"SellerName\",\"Type\":\"String\"},"
        +   "{\"Name\":\"ListedAt\",\"Type\":\"String\"},{\"Name\":\"Sold\",\"Type\":\"String\"}"
        + "],"
        + "\"Msgs\":["
        +   "{\"Name\":\"GetObj\",\"Type\":{\"ObjName\":\"String\",\"ObjDesc\":\"String\"}}"
        + "]}";
  }

  private static String bsLibrarySrvJson()
  {
    return "{"
        + "\"SrvAppId\":\"bookstore\","
        + "\"SrvId\":\"library\","
        + "\"Attrs\":[],"
        + "\"Msgs\":["
        +   "{\"Name\":\"BuyBook\","
        +    "\"Parms\":[{\"Name\":\"ListingDomId\",\"Type\":\"String\"},{\"Name\":\"Title\",\"Type\":\"String\"},"
        +               "{\"Name\":\"Author\",\"Type\":\"String\"},{\"Name\":\"Price\",\"Type\":\"String\"},"
        +               "{\"Name\":\"Condition\",\"Type\":\"String\"},{\"Name\":\"SellerActId\",\"Type\":\"String\"},"
        +               "{\"Name\":\"SellerName\",\"Type\":\"String?\"},{\"Name\":\"ISBN\",\"Type\":\"String?\"},"
        +               "{\"Name\":\"Description\",\"Type\":\"String?\"}],"
        +    "\"Type\":{\"BookId\":\"String\"}},"
        +   "{\"Name\":\"GetLibrary\","
        +    "\"Type\":{\"Books\":[{\"DomId\":\"String\",\"Title\":\"String\",\"Author\":\"String\","
        +                          "\"Price\":\"String\",\"Condition\":\"String\","
        +                          "\"SellerName\":\"String\",\"PurchasedAt\":\"String\"}]}}"
        + "]}";
  }

  private static String bsBookSrvJson()
  {
    return "{"
        + "\"SrvAppId\":\"bookstore\","
        + "\"SrvId\":\"book\","
        + "\"Attrs\":["
        +   "{\"Name\":\"Title\",\"Type\":\"String\"},{\"Name\":\"Author\",\"Type\":\"String\"},"
        +   "{\"Name\":\"ISBN\",\"Type\":\"String?\"},{\"Name\":\"Price\",\"Type\":\"String\"},"
        +   "{\"Name\":\"Condition\",\"Type\":\"String\"},{\"Name\":\"Description\",\"Type\":\"String?\"},"
        +   "{\"Name\":\"SellerActId\",\"Type\":\"String\"},{\"Name\":\"SellerName\",\"Type\":\"String\"},"
        +   "{\"Name\":\"PurchasedAt\",\"Type\":\"String\"},{\"Name\":\"ListingDomId\",\"Type\":\"String\"}"
        + "],"
        + "\"Msgs\":["
        +   "{\"Name\":\"GetObj\",\"Type\":{\"ObjName\":\"String\",\"ObjDesc\":\"String\"}}"
        + "]}";
  }

  private static String bsCartSrvJson()
  {
    return "{"
        + "\"SrvAppId\":\"bookstore\","
        + "\"SrvId\":\"cart\","
        + "\"Attrs\":[],"
        + "\"Msgs\":["
        +   "{\"Name\":\"GetCart\","
        +    "\"Type\":{\"Items\":[{\"ListingDomId\":\"String\",\"Title\":\"String\",\"Author\":\"String\","
        +                          "\"Price\":\"String\",\"Condition\":\"String\",\"SellerActId\":\"String\","
        +                          "\"SellerName\":\"String\",\"AddedAt\":\"String\",\"Available\":\"String\"}]}},"
        +   "{\"Name\":\"AddToCart\","
        +    "\"Parms\":[{\"Name\":\"ListingDomId\",\"Type\":\"String\"},{\"Name\":\"Title\",\"Type\":\"String\"},"
        +               "{\"Name\":\"Author\",\"Type\":\"String?\"},{\"Name\":\"SellerActId\",\"Type\":\"String\"}],"
        +    "\"Type\":{\"ListingDomId\":\"String\",\"AlreadyInCart\":\"String?\"}},"
        +   "{\"Name\":\"RemoveFromCart\","
        +    "\"Parms\":[{\"Name\":\"ListingDomId\",\"Type\":\"String\"}],"
        +    "\"Type\":{\"Removed\":\"String\"}},"
        +   "{\"Name\":\"BuyCartItems\","
        +    "\"Parms\":[{\"Name\":\"ListingDomIds\",\"Type\":\"String\"}],"
        +    "\"Type\":{\"Bought\":\"String\",\"Errors\":\"String?\"}}"
        + "]}";
  }

  private static String bsCartItemSrvJson()
  {
    return "{"
        + "\"SrvAppId\":\"bookstore\","
        + "\"SrvId\":\"cartitem\","
        + "\"Attrs\":["
        +   "{\"Name\":\"ListingDomId\",\"Type\":\"String\"},{\"Name\":\"Title\",\"Type\":\"String\"},"
        +   "{\"Name\":\"Author\",\"Type\":\"String\"},{\"Name\":\"ISBN\",\"Type\":\"String?\"},"
        +   "{\"Name\":\"Price\",\"Type\":\"String\"},{\"Name\":\"Condition\",\"Type\":\"String\"},"
        +   "{\"Name\":\"Description\",\"Type\":\"String?\"},{\"Name\":\"SellerActId\",\"Type\":\"String\"},"
        +   "{\"Name\":\"SellerName\",\"Type\":\"String\"},{\"Name\":\"AddedAt\",\"Type\":\"String\"}"
        + "],"
        + "\"Msgs\":["
        +   "{\"Name\":\"GetObj\",\"Type\":{\"ObjName\":\"String\",\"ObjDesc\":\"String\"}}"
        + "]}";
  }

  private static String bsCatalogSrvJson()
  {
    return "{"
        + "\"SrvAppId\":\"bookstore\","
        + "\"SrvId\":\"catalog\","
        + "\"Attrs\":[],"
        + "\"Msgs\":["
        +   "{\"Name\":\"Register\","
        +    "\"Parms\":[{\"Name\":\"ListingDomId\",\"Type\":\"String\"},{\"Name\":\"Title\",\"Type\":\"String\"},"
        +               "{\"Name\":\"Author\",\"Type\":\"String\"},{\"Name\":\"SellerActId\",\"Type\":\"String\"}],"
        +    "\"Type\":{\"Registered\":\"String\"}},"
        +   "{\"Name\":\"Deregister\","
        +    "\"Parms\":[{\"Name\":\"ListingDomId\",\"Type\":\"String\"}],"
        +    "\"Type\":{\"Deregistered\":\"String\"}},"
        +   "{\"Name\":\"GetCatalog\","
        +    "\"Type\":{\"Listings\":[{\"DomId\":\"String\",\"Title\":\"String\",\"Author\":\"String\","
        +                             "\"SellerActId\":\"String\",\"Price\":\"String\","
        +                             "\"Condition\":\"String\",\"Available\":\"String\"}]}},"
        +   "{\"Name\":\"UserRegister\","
        +    "\"Parms\":[{\"Name\":\"ActId\",\"Type\":\"String\"},{\"Name\":\"UsrId\",\"Type\":\"String\"},"
        +               "{\"Name\":\"UsrName\",\"Type\":\"String\"}],"
        +    "\"Type\":{\"Registered\":\"String\"}}"
        + "]}";
  }

  // ---------------------------------------------------------------------------
  // Slim class document builders — Implements + MsgPolicy; no inline Attrs/Msgs
  // ---------------------------------------------------------------------------

  /** bookstore.app slim class — SideEffect is now per-Msg in the service descriptor. */
  private static String bsAppClsJson()
  {
    return "{"
        + "\"ClsAppId\":\"bookstore\","
        + "\"ClsId\":\"app\","
        + "\"Description\":\"Bookstore app entry point. Use these LLM-native operations to browse"
        +   " the user's listings, library, shopping cart, and the global catalog.\","
        + "\"Implements\":[\"bookstore.app\"],"
        + "\"Auth\":\"isVerified\""
        + "}";
  }

  private static String bsForsaleClsJson()
  {
    return "{"
        + "\"ClsAppId\":\"bookstore\","
        + "\"ClsId\":\"forsale\","
        + "\"Implements\":[\"bookstore.forsale\"],"
        + "\"MsgPolicy\":["
        +   "{\"Srv\":\"bookstore.forsale\",\"Name\":\"GetForSale\",\"SideEffect\":\"Read\"},"
        +   "{\"Srv\":\"bookstore.forsale\",\"Name\":\"DelistBook\",\"SideEffect\":\"Destructive\"}"
        + "]}";
  }

  private static String bsListingClsJson()
  {
    return "{"
        + "\"ClsAppId\":\"bookstore\","
        + "\"ClsId\":\"listing\","
        + "\"Implements\":[\"bookstore.listing\"],"
        + "\"MsgPolicy\":["
        +   "{\"Srv\":\"bookstore.listing\",\"Name\":\"GetObj\",\"SideEffect\":\"Read\"}"
        + "]}";
  }

  private static String bsLibraryClsJson()
  {
    return "{"
        + "\"ClsAppId\":\"bookstore\","
        + "\"ClsId\":\"library\","
        + "\"Implements\":[\"bookstore.library\"],"
        + "\"MsgPolicy\":["
        +   "{\"Srv\":\"bookstore.library\",\"Name\":\"GetLibrary\",\"SideEffect\":\"Read\"}"
        + "]}";
  }

  private static String bsBookClsJson()
  {
    return "{"
        + "\"ClsAppId\":\"bookstore\","
        + "\"ClsId\":\"book\","
        + "\"Implements\":[\"bookstore.book\"],"
        + "\"MsgPolicy\":["
        +   "{\"Srv\":\"bookstore.book\",\"Name\":\"GetObj\",\"SideEffect\":\"Read\"}"
        + "]}";
  }

  private static String bsCartClsJson()
  {
    return "{"
        + "\"ClsAppId\":\"bookstore\","
        + "\"ClsId\":\"cart\","
        + "\"Implements\":[\"bookstore.cart\"],"
        + "\"MsgPolicy\":["
        +   "{\"Srv\":\"bookstore.cart\",\"Name\":\"GetCart\",\"SideEffect\":\"Read\"},"
        +   "{\"Srv\":\"bookstore.cart\",\"Name\":\"RemoveFromCart\",\"SideEffect\":\"Destructive\"}"
        + "]}";
  }

  private static String bsCartItemClsJson()
  {
    return "{"
        + "\"ClsAppId\":\"bookstore\","
        + "\"ClsId\":\"cartitem\","
        + "\"Implements\":[\"bookstore.cartitem\"],"
        + "\"MsgPolicy\":["
        +   "{\"Srv\":\"bookstore.cartitem\",\"Name\":\"GetObj\",\"SideEffect\":\"Read\"}"
        + "]}";
  }

  private static String bsCatalogClsJson()
  {
    return "{"
        + "\"ClsAppId\":\"bookstore\","
        + "\"ClsId\":\"catalog\","
        + "\"Implements\":[\"bookstore.catalog\"],"
        + "\"MsgPolicy\":["
        +   "{\"Srv\":\"bookstore.catalog\",\"Name\":\"GetCatalog\",\"SideEffect\":\"Read\"},"
        +   "{\"Srv\":\"bookstore.catalog\",\"Name\":\"Deregister\",\"SideEffect\":\"Destructive\"}"
        + "]}";
  }

  private static void addLnkIfMissing(final DomId domId,
                                      final DomId lnkDomId,
                                      final String lnkClsAppId,
                                      final String lnkClsId,
                                      final String lnkObjName,
                                      final String lnkObjDesc,
                                      final String tagAppId,
                                      final String tag,
                                      final String val,
                                      final long seqNum) throws DomatarException
  {
    if (LnkDb.getLnk(domId, lnkDomId, tagAppId, tag) == null)
      LnkDb.addLnk(new Lnk(domId, lnkDomId,
                            lnkClsAppId, lnkClsId,
                            lnkObjName, lnkObjDesc,
                            tagAppId, tag,
                            val, seqNum));
  }
}
