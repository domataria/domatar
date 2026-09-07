/*
 * Copyright (c) 2024 Domatar
 */

package com.domatar.install;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.logging.Logger;

import com.domatar.core.DomatarConfig;
import com.domatar.db.HstDb;
import com.domatar.util.Hst;
import com.domatar.util.JsonMsg;
import com.domatar.util.ObjAttrs;
import com.domatar.util.DomId;

/**
 * Fetches an hst record from the global directory and caches it locally.
 * Used when a peer's cached provider key is stale after key rotation
 * (ephemeral ProviderKeyStore) so credential-chain verify can refresh.
 */
public final class DirectoryLookup
{
  private static final Logger LOG = Logger.getLogger(DirectoryLookup.class.getName());

  private DirectoryLookup() {}

  /**
   * GET {@code hstId} from {@link DomatarConfig#getDirectory()}, write into
   * local {@link HstDb}, and return the cached row. Non-fatal: returns null
   * on any failure.
   */
  public static Hst fetchAndCache(final String hstId)
  {
    if (hstId == null || hstId.isEmpty())
      return null;

    try
    {
      final JsonMsg req = new JsonMsg();
      final ObjAttrs attrs = new ObjAttrs();

      attrs.addAttr("HstId", hstId);

      final DomId src = new DomId(DomatarConfig.getHstId(), "hst", "lookup@hst", "get");
      final DomId dst = new DomId("domatar", "hst", "domatar@hst", "hsts");
      final com.domatar.core.Context ctx = new com.domatar.core.Context(
          "lookup@hst", null, null, "127.0.0.1", null, null);

      req.addRequestHead(src, dst, ctx);
      req.addRequestBody("GetHst", attrs);
      req.addClsId("hst", "hsts");

      final String scheme    = DomatarConfig.getWireScheme();
      final String directory = DomatarConfig.getDirectory();
      final String urlStr    = scheme + "://" + directory
          + DomatarConfig.PLATFORM_CONTEXT_PATH + "/Msg";

      final URL url = new URL(urlStr);
      final HttpURLConnection conn = (HttpURLConnection) url.openConnection();

      conn.setDoInput(true);
      conn.setDoOutput(true);
      conn.setRequestMethod("POST");
      conn.setRequestProperty("Content-Type",
          "application/x-www-form-urlencoded; charset=utf-8");
      conn.setConnectTimeout(10000);
      conn.setReadTimeout(10000);

      final OutputStreamWriter osw =
          new OutputStreamWriter(conn.getOutputStream(), StandardCharsets.UTF_8);
      final String postStr = "Msg=" + java.net.URLEncoder.encode(req.toString(), "UTF-8");

      osw.write(postStr);
      osw.flush();
      osw.close();

      final BufferedReader br = new BufferedReader(
          new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
      final StringBuilder sb = new StringBuilder();
      String line;

      while ((line = br.readLine()) != null)
        sb.append(line);

      br.close();

      final JsonMsg resp = new JsonMsg(sb.toString());

      if (!"Success".equals(resp.getError()))
      {
        LOG.warning("DirectoryLookup GetHst failed for " + hstId
            + ": " + resp.getErrorMsg());
        return null;
      }

      final String domain = resp.getAttr("Domain");
      final String prvId  = resp.getAttr("PrvId");
      final String pubKey = resp.getAttr("PubKey");
      final String sig    = resp.getAttr("RecordSig");
      final String verStr = resp.getAttr("Version");

      if (domain == null && prvId == null)
        return null;

      long version = 0L;

      if (verStr != null)
      {
        try { version = Long.parseLong(verStr); }
        catch (final NumberFormatException ignored) {}
      }

      HstDb.updateHst(hstId, domain, prvId, version, System.currentTimeMillis(),
                      pubKey, sig);

      return HstDb.getHst(hstId);
    }
    catch (final Exception e)
    {
      LOG.warning("DirectoryLookup fetchAndCache failed for " + hstId + ": " + e);
      return null;
    }
  }
}
