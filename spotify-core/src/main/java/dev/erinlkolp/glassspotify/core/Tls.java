package dev.erinlkolp.glassspotify.core;

import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.net.UnknownHostException;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * An {@link SSLSocketFactory} that speaks only TLS 1.2.
 *
 * <p>The stock trust store on this ROM reaches Spotify without help — measured
 * on-device 2026-08-09, chain terminating at DigiCert Global Root G2 (issued 2013, so
 * present in the 2015 store). So no custom trust anchors are needed and none are
 * installed here; the platform's own verification stays fully in force.
 *
 * <p>What this class does fix is that API 22 still advertises SSLv3 and TLSv1.0 by
 * default. Spotify will not negotiate those, but narrowing the offer removes the
 * question entirely for a handful of lines.
 */
public final class Tls {

    private static final String[] PROTOCOLS = { "TLSv1.2" };

    /**
     * One shared instance, deliberately.
     *
     * <p>This must be a singleton or connection pooling silently stops working.
     * API 22's {@code HttpsURLConnection} is backed by com.android.okhttp, which
     * includes the {@code SSLSocketFactory} in the {@code Address} it uses as the
     * connection-pool key, compared by reference since this class defines no
     * {@code equals}. Returning a fresh factory per request therefore produces a
     * fresh {@code Address} that matches nothing in the pool, so every request pays a
     * full TLS 1.2 handshake — meaningful on a 3-second poll loop against this
     * device's battery. Returning the same instance lets the pool do its job.
     *
     * <p>Safe to share: the factory is stateless, and {@link SSLSocketFactory}
     * implementations are required to be thread-safe.
     */
    private static final SSLSocketFactory INSTANCE =
            new Tls12SocketFactory((SSLSocketFactory) SSLSocketFactory.getDefault());

    private Tls() {
    }

    /** @return the shared TLS 1.2 factory. Never construct a second one — see {@link #INSTANCE}. */
    public static SSLSocketFactory socketFactory() {
        return INSTANCE;
    }

    private static final class Tls12SocketFactory extends SSLSocketFactory {

        private final SSLSocketFactory delegate;

        Tls12SocketFactory(SSLSocketFactory delegate) {
            this.delegate = delegate;
        }

        private Socket restrict(Socket socket) {
            if (socket instanceof SSLSocket) {
                ((SSLSocket) socket).setEnabledProtocols(PROTOCOLS);
            }
            return socket;
        }

        @Override
        public String[] getDefaultCipherSuites() {
            return delegate.getDefaultCipherSuites();
        }

        @Override
        public String[] getSupportedCipherSuites() {
            return delegate.getSupportedCipherSuites();
        }

        @Override
        public Socket createSocket(Socket s, String host, int port, boolean autoClose)
                throws IOException {
            return restrict(delegate.createSocket(s, host, port, autoClose));
        }

        @Override
        public Socket createSocket(String host, int port)
                throws IOException, UnknownHostException {
            return restrict(delegate.createSocket(host, port));
        }

        @Override
        public Socket createSocket(String host, int port, InetAddress localHost, int localPort)
                throws IOException, UnknownHostException {
            return restrict(delegate.createSocket(host, port, localHost, localPort));
        }

        @Override
        public Socket createSocket(InetAddress host, int port) throws IOException {
            return restrict(delegate.createSocket(host, port));
        }

        @Override
        public Socket createSocket(InetAddress address, int port,
                InetAddress localAddress, int localPort) throws IOException {
            return restrict(delegate.createSocket(address, port, localAddress, localPort));
        }
    }
}
