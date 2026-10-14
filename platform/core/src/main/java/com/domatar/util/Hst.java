/*
 * Copyright (c) 2024 Domatar
 */

package com.domatar.util;

/**
 * One directory row. {@code prvId} is the host that physically provides
 * this one. {@code version == 0} and {@code fetchedAt == 0} mean
 * unknown. {@code pubKey} is null on a sub-host.
 */
public class Hst
{
  public final String hstId;     // Host Id
  public final String domain;    // Domain
  public final String prvId;     // Provider Id (a hstId; the host that physically provides this one)
  public final long   version;   // Monotonic version of the directory record (0 = unknown).
  public final long   fetchedAt; // Wall-clock millis when this row was last refreshed (0 = unknown).
  /** Provider operational Ed25519 public key (Base64Encoder, 43 chars). Null for sub-hosts. Phase 3+. */
  public final String pubKey;
  /** Directory-root Ed25519 signature over canonical {HstId,Domain,PrvId,Version,PubKey}. Phase 3+. */
  public final String recordSig;

  public Hst (String hstId,
              String domain,
              String prvId)
  {
    this(hstId, domain, prvId, 0L, 0L, null, null);
  }

  public Hst (String hstId,
              String domain,
              String prvId,
              long   version,
              long   fetchedAt)
  {
    this(hstId, domain, prvId, version, fetchedAt, null, null);
  }

  public Hst (String hstId,
              String domain,
              String prvId,
              long   version,
              long   fetchedAt,
              String pubKey,
              String recordSig)
  {
    this.hstId     = hstId;
    this.domain    = domain;
    this.prvId     = prvId;
    this.version   = version;
    this.fetchedAt = fetchedAt;
    this.pubKey    = pubKey;
    this.recordSig = recordSig;
  }

  /**
   * Part of a sub-host id after the first {@link DomId#HOST_SEP}.
   * For {@code app-actId} that is the actId. For a replica
   * {@code app-actId-prvId} it is {@code actId-prvId}, not the actId;
   * use {@link DomId#replicaActId} for that shape. Returns the whole
   * id when there is no separator.
   */
  public String getSubHst()
  {
    final int i = hstId.indexOf(DomId.HOST_SEP.charAt(0));

    return (i >= 0) ? hstId.substring(i + 1) : hstId;
  }
}
