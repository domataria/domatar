/*
 * Copyright (c) 2024 Domatar
 */

package com.domatar.crypto;

import com.domatar.util.Base64Encoder;
import com.domatar.util.JsonHashMap;
import com.domatar.util.JsonMap;
import com.domatar.util.DomatarException;

import java.security.PrivateKey;
import java.security.PublicKey;

/**
 * Genesis-signed {@code actId -> ownId} binding (Spec-OwnIds.txt PART 6).
 *
 * Self-authenticating: a verifier that knows only the actId can validate
 * the binding with no external lookup (PART 6.3). Signed with the genesis
 * private key over CanonicalJson of the binding map excluding GenesisSig
 * (Update-OwnIds.txt K5).
 *
 * Wire / map fields:
 *   ActId, GenesisPubKey, OwnId, OwnPubKey, Version, NotBefore, GenesisSig
 */
public class Binding
{
  public final String actId;
  public final String genesisPubKeyB64;
  public final String ownId;
  public final String ownPubKeyB64;
  public final long   version;
  public final long   notBefore;
  public final String genesisSig;

  private Binding(final String actId,
                  final String genesisPubKeyB64,
                  final String ownId,
                  final String ownPubKeyB64,
                  final long   version,
                  final long   notBefore,
                  final String genesisSig)
  {
    this.actId            = actId;
    this.genesisPubKeyB64 = genesisPubKeyB64;
    this.ownId            = ownId;
    this.ownPubKeyB64     = ownPubKeyB64;
    this.version          = version;
    this.notBefore        = notBefore;
    this.genesisSig       = genesisSig;
  }

  /**
   * Builds and signs a binding for {@code ownPubKey} under the given genesis
   * keys at the supplied monotonic {@code version}.
   */
  public static Binding sign(final AccountKeys genesisKeys,
                             final byte[] ownPubKey,
                             final long version)
  {
    final String ownId            = AccountKeys.deriveOwnId(ownPubKey);
    final String actId            = genesisKeys.actId;
    final String genesisPubKeyB64 = Base64Encoder.encode(genesisKeys.rootPubKey);
    final String ownPubKeyB64     = Base64Encoder.encode(ownPubKey);
    final long   notBefore        = System.currentTimeMillis();

    final Binding unsigned = new Binding(actId, genesisPubKeyB64, ownId,
                                         ownPubKeyB64, version, notBefore, null);

    final byte[]     canonical = CanonicalJson.canonicalizeExcluding(
                                     unsigned.toMap(), "GenesisSig");
    final PrivateKey privKey   = KeyOps.privateKeyFromBytes(genesisKeys.rootPrivKey);
    final byte[]     sigBytes  = KeyOps.sign(privKey, canonical);
    final String     genesisSig = Base64Encoder.encode(sigBytes);

    return new Binding(actId, genesisPubKeyB64, ownId, ownPubKeyB64,
                       version, notBefore, genesisSig);
  }

  /**
   * Verifies this binding under a specific fingerprint version
   * (Spec-ActId-Versioning.txt PART 5.1):
   *   (a) GenesisPubKey fingerprints to ActId under fpVersion
   *   (b) GenesisSig verifies under GenesisPubKey
   *   (c) OwnPubKey fingerprints to OwnId under fpVersion
   *
   * Returns false (never throws) on any decode/parse failure.
   */
  public boolean verify(final int fpVersion)
  {
    if (actId == null || genesisPubKeyB64 == null || ownId == null
            || ownPubKeyB64 == null || genesisSig == null)
      return false;

    final byte[] genesisPub;
    try
    {
      genesisPub = Base64Encoder.decode(genesisPubKeyB64);
    }
    catch (final RuntimeException e)
    {
      return false;
    }

    if (genesisPub.length != 32)
      return false;

    if (!AccountKeys.fingerprintsTo(genesisPub, actId, fpVersion))
      return false;

    final PublicKey genesisKey;
    try
    {
      genesisKey = KeyOps.publicKeyFromBytes(genesisPub);
    }
    catch (final RuntimeException e)
    {
      return false;
    }

    final byte[] canonical = CanonicalJson.canonicalizeExcluding(toMap(), "GenesisSig");
    final byte[] sigBytes;
    try
    {
      sigBytes = Base64Encoder.decode(genesisSig);
    }
    catch (final RuntimeException e)
    {
      return false;
    }

    if (!KeyOps.verify(genesisKey, canonical, sigBytes))
      return false;

    final byte[] ownPub;
    try
    {
      ownPub = Base64Encoder.decode(ownPubKeyB64);
    }
    catch (final RuntimeException e)
    {
      return false;
    }

    if (ownPub.length != 32)
      return false;

    if (!AccountKeys.fingerprintsTo(ownPub, ownId, fpVersion))
      return false;

    return true;
  }

  /**
   * No-lookup, version-tolerant verify (Spec-OwnIds.txt PART 6.3; KD2):
   * tries every registered fingerprint version.
   */
  public boolean verify()
  {
    for (final int v : AccountKeys.registeredVersions())
      if (verify(v))
        return true;

    return false;
  }

  /** Returns this binding as a JSON-compatible map (includes GenesisSig). */
  public JsonMap toMap()
  {
    final JsonMap m = new JsonHashMap();
    m.put("ActId",         actId);
    m.put("GenesisPubKey", genesisPubKeyB64);
    m.put("GenesisSig",    genesisSig);
    m.put("NotBefore",     notBefore);
    m.put("OwnId",         ownId);
    m.put("OwnPubKey",     ownPubKeyB64);
    m.put("Version",       version);
    return m;
  }

  /** Serializes to a compact JSON string. */
  public String toJson() throws DomatarException
  {
    return com.domatar.util.Json.toJson(toMap());
  }

  /**
   * Deserializes from a JSON string.
   *
   * @throws DomatarException if the JSON cannot be parsed
   */
  public static Binding fromJson(final String json) throws DomatarException
  {
    return fromMap(com.domatar.util.Json.parseMap(json));
  }

  /** Constructs from a pre-parsed JSON map (e.g. a Sec envelope sub-map). */
  public static Binding fromMap(final JsonMap m)
  {
    if (m == null)
      return null;

    return new Binding(
        m.getString("ActId"),
        m.getString("GenesisPubKey"),
        m.getString("OwnId"),
        m.getString("OwnPubKey"),
        parseLong(m.get("Version")),
        parseLong(m.get("NotBefore")),
        m.getString("GenesisSig"));
  }

  /**
   * Reconstructs a Binding from columns stored on the act row
   * ({@code ActDb.getBindingRow}). Derives OwnId from OwnPubKey.
   */
  public static Binding fromStored(final String actId,
                                   final String genesisPubKeyB64,
                                   final String ownPubKeyB64,
                                   final long   version,
                                   final long   notBefore,
                                   final String genesisSig)
  {
    final byte[] ownPub = Base64Encoder.decode(ownPubKeyB64);
    final String ownId  = AccountKeys.deriveOwnId(ownPub);
    return new Binding(actId, genesisPubKeyB64, ownId, ownPubKeyB64,
                       version, notBefore, genesisSig);
  }

  private static long parseLong(final Object obj)
  {
    if (obj instanceof Number)
      return ((Number) obj).longValue();
    if (obj instanceof String)
    {
      try
      {
        return Long.parseLong((String) obj);
      }
      catch (final NumberFormatException ignored)
      {
      }
    }
    return 0L;
  }
}
