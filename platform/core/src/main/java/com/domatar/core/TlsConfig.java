/*
 * Copyright (c) 2024 Domatar
 */

package com.domatar.core;

import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import java.io.FileInputStream;
import java.security.KeyStore;
import java.util.logging.Logger;

/**
 * TLS configuration for outbound inter-provider HTTPS connections
 * (Spec-Security.txt PART 9.1 / Phase 6).
 *
 * <h2>Trust store</h2>
 * When {@code DOMATAR_TRUST_STORE_PATH} / {@code TrustStorePath} is
 * configured, the specified JKS/PKCS12 store is used as the trust anchor for
 * outbound TLS connections.  Provider certificates must chain to an entry in
 * this store.  When absent, the JVM default CA trust store is used (standard
 * CA-signed certificates just work; self-signed dev certs need to be in the
 * JVM's {@code cacerts}).
 *
 * <h2>Key store (mTLS)</h2>
 * When {@code DOMATAR_KEY_STORE_PATH} / {@code KeyStorePath} is configured,
 * the specified JKS/PKCS12 store is used as the client identity for outbound
 * mTLS connections (e.g. directory writes).  The key-store must contain the
 * provider's TLS client certificate and corresponding private key.
 *
 * <h2>Certificate provisioning</h2>
 * To create a self-signed client cert for a provider using the OpenSSL CLI:
 * <pre>
 *   openssl req -x509 -newkey ec -pkeyopt ec_paramgen_curve:P-256 \
 *       -keyout provider.key.pem -out provider.cert.pem -days 3650 -nodes \
 *       -subj "/CN=&lt;PrvId&gt;"
 *   openssl pkcs12 -export -in provider.cert.pem -inkey provider.key.pem \
 *       -out provider-keystore.p12 -name provider
 * </pre>
 * Then set {@code KeyStorePath=/path/to/provider-keystore.p12} and
 * {@code KeyStorePassword=&lt;password&gt;} in {@code provider.config.txt}.
 *
 * The directory node's trust store must contain every provider's cert (or a
 * shared CA cert) for mTLS to work:
 * <pre>
 *   keytool -importcert -file provider.cert.pem -alias &lt;PrvId&gt; \
 *       -keystore directory-truststore.jks -storepass &lt;password&gt;
 * </pre>
 *
 * <h2>Dev mode</h2>
 * Set {@code WireScheme=http} in {@code provider.config.txt} to bypass TLS
 * entirely for local same-host simulation.  See {@link DomatarConfig#getWireScheme()}.
 */
public class TlsConfig
{
    private static final Logger LOG = Logger.getLogger(TlsConfig.class.getName());

    private static volatile SSLSocketFactory cached = null;

    private TlsConfig() {}

    /**
     * Returns an {@link SSLSocketFactory} configured from
     * {@code provider.config.txt} / environment variables.
     *
     * <ul>
     *   <li>If neither trust store nor key store is configured, returns
     *       {@code null} — the caller should use the JVM default (do not call
     *       {@code setSSLSocketFactory(null)} on {@code HttpsURLConnection};
     *       just skip the call).</li>
     *   <li>On first call the factory is built and cached for subsequent
     *       requests.</li>
     *   <li>Any configuration or I/O error is logged and {@code null}
     *       is returned (falling back to JVM defaults).</li>
     * </ul>
     */
    public static SSLSocketFactory getSocketFactory()
    {
        if (cached != null)
            return cached;

        synchronized (TlsConfig.class)
        {
            if (cached != null)
                return cached;

            final String trustPath     = DomatarConfig.getTrustStorePath();
            final String trustPassword = DomatarConfig.getTrustStorePassword();
            final String keyPath       = DomatarConfig.getKeyStorePath();
            final String keyPassword   = DomatarConfig.getKeyStorePassword();

            if (trustPath == null && keyPath == null)
                return null; // use JVM defaults

            try
            {
                final TrustManager[] trustManagers = buildTrustManagers(trustPath, trustPassword);
                final KeyManager[]   keyManagers   = buildKeyManagers(keyPath, keyPassword);

                final SSLContext ctx = SSLContext.getInstance("TLSv1.3");
                ctx.init(keyManagers, trustManagers, null);

                cached = ctx.getSocketFactory();

                LOG.info("TlsConfig: custom SSLSocketFactory built"
                    + (trustPath != null ? " (trustStore=" + trustPath + ")" : "")
                    + (keyPath   != null ? " (keyStore="   + keyPath   + ")" : ""));

                return cached;
            }
            catch (final Exception e)
            {
                LOG.warning("TlsConfig: failed to build SSLSocketFactory, falling back to JVM defaults: " + e);
                return null;
            }
        }
    }

    /**
     * Forces the cached factory to be rebuilt on the next call to
     * {@link #getSocketFactory()}.  Useful after configuration changes in tests.
     */
    public static synchronized void reset()
    {
        cached = null;
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private static TrustManager[] buildTrustManagers(final String path, final String password)
        throws Exception
    {
        if (path == null)
            return null; // JVM default trust managers

        final KeyStore ks = loadKeyStore(path, password);
        final TrustManagerFactory tmf = TrustManagerFactory.getInstance(
            TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(ks);
        return tmf.getTrustManagers();
    }

    private static KeyManager[] buildKeyManagers(final String path, final String password)
        throws Exception
    {
        if (path == null)
            return null; // no client cert (non-mTLS mode)

        final KeyStore ks = loadKeyStore(path, password);
        final KeyManagerFactory kmf = KeyManagerFactory.getInstance(
            KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, password != null ? password.toCharArray() : new char[0]);
        return kmf.getKeyManagers();
    }

    private static KeyStore loadKeyStore(final String path, final String password)
        throws Exception
    {
        final char[] pwdChars = password != null ? password.toCharArray() : new char[0];

        // Auto-detect PKCS12 vs JKS by file extension.
        final String type = path.toLowerCase().endsWith(".p12")
                         || path.toLowerCase().endsWith(".pfx")
                            ? "PKCS12" : "JKS";

        final KeyStore ks = KeyStore.getInstance(type);

        try (final FileInputStream fis = new FileInputStream(path))
        {
            ks.load(fis, pwdChars);
        }

        return ks;
    }
}
