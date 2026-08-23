/*
 * Copyright (c) 2024 Domatar
 */

package com.appstore.webui;

import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;

import com.domatar.core.Context;
import com.domatar.core.DomatarConfig;
import com.domatar.db.ActDb;
import com.domatar.servlet.DomatarServlet;
import com.domatar.util.Act;
import com.domatar.util.JsonMsg;
import com.domatar.util.ObjAttrs;
import com.domatar.util.DomId;
import com.domatar.util.DomatarException;
import com.domatar.util.DomatarMsgClient;

/**
 * Browser-facing endpoint backing the App Store page.
 * Spec-Installation.txt PART 7 / Spec-AppStore.txt PART 7.
 *
 * Actions:
 *   SiteRole                              — local { IsProvider, PrvId, Domain }
 *   GetApps / GetInstalled / UninstallApp — local home
 *   InstallApp                            — ActManager (PrvId optional)
 *   SearchApps / GetListing               — central registry (read)
 *   RegisterOffer / WithdrawOffer         — provider-gated, then registry
 */
@WebServlet("/AppstoreWui/*")
public class AppstoreWui extends DomatarServlet
{
  private static final long serialVersionUID = 1L;

  /** Phase 0 live ActId on db2; used only if ActDb lookup fails. */
  private static final String REGISTRY_ACT_FALLBACK =
      "Tocu5~rhqDF8ZylMvKJ9Q_Ulnnpq_IGY";

  private static volatile String cachedRegistryActId;

  @Override
  protected boolean isAnonymousAccess(final HttpServletRequest req)
  {
    return "SiteRole".equals(getParam(req, "Action"));
  }

  @Override
  protected JsonMsg getMsg(final HttpServletRequest req,
                           final DomId srcDomId,
                           final Context context,
                           final Act srcAct,
                           final DomatarMsgClient msgClient) throws DomatarException
  {
    final JsonMsg msg   = new JsonMsg();
    final String  opr   = getParam(req, "Action");
    final String  actId = srcDomId.actId;

    if (opr == null)
    {
      msg.addError(opr, "Missing action");
      return msg;
    }

    if ("SiteRole".equals(opr))
    {
      final ObjAttrs attrs = new ObjAttrs();
      attrs.addAttr("IsProvider", isProvider(context) ? "True" : "False");
      attrs.addAttr("PrvId", DomatarConfig.getPrvId());
      attrs.addAttr("Domain", DomatarConfig.getDomain());
      final String pub = DomatarConfig.getPublicDomain();
      if (pub != null)
        attrs.addAttr("PublicDomain", pub);
      final String origin = DomatarConfig.getAssetOrigin();
      if (origin != null)
        attrs.addAttr("AssetOrigin", origin);
      final String browserOrigin = DomatarConfig.getBrowserOrigin();
      if (browserOrigin != null)
        attrs.addAttr("BrowserOrigin", browserOrigin);
      attrs.addAttr("AssetContextPath", DomatarConfig.getAssetContextPath());
      msg.addResponseBody(opr, attrs);
      return msg;
    }

    if ("GetApps".equals(opr) || "GetInstalled".equals(opr)
        || "UninstallApp".equals(opr))
    {
      final String prvId = DomatarConfig.getPrvId();
      final DomId dst = new DomId(
          DomId.subHstId("appstore", actId, prvId), "appstore", actId, "home");
      msg.addRequestHead(srcDomId, dst, context);
      msg.addClsId("appstore", "home");

      if ("UninstallApp".equals(opr))
      {
        final String   appId = getParam(req, "AppId");
        final ObjAttrs attrs = new ObjAttrs();
        attrs.addAttr("AppId", appId);
        msg.addRequestBody(opr, attrs);
      }
      else
        msg.addRequestBody(opr, null);
    }
    else if ("InstallApp".equals(opr))
    {
      final String appId     = getParam(req, "AppId");
      final String homePrvId = DomatarConfig.getPrvId();
      String       prvId     = getParam(req, "PrvId");

      if (prvId == null || prvId.isEmpty())
        prvId = homePrvId;

      String centralAppId = DomId.getAppId(context.usrId);
      if (centralAppId == null)
        centralAppId = homePrvId;

      final DomId dst = new DomId(centralAppId, "act", "act@act", "actManager");
      msg.addRequestHead(srcDomId, dst, context);
      msg.addClsId("act", "actManager");

      final ObjAttrs attrs = new ObjAttrs();
      attrs.addAttr("AppId", appId);
      attrs.addAttr("PrvId", prvId);

      // Home: default local Domain. Remote: forward Domain/PrvActId from the
      // marketplace picker (nested GetListing may fail Auth stamping).
      if (homePrvId.equals(prvId))
      {
        attrs.addAttr("Domain", DomatarConfig.getDomain());
        final String pub = DomatarConfig.getPublicDomain();
        if (pub != null)
          attrs.addAttr("PublicDomain", pub);
        final String bo = DomatarConfig.getBrowserOrigin();
        if (bo != null)
          attrs.addAttr("BrowserOrigin", bo);
      }
      else
      {
        putIfPresent(attrs, "Domain", getParam(req, "Domain"));
        putIfPresent(attrs, "PublicDomain", getParam(req, "PublicDomain"));
        putIfPresent(attrs, "BrowserOrigin", getParam(req, "BrowserOrigin"));
        putIfPresent(attrs, "AppUrl", getParam(req, "AppUrl"));
        putIfPresent(attrs, "PrvActId", getParam(req, "PrvActId"));
      }

      msg.addRequestBody("InstallApp", attrs);
    }
    else if ("SearchApps".equals(opr) || "GetListing".equals(opr))
    {
      final DomId dst = registryDst();
      msg.addRequestHead(srcDomId, dst, context);
      msg.addClsId("appstore", "registry");

      final ObjAttrs attrs = new ObjAttrs();

      if ("SearchApps".equals(opr))
      {
        putIfPresent(attrs, "Query", getParam(req, "Query"));
        putIfPresent(attrs, "Limit", getParam(req, "Limit"));
      }
      else
        attrs.addAttr("AppId", getParam(req, "AppId"));

      msg.addRequestBody(opr, attrs);
    }
    else if ("RegisterOffer".equals(opr) || "WithdrawOffer".equals(opr))
    {
      if (!isProvider(context))
      {
        msg.addError(opr, "Not authorized");
        return msg;
      }

      final DomId dst = registryDst();
      msg.addRequestHead(srcDomId, dst, context);
      msg.addClsId("appstore", "registry");

      final ObjAttrs attrs = new ObjAttrs();
      attrs.addAttr("AppId", getParam(req, "AppId"));
      attrs.addAttr("PrvId", DomatarConfig.getPrvId());
      attrs.addAttr("Domain", DomatarConfig.getDomain());
      final String pub = DomatarConfig.getPublicDomain();
      if (pub != null)
        attrs.addAttr("PublicDomain", pub);
      final String browserOrigin = DomatarConfig.getBrowserOrigin();
      if (browserOrigin != null)
        attrs.addAttr("BrowserOrigin", browserOrigin);
      attrs.addAttr("PrvActId", DomatarConfig.getPrvActId());

      if ("RegisterOffer".equals(opr))
      {
        putIfPresent(attrs, "AppName", getParam(req, "AppName"));
        putIfPresent(attrs, "AppDesc", getParam(req, "AppDesc"));
        putIfPresent(attrs, "Version", getParam(req, "Version"));
      }

      msg.addRequestBody(opr, attrs);
    }
    else
    {
      msg.addError(opr, "Unknown action: " + opr);
    }

    return msg;
  }

  private static DomId registryDst() throws DomatarException
  {
    return new DomId("appstore", "appstore", registryActId(), "registry");
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
      System.out.println("WARN: AppstoreWui registryActId lookup failed: " + e);
    }

    return REGISTRY_ACT_FALLBACK;
  }

  private static boolean isProvider(final Context ctx)
  {
    if (ctx == null || ctx.actId == null)
      return false;

    final String prvActId = DomatarConfig.getPrvActId();

    return prvActId != null && prvActId.equals(ctx.actId);
  }

  private static void putIfPresent(final ObjAttrs attrs, final String key, final String val)
      throws DomatarException
  {
    if (val != null && !val.isEmpty())
      attrs.addAttr(key, val);
  }
}
