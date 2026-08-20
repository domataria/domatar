/*
 * Copyright (c) 2024 Domatar
 */

package com.domatar.objimpl;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.domatar.act.ProviderDefaults;
import com.domatar.core.Auth;
import com.domatar.core.Context;
import com.domatar.core.DomatarConfig;
import com.domatar.db.LnkDb;
import com.domatar.db.ObjDb;
import com.domatar.install.CatalogInstall;
import com.domatar.install.DefaultShellBindings;
import com.domatar.install.UserSubstrateIds;
import com.domatar.util.JsonArrayList;
import com.domatar.util.JsonHashMap;
import com.domatar.util.JsonList;
import com.domatar.util.JsonMap;
import com.domatar.util.JsonMsg;
import com.domatar.util.Lnk;
import com.domatar.util.Obj;
import com.domatar.util.ObjAttrs;
import com.domatar.util.ObjImpl;
import com.domatar.util.DomId;
import com.domatar.util.DomatarException;
import com.domatar.util.DomatarMsgClient;

/**
 * Handler for (domatar, catalog) — the App Catalog container on the
 * Domatar App sub-host (Spec-Installation.txt PART 7.3).
 *
 * Supported messages:
 *   Open                — standard Navigator open (from ObjImpl)
 *   GetCatalog          — structured catalog list with IsDefault flags
 *   RegisterApp         — idempotently adds a catalog entry for one app
 *   GetDefaultAppsConfig / SetDefaultApps / ResetDefaultApps
 *   GetDefaultShells / SetDefaultShells / ResetDefaultShells
 *                       — provider site customization
 *                         (Spec-Provider-Customization.txt PART 4;
 *                          Spec-Mandatory-App-Rewrite.txt PART 10.2)
 */
public class AppCatalogImpl extends ObjImpl
{
  /** User-visible mandatory apps, UI display order (domatar is silent). */
  private static final String[] UI_MANDATORY =
      new String[] { "navigator", "login", "desktop" };

  /** Stock shell-capable apps until Phase 8 ShellRoles AppConfig. */
  private static final String[] STOCK_SHELL_APPS =
      new String[] { "login", "desktop", "navigator", "appstore" };

  @Override
  public String handleMsg(final String msg,
                          final Obj obj,
                          final String contextPath,
                          final String contextRealPath,
                          final DomatarMsgClient msgClient) throws DomatarException
  {
    final JsonMsg inMsg  = new JsonMsg(msg);
    final String  opr    = inMsg.getOperation();
    final JsonMsg outMsg = new JsonMsg();

    if (!hasRights(inMsg, obj, msgClient))
      return notAuthorized(inMsg);

    if ("GetCatalog".equals(opr))
      getCatalog(opr, inMsg, outMsg, obj);
    else if ("RegisterApp".equals(opr))
      registerApp(opr, inMsg, outMsg, obj);
    else if ("HasCatalogEntry".equals(opr))
      hasCatalogEntry(opr, inMsg, outMsg);
    else if ("GetDefaultAppsConfig".equals(opr))
      getDefaultAppsConfig(opr, inMsg, outMsg, obj);
    else if ("SetDefaultApps".equals(opr))
      setDefaultApps(opr, inMsg, outMsg, obj);
    else if ("ResetDefaultApps".equals(opr))
      resetDefaultApps(opr, inMsg, outMsg, obj);
    else if ("GetDefaultShells".equals(opr))
      getDefaultShells(opr, inMsg, outMsg, obj);
    else if ("SetDefaultShells".equals(opr))
      setDefaultShells(opr, inMsg, outMsg, obj);
    else if ("ResetDefaultShells".equals(opr))
      resetDefaultShells(opr, inMsg, outMsg, obj);
    else
      return super.handleMsg(msg, obj, contextPath, contextRealPath, msgClient);

    return outMsg.toString();
  }

  /**
   * Site-config ops require a verified session. GetCatalog / RegisterApp /
   * HasCatalogEntry keep the prior public base policy (super.hasRights → true).
   * HasCatalogEntry must stay public so the central registry can probe a
   * remote provider catalog without a local Binding for that provider actId.
   */
  @Override
  public boolean hasRights(final JsonMsg inMsg, final Obj obj,
                           final DomatarMsgClient msgClient)
      throws DomatarException
  {
    final String op = inMsg.getOperation();

    if ("GetDefaultAppsConfig".equals(op)
        || "SetDefaultApps".equals(op)
        || "ResetDefaultApps".equals(op)
        || "GetDefaultShells".equals(op)
        || "SetDefaultShells".equals(op)
        || "ResetDefaultShells".equals(op))
      return Auth.isVerified(inMsg);

    return super.hasRights(inMsg, obj, msgClient);
  }

  // -------------------------------------------------------------------------

  /**
   * Marketplace offer probe (Spec-AppStore KD3): is AppId in THIS node's
   * provider catalog?
   */
  private void hasCatalogEntry(final String opr, final JsonMsg inMsg,
                               final JsonMsg outMsg)
      throws DomatarException
  {
    final String appId = inMsg.getAttr("AppId");

    if (appId == null || appId.isEmpty())
    {
      outMsg.addError(opr, "Missing AppId");
      return;
    }

    final boolean present =
        CatalogInstall.hasCatalogEntry(DomatarConfig.getPrvId(), appId);
    final ObjAttrs out = new ObjAttrs();

    out.addAttr("Present", present ? "True" : "False");
    outMsg.addResponseBody(opr, out);
  }

  /**
   * Returns every installed app in the catalog with an IsDefault flag that
   * indicates whether the app is in this provider's DefaultApps list.
   *
   * Response body:
   *   { Apps: [ { AppId, AppName, AppDesc, Version, IsDefault }, ... ] }
   */
  private void getCatalog(final String opr, final JsonMsg inMsg, final JsonMsg outMsg,
                          final Obj obj)
      throws DomatarException
  {
    final DomId catalogId = catalogDomId(inMsg, obj);

    final List<String> defaultApps =
        ProviderDefaults.resolveDefaultApps(catalogId.actId);
    final List<Lnk>   lnks        = LnkDb.getLnks(catalogId, "domatar", "catalogEntry",
                                                   null, null, 1000, false);
    final JsonList     apps        = new JsonArrayList();

    for (final Lnk lnk : lnks)
    {
      final Obj entry = ObjDb.getObj(lnk.lnkDomId);

      if (entry == null)
        continue;

      final ObjAttrs entryAttrs = entry.attrs != null ? entry.attrs : new ObjAttrs();
      final String   appId      = entryAttrs.getAttr("AppId");
      final String   appName    = entryAttrs.getAttr("AppName");
      final String   appDesc    = entryAttrs.getAttr("AppDesc");
      final String   version    = entryAttrs.getAttr("Version");
      final boolean  isDefault  = appId != null && defaultApps.contains(appId);

      final JsonMap row = new JsonHashMap();
      row.put("AppId",     appId    != null ? appId    : "");
      row.put("AppName",   appName  != null ? appName  : "");
      row.put("AppDesc",   appDesc  != null ? appDesc  : "");
      row.put("Version",   version  != null ? version  : "");
      row.put("IsDefault", isDefault ? "True" : "False");
      apps.add(row);
    }

    final ObjAttrs outAttrs = new ObjAttrs();
    outAttrs.addAttr("Apps", apps);
    outMsg.addResponseBody(opr, outAttrs);
  }

  /**
   * Idempotently registers one application in the catalog.
   *
   * Request body:
   *   { AppId, AppName, AppDesc, Version }
   *
   * The caller (an app's installProvider routine, or the Setup servlet) must
   * be verified.  The provider account is the owner of the catalog.
   */
  private void registerApp(final String opr, final JsonMsg inMsg, final JsonMsg outMsg,
                           final Obj obj)
      throws DomatarException
  {
    final String appId = inMsg.getAttr("AppId");
    String appName     = inMsg.getAttr("AppName");
    String appDesc     = inMsg.getAttr("AppDesc");
    String version     = inMsg.getAttr("Version");

    if (appId == null || appId.isEmpty())
    {
      outMsg.addError(opr, "Missing AppId");
      return;
    }

    // Use the provider id from config (fingerprint actIds have no '@' to parse).
    final String prvId = DomatarConfig.getPrvId();

    if (appName == null)
      appName = appId;
    if (appDesc == null)
      appDesc = "";
    if (version == null)
      version = "1.0";

    CatalogInstall.registerInCatalog(prvId, appId, appName, appDesc, version);

    outMsg.addResponseBody(opr, new ObjAttrs());
  }

  // -------------------------------------------------------------------------
  // Site customization (Spec-Provider-Customization.txt PART 4)
  // -------------------------------------------------------------------------

  private void getDefaultAppsConfig(final String opr, final JsonMsg inMsg,
                                    final JsonMsg outMsg, final Obj obj)
      throws DomatarException
  {
    writeConfigBody(opr, inMsg, outMsg, obj);
  }

  private void setDefaultApps(final String opr, final JsonMsg inMsg,
                              final JsonMsg outMsg, final Obj obj)
      throws DomatarException
  {
    if (!isProvider(inMsg))
    {
      outMsg.addError(opr, "Not authorized");
      return;
    }

    if (ProviderDefaults.isLocked())
    {
      outMsg.addError(opr,
          "Default apps are pinned by DOMATAR_DEFAULT_APPS and cannot be "
          + "edited here.");
      return;
    }

    final DomId        catalogId = catalogDomId(inMsg, obj);
    final String       prvActId  = catalogId.actId;
    final Set<String>  catalogIds = catalogAppIds(catalogId);
    final List<String> parsed     = parseAppIdsCsv(inMsg.getAttr("AppIds"));

    for (final String appId : parsed)
    {
      if (ProviderDefaults.MANDATORY_APPS.contains(appId))
        continue;
      if (!catalogIds.contains(appId))
      {
        outMsg.addError(opr,
            "App \"" + appId + "\" is not in the provider catalog.");
        return;
      }
    }

    ProviderDefaults.writeProviderConfigApps(prvActId, parsed);
    writeConfigBody(opr, inMsg, outMsg, obj);
  }

  private void resetDefaultApps(final String opr, final JsonMsg inMsg,
                                final JsonMsg outMsg, final Obj obj)
      throws DomatarException
  {
    if (!isProvider(inMsg))
    {
      outMsg.addError(opr, "Not authorized");
      return;
    }

    if (ProviderDefaults.isLocked())
    {
      outMsg.addError(opr,
          "Default apps are pinned by DOMATAR_DEFAULT_APPS and cannot be "
          + "edited here.");
      return;
    }

    final DomId catalogId = catalogDomId(inMsg, obj);
    ProviderDefaults.clearDefaultApps(catalogId.actId);
    writeConfigBody(opr, inMsg, outMsg, obj);
  }

  // -------------------------------------------------------------------------
  // Default shell role bindings (PART 10.2)
  // -------------------------------------------------------------------------

  private void getDefaultShells(final String opr, final JsonMsg inMsg,
                                final JsonMsg outMsg, final Obj obj)
      throws DomatarException
  {
    writeShellsBody(opr, inMsg, outMsg, obj);
  }

  private void setDefaultShells(final String opr, final JsonMsg inMsg,
                                final JsonMsg outMsg, final Obj obj)
      throws DomatarException
  {
    if (!isProvider(inMsg))
    {
      outMsg.addError(opr, "Not authorized");
      return;
    }

    final DomId catalogId = catalogDomId(inMsg, obj);
    final Map<String, String> chosen = new LinkedHashMap<>();

    // Prefer compact Shells attr; else per-role AppId attrs.
    final String compact = inMsg.getAttr("Shells");
    if (compact != null && !compact.trim().isEmpty())
      chosen.putAll(DefaultShellBindings.parse(compact));
    else
    {
      for (final String roleId : UserSubstrateIds.ROLE_IDS)
      {
        final String appId = inMsg.getAttr(
            Character.toUpperCase(roleId.charAt(0))
            + roleId.substring(1) + "AppId");
        if (appId != null && !appId.trim().isEmpty())
          chosen.put(roleId, appId.trim());
      }
    }

    final Set<String> allowed = stockShellAppIdSet();
    for (final Map.Entry<String, String> e :
         DefaultShellBindings.normalize(chosen).entrySet())
    {
      if (!isKnownShellRole(e.getKey()))
      {
        outMsg.addError(opr, "Unknown shell role: " + e.getKey());
        return;
      }
      if (!allowed.contains(e.getValue()))
      {
        outMsg.addError(opr,
            "App \"" + e.getValue()
            + "\" is not a shell-capable option for this provider yet.");
        return;
      }
    }

    ProviderDefaults.setDefaultShellAppIds(catalogId.actId, chosen);
    writeShellsBody(opr, inMsg, outMsg, obj);
  }

  private void resetDefaultShells(final String opr, final JsonMsg inMsg,
                                  final JsonMsg outMsg, final Obj obj)
      throws DomatarException
  {
    if (!isProvider(inMsg))
    {
      outMsg.addError(opr, "Not authorized");
      return;
    }

    final DomId catalogId = catalogDomId(inMsg, obj);
    ProviderDefaults.clearDefaultShells(catalogId.actId);
    writeShellsBody(opr, inMsg, outMsg, obj);
  }

  private void writeShellsBody(final String opr, final JsonMsg inMsg,
                               final JsonMsg outMsg, final Obj obj)
      throws DomatarException
  {
    final ObjAttrs outAttrs = new ObjAttrs();

    if (!isProvider(inMsg))
    {
      outAttrs.addAttr("IsProvider", "False");
      outMsg.addResponseBody(opr, outAttrs);
      return;
    }

    final DomId catalogId = catalogDomId(inMsg, obj);
    final Map<String, String> effective =
        ProviderDefaults.getDefaultShellAppIds(catalogId.actId);

    final JsonList options = new JsonArrayList();
    for (final String appId : STOCK_SHELL_APPS)
    {
      final JsonMap opt = new JsonHashMap();
      opt.put("AppId", appId);
      opt.put("AppName", displayName(appId, null));
      options.add(opt);
    }

    final JsonList roles = new JsonArrayList();
    for (final String roleId : UserSubstrateIds.ROLE_IDS)
    {
      final JsonMap row = new JsonHashMap();
      row.put("RoleId", roleId);
      row.put("AppId", effective.get(roleId));
      row.put("Options", options);
      roles.add(row);
    }

    outAttrs.addAttr("IsProvider", "True");
    outAttrs.addAttr("Shells", roles);
    outMsg.addResponseBody(opr, outAttrs);
  }

  private static boolean isKnownShellRole(final String roleId)
  {
    for (final String r : UserSubstrateIds.ROLE_IDS)
    {
      if (r.equals(roleId))
        return true;
    }
    return false;
  }

  private static Set<String> stockShellAppIdSet()
  {
    final Set<String> s = new HashSet<>();
    for (final String a : STOCK_SHELL_APPS)
      s.add(a);
    return s;
  }

  /**
   * Shared GetDefaultAppsConfig response. Non-providers get IsProvider=False
   * only (no Apps leak).
   */
  private void writeConfigBody(final String opr, final JsonMsg inMsg,
                               final JsonMsg outMsg, final Obj obj)
      throws DomatarException
  {
    final ObjAttrs outAttrs = new ObjAttrs();

    if (!isProvider(inMsg))
    {
      outAttrs.addAttr("IsProvider", "False");
      outMsg.addResponseBody(opr, outAttrs);
      return;
    }

    final DomId        catalogId = catalogDomId(inMsg, obj);
    final List<String> effective =
        ProviderDefaults.resolveDefaultApps(catalogId.actId);
    final Map<String, String> names = new LinkedHashMap<>();

    for (final Lnk lnk : LnkDb.getLnks(catalogId, "domatar", "catalogEntry",
                                        null, null, 1000, false))
    {
      final Obj entry = ObjDb.getObj(lnk.lnkDomId);
      if (entry == null)
        continue;
      final ObjAttrs a = entry.attrs != null ? entry.attrs : new ObjAttrs();
      final String appId = a.getAttr("AppId");
      if (appId == null || appId.isEmpty() || "domatar".equals(appId))
        continue;
      final String appName = a.getAttr("AppName");
      names.put(appId, appName != null && !appName.isEmpty() ? appName : appId);
    }

    final JsonList apps = new JsonArrayList();
    final Set<String> emitted = new HashSet<>();

    for (final String appId : UI_MANDATORY)
    {
      apps.add(configRow(appId, displayName(appId, names.get(appId)),
                         true, effective.contains(appId)));
      emitted.add(appId);
    }

    for (final String appId : effective)
    {
      if (emitted.contains(appId) || "domatar".equals(appId))
        continue;
      if (!names.containsKey(appId))
        continue;
      apps.add(configRow(appId, displayName(appId, names.get(appId)),
                         false, true));
      emitted.add(appId);
    }

    for (final Map.Entry<String, String> e : names.entrySet())
    {
      if (emitted.contains(e.getKey()))
        continue;
      apps.add(configRow(e.getKey(), displayName(e.getKey(), e.getValue()),
                         false, false));
    }

    outAttrs.addAttr("IsProvider", "True");
    outAttrs.addAttr("Locked",
        ProviderDefaults.isLocked() ? "True" : "False");
    outAttrs.addAttr("Apps", apps);
    outMsg.addResponseBody(opr, outAttrs);
  }

  private static JsonMap configRow(final String appId, final String appName,
                                   final boolean mandatory,
                                   final boolean isDefault)
  {
    final JsonMap row = new JsonHashMap();
    row.put("AppId",       appId);
    row.put("AppName",     appName);
    row.put("IsDefault",   isDefault ? "True" : "False");
    row.put("IsMandatory", mandatory ? "True" : "False");
    return row;
  }

  private static String displayName(final String appId, final String catalogName)
  {
    if ("navigator".equals(appId))
      return "Navigator";
    if ("login".equals(appId))
      return "Account";
    if ("desktop".equals(appId))
      return "Desktop";
    if ("appstore".equals(appId))
      return "App Store";
    return catalogName != null ? catalogName : appId;
  }

  private static boolean isProvider(final JsonMsg inMsg) throws DomatarException
  {
    final Context ctx = inMsg.getContext();
    final String  prv = DomatarConfig.getPrvActId();
    return ctx != null && ctx.actId != null && prv != null
           && ctx.actId.equals(prv);
  }

  private static Set<String> catalogAppIds(final DomId catalogId)
      throws DomatarException
  {
    final Set<String> ids = new HashSet<>();
    for (final Lnk lnk : LnkDb.getLnks(catalogId, "domatar", "catalogEntry",
                                        null, null, 1000, false))
    {
      final Obj entry = ObjDb.getObj(lnk.lnkDomId);
      if (entry == null || entry.attrs == null)
        continue;
      final String appId = entry.attrs.getAttr("AppId");
      if (appId != null && !appId.isEmpty())
        ids.add(appId);
    }
    return ids;
  }

  private static List<String> parseAppIdsCsv(final String csv)
  {
    final List<String> out = new ArrayList<>();
    if (csv == null || csv.trim().isEmpty())
      return out;
    for (final String part : csv.split(","))
    {
      final String t = part.trim();
      if (!t.isEmpty())
        out.add(t);
    }
    return out;
  }

  /**
   * When the request carries a ClsId envelope, sendLocal may pass obj=null;
   * use the message destination (same pattern as ConversationsImpl.sendMessage).
   */
  private static DomId catalogDomId(final JsonMsg inMsg, final Obj obj)
      throws DomatarException
  {
    if (obj != null)
      return obj.domId;
    return inMsg.getDstId();
  }
}
