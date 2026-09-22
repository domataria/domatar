package com.domatar.hst;

import com.domatar.core.RequestContext;
import com.domatar.core.DomatarConfig;
import com.domatar.crypto.DirectoryTrust;
import com.domatar.crypto.KeyOps;
import com.domatar.db.HstDb;
import com.domatar.util.Base64Encoder;
import com.domatar.util.Hst;
import com.domatar.util.JsonMsg;
import com.domatar.util.Obj;
import com.domatar.util.ObjAttrs;
import com.domatar.util.ObjImpl;
import com.domatar.util.DomatarException;
import com.domatar.util.DomatarMsgClient;

import java.security.PublicKey;

public class HstsImpl extends ObjImpl
{
  @Override
  public String handleMsg(final String msg, final Obj obj, final String contextPath,
                          final String contextRealPath, final DomatarMsgClient msgClient)
      throws DomatarException
  {
    final JsonMsg inMsg  = new JsonMsg(msg);
    final String  opr    = inMsg.getOperation();

    if ("Compensate".equals(opr))
      return super.handleMsg(msg, obj, contextPath, contextRealPath, msgClient);

    final JsonMsg outMsg = new JsonMsg();

    if (!hasRights(inMsg, obj, msgClient))
      return notAuthorized(inMsg);

    if ("UpdateHst".equals(opr))
      updateHst(opr, inMsg, outMsg, msgClient);
    else if ("GetHst".equals(opr))
      getHst(opr, inMsg, outMsg, msgClient);
    else
      return super.handleMsg(msg, obj, contextPath, contextRealPath, msgClient);

    return outMsg.toString();
  }

  // hasRights: inherits the default (public). The directory must be reachable
  // by peers that aren't logged in here - that's the whole point of the
  // hst.hsts service. UpdateHst is public in HTTP mode; in mTLS mode the
  // transport layer enforces the client certificate requirement and the
  // application layer verifies the cert's key matches the expected provider.

  private void updateHst(final String opr, final JsonMsg inMsg, final JsonMsg outMsg,
                         final DomatarMsgClient msgClient) throws DomatarException
  {
    final ObjAttrs attrs  = inMsg.getAttrs();
    final String   hstId  = attrs.getAttr("HstId");
    final String   domain = attrs.getAttr("Domain");
    final String   prvId  = attrs.getAttr("PrvId");
    final String   pubKey = attrs.getAttr("PubKey"); // optional; null if not publishing a key

    // Phase 6: mTLS enforcement for directory writes.
    // When DOMATAR_MTLS_REQUIRED=true (default when WireScheme=https), the
    // caller must present a TLS client certificate whose public key matches
    // the stored provider operational key.  This ensures only the rightful
    // provider can update its own directory record.
    if (DomatarConfig.isMtlsRequired())
    {
      final byte[] certPubKeyEncoded = RequestContext.getClientCertPubKeyEncoded();

      if (certPubKeyEncoded == null)
      {
        outMsg.addError(opr, "mTLS client certificate required for directory writes");
        return;
      }

      // Verify: the cert's public key must match the stored provider key.
      // For a new provider (no pubKey stored yet), we accept the first write
      // so the provider can bootstrap its record (idempotent: the pubKey
      // stored by this write is what future calls will verify against).
      if (hstId != null)
      {
        final Hst stored = HstDb.getHst(hstId);

        if (stored != null && stored.pubKey != null)
        {
          if (!certMatchesStoredKey(certPubKeyEncoded, stored.pubKey))
          {
            outMsg.addError(opr, "mTLS: client certificate does not match stored provider key");
            return;
          }
        }
      }
    }

    // Write the core fields (version bump handled inside HstDb).
    HstDb.updateHst(hstId, domain, prvId);

    if (pubKey != null)
    {
      // Re-read to get the current version after upsert.
      final Hst stored = HstDb.getHst(hstId);
      final long version = (stored != null) ? stored.version : 1L;

      // Sign the record with the directory root private key (no-op if this
      // node is not the directory server — signRecord returns null).
      final String recordSig = DirectoryTrust.signRecord(hstId, domain, prvId, version, pubKey);

      HstDb.updateHstKeys(hstId, pubKey, recordSig);
    }

    outMsg.addResponseBody(opr, null);
  }

  private void getHst(final String opr, final JsonMsg inMsg, final JsonMsg outMsg,
                      final DomatarMsgClient msgClient) throws DomatarException
  {
    final ObjAttrs attrs = inMsg.getAttrs();
    final String hstId   = attrs.getAttr("HstId");
    final Hst hst        = HstDb.getHst(hstId);

    if (hst == null)
    {
      outMsg.addError(opr, "Hst not found");
      return;
    }

    final ObjAttrs outAttrs = new ObjAttrs();
    outAttrs.addAttr("HstId",   hst.hstId);
    outAttrs.addAttr("Domain",  hst.domain);
    outAttrs.addAttr("PrvId",   hst.prvId);
    outAttrs.addAttr("Version", Long.toString(hst.version));

    if (hst.pubKey != null)
      outAttrs.addAttr("PubKey", hst.pubKey);

    if (hst.recordSig != null)
      outAttrs.addAttr("RecordSig", hst.recordSig);

    outMsg.addResponseBody(opr, outAttrs);
  }

  // -------------------------------------------------------------------------
  // mTLS helper
  // -------------------------------------------------------------------------

  /**
   * Returns {@code true} iff the DER-encoded public key from the TLS client
   * certificate matches the stored provider operational key.
   *
   * <p>The stored key ({@code hst.pubKey}) is a Base64Encoder-encoded 32-byte
   * raw Ed25519 public key.  The cert's key ({@code certPubKeyEncoded}) is the
   * X.509 SubjectPublicKeyInfo DER encoding produced by
   * {@code PublicKey.getEncoded()}, which has a fixed 12-byte header prefix
   * (see {@link com.domatar.crypto.KeyOps}).  We reconstruct the provider's
   * stored key as a {@link PublicKey} and compare encoded forms, so different
   * key types produce different encodings and are cleanly rejected.
   */
  private static boolean certMatchesStoredKey(final byte[] certPubKeyEncoded,
                                              final String storedPubKeyB64)
  {
    try
    {
      final byte[]    storedRawBytes = Base64Encoder.decode(storedPubKeyB64);
      final PublicKey storedPubKey   = KeyOps.publicKeyFromBytes(storedRawBytes);
      final byte[]    storedEncoded  = storedPubKey.getEncoded();

      if (storedEncoded.length != certPubKeyEncoded.length)
        return false;

      for (int i = 0; i < storedEncoded.length; i++)
        if (storedEncoded[i] != certPubKeyEncoded[i])
          return false;

      return true;
    }
    catch (final Exception e)
    {
      System.out.println("WARN: certMatchesStoredKey failed: " + e);
      return false;
    }
  }
}
