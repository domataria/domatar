/*
 * Copyright (c) 2024 Domatar
 */

package com.domatar.db;

import com.domatar.util.DomatarException;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Low-level SQL helpers used exclusively by
 * {@link com.domatar.install.SecurityMigration}.
 *
 * This class lives in the {@code com.domatar.db} package so it can use the
 * package-private {@link DbConnection} API.
 *
 * Spec-Security.txt PART 3.3; Update-Security.txt Task 2.12.
 */
public class SecurityMigrationDb
{
    private SecurityMigrationDb() {}

    // -------------------------------------------------------------------------
    // Step 1a helpers
    // -------------------------------------------------------------------------

    /** Returns all non-null ActId values from the act table. */
    public static List<String> getAllActIds() throws DomatarException
    {
        DbConnection conn = null;
        Statement    stmt = null;
        ResultSet    rset = null;

        final List<String> ids = new ArrayList<>();

        try
        {
            conn = new DbConnection(SecurityMigrationDb.class, "getAllActIds");
            stmt = conn.createStatement();
            rset = stmt.executeQuery("SELECT ActId FROM act");

            while (rset.next())
            {
                final String id = rset.getString(1);

                if (id != null)
                    ids.add(id);
            }
        }
        catch (final SQLException e)
        {
            throw new DomatarException(e);
        }
        finally
        {
            closeQuietly(rset, stmt, conn);
        }

        return ids;
    }

    // -------------------------------------------------------------------------
    // Step 1b helpers
    // -------------------------------------------------------------------------

    /** Returns all HstId values that contain the old {@code '~'} separator. */
    public static List<String> getOldSepHstIds() throws DomatarException
    {
        DbConnection conn = null;
        Statement    stmt = null;
        ResultSet    rset = null;

        final List<String> ids = new ArrayList<>();

        try
        {
            conn = new DbConnection(SecurityMigrationDb.class, "getOldSepHstIds");
            stmt = conn.createStatement();
            rset = stmt.executeQuery("SELECT HstId FROM hst WHERE HstId LIKE '%~%'");

            while (rset.next())
            {
                final String id = rset.getString(1);

                if (id != null)
                    ids.add(id);
            }
        }
        catch (final SQLException e)
        {
            throw new DomatarException(e);
        }
        finally
        {
            closeQuietly(rset, stmt, conn);
        }

        return ids;
    }

    /**
     * Returns all distinct HstId values from {@code obj} and {@code lnk} that
     * use the old {@code '~'} separator followed by a legacy {@code name@app}
     * actId (i.e. the part after {@code '~'} contains {@code '@'}).
     * These sub-host IDs are not registered in the {@code hst} table but still
     * need to be remapped.
     */
    public static List<String> getOldSepSubHstIds() throws DomatarException
    {
        DbConnection conn = null;
        Statement    stmt = null;
        ResultSet    rset = null;

        final List<String> ids = new ArrayList<>();

        try
        {
            conn = new DbConnection(SecurityMigrationDb.class, "getOldSepSubHstIds");
            stmt = conn.createStatement();
            // '%~%@%' matches old format  prefix~name@app  (@ appears after ~).
            // Fingerprint actIds never contain '@', so this safely excludes
            // new HstIds like  aiagent-KQ9uSwRI82o0vtRuYVq~9kzUtavHszCb.
            rset = stmt.executeQuery(
                "SELECT DISTINCT HstId FROM obj WHERE HstId LIKE '%~%@%'"
                    + " UNION "
                    + "SELECT DISTINCT HstId FROM lnk WHERE HstId LIKE '%~%@%'");

            while (rset.next())
            {
                final String id = rset.getString(1);

                if (id != null)
                    ids.add(id);
            }
        }
        catch (final SQLException e)
        {
            throw new DomatarException(e);
        }
        finally
        {
            closeQuietly(rset, stmt, conn);
        }

        return ids;
    }

    // -------------------------------------------------------------------------
    // Step 2: persist sealed keys
    // -------------------------------------------------------------------------

    /**
     * For each entry in {@code sealedKeys}: if the act row's OwnPrvKey is
     * currently NULL, set it to the sealed value.  Returns the count of rows
     * actually updated.
     */
    public static int persistSealedKeys(final Map<String, String> sealedKeys)
        throws DomatarException
    {
        int count = 0;

        DbConnection      conn  = null;
        PreparedStatement pstmt = null;

        try
        {
            conn  = new DbConnection(SecurityMigrationDb.class, "persistSealedKeys");
            pstmt = conn.prepareStatement(
                "UPDATE act SET OwnPrvKey=? WHERE ActId=? AND OwnPrvKey IS NULL");

            for (final Map.Entry<String, String> e : sealedKeys.entrySet())
            {
                pstmt.setString(1, e.getValue());
                pstmt.setString(2, e.getKey());
                count += pstmt.executeUpdate();
            }
        }
        catch (final SQLException e)
        {
            throw new DomatarException(e);
        }
        finally
        {
            closeQuietly(null, pstmt, conn);
        }

        return count;
    }

    // -------------------------------------------------------------------------
    // Step 3: remap whole-value columns
    // -------------------------------------------------------------------------

    /**
     * For each entry in {@code replacements}: UPDATE table SET col=newVal WHERE col=oldVal.
     * Returns total rows updated.
     */
    public static int remapWholeColumn(final String table,
                                        final String col,
                                        final List<Map.Entry<String, String>> replacements)
        throws DomatarException
    {
        int count = 0;

        DbConnection      conn  = null;
        PreparedStatement pstmt = null;

        try
        {
            conn  = new DbConnection(SecurityMigrationDb.class, "remapWhole-" + table + "." + col);
            pstmt = conn.prepareStatement(
                "UPDATE `" + table + "` SET `" + col + "`=? WHERE `" + col + "`=?");

            for (final Map.Entry<String, String> e : replacements)
            {
                pstmt.setString(1, e.getValue());
                pstmt.setString(2, e.getKey());
                count += pstmt.executeUpdate();
            }
        }
        catch (final SQLException e)
        {
            throw new DomatarException(e);
        }
        finally
        {
            closeQuietly(null, pstmt, conn);
        }

        return count;
    }

    /**
     * Remaps ObjId/LnkObjId values that begin with {@code "host-<oldHstId>"}
     * to {@code "host-<newHstId>"}.  Returns rows updated.
     */
    public static int remapObjIdHostRefs(final String table,
                                          final String col,
                                          final List<Map.Entry<String, String>> hstReplacements)
        throws DomatarException
    {
        int count = 0;

        DbConnection      conn  = null;
        PreparedStatement pstmt = null;

        try
        {
            conn  = new DbConnection(SecurityMigrationDb.class, "remapObjId-" + table + "." + col);
            pstmt = conn.prepareStatement(
                "UPDATE `" + table + "` SET `" + col + "`=? WHERE `" + col + "`=?");

            for (final Map.Entry<String, String> e : hstReplacements)
            {
                final String oldRef = "host-" + e.getKey();
                final String newRef = "host-" + e.getValue();

                pstmt.setString(1, newRef);
                pstmt.setString(2, oldRef);
                count += pstmt.executeUpdate();
            }
        }
        catch (final SQLException e)
        {
            throw new DomatarException(e);
        }
        finally
        {
            closeQuietly(null, pstmt, conn);
        }

        return count;
    }

    /**
     * Remaps tokens inside a text column (Attrs JSON, Val varchar) using
     * MySQL {@code REPLACE}.  Applies HOST-ID replacements first, then ACCOUNT
     * replacements.  Returns rows updated.
     *
     * @param isJsonType true when the column is typed JSON (requires
     *                   {@code CAST(result AS JSON)}); false for VARCHAR
     *                   columns where the CAST would fail on empty/non-JSON values.
     */
    public static int remapJsonColumn(final String table,
                                       final String col,
                                       final List<Map.Entry<String, String>> hstReplacements,
                                       final List<Map.Entry<String, String>> actReplacements,
                                       final boolean isJsonType)
        throws DomatarException
    {
        int count = 0;

        DbConnection      conn  = null;
        PreparedStatement pstmt = null;

        final String setSql = isJsonType
            ? "`" + col + "`=CAST(REPLACE(CAST(`" + col + "` AS CHAR),?,?) AS JSON)"
            : "`" + col + "`=REPLACE(`" + col + "`,?,?)";

        try
        {
            conn  = new DbConnection(SecurityMigrationDb.class, "remapJson-" + table + "." + col);
            pstmt = conn.prepareStatement(
                "UPDATE `" + table + "` SET " + setSql
                    + " WHERE `" + col + "` IS NOT NULL"
                    + "   AND `" + col + "` LIKE ?");

            for (final Map.Entry<String, String> e : hstReplacements)
                count += execReplaceUpdate(pstmt, e.getKey(), e.getValue());

            for (final Map.Entry<String, String> e : actReplacements)
                count += execReplaceUpdate(pstmt, e.getKey(), e.getValue());
        }
        catch (final SQLException e)
        {
            throw new DomatarException(e);
        }
        finally
        {
            closeQuietly(null, pstmt, conn);
        }

        return count;
    }

    /** Remaps act.ActId (done last). */
    public static int remapActIdColumn(final List<Map.Entry<String, String>> actReplacements)
        throws DomatarException
    {
        int count = 0;

        DbConnection      conn  = null;
        PreparedStatement pstmt = null;

        try
        {
            conn  = new DbConnection(SecurityMigrationDb.class, "remapActIdCol");
            pstmt = conn.prepareStatement("UPDATE act SET ActId=? WHERE ActId=?");

            for (final Map.Entry<String, String> e : actReplacements)
            {
                pstmt.setString(1, e.getValue());
                pstmt.setString(2, e.getKey());
                count += pstmt.executeUpdate();
            }
        }
        catch (final SQLException e)
        {
            throw new DomatarException(e);
        }
        finally
        {
            closeQuietly(null, pstmt, conn);
        }

        return count;
    }

    // -------------------------------------------------------------------------
    // Step 4: verify
    // -------------------------------------------------------------------------

    /**
     * Returns the number of rows in {@code table.col} whose value CONTAINS the
     * given token as a substring.
     */
    public static long countTokenOccurrences(final String table,
                                              final String col,
                                              final String token)
        throws DomatarException
    {
        DbConnection conn = null;
        Statement    stmt = null;
        ResultSet    rset = null;

        try
        {
            conn = new DbConnection(SecurityMigrationDb.class, "countToken");
            stmt = conn.createStatement();
            rset = stmt.executeQuery(
                "SELECT COUNT(*) FROM `" + table + "` WHERE CAST(`" + col
                    + "` AS CHAR) LIKE '%" + escapeLike(token) + "%'");

            return rset.next() ? rset.getLong(1) : 0L;
        }
        catch (final SQLException e)
        {
            throw new DomatarException(e);
        }
        finally
        {
            closeQuietly(rset, stmt, conn);
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static int execReplaceUpdate(
        final PreparedStatement pstmt,
        final String oldToken,
        final String newToken) throws SQLException
    {
        pstmt.setString(1, oldToken);
        pstmt.setString(2, newToken);
        pstmt.setString(3, "%" + oldToken + "%");
        return pstmt.executeUpdate();
    }

    private static String escapeLike(final String s)
    {
        return s.replace("\\", "\\\\")
                .replace("%",  "\\%")
                .replace("_",  "\\_");
    }

    private static void closeQuietly(final ResultSet rs,
                                      final AutoCloseable stmt,
                                      final DbConnection conn)
    {
        try { if (rs   != null) rs.close();  } catch (Exception ignored) {}
        try { if (stmt != null) stmt.close(); } catch (Exception ignored) {}
        try { if (conn != null) conn.close(); } catch (Exception ignored) {}
    }
}
