/*
 * Copyright (c) 2024 Domatar
 */

package com.domatar.install;

import java.util.List;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.domatar.app.App;
import com.domatar.app.AppRegistry;
import com.domatar.core.DomatarConfig;
import com.domatar.db.ActDb;
import com.domatar.db.HstDb;
import com.domatar.db.LnkDb;
import com.domatar.db.ObjDb;
import com.domatar.util.Act;
import com.domatar.util.IdGen;
import com.domatar.util.Lnk;
import com.domatar.util.Obj;
import com.domatar.util.ObjAttrs;
import com.domatar.util.DomId;
import com.domatar.util.DomatarException;
import com.domatar.util.DomatarMsgClient;

/**
 * Idempotent install of {@code domatar-&lt;actId&gt;-&lt;prvId&gt;} with
 * app-domatar, userApps, shells, membership, binding, and default shell
 * bindings (Update-Mandatory-App-Rewrite.txt Phases 1 / 4).
 */
public final class UserSubstrateInstall
{
  private static final Logger LOG =
      Logger.getLogger(UserSubstrateInstall.class.getName());

  private UserSubstrateInstall() {}

  public static void ensureUserSubstrate(final String actId,
                                         final String usrId,
                                         final String usrName,
                                         final String prvId,
                                         final String domain,
                                         final DomatarMsgClient msgClient)
      throws DomatarException
  {
    if (actId == null || prvId == null || domain == null)
      throw new DomatarException(
          "UserSubstrateInstall.ensureUserSubstrate requires actId, prvId, domain");

    final String hstId = UserSubstrateIds.hstId(actId, prvId);

    if (HstDb.getHst(hstId) == null)
      HstDb.addHst(hstId, domain, prvId);

    DirectoryRegister.registerHst(hstId, domain, prvId, msgClient);

    final DomId appDomatar = UserSubstrateIds.appDomatar(actId, prvId);
    final DomId userApps   = UserSubstrateIds.userApps(actId, prvId);
    final DomId shells     = UserSubstrateIds.shells(actId, prvId);
    final DomId membership = UserSubstrateIds.membership(actId, prvId);
    final DomId binding    = UserSubstrateIds.binding(actId, prvId);

    ObjDb.addObjIfMissing(appDomatar, "domatar", "app",
        "Domatar", "Account substrate");
    ObjDb.reclassObj(appDomatar, "domatar", "app");

    ObjDb.addObjIfMissing(userApps, "domatar", "userApps",
        "User apps", "Installed-app registry");

    if (LnkDb.getLnk(appDomatar, userApps, "navigator", "container") == null)
      LnkDb.addLnk(new Lnk(appDomatar, userApps,
          "domatar", "userApps",
          "User apps", "Installed-app registry",
          "navigator", "container",
          null, 1));

    ObjDb.addObjIfMissing(shells, "domatar", "shells",
        "Shells", "Shell-role bindings");

    if (LnkDb.getLnk(appDomatar, shells, "navigator", "container") == null)
      LnkDb.addLnk(new Lnk(appDomatar, shells,
          "domatar", "shells",
          "Shells", "Shell-role bindings",
          "navigator", "container",
          null, 2));

    ObjDb.addObjIfMissing(membership, "domatar", "membership",
        "Membership", "Login-home peers");

    if (LnkDb.getLnk(appDomatar, membership, "navigator", "container") == null)
      LnkDb.addLnk(new Lnk(appDomatar, membership,
          "domatar", "membership",
          "Membership", "Login-home peers",
          "navigator", "container",
          null, 3));

    ObjDb.addObjIfMissing(binding, "domatar", "binding",
        "Binding", "actId->ownId binding");

    if (LnkDb.getLnk(appDomatar, binding, "navigator", "container") == null)
      LnkDb.addLnk(new Lnk(appDomatar, binding,
          "domatar", "binding",
          "Binding", "actId->ownId binding",
          "navigator", "container",
          null, 4));

    SrvInstall.ensureSrvsContainer(appDomatar,
        "Service descriptors for user substrate", "domatar", 5);
    ClsInstall.ensureClssContainer(appDomatar,
        "Class descriptors for user substrate", "domatar", 6);

    installDescriptors(appDomatar, userApps, shells, membership, binding);
    ensureDefaultShells(actId, prvId);
    ensureShellAppHosts(actId, usrId, usrName, prvId, domain, msgClient);
    MembershipMigrator.seedBindingFromAct(actId, prvId);
  }

  /**
   * Minimal substrate on an object-only host: hst + app-domatar +
   * membership + binding + empty userApps. No shells, no shell rows,
   * no default-shell InstallUser (KD5).
   */
  public static void ensureObjectOnlySubstrate(final String actId,
                                              final String prvId,
                                              final String domain,
                                              final DomatarMsgClient msgClient)
      throws DomatarException
  {
    if (actId == null || prvId == null || domain == null)
      throw new DomatarException(
          "UserSubstrateInstall.ensureObjectOnlySubstrate requires actId, prvId, domain");

    final String hstId = UserSubstrateIds.hstId(actId, prvId);

    if (HstDb.getHst(hstId) == null)
      HstDb.addHst(hstId, domain, prvId);

    DirectoryRegister.registerHst(hstId, domain, prvId, msgClient);

    final DomId appDomatar = UserSubstrateIds.appDomatar(actId, prvId);
    final DomId userApps   = UserSubstrateIds.userApps(actId, prvId);
    final DomId membership = UserSubstrateIds.membership(actId, prvId);
    final DomId binding    = UserSubstrateIds.binding(actId, prvId);

    ObjDb.addObjIfMissing(appDomatar, "domatar", "app",
        "Domatar", "Account substrate");
    ObjDb.reclassObj(appDomatar, "domatar", "app");

    ObjDb.addObjIfMissing(userApps, "domatar", "userApps",
        "User apps", "Installed-app registry");

    if (LnkDb.getLnk(appDomatar, userApps, "navigator", "container") == null)
      LnkDb.addLnk(new Lnk(appDomatar, userApps,
          "domatar", "userApps",
          "User apps", "Installed-app registry",
          "navigator", "container",
          null, 1));

    ObjDb.addObjIfMissing(membership, "domatar", "membership",
        "Membership", "Login-home peers");

    if (LnkDb.getLnk(appDomatar, membership, "navigator", "container") == null)
      LnkDb.addLnk(new Lnk(appDomatar, membership,
          "domatar", "membership",
          "Membership", "Login-home peers",
          "navigator", "container",
          null, 3));

    ObjDb.addObjIfMissing(binding, "domatar", "binding",
        "Binding", "actId->ownId binding");

    if (LnkDb.getLnk(appDomatar, binding, "navigator", "container") == null)
      LnkDb.addLnk(new Lnk(appDomatar, binding,
          "domatar", "binding",
          "Binding", "actId->ownId binding",
          "navigator", "container",
          null, 4));

    SrvInstall.ensureSrvsContainer(appDomatar,
        "Service descriptors for user substrate", "domatar", 5);
    ClsInstall.ensureClssContainer(appDomatar,
        "Class descriptors for user substrate", "domatar", 6);

    installNonShellDescriptors(appDomatar, userApps, membership, binding);
  }

  /**
   * Backfill substrate for every act on this node (localhost Setup).
   * Idempotent: skips when {@code userApps} already exists.
   */
  public static String ensureAll(final DomatarMsgClient msgClient)
      throws DomatarException
  {
    final String prvId  = DomatarConfig.getPrvId();
    final String domain = DomatarConfig.getDomain();

    if (prvId == null || domain == null)
      throw new DomatarException("ensureAll requires PrvId and Domain");

    int migrated = 0;
    int skipped  = 0;
    int failed   = 0;
    int backfilled = 0;
    final StringBuilder failures = new StringBuilder();
    final Set<String> actIds = distinctActIds();

    for (final String actId : actIds)
    {
      try
      {
        final DomId userApps = UserSubstrateIds.userApps(actId, prvId);
        final boolean existed = ObjDb.getObj(userApps) != null;

        final Act act = ActDb.getAct(actId);
        final String usrId = act != null ? act.usrId : actId;
        final String usrName = act != null ? act.usrName : actId;

        ensureUserSubstrate(actId, usrId, usrName, prvId, domain, msgClient);

        if (existed)
          skipped++;
        else
          migrated++;

        backfilled += backfillUserAppsFromDesktop(actId, prvId);
      }
      catch (final Exception e)
      {
        failed++;
        failures.append("  actId=").append(actId).append(": ").append(e).append('\n');
        LOG.log(Level.WARNING, "UserSubstrateInstall.ensureAll failed for " + actId, e);
      }
    }

    final StringBuilder summary = new StringBuilder();
    summary.append("UserSubstrateInstall.ensureAll complete (prvId=").append(prvId)
        .append(").\n");
    summary.append("  migrated=").append(migrated)
        .append("  skipped=").append(skipped)
        .append("  backfilledRows=").append(backfilled)
        .append("  failed=").append(failed).append('\n');

    if (failures.length() > 0)
    {
      summary.append("Failures:\n");
      summary.append(failures);
    }

    LOG.info(summary.toString());
    return summary.toString();
  }

  /**
   * Copy live Desktop tile rows onto {@code userApps} when missing
   * (Phase 1 ops backfill for accounts that pre-date dual-write).
   * Returns number of rows written.
   */
  public static int backfillUserAppsFromDesktop(final String actId,
                                                final String prvId)
      throws DomatarException
  {
    final String desktopHst = DomId.localSubHstId("desktop", actId, prvId);
    final DomId userApps = UserSubstrateIds.userApps(actId, prvId);
    final List<Obj> tiles = ObjDb.getObjPrefix(desktopHst, "desktop", actId, "app",
        null, 1000);
    int written = 0;

    for (final Obj tile : tiles)
    {
      if (!"desktop".equals(tile.clsAppId) || !"app".equals(tile.clsId))
        continue;

      if ("True".equals(tile.attrs != null ? tile.attrs.getAttr("Tombstone") : null))
        continue;

      final String appId = IdGen.getIdSuffix(tile.domId.objId);

      if (appId == null || appId.isEmpty())
        continue;

      if ("login".equals(appId) || "navigator".equals(appId) || "desktop".equals(appId))
        continue;

      final DomId row = UserSubstrateIds.userAppRow(actId, prvId, appId);

      if (ObjDb.getObj(row) != null)
        continue;

      final ObjAttrs src = tile.attrs != null ? tile.attrs : new ObjAttrs();
      final ObjAttrs attrs = new ObjAttrs();
      final String now = IdGen.getCurTimeBase64();
      final String display = src.getAttr("DisplayName") != null
          ? src.getAttr("DisplayName") : appId;

      attrs.addAttr("DisplayName", display);
      attrs.addAttr("IconPath",
          src.getAttr("IconPath") != null ? src.getAttr("IconPath") : "");
      attrs.addAttr("LaunchPath",
          src.getAttr("LaunchPath") != null ? src.getAttr("LaunchPath") : "");
      attrs.addAttr("Position",
          src.getAttr("Position") != null ? src.getAttr("Position") : "100");
      attrs.addAttr("Version", now);
      attrs.addAttr("Tombstone", "False");

      final String hostPrvId = src.getAttr("HostPrvId");
      final String appHstId = src.getAttr("AppHstId");

      if (hostPrvId != null && !hostPrvId.isEmpty())
        attrs.addAttr("HostPrvId", hostPrvId);

      if (appHstId != null && !appHstId.isEmpty())
        attrs.addAttr("AppHstId", appHstId);

      ObjDb.addObj(new Obj(row, "domatar", "userApp", display,
          "Installed application", attrs));

      if (LnkDb.getLnk(userApps, row, "domatar", "userApp") == null)
        LnkDb.addLnk(new Lnk(userApps, row,
            "domatar", "userApp",
            display, "Installed application",
            "domatar", "userApp",
            appId, 0));

      written++;
    }

    return written;
  }

  public static void ensureDefaultShells(final String actId, final String prvId)
      throws DomatarException
  {
    // ProviderDefaults lives in domatar-app; core reads the same attr via
    // DefaultShellBindings (Update-Mandatory-App-Rewrite.txt Phase 6).
    final Map<String, String> bindings =
        DefaultShellBindings.resolve(DomatarConfig.getPrvActId());
    final DomId shells = UserSubstrateIds.shells(actId, prvId);

    for (final String roleId : UserSubstrateIds.ROLE_IDS)
    {
      final String appId = bindings.containsKey(roleId)
          ? bindings.get(roleId) : roleId;
      final DomId row = UserSubstrateIds.shellRow(actId, prvId, prvId, roleId);
      final String now = IdGen.getCurTimeBase64();
      final String display = shellDisplayName(roleId);
      final String appHstId = DomId.subHstId(appId, actId, prvId);
      final String launchPath = LaunchPaths.forShell(roleId, appId);
      final String iconPath = IconPaths.launcher(appId);

      final Obj existing = ObjDb.getObj(row);

      if (existing == null)
      {
        final ObjAttrs attrs = new ObjAttrs();

        attrs.addAttr("LoginPrvId", prvId);
        attrs.addAttr("RoleId", roleId);
        attrs.addAttr("AppId", appId);
        attrs.addAttr("AppHstId", appHstId);
        attrs.addAttr("LaunchPath", launchPath);
        attrs.addAttr("IconPath", iconPath);
        attrs.addAttr("Version", now);

        ObjDb.addObj(new Obj(row, "domatar", "shell", display,
            "Default " + roleId + " shell", attrs));
      }
      else if (existing.attrs != null
          && "login".equals(roleId) && "login".equals(appId))
      {
        // Repair stock Account tile: older seeds used login.html.
        final String cur = existing.attrs.getAttr("LaunchPath");

        if (cur == null || cur.isEmpty() || cur.endsWith("/login.html")
            || cur.endsWith("/login/login") || "/login/login.html".equals(cur)
            || LaunchPaths.relative("login").equals(cur))
        {
          final ObjAttrs attrs = existing.attrs;

          attrs.addAttr("LaunchPath", launchPath);
          attrs.addAttr("Version", now);
          ObjDb.modifyObj(existing.modify(null, null, null, null, null, attrs));
        }
      }

      if (LnkDb.getLnk(shells, row, "domatar", "shell") == null)
        LnkDb.addLnk(new Lnk(shells, row,
            "domatar", "shell",
            display, "Default " + roleId + " shell",
            "domatar", "shell",
            roleId, 0));
    }
  }

  /**
   * InstallUser each bound shell AppId when its local replica host is
   * missing or empty. In-process (Setup has no user session). Object-only
   * substrate does not call this.
   */
  private static void ensureShellAppHosts(final String actId,
                                          final String usrId,
                                          final String usrName,
                                          final String prvId,
                                          final String domain,
                                          final DomatarMsgClient msgClient)
  {
    try
    {
      final Map<String, String> bindings =
          DefaultShellBindings.resolve(DomatarConfig.getPrvActId());
      final LinkedHashSet<String> toInstall = new LinkedHashSet<String>();

      for (final String roleId : UserSubstrateIds.ROLE_IDS)
      {
        String appId = bindings.containsKey(roleId)
            ? bindings.get(roleId) : roleId;

        if (appId == null || appId.isEmpty())
          appId = roleId;

        toInstall.add(appId);
      }

      for (final String appId : toInstall)
      {
        try
        {
          final String hstId = UserHostIds.resolve(appId, actId, usrId, prvId);

          if (hstId == null)
            continue;

          final DomId appObj = new DomId(hstId, appId, actId, "app-" + appId);
          final DomId clss   = new DomId(hstId, appId, actId, "clss");

          if (ObjDb.getObj(appObj) != null && ObjDb.getObj(clss) != null)
            continue;

          if (HstDb.getHst(hstId) == null)
            HstDb.addHst(hstId, domain, prvId);

          if (msgClient != null)
            DirectoryRegister.registerHst(hstId, domain, prvId, msgClient);

          final App app = AppRegistry.get(appId);

          if (app == null || app.installInstance == null)
          {
            LOG.warning("ensureShellAppHosts no AppInstall for " + appId
                + " actId=" + actId);
            continue;
          }

          app.installInstance.installUser(
              actId, usrId, usrName, prvId, domain, msgClient);
        }
        catch (final Exception e)
        {
          LOG.log(Level.WARNING, "ensureShellAppHosts InstallUser " + appId
              + " failed for actId=" + actId, e);
        }
      }
    }
    catch (final Exception e)
    {
      LOG.log(Level.WARNING, "ensureShellAppHosts failed for actId=" + actId, e);
    }
  }

  private static void installDescriptors(final DomId appDomatar,
                                         final DomId userApps,
                                         final DomId shells,
                                         final DomId membership,
                                         final DomId binding) throws DomatarException
  {
    installNonShellDescriptors(appDomatar, userApps, membership, binding);

    SrvInstall.addSrvObj(shells, "domatar", "shells",
        "Shell-role bindings for a login home",
        "{\"SrvAppId\":\"domatar\",\"SrvId\":\"shells\",\"Attrs\":[],"
        + "\"Msgs\":["
        + "{\"Name\":\"GetShells\",\"Type\":{\"Shells\":[]},\"Parms\":[{\"Name\":\"LoginPrvId\",\"Type\":\"String?\"}]},"
        + "{\"Name\":\"SetShell\",\"Parms\":[{\"Name\":\"RoleId\",\"Type\":\"String\"},{\"Name\":\"AppId\",\"Type\":\"String\"}],\"Type\":{\"Set\":\"String\"}},"
        + "{\"Name\":\"ResetShellsToDefaults\",\"Parms\":[],\"Type\":{\"Reset\":\"String\"}}"
        + "]}");

    ClsInstall.upsertClsImplementing(shells, "domatar", "shells",
        "Shell-role bindings for a login home",
        "{\"ClsAppId\":\"domatar\",\"ClsId\":\"shells\","
        + "\"Implements\":[\"domatar.shells\"],"
        + "\"MsgPolicy\":["
        + "{\"Srv\":\"domatar.shells\",\"Name\":\"GetShells\",\"SideEffect\":\"Read\"},"
        + "{\"Srv\":\"domatar.shells\",\"Name\":\"SetShell\",\"SideEffect\":\"Write\"},"
        + "{\"Srv\":\"domatar.shells\",\"Name\":\"ResetShellsToDefaults\",\"SideEffect\":\"Write\"}"
        + "]}");

    ClsInstall.upsertClsImplementing(appDomatar, "domatar", "shell",
        "One shell-role binding row",
        "{\"ClsAppId\":\"domatar\",\"ClsId\":\"shell\","
        + "\"Implements\":[\"domatar.shell\"],"
        + "\"Auth\":\"isVerified\"}");
  }

  private static void installNonShellDescriptors(final DomId appDomatar,
                                                 final DomId userApps,
                                                 final DomId membership,
                                                 final DomId binding)
      throws DomatarException
  {
    SrvInstall.addSrvObj(userApps, "domatar", "userApps",
        "Per-user installed-app registry",
        "{\"SrvAppId\":\"domatar\",\"SrvId\":\"userApps\",\"Attrs\":[],"
        + "\"Msgs\":["
        + "{\"Name\":\"GetUserApps\",\"Type\":{\"Apps\":[],\"Version\":\"String\"},\"Parms\":[]},"
        + "{\"Name\":\"GetUserAppsVersion\",\"Type\":{\"Version\":\"String\"},\"Parms\":[]},"
        + "{\"Name\":\"PullUserApps\",\"Type\":{\"Apps\":[]},\"Parms\":[]},"
        + "{\"Name\":\"MergeUserApps\",\"Parms\":[{\"Name\":\"Apps\",\"Type\":\"List\"}],\"Type\":{\"Merged\":\"String\"}},"
        + "{\"Name\":\"ReconcileUserApps\",\"Parms\":[],\"Type\":{\"Reconciled\":\"String\"}},"
        + "{\"Name\":\"InstallUserApp\",\"Parms\":[{\"Name\":\"AppId\",\"Type\":\"String\"}],\"Type\":{\"Installed\":\"String\"}},"
        + "{\"Name\":\"UninstallUserApp\",\"Parms\":[{\"Name\":\"AppId\",\"Type\":\"String\"}],\"Type\":{\"Installed\":\"String\"}}"
        + "]}");

    ClsInstall.upsertClsImplementing(userApps, "domatar", "userApps",
        "Per-user installed-app registry",
        "{\"ClsAppId\":\"domatar\",\"ClsId\":\"userApps\","
        + "\"Implements\":[\"domatar.userApps\"],"
        + "\"MsgPolicy\":["
        + "{\"Srv\":\"domatar.userApps\",\"Name\":\"GetUserApps\",\"SideEffect\":\"Read\"},"
        + "{\"Srv\":\"domatar.userApps\",\"Name\":\"GetUserAppsVersion\",\"SideEffect\":\"Read\"},"
        + "{\"Srv\":\"domatar.userApps\",\"Name\":\"PullUserApps\",\"SideEffect\":\"Read\"},"
        + "{\"Srv\":\"domatar.userApps\",\"Name\":\"ReconcileUserApps\",\"SideEffect\":\"Read\"},"
        + "{\"Srv\":\"domatar.userApps\",\"Name\":\"UninstallUserApp\",\"SideEffect\":\"Destructive\"}"
        + "]}");

    ClsInstall.upsertClsImplementing(appDomatar, "domatar", "userApp",
        "One installed-app registry row",
        "{\"ClsAppId\":\"domatar\",\"ClsId\":\"userApp\","
        + "\"Implements\":[\"domatar.userApp\"],"
        + "\"Auth\":\"isVerified\"}");

    SrvInstall.addSrvObj(membership, "domatar", "membership",
        "Login-home membership peers",
        "{\"SrvAppId\":\"domatar\",\"SrvId\":\"membership\",\"Attrs\":[],"
        + "\"Msgs\":["
        + "{\"Name\":\"ListMembership\",\"Type\":{\"Peers\":[]},\"Parms\":[]},"
        + "{\"Name\":\"AddPeer\",\"Parms\":[{\"Name\":\"UsrId\",\"Type\":\"String\"}],\"Type\":{}},"
        + "{\"Name\":\"TombstonePeer\",\"Parms\":[{\"Name\":\"UsrId\",\"Type\":\"String\"}],\"Type\":{}},"
        + "{\"Name\":\"ReconcileMembership\",\"Parms\":[],\"Type\":{\"Reconciled\":\"String\"}}"
        + "]}");

    ClsInstall.upsertClsImplementing(membership, "domatar", "membership",
        "Login-home membership peers",
        "{\"ClsAppId\":\"domatar\",\"ClsId\":\"membership\","
        + "\"Implements\":[\"domatar.membership\"],"
        + "\"MsgPolicy\":["
        + "{\"Srv\":\"domatar.membership\",\"Name\":\"ListMembership\",\"SideEffect\":\"Read\"},"
        + "{\"Srv\":\"domatar.membership\",\"Name\":\"ReconcileMembership\",\"SideEffect\":\"Read\"},"
        + "{\"Srv\":\"domatar.membership\",\"Name\":\"TombstonePeer\",\"SideEffect\":\"Destructive\"}"
        + "]}");

    ClsInstall.upsertClsImplementing(appDomatar, "domatar", "peer",
        "One membership peer row",
        "{\"ClsAppId\":\"domatar\",\"ClsId\":\"peer\","
        + "\"Implements\":[\"domatar.peer\"],"
        + "\"Auth\":\"isVerified\"}");

    SrvInstall.addSrvObj(binding, "domatar", "binding",
        "actId->ownId binding",
        "{\"SrvAppId\":\"domatar\",\"SrvId\":\"binding\",\"Attrs\":[],"
        + "\"Msgs\":["
        + "{\"Name\":\"GetBinding\",\"Type\":{},\"Parms\":[]},"
        + "{\"Name\":\"SetBinding\",\"Parms\":[],\"Type\":{}},"
        + "{\"Name\":\"PutBinding\",\"Parms\":[],\"Type\":{}}"
        + "]}");

    ClsInstall.upsertClsImplementing(binding, "domatar", "binding",
        "actId->ownId binding",
        "{\"ClsAppId\":\"domatar\",\"ClsId\":\"binding\","
        + "\"Implements\":[\"domatar.binding\"],"
        + "\"MsgPolicy\":["
        + "{\"Srv\":\"domatar.binding\",\"Name\":\"GetBinding\",\"SideEffect\":\"Read\"},"
        + "{\"Srv\":\"domatar.binding\",\"Name\":\"SetBinding\",\"SideEffect\":\"Write\"}"
        + "]}");
  }

  private static Set<String> distinctActIds() throws DomatarException
  {
    final Set<String> ids = new LinkedHashSet<>();
    final List<Act> acts = ActDb.getAllActs();

    if (acts == null)
      return ids;

    for (final Act act : acts)
    {
      if (act != null && act.actId != null && !act.actId.isEmpty())
        ids.add(act.actId);
    }

    return ids;
  }

  private static String shellDisplayName(final String roleId)
  {
    if ("login".equals(roleId))
      return "Account";
    if ("appstore".equals(roleId))
      return "App Store";
    return capitalize(roleId);
  }

  private static String capitalize(final String s)
  {
    if (s == null || s.isEmpty())
      return s;

    return Character.toUpperCase(s.charAt(0)) + s.substring(1);
  }
}
