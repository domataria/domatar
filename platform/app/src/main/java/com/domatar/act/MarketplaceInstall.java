/*
 * Copyright (c) 2024 Domatar
 */

package com.domatar.act;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;

import com.domatar.app.App;
import com.domatar.app.AppRegistry;
import com.domatar.core.AppConfig;
import com.domatar.core.Context;
import com.domatar.core.DomatarConfig;
import com.domatar.db.HstDb;
import com.domatar.db.ObjDb;
import com.domatar.install.CatalogInstall;
import com.domatar.install.DirectoryLookup;
import com.domatar.install.DirectoryRegister;
import com.domatar.install.AssetPaths;
import com.domatar.install.AppUrls;
import com.domatar.install.HomeHostInstall;
import com.domatar.install.IconPaths;
import com.domatar.install.LaunchPaths;
import com.domatar.install.UserAppRegistry;
import com.domatar.install.UserInstallDispatch;
import com.domatar.install.UserSubstrateIds;
import com.domatar.util.Hst;
import com.domatar.util.Obj;
import com.domatar.util.Json;
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
  private static volatile String cachedRegistryActId;

  private static final ConcurrentHashMap<String, String> browserOriginByWire =
      new ConcurrentHashMap<>();

  private MarketplaceInstall() {}

  /** Active offer row from registry GetListing. */
  public static final class ActiveOffer
  {
    public final String prvId;
    public final String domain;
    public final String publicDomain;
    public final String browserOrigin;
    public final String prvActId;
    public final String appVersion;

    public ActiveOffer(final String prvId, final String domain,
                       final String publicDomain, final String browserOrigin,
                       final String prvActId,
                       final String appVersion)
    {
      this.prvId          = prvId;
      this.domain         = domain;
      this.publicDomain   = publicDomain;
      this.browserOrigin  = browserOrigin;
      this.prvActId       = prvActId;
      this.appVersion     = appVersion;
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
    String browserOrigin = inMsg.getAttr("BrowserOrigin");
    String appUrl        = inMsg.getAttr("AppUrl");
    String prvActId      = inMsg.getAttr("PrvActId");

    if (prvId == null || prvId.isEmpty())
      prvId = homePrv;

    String hstId = UserInstallDispatch.userSubHstId(appId, actId, usrId, prvId);
    Hst existingHst = HstDb.getHst(hstId);

    if (existingHst == null)
      existingHst = DirectoryLookup.fetchAndCache(hstId);

    boolean restoreExisting = existingHst != null;

    // Portable host already on another provider: restore the launcher if
    // Uninstall hid it; refuse only when the tile is still live (PART 9.4).
    if (existingHst != null && existingHst.prvId != null
        && !prvId.equals(existingHst.prvId))
    {
      if (launcherIsLive(actId, homePrv, appId))
      {
        outMsg.addError(opr,
            "Already installed on provider " + existingHst.prvId);
        return;
      }

      prvId = existingHst.prvId;
      if (existingHst.domain != null && !existingHst.domain.isEmpty())
        domain = existingHst.domain;
      publicDomain = null;
      browserOrigin = null;
      prvActId = null;
      hstId = UserInstallDispatch.userSubHstId(appId, actId, usrId, prvId);
      restoreExisting = true;
    }

    final Obj priorRow = loadUserAppRow(actId, homePrv, appId);

    if (AppUrls.isBlank(browserOrigin))
      browserOrigin = attr(priorRow, "HostBrowserOrigin");
    if (AppUrls.isBlank(publicDomain))
      publicDomain = attr(priorRow, "HostPublicDomain");
    if (AppUrls.isBlank(domain))
      domain = attr(priorRow, "HostDomain");

    boolean remote = !prvId.equals(homePrv);

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
      if (browserOrigin == null || browserOrigin.isEmpty())
        browserOrigin = offer.browserOrigin;
      if (prvActId == null || prvActId.isEmpty())
        prvActId = offer.prvActId;
    }
    else if (remote && listingOk && !restoreExisting)
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

    if ((browserOrigin == null || browserOrigin.isEmpty()) && !remote)
      browserOrigin = DomatarConfig.getBrowserOrigin();

    if (remote && AppUrls.isBlank(browserOrigin))
    {
      browserOrigin = originFromUrl(attr(priorRow, "LaunchPath"));
      if (AppUrls.isBlank(browserOrigin))
        browserOrigin = originFromUrl(attr(priorRow, "IconPath"));
      if (AppUrls.isBlank(browserOrigin))
        browserOrigin = fetchOfferingBrowserOrigin(domain);
    }

    final String offeringPrvActId = prvActId;

    try
    {
      // Restore does not re-probe the offering catalog: the portable host
      // already exists, and home often lacks PrvActId for a remote probe.
      if (!restoreExisting
          && !probeCatalog(prvId, offeringPrvActId, domain, appId, msgClient))
      {
        outMsg.addError(opr,
            "App \"" + appId + "\" is not in the provider catalog. Deploy the WAR and run /"
            + appId + "/Setup once (Spec-Installation.txt PART 4).");
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
          browserOrigin, appUrl, hstId, remote, msgClient);

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
          row.getString("BrowserOrigin"),
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
                                       final String browserOrigin,
                                       final String appUrlIn,
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

    String appUrl = appUrlIn;

    if ((appUrl == null || appUrl.isEmpty()) && cfg != null)
      appUrl = cfg.getAppUrl();

    final String scheme = DomatarConfig.getWireScheme();
    final String launchPath = LaunchPaths.forInstall(
        remote, scheme, appUrl, browserOrigin, publicDomain, domain, appId);
    final String iconPath = IconPaths.forInstall(
        remote, scheme, appUrl, browserOrigin, publicDomain, domain, appId);

    String storedAppUrl = (remote || !AppUrls.isBlank(appUrl))
        ? AppUrls.resolve(appUrl, scheme, browserOrigin, publicDomain, domain)
        : "";

    if (!AppUrls.isBrowserReachable(storedAppUrl))
      storedAppUrl = "";

    try
    {
      UserAppRegistry.upsertUserApp(actId, homePrv, appId, displayName,
          iconPath, launchPath, storedAppUrl, browserOrigin, publicDomain,
          domain, prvId, hstId, null, msgClient, inMsg);
    }
    catch (final DomatarException e)
    {
      System.out.println("WARN: ensureDesktopTile UserAppRegistry upsert failed for "
          + appId + ": " + e);
    }
  }

  /**
   * True when the home userApps row exists and is not tombstoned.
   * Missing or unreadable rows are treated as uninstalled so Install can
   * restore a tile onto an existing portable host.
   */
  static boolean launcherIsLive(final String actId, final String homePrv,
                                final String appId)
  {
    final Obj row = loadUserAppRow(actId, homePrv, appId);

    if (row == null || row.attrs == null)
      return false;

    try
    {
      return !"True".equals(row.attrs.getAttr("Tombstone"));
    }
    catch (final DomatarException e)
    {
      return false;
    }
  }

  private static Obj loadUserAppRow(final String actId, final String homePrv,
                                    final String appId)
  {
    if (actId == null || homePrv == null || appId == null)
      return null;

    try
    {
      return ObjDb.getObj(UserSubstrateIds.userAppRow(actId, homePrv, appId));
    }
    catch (final DomatarException e)
    {
      return null;
    }
  }

  private static String attr(final Obj row, final String key)
  {
    if (row == null || row.attrs == null || key == null)
      return null;

    try
    {
      final String v = row.attrs.getAttr(key);

      return (v == null || v.isEmpty()) ? null : v;
    }
    catch (final DomatarException e)
    {
      return null;
    }
  }

  private static String originFromUrl(final String url)
  {
    if (!AppUrls.isBrowserReachable(url))
      return null;

    try
    {
      final URL u = new URL(url);
      final int port = u.getPort();
      final String host = u.getHost();

      if (host == null || host.isEmpty())
        return null;

      return u.getProtocol() + "://" + host + (port > 0 ? ":" + port : "");
    }
    catch (final Exception e)
    {
      return null;
    }
  }

  /**
   * Server-side SiteRole against the offering wire host (Docker DNS). The
   * user's browser cannot resolve that host; this node can, and SiteRole
   * carries BrowserOrigin (e.g. http://localhost:9080).
   */
  public static String fetchOfferingBrowserOrigin(final String wireDomain)
  {
    if (AppUrls.isBlank(wireDomain))
      return null;

    final String cached = browserOriginByWire.get(wireDomain);

    if (cached != null)
      return cached.isEmpty() ? null : cached;

    final String scheme = DomatarConfig.getWireScheme();
    final String url = (scheme == null || scheme.isEmpty() ? "http" : scheme)
        + "://" + wireDomain + AssetPaths.WIRE_CONTEXT
        + "/appstore/Wui/AppstoreWui?Action=SiteRole";

    HttpURLConnection conn = null;

    try
    {
      conn = (HttpURLConnection) new URL(url).openConnection();
      conn.setConnectTimeout(3000);
      conn.setReadTimeout(3000);
      conn.setRequestMethod("GET");
      conn.setInstanceFollowRedirects(true);

      if (conn.getResponseCode() != 200)
        return null;

      byte[] buf;

      try (InputStream in = conn.getInputStream())
      {
        buf = in.readAllBytes();
      }

      final Object parsed = Json.parse(
          new String(buf, StandardCharsets.UTF_8));

      if (!(parsed instanceof JsonMap))
        return null;

      final JsonMap body = (JsonMap) parsed;
      JsonMap attrs = body.getMap("Attrs");

      if (attrs == null)
        attrs = body;

      final String origin = attrs.getString("BrowserOrigin");

      if (!AppUrls.isBrowserReachable(origin))
        return null;

      browserOriginByWire.put(wireDomain, origin);
      return origin;
    }
    catch (final Exception e)
    {
      System.out.println("WARN: fetchOfferingBrowserOrigin " + url + ": " + e);
      return null;
    }
    finally
    {
      if (conn != null)
        conn.disconnect();
    }
  }

  private static String registryActId() throws DomatarException
  {
    if (cachedRegistryActId != null)
      return cachedRegistryActId;

    final String actId = HomeHostInstall.actId("appstore");

    if (actId == null)
      throw new DomatarException("home user appstore@appstore not found");

    cachedRegistryActId = actId;
    return cachedRegistryActId;
  }
}
