/*
 * Copyright (c) 2024 Domatar
 */

package com.domatar.install;

import com.domatar.core.Context;
import com.domatar.core.DomatarConfig;
import com.domatar.crypto.DirectoryTrust;
import com.domatar.crypto.ProviderKeyStore;
import com.domatar.db.HstDb;
import com.domatar.util.Base64Encoder;
import com.domatar.util.Hst;
import com.domatar.util.JsonMsg;
import com.domatar.util.ObjAttrs;
import com.domatar.util.DomId;
import com.domatar.util.DomatarException;

import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Publishes this node's operational provider public key into the local hst
 * row and best-effort to the directory (Spec-Security PART 5.2).
 *
 * <p>Safe to call after every Tomcat start / {@code /Setup}: without a
 * persisted {@code ProviderKeyPath}, ephemeral keys rotate on recreate and
 * peers reject OriginSig until hst.PubKey is refreshed.
 */
public final class ProviderKeyPublish
{
  private ProviderKeyPublish() {}

  public static void publishLocalAndDirectory() throws DomatarException
  {
    publish(DomatarConfig.getPrvId(), DomatarConfig.getDomain());
  }

  public static void publish(final String prvId, final String domain)
      throws DomatarException
  {
    if (prvId == null || prvId.isEmpty() || domain == null || domain.isEmpty())
      return;

    final byte[] pubKeyBytes = ProviderKeyStore.publicKeyBytes();
    final String pubKeyB64   = Base64Encoder.encode(pubKeyBytes);

    final Hst existing = HstDb.getHst(prvId);
    final long version = (existing != null) ? existing.version : 1L;

    final String recordSig =
        DirectoryTrust.signRecord(prvId, domain, prvId, version, pubKeyB64);

    HstDb.updateHstKeys(prvId, pubKeyB64, recordSig);

    try
    {
      final DomId directoryId = new DomId("domatar", "hst", "domatar@hst", "hsts");
      final DomId src = new DomId(prvId, "hst", prvId + "@hst", "publish");
      final Context ctx = new Context(
          prvId + "@" + prvId, null, null, "127.0.0.1", null, false, null,
          new DomId[0]);
      final JsonMsg updateMsg = new JsonMsg();
      final ObjAttrs attrs = new ObjAttrs();

      attrs.addAttr("HstId",  prvId);
      attrs.addAttr("Domain", domain);
      attrs.addAttr("PrvId",  prvId);
      attrs.addAttr("PubKey", pubKeyB64);

      updateMsg.addRequestHead(src, directoryId, ctx);
      updateMsg.addRequestBody("UpdateHst", attrs);
      updateMsg.addClsId("hst", "hsts");

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
      final String postStr = "Msg="
          + URLEncoder.encode(updateMsg.toString(), "UTF-8");

      osw.write(postStr);
      osw.flush();
      osw.close();

      final int code = conn.getResponseCode();

      if (code < 200 || code >= 300)
        System.out.println("WARN: ProviderKeyPublish directory UpdateHst HTTP "
            + code + " for prvId=" + prvId);
    }
    catch (final Exception e)
    {
      System.out.println("WARN: ProviderKeyPublish directory UpdateHst failed for prvId="
          + prvId + ": " + e);
    }
  }
}
