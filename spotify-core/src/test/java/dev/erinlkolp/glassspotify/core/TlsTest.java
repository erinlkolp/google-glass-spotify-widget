package dev.erinlkolp.glassspotify.core;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;

import javax.net.ssl.SSLSocketFactory;
import org.junit.Test;

public class TlsTest {

    /**
     * Pins the singleton, because losing it is silent.
     *
     * <p>API 22's HttpsURLConnection is backed by com.android.okhttp, which keys its
     * connection pool on an Address that includes the SSLSocketFactory, compared by
     * reference. A per-request factory therefore misses the pool entirely and every
     * request pays a full TLS 1.2 handshake — on a 3-second poll loop that is a real
     * battery cost, and nothing about it looks wrong from the outside.
     */
    @Test
    public void socketFactoryIsASingletonSoConnectionPoolingWorks() {
        assertSame(Tls.socketFactory(), Tls.socketFactory());
    }

    @Test
    public void socketFactoryIsUsable() {
        SSLSocketFactory factory = Tls.socketFactory();

        assertNotNull(factory);
        assertNotNull(factory.getDefaultCipherSuites());
        assertNotNull(factory.getSupportedCipherSuites());
    }

    /** The delegate's suites must pass through untouched; only protocols are narrowed. */
    @Test
    public void cipherSuitesAreDelegatedRatherThanRestricted() {
        SSLSocketFactory delegate = (SSLSocketFactory) SSLSocketFactory.getDefault();

        assertArrayEquals(
                delegate.getDefaultCipherSuites(),
                Tls.socketFactory().getDefaultCipherSuites());
        assertArrayEquals(
                delegate.getSupportedCipherSuites(),
                Tls.socketFactory().getSupportedCipherSuites());
    }
}
