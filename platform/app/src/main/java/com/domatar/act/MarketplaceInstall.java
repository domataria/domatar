/*
 * Copyright (c) 2024 Domatar
 */

package com.domatar.act;

import com.domatar.app.App;
import com.domatar.app.AppRegistry;
import com.domatar.core.AppConfig;
import com.domatar.core.Context;
import com.domatar.core.DomatarConfig;
import com.domatar.db.ActDb;
import com.domatar.db.HstDb;
import com.domatar.install.CatalogInstall;
import com.domatar.install.DirectoryLookup;
import com.domatar.install.DirectoryRegister;
import com.domatar.install.IconPaths;
import com.domatar.install.LaunchPaths;
import com.domatar.install.UserAppRegistry;
import com.domatar.install.UserInstallDispatch;
import com.domatar.util.Act;
import com.domatar.util.Hst;
import com.domatar.util.JsonList;
import com.domatar.util.JsonMap;
import com.domatar.util.JsonMsg;
import com.domatar.util.ObjAttrs;
import com.domatar.util.DomId;
import com.domatar.util.DomatarException;
import com.domatar.util.DomatarMsgClient;

/**
 * Marketplace InstallApp(AppId, PrvId) flow (Spec-AppStore PART 7.4 / 9–11;
 * Spec-Foreign-Provider-Installation.txt PART 5). KD5 retired: foreign
 * install HostProvisions an object-only host when the account has no
 * peer on N yet.
 */
public final class MarketplaceInstall
{
  /** Live sim ActId for appstore@appstore on db2 (Phase 0). */
  private static final String REGISTRY_ACT_FALLBACK =
      "Tocu5~rhqDF8ZylMvKJ9Q_Ulnnpq_IGY";

  private static volatile String cachedRegistryActId;

  private MarketplaceInstall() {}

  /** Active offer row from registry GetListing. */
  public static final class ActiveOffer
  {
    public final String prvId;
    public final String domain;
    public final String publicDomain;
    public final String prvActId;
    public final String appVersion;

    public ActiveOffer(final String prvId, final String domain,
                       final String publicDomain, final String prvActId,
                       final String appVersion)
    {
      this.prvId         = prvId;
      this.domain        = domain;
      this.publicDomain  = publicDomain;
      this.prvActId      = prvActId;
      this.appVersion    = appVersion;
    }
  }

  /**
   * Full InstallApp path. Writes Success/Failure onto {@code outMsg}.
   */
  public static void install(final String opr, final JsonMsg inMsg,
                             final JsonMsg outMsg,
                             final DomatarMsgClient msgClient)
      throws DomatarException
  {
    final String appId = inMsg.getAttr("AppId");

    if (appId == null || appId.isEmpty())
    {
      outMsg.addError(opr, "Missing AppId");
      return;
    }

    final Context ctx = inMsg.getContext();

    if (ctx == null || ctx.actId == null)
    {
      outMsg.addError(opr, "Missing session context");
      return;
    }

    final String actId   = ctx.actId;
    final String usrId   = ctx.usrId;
    final String usrName = ctx.usrName;
    final String homePrv = DomatarConfig.getPrvId();

    String prvId         = inMsg.getAttr("PrvId");
    String domain        = inMsg.getAttr("Domain");
    String publicDomain  = inMsg.getAttr("PublicDomain");
    String prvActId      = inMsg.getAttr("PrvActId");

    if (prvId == null || prvId.isEmpty())
      prvId = homePrv;

    final boolean remote = !prvId.equals(homePrv);

    ActiveOffer offer = null;
    boolean listingOk = false;

    try
    {
      offer = loadActiveOffer(appId, prvId, msgClient);
      listingOk = true;
    }
    catch (final DomatarException e)
    {
      // Nested GetListing is cross-provider (registry on prv2) and may fail
      // Auth.isVerified stamping; Domain/PrvActId from the Wui/offer picker
      // are enough to continue for remote installs.
      if (remote && (domain == null || domain.isEmpty()))
      {
        outMsg.addError(opr, e.getMessage() != null ? e.getMessage()
            : "Could not resolve marketplace offer");
        return;
      }
    }

    if (offer != null)
    {
      if (domain == null || domain.isEmpty())
        domain = offer.domain;
      if (publicDomain == null || publicDomain.isEmpty())
        publicDomain = offer.publicDomain;
      if (prvActId == null || prvActId.isEmpty())
        prvActId = offer.prvActId;
    }
    else if (remote && listingOk)
    {
      outMsg.addError(opr,
          "No active offer for this app on that provider");
      return;
    }
    else if (remote && (domain == null || domain.isEmpty()))
    {
      outMsg.addError(opr,
          "No active offer for this app on that provider");
      return;
    }

    if (domain == null || domain.isEmpty())
      domain = DomatarConfig.getDomain();

    if ((publicDomain == null || publicDomain.isEmpty()) && !remote)
      publicDomain = DomatarConfig.getPublicDomain();

    final String offeringPrvActId = prvActId;

    try
    {
      if (!probeCatalog(prvId, offeringPrvActId, domain, appId, msgClient))
      {
        outMsg.addError(opr,
            "App \"" + appId + "\" is not in the provider catalog. Deploy the WAR and run /"
            + appId + "/Setup once (Spec-Installation.txt PART 4).");
        return;
      }

      final String hstId = UserInstallDispatch.userSubHstId(appId, actId, usrId, prvId);
      Hst existing = HstDb.getHst(hstId);

      if (existing == null)
        existing = DirectoryLookup.fetchAndCache(hstId);

      if (existing != null && existing.prvId != null
          && !prvId.equals(existing.prvId))
      {
        outMsg.addError(opr,
            "Already installed on provider " + existing.prvId);
        return;
      }

      if (remote && !HostPresence.hasPeer(actId, prvId))
      {
        try
        {
          HostProvisionDriver.ensure(actId, prvId, domain, msgClient, inMsg);
        }
        catch (final DomatarException e)
        {
          final String detail = e.getMessage() != null ? e.getMessage() : e.toString();

          outMsg.addError(opr, detail.startsWith("HostProvision failed")
              ? detail : "HostProvision failed: " + detail);
          return;
        }
      }

      // Host may already exist after Uninstall (data preserved). InstallUser
      // is idempotent and restores Navigator root→app links.
      UserInstallDispatch.sendInstallUser(
          appId, actId, usrId, usrName, prvId, domain, msgClient);

      ensureDesktopTile(inMsg, actId, appId, prvId, domain, publicDomain,
          hstId, remote, msgClient);

      final ObjAttrs out = new ObjAttrs();
      out.addAttr("Status", "Installed");
      out.addAttr("AppId", appId);
      out.addAttr("PrvId", prvId);
      out.addAttr("HstId", hstId);
      outMsg.addResponseBody(opr, out);
    }
    catch (final DomatarException e)
    {
      final String detail = e.getMessage() != null ? e.getMessage() : e.toString();
      outMsg.addError(opr, "Could not install app: " + detail);
    }
  }

  /**
   * Find an Active offer for {@code appId} on {@code prvId} via registry
   * GetListing. Returns null when none (or registry empty).
   */
  public static ActiveOffer loadActiveOffer(final String appId, final String prvId,
                                            final DomatarMsgClient msgClient)
      throws DomatarException
  {
    if (msgClient == null || appId == null || prvId == null)
      return null;

    final DomId reg = new DomId("appstore", "appstore", registryActId(), "registry");
    final JsonMsg req = new JsonMsg();
    final ObjAttrs attrs = new ObjAttrs();

    attrs.addAttr("AppId", appId);
    req.addRequestBody("GetListing", attrs);
    req.addClsId("appstore", "registry");

    final JsonMsg resp = msgClient.send(reg, req);

    if (resp == null || resp.isFailure())
    {
      final String detail = resp != null ? resp.getErrorMsg() : "null response";
      throw new DomatarException(
          detail != null && !detail.isEmpty() ? detail
              : "GetListing failed for " + appId);
    }

    final ObjAttrs body = resp.getAttrs();
    final JsonList offers = body != null ? body.getAttrList("Offers") : null;

    if (offers == null)
      return null;

    for (int i = 0; i < offers.size(); i++)
    {
      final Object item = offers.get(i);

      if (!(item instanceof JsonMap))
        continue;

      final JsonMap row = (JsonMap) item;
      final String rowPrv = row.getString("PrvId");
      final String status = row.getString("Status");

      if (!prvId.equals(rowPrv))
        continue;

      if (status != null && !"Active".equals(status))
        continue;

      return new ActiveOffer(
          rowPrv,
          row.getString("Domain"),
          row.getString("PublicDomain"),
          row.getString("PrvActId"),
          row.getString("AppVersion"));
    }

    return null;
  }

  public static boolean probeCatalog(final String prvId, final String prvActId,
                                     final String domain, final String appId,
                                     final DomatarMsgClient msgClient)
      throws DomatarException
  {
    if (prvId != null && prvId.equals(DomatarConfig.getPrvId()))
      return CatalogInstall.hasCatalogEntry(prvId, appId);

    if (prvActId == null || prvActId.isEmpty() || msgClient == null)
      return false;

    // Offering catalog hosts are often only in the offering node's local
    // hst table — publish so the home node can route HasCatalogEntry.
    if (domain != null && !domain.isEmpty())
      DirectoryRegister.publishHst(
          DomId.subHstId("domatar", prvActId), domain, prvId, msgClient);

    final DomId catalogId = new DomId(
        DomId.subHstId("domatar", prvActId), "domatar", prvActId, "app-catalog");
    final JsonMsg req = new JsonMsg();
    final ObjAttrs attrs = new ObjAttrs();

    attrs.addAttr("AppId", appId);
    req.addRequestBody("HasCatalogEntry", attrs);
    req.addClsId("domatar", "catalog");

    try
    {
      final JsonMsg resp = msgClient.send(catalogId, req);

      if (resp == null || resp.isFailure())
        return false;

      return "True".equals(resp.getAttr("Present"));
    }
    catch (final DomatarException e)
    {
      return false;
    }
  }

  public static void ensureDesktopTile(final JsonMsg inMsg, final String actId,
                                       final String appId, final String prvId,
                                       final String domain,
                                       final String publicDomain,
                                       final String hstId,
                                       final boolean remote,
                                       final DomatarMsgClient msgClient)
      throws DomatarException
  {
    if (msgClient == null)
      return;

    final String homePrv = DomatarConfig.getPrvId();

    String displayName = appId;
    final App appReg = AppRegistry.get(appId);
    final ClassLoader cl = (appReg != null) ? appReg.classLoader
        : Thread.currentThread().getContextClassLoader();
    final AppConfig cfg = AppConfig.load(appId, cl);

    if (cfg != null && cfg.getAppName() != null && !cfg.getAppName().isEmpty())
      displayName = cfg.getAppName();

    final String scheme = DomatarConfig.getWireScheme();
    // Spec-Icons: browser PublicDomain for tiles; wire Domain for Msg routing.
    final String launchPath = LaunchPaths.forInstall(
        remote, scheme, publicDomain, domain, appId);
    final String iconPath = IconPaths.forInstall(
        remote, scheme, publicDomain, domain, appId);

    // Phase 5: userApps is authoritative — do not write Desktop tiles.
    try
    {
      UserAppRegistry.upsertUserApp(actId, homePrv, appId, displayName,
          iconPath, launchPath, prvId, hstId, null, msgClient, inMsg);
    }
    catch (final DomatarException e)
    {
      System.out.println("WARN: ensureDesktopTile UserAppRegistry upsert failed for "
          + appId + ": " + e);
    }
  }

  private static String registryActId()
  {
    if (cachedRegistryActId != null)
      return cachedRegistryActId;

    try
    {
      final Act act = ActDb.getActByUsrId("appstore@appstore");

      if (act != null && act.actId != null && !act.actId.isEmpty())
      {
        cachedRegistryActId = act.actId;
        return cachedRegistryActId;
      }
    }
    catch (final DomatarException e)
    {
      System.out.println("WARN: MarketplaceInstall registryActId lookup failed: "
          + e);
    }

    return REGISTRY_ACT_FALLBACK;
  }
}
