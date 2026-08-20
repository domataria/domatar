/*
 * Copyright (c) 2024 Domatar
 */

package com.domatar.core;

/**
 * Per-request thread-local data propagated from the servlet tier to handlers.
 *
 * <h2>mTLS client certificate (Phase 6)</h2>
 * When a Tomcat Connector is configured for mutual TLS, the inbound client
 * certificate chain is available via the servlet request attribute
 * {@code javax.servlet.request.X509Certificate}.  {@code Msg.doAction} extracts
 * the leaf certificate's DER-encoded public key bytes and stores them here so
 * that handlers (e.g. {@code HstsImpl.updateHst}) can verify the caller's
 * identity without access to the original {@code HttpServletRequest}.
 *
 * <p><b>Lifecycle:</b> {@code Msg.doAction} calls {@link #clear()} at the start
 * of every request (before any handler runs) and again in a {@code finally} block
 * after dispatch, ensuring no cross-request leakage.
 *
 * <p><b>Thread safety:</b> all state is stored in a {@link ThreadLocal}; no
 * external synchronization is needed.
 */
public class RequestContext
{
    private RequestContext() {}

    /**
     * DER-encoded public key bytes of the TLS client certificate's leaf
     * certificate, or {@code null} if the request arrived without a client
     * certificate (HTTP mode or one-way TLS).
     *
     * <p>The encoding is the raw output of {@code X509Certificate.getPublicKey()
     * .getEncoded()} (X.509 SubjectPublicKeyInfo format for Ed25519 and P-256 /
     * RSA keys alike).
     */
    private static final ThreadLocal<byte[]> clientCertPubKeyEncoded =
        new ThreadLocal<>();

    // -------------------------------------------------------------------------
    // Setters / getters
    // -------------------------------------------------------------------------

    /**
     * Stores the leaf TLS client certificate's DER-encoded public key for this
     * request.  Call from {@code Msg.doAction} before dispatching.
     *
     * @param encoded {@code X509Certificate.getPublicKey().getEncoded()} output,
     *                or {@code null} to clear
     */
    public static void setClientCertPubKeyEncoded(final byte[] encoded)
    {
        clientCertPubKeyEncoded.set(encoded);
    }

    /**
     * Returns the DER-encoded public key of the TLS client certificate for
     * this request, or {@code null} if none was presented.
     */
    public static byte[] getClientCertPubKeyEncoded()
    {
        return clientCertPubKeyEncoded.get();
    }

    /**
     * Clears all per-request state.  Must be called in a {@code finally} block
     * at the end of every request to avoid memory leaks in thread-pool
     * environments.
     */
    public static void clear()
    {
        clientCertPubKeyEncoded.remove();
    }
}
