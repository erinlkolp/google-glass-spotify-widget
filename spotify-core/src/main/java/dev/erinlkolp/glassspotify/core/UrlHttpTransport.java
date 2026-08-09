package dev.erinlkolp.glassspotify.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.nio.charset.Charset;
import javax.net.ssl.HttpsURLConnection;

/** The only class in the project that performs real network I/O. */
public final class UrlHttpTransport implements HttpTransport {

    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final int CONNECT_TIMEOUT_MS = 10000;
    private static final int READ_TIMEOUT_MS = 10000;

    @Override
    public HttpResponse execute(String method, String url, String bearer, String formBody)
            throws IOException {
        HttpsURLConnection connection = (HttpsURLConnection) new URL(url).openConnection();
        // No unconditional connection.disconnect() in a finally here, deliberately:
        // disconnect() tears down the pooled socket, forcing a fresh TLS 1.2
        // handshake on the *next* poll — a real cost every 3 seconds on this
        // OMAP4430. A fully drained response stream is what actually returns a
        // connection to HttpsURLConnection's keep-alive pool, and readAll() below
        // always fully drains and closes whichever stream (input or error) it is
        // handed. `drained` tracks whether we got that far: on the normal path we
        // leave the connection alone so it can be reused; if something throws before
        // the stream is fully read, the connection is in an indeterminate state that
        // must not be pooled, so the finally block below disconnects it explicitly.
        // Do not change this back to an unconditional disconnect() in finally.
        boolean drained = false;
        try {
            connection.setSSLSocketFactory(Tls.socketFactory());
            connection.setRequestMethod(method);
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setInstanceFollowRedirects(false);

            if (bearer != null) {
                connection.setRequestProperty("Authorization", "Bearer " + bearer);
            }

            if (formBody != null) {
                byte[] payload = formBody.getBytes(UTF8);
                connection.setDoOutput(true);
                connection.setFixedLengthStreamingMode(payload.length);
                connection.setRequestProperty(
                        "Content-Type", "application/x-www-form-urlencoded");
                OutputStream out = connection.getOutputStream();
                try {
                    out.write(payload);
                    out.flush();
                } finally {
                    out.close();
                }
            } else if (!"GET".equals(method)) {
                // Spotify's PUT/POST player endpoints take no body, but some servers
                // and proxies reject a bodyless PUT without an explicit length.
                connection.setRequestProperty("Content-Length", "0");
            }

            int code = connection.getResponseCode();
            InputStream stream = (code >= 400)
                    ? connection.getErrorStream()
                    : connection.getInputStream();
            HttpResponse response = new HttpResponse(code, readAll(stream));
            drained = true;
            return response;
        } finally {
            if (!drained) {
                connection.disconnect();
            }
        }
    }

    private static String readAll(InputStream in) throws IOException {
        if (in == null) {
            return "";
        }
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            int read;
            while ((read = in.read(chunk)) != -1) {
                buffer.write(chunk, 0, read);
            }
            return new String(buffer.toByteArray(), UTF8);
        } finally {
            in.close();
        }
    }
}
