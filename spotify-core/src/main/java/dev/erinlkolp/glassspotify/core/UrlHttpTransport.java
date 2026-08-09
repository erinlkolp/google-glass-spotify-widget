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
            return new HttpResponse(code, readAll(stream));
        } finally {
            connection.disconnect();
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
