package spike;

import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URL;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * Answers one question: can this 2015-era AOSP 5.1.1 trust store complete a TLS
 * handshake with Spotify's endpoints?
 *
 * <p>If yes, TlsFactory and the bundled PEM anchors come out of the design. If no,
 * the printed issuer chain tells us exactly which roots to bundle.
 *
 * <p>No enums anywhere — d8 8.2.2-dev NPEs on enums compiled by JDK 21 javac.
 */
public final class TlsProbe {

    private TlsProbe() {
    }

    public static void main(String[] args) {
        System.out.println("java.version = " + System.getProperty("java.version"));
        System.out.println("vm          = " + System.getProperty("java.vm.name"));
        System.out.println("ssl.provider= " + java.security.Security.getProperty("ssl.SocketFactory.provider"));

        handshake("api.spotify.com");
        handshake("accounts.spotify.com");

        httpGet("https://api.spotify.com/v1/me");
        httpGet("https://accounts.spotify.com/api/token");

        System.out.println();
        System.out.println("PROBE COMPLETE");
    }

    /** Raw socket handshake: reports negotiated protocol, cipher, and the served chain. */
    private static void handshake(String host) {
        System.out.println();
        System.out.println("=== HANDSHAKE " + host + ":443");
        SSLSocket socket = null;
        try {
            SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
            socket = (SSLSocket) factory.createSocket();
            socket.connect(new InetSocketAddress(host, 443), 15000);
            socket.setSoTimeout(15000);

            System.out.println("enabled protocols = " + join(socket.getEnabledProtocols()));

            socket.startHandshake();

            SSLSession session = socket.getSession();
            System.out.println("RESULT   = OK");
            System.out.println("protocol = " + session.getProtocol());
            System.out.println("cipher   = " + session.getCipherSuite());

            Certificate[] chain = session.getPeerCertificates();
            System.out.println("chain length = " + chain.length + " (server-sent; root usually omitted)");
            for (int i = 0; i < chain.length; i++) {
                if (!(chain[i] instanceof X509Certificate)) {
                    continue;
                }
                X509Certificate cert = (X509Certificate) chain[i];
                System.out.println("  [" + i + "] subject = " + cert.getSubjectDN());
                System.out.println("      issuer  = " + cert.getIssuerDN());
                System.out.println("      expires = " + cert.getNotAfter());
            }
        } catch (Throwable t) {
            System.out.println("RESULT   = FAIL");
            System.out.println("exception= " + t.getClass().getName());
            System.out.println("message  = " + t.getMessage());
            Throwable cause = t.getCause();
            int guard = 0;
            while (cause != null && guard < 5) {
                System.out.println("  caused by " + cause.getClass().getName() + ": " + cause.getMessage());
                cause = cause.getCause();
                guard++;
            }
        } finally {
            if (socket != null) {
                try {
                    socket.close();
                } catch (Throwable ignored) {
                    // closing a failed socket is not interesting
                }
            }
        }
    }

    /** Real HTTP request. 401/400/405 all count as success — they prove TLS completed. */
    private static void httpGet(String spec) {
        System.out.println();
        System.out.println("=== GET " + spec);
        HttpsURLConnection conn = null;
        try {
            URL url = new URL(spec);
            conn = (HttpsURLConnection) url.openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);
            conn.setRequestMethod("GET");

            int code = conn.getResponseCode();
            System.out.println("RESULT = OK (TLS completed)");
            System.out.println("HTTP   = " + code);
            System.out.println("cipher = " + conn.getCipherSuite());

            InputStream in = (code >= 400) ? conn.getErrorStream() : conn.getInputStream();
            System.out.println("body   = " + readSome(in));
        } catch (Throwable t) {
            System.out.println("RESULT = FAIL");
            System.out.println("exception= " + t.getClass().getName());
            System.out.println("message  = " + t.getMessage());
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private static String readSome(InputStream in) {
        if (in == null) {
            return "(none)";
        }
        try {
            byte[] buffer = new byte[512];
            int total = 0;
            int read;
            while (total < buffer.length
                    && (read = in.read(buffer, total, buffer.length - total)) != -1) {
                total += read;
            }
            return new String(buffer, 0, total, "UTF-8").replace('\n', ' ');
        } catch (Throwable t) {
            return "(read failed: " + t.getMessage() + ")";
        } finally {
            try {
                in.close();
            } catch (Throwable ignored) {
                // nothing useful to do
            }
        }
    }

    private static String join(String[] values) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                out.append(", ");
            }
            out.append(values[i]);
        }
        return out.toString();
    }
}
