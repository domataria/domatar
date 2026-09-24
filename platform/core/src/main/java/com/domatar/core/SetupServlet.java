/*
 * Copyright (c) 2024 Domatar
 */

package com.domatar.core;

import com.domatar.core.DomatarConfig;
import com.domatar.util.DomatarException;
import com.domatar.util.DomatarMsgClient;
import com.domatar.db.DbConnection;
import com.domatar.install.MembershipFanout;
import com.domatar.install.MembershipMigration;
import com.domatar.install.LoginHostCutover;
import com.domatar.install.DesktopReplica;
import com.domatar.install.DesktopSyncCutover;
import com.domatar.install.DesktopTombstonePurge;
import com.domatar.install.NavigatorReplica;
import com.domatar.install.NavAppEntry;
import com.domatar.install.OpLogInstall;
import com.domatar.install.PaymentInstall;
import com.domatar.install.OwnIdsMigration;
import com.domatar.install.OwnIdsRebind;
import com.domatar.install.SecurityMigration;
import com.domatar.install.MembershipMigrator;
import com.domatar.install.UserSubstrateInstall;
import com.domatar.util.DomId;
import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.io.PrintWriter;

/**
 * Base class for one-shot idempotent setup endpoints.
 *
 * On the first GET or POST request:
 *   - Verifies the request originates from localhost (127.0.0.1 / ::1).
 *   - Calls isAlreadyDone().  If true: responds with 404 (endpoint is now
 *     disabled, per Spec-Installation.txt PART 3.2 step 4).
 *   - Calls doSetup().  On success: responds with a plain-text success message.
 *   - On error: responds with 500 and a plain-text error description.
 *
 * Subclasses implement isAlreadyDone() and doSetup().
 *
 * Security: the endpoint accepts requests from 127.0.0.1 and ::1 only.
 * All other callers receive 403.
 *
 * Spec-Installation.txt PART 3.2 / PART 4.3.
 */
public abstract class SetupServlet extends HttpServlet
{
  private static final long serialVersionUID = 1L;

  // -------------------------------------------------------------------------
  // Contract for subclasses
  // -------------------------------------------------------------------------

  /**
   * Returns true if setup has already been completed (the endpoint should
   * return 404 to signal it is now disabled).
   */
  protected abstract boolean isAlreadyDone() throws Exception;

  /**
   * Performs the one-time setup work.  Must be idempotent.
   */
  protected abstract void doSetup() throws Exception;

  /**
   * Called immediately after {@link #doSetup()} succeeds, before the HTTP
   * 200 response is written.  Override to trigger downstream setup steps
   * (e.g. calling sibling app {@code /Setup} endpoints).
   * The default implementation is a no-op.
   */
  protected void onSetupComplete(HttpServletRequest req, HttpServletResponse res)
      throws Exception {}

  // -------------------------------------------------------------------------
  // Servlet lifecycle
  // -------------------------------------------------------------------------

  @Override
  public void init(ServletConfig config) throws ServletException
  {
    super.init(config);

    ServletContext ctx = getServletContext();

    String dbOverride = DomatarConfig.getDbUrlOverride();

    String dbUrl = (dbOverride != null && !dbOverride.isEmpty())
                       ? dbOverride
                       : ctx.getInitParameter("DbConnection");

    DbConnection.setConnectStr(dbUrl);
  }

  // -------------------------------------------------------------------------
  // HTTP handlers
  // -------------------------------------------------------------------------

  @Override
  protected void doGet(HttpServletRequest req, HttpServletResponse res)
      throws ServletException, IOException
  {
    handle(req, res);
  }

  @Override
  protected void doPost(HttpServletRequest req, HttpServletResponse res)
      throws ServletException, IOException
  {
    handle(req, res);
  }

  // -------------------------------------------------------------------------

  private void handle(HttpServletRequest req, HttpServletResponse res)
      throws IOException
  {
    res.setContentType("text/plain; charset=UTF-8");

    PrintWriter out = res.getWriter();

    // Localhost-only guard (applies to ALL actions on this endpoint).
    String remoteAddr = req.getRemoteAddr();

    if (!"127.0.0.1".equals(remoteAddr) && !"::1".equals(remoteAddr))
    {
      res.setStatus(HttpServletResponse.SC_FORBIDDEN);
      out.println("Setup endpoint is only accessible from localhost.");
      return;
    }

    try
    {
      OpLogInstall.ensureTables();
    }
    catch (final Exception e)
    {
      System.out.println("WARN: OpLogInstall.ensureTables failed: " + e);
    }

    try
    {
      PaymentInstall.ensureTable();
    }
    catch (final Exception e)
    {
      System.out.println("WARN: PaymentInstall.ensureTable failed: " + e);
    }

    // Phase 2 security migration: GET /Setup?action=migrate-security
    // Idempotent; safe to run again (already-migrated accounts are skipped).
    // Run this AFTER the three SQL migration scripts have been applied to the
    // database:
    //   1. mySQL/migrate-2026-security-schema.sql   (act.OwnPrvKey)
    //   2. mySQL/migrate-2026-security-hst-keys.sql (hst.PubKey, RecordSig)
    //   3. mySQL/migrate-2026-security-deleg.sql    (act delegation columns)
    final String action = req.getParameter("action");

    if ("migrate-security".equals(action))
    {
      try
      {
        final String result = SecurityMigration.migrateAll();
        res.setStatus(HttpServletResponse.SC_OK);
        out.println(result);
      }
      catch (Exception e)
      {
        res.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        out.println("SecurityMigration failed: " + e.getMessage());
        e.printStackTrace();
      }
      return;
    }

    // OwnIds Phase 4: GET /Setup?action=migrate-ownids
    // Idempotent; accounts with GenesisPubKey already set are skipped.
    // Requires mySQL/dump-2024-01-22c-ownids-schema.sql applied first.
    if ("migrate-ownids".equals(action))
    {
      try
      {
        final String result = OwnIdsMigration.migrate();
        res.setStatus(HttpServletResponse.SC_OK);
        out.println(result);
      }
      catch (Exception e)
      {
        res.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        out.println("OwnIdsMigration failed: " + e.getMessage());
        e.printStackTrace();
      }
      return;
    }

    // Login-Multiple Phase 4: GET /Setup?action=migrate-membership
    // Idempotent backfill of login-<actId>-<prvId> replicas for existing acts.
    if ("migrate-membership".equals(action))
    {
      try
      {
        final String domain = req.getServerName();
        final DomatarMsgClient client = operatorClient(req, domain, "setup@setup", "migrate");

        final String result = MembershipMigration.migrate(client);
        res.setStatus(HttpServletResponse.SC_OK);
        out.println(result);
      }
      catch (Exception e)
      {
        res.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        out.println("MembershipMigration failed: " + e.getMessage());
        e.printStackTrace();
      }
      return;
    }

    // Login-Multiple Phase 5: GET /Setup?action=cutover-membership
    // Move app-login onto the replica and DELETE login-<actId> hosts.
    if ("cutover-membership".equals(action))
    {
      try
      {
        final String domain = req.getServerName();
        final DomatarMsgClient client = operatorClient(req, domain, "setup@setup", "cutover");

        final String result = LoginHostCutover.cutover(client);
        res.setStatus(HttpServletResponse.SC_OK);
        out.println(result);
      }
      catch (Exception e)
      {
        res.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        out.println("LoginHostCutover failed: " + e.getMessage());
        e.printStackTrace();
      }
      return;
    }

    // Desktop-Synchronized Phase 4: GET /Setup?action=migrate-desktop-sync
    // Move bare desktop-<actId> / navigator-<actId> onto qualified replicas
    // and DELETE the bare hosts.
    if ("migrate-desktop-sync".equals(action))
    {
      try
      {
        final String domain = DomatarConfig.getDomain() != null
            ? DomatarConfig.getDomain()
            : req.getServerName();
        final DomatarMsgClient client = operatorClient(req, domain, "setup@setup", "desktop-sync");

        final String result = DesktopSyncCutover.cutover(client);
        res.setStatus(HttpServletResponse.SC_OK);
        out.println(result);
      }
      catch (Exception e)
      {
        res.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        out.println("DesktopSyncCutover failed: " + e.getMessage());
        e.printStackTrace();
      }
      return;
    }

    // Desktop-Synchronized Phase 5: GET /Setup?action=purge-desktop-tombstones
    // Physically delete aged Tombstone=True tile rows on local Desktop replicas.
    if ("purge-desktop-tombstones".equals(action))
    {
      try
      {
        final String result = DesktopTombstonePurge.purge();
        res.setStatus(HttpServletResponse.SC_OK);
        out.println(result);
      }
      catch (Exception e)
      {
        res.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        out.println("DesktopTombstonePurge failed: " + e.getMessage());
        e.printStackTrace();
      }
      return;
    }

    // Mandatory-App-Rewrite Phase 1: GET /Setup?action=ensure-user-substrate
    // Idempotent backfill of domatar-<actId>-<prvId> for existing accounts.
    if ("ensure-user-substrate".equals(action))
    {
      try
      {
        final String domain = DomatarConfig.getDomain() != null
            ? DomatarConfig.getDomain()
            : req.getServerName();
        final DomatarMsgClient client = operatorClient(req, domain, "setup@setup", "user-substrate");

        final String result = UserSubstrateInstall.ensureAll(client);
        res.setStatus(HttpServletResponse.SC_OK);
        out.println(result);
      }
      catch (Exception e)
      {
        res.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        out.println("UserSubstrateInstall.ensureAll failed: " + e.getMessage());
        e.printStackTrace();
      }
      return;
    }

    // Mandatory-App-Rewrite Phase 4: GET /Setup?action=migrate-domatar-membership
    // Copy login-replica peers onto domatar-<actId>-<prvId> membership.
    if ("migrate-domatar-membership".equals(action))
    {
      try
      {
        final String domain = DomatarConfig.getDomain() != null
            ? DomatarConfig.getDomain()
            : req.getServerName();
        final DomatarMsgClient client = operatorClient(req, domain, "setup@setup", "domatar-membership");

        final String result = MembershipMigrator.migrateAll(client);
        res.setStatus(HttpServletResponse.SC_OK);
        out.println(result);
      }
      catch (Exception e)
      {
        res.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        out.println("MembershipMigrator.migrateAll failed: " + e.getMessage());
        e.printStackTrace();
      }
      return;
    }

    // OwnIds Phase 5 sim/ops: GET /Setup?action=rebind-ownids&actId=...
    // Localhost-only. Prefer ActWui Action=Rebind for verified user sessions;
    // this path exercises PART 10 when driving acceptance without cookies.
    if ("rebind-ownids".equals(action))
    {
      try
      {
        final String actId = req.getParameter("actId");
        final String result = OwnIdsRebind.rebind(actId);

        // Phase 2 / K7: also refresh the local membership binding obj and
        // fan out so replicas learn the rebind (Setup path has no browser
        // session; ActManagerImpl.rebind does the same for ActWui).
        try
        {
          final com.domatar.crypto.Binding binding = com.domatar.db.ActDb.getBinding(actId);

          if (binding != null)
          {
            final String domain = req.getServerName();
            final DomatarMsgClient client = operatorClient(req, domain, actId, "rebind");

            MembershipFanout.publishBinding(actId, binding, client);
          }
        }
        catch (final Exception pubEx)
        {
          System.out.println("WARN: rebind-ownids membership publish failed: " + pubEx);
        }

        res.setStatus(HttpServletResponse.SC_OK);
        out.println(result);
      }
      catch (Exception e)
      {
        res.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        out.println("OwnIdsRebind failed: " + e.getMessage());
        e.printStackTrace();
      }
      return;
    }

    // Desktop-Synchronized Phase 3 sim: seed one account's qualified Desktop
    // (+ Navigator) replica on THIS provider. GET
    // /Setup?action=ensure-desktop-replica&actId=...
    // Localhost-only. Idempotent. Used until Phase 4 bulk migrate.
    if ("ensure-desktop-replica".equals(action))
    {
      try
      {
        final String actId = req.getParameter("actId");

        if (actId == null || actId.isEmpty())
        {
          res.setStatus(HttpServletResponse.SC_BAD_REQUEST);
          out.println("Missing actId");
          return;
        }

        final String domain = DomatarConfig.getDomain() != null
            ? DomatarConfig.getDomain()
            : (req.getServerName() + ":" + req.getServerPort());
        final String prvId  = DomatarConfig.getPrvId();
        final DomatarMsgClient client = operatorClient(req, domain, actId, "desktop-replica");

        NavigatorReplica.ensure(actId, actId, domain, prvId, client);
        DesktopReplica.ensure(actId, domain, prvId, client);

        final String desktopRepId = DomId.subHstId("desktop", actId, prvId);
        final String navRepId     = DomId.subHstId("navigator", actId, prvId);
        final DomId rootId    = new DomId(navRepId,     "navigator", actId, "root");
        final DomId appDeskId = new DomId(desktopRepId, "desktop",   actId, "app-desktop");

        NavAppEntry.ensureRootLnk(rootId, appDeskId, "desktop",
            "Desktop", "Your home screen", 3);

        res.setStatus(HttpServletResponse.SC_OK);
        out.println("ensure-desktop-replica ok actId=" + actId
            + " prvId=" + prvId
            + " desktop=" + desktopRepId);
      }
      catch (Exception e)
      {
        res.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        out.println("ensure-desktop-replica failed: " + e.getMessage());
        e.printStackTrace();
      }
      return;
    }

    // Default action: one-shot provider bootstrap (setup).
    try
    {
      if (isAlreadyDone())
      {
        // Bootstrap is done, but Tomcat recreate can mint a new ephemeral
        // operational key — refresh hst.PubKey so cross-prv HopSig verifies.
        try
        {
          com.domatar.install.ProviderKeyPublish.publishLocalAndDirectory();
        }
        catch (final Exception e)
        {
          System.out.println("WARN: ProviderKeyPublish on already-done Setup: " + e);
        }

        // Idempotent: offered home hosts + home users, then each app's
        // installProvider (registry/catalog objects owned by the home user).
        doSetup();
        onSetupComplete(req, res);

        res.setStatus(HttpServletResponse.SC_OK);
        out.println("Setup has already been completed. Provider key republished; "
            + "home hosts refreshed.");
        return;
      }

      doSetup();
      onSetupComplete(req, res);

      res.setStatus(HttpServletResponse.SC_OK);
      out.println("Setup completed successfully.");
    }
    catch (Exception e)
    {
      res.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
      out.println("Setup failed: " + e.getMessage());
      e.printStackTrace();
    }
  }

  private static DomatarMsgClient operatorClient(final jakarta.servlet.http.HttpServletRequest req,
                                                 final String domain, final String actId,
                                                 final String objId) throws DomatarException
  {
    final com.domatar.util.DomId src = new com.domatar.util.DomId(
        DomatarConfig.getHstId(), "setup", actId, objId);
    return HttpClient.operatorClient(src, domain,
        req.getServletContext().getContextPath(),
        req.getServletContext().getRealPath(""),
        actId);
  }

}
