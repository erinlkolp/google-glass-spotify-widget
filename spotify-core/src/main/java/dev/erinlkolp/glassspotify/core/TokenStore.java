package dev.erinlkolp.glassspotify.core;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import javax.net.ssl.SSLException;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Holds the PKCE refresh token and mints access tokens from it.
 *
 * <p><b>Rotation is the dangerous part.</b> Spotify's PKCE flow returns a new refresh
 * token on most refreshes and invalidates the one just used. If a rotated token were
 * lost — a partial write, a crash mid-save — the app would be permanently locked out
 * and the laptop bootstrap would have to be repeated. So the new token is written
 * atomically and persisted <em>before</em> the access token it arrived with is handed
 * to any caller, and a failed write is fatal rather than swallowed.
 */
public final class TokenStore {

    private static final String TOKEN_URL = "https://accounts.spotify.com/api/token";
    private static final Charset UTF8 = Charset.forName("UTF-8");
    /** Refresh this long before nominal expiry, so a request never races the deadline. */
    private static final long EXPIRY_SKEW_MS = 60L * 1000L;

    private final File tokenFile;
    private final String clientId;
    private final HttpTransport transport;
    private final Clock clock;

    private String accessToken;
    private long accessTokenExpiresAtMs;

    public TokenStore(File tokenFile, String clientId, HttpTransport transport, Clock clock) {
        if (tokenFile == null || clientId == null || transport == null || clock == null) {
            throw new IllegalArgumentException("all constructor arguments are required");
        }
        this.tokenFile = tokenFile;
        this.clientId = clientId;
        this.transport = transport;
        this.clock = clock;
    }

    /** @return a valid access token, refreshing first if the cached one is stale. */
    public String accessToken() throws SpotifyException {
        if (accessToken != null && clock.nowMs() + EXPIRY_SKEW_MS < accessTokenExpiresAtMs) {
            return accessToken;
        }
        refresh();
        return accessToken;
    }

    /** Discards the cached access token so the next call refreshes. Used after a 401. */
    public void invalidateAccessToken() {
        accessToken = null;
        accessTokenExpiresAtMs = 0L;
    }

    private void refresh() throws SpotifyException {
        String refreshToken = readRefreshToken();

        HttpResponse response;
        try {
            response = transport.execute("POST", TOKEN_URL, null, refreshBody(refreshToken));
        } catch (SSLException e) {
            throw new SpotifyException(Status.TLS_FAILED, e);
        } catch (IOException e) {
            throw new SpotifyException(Status.NO_NETWORK, e);
        }

        if (response.code == 400 || response.code == 401) {
            // invalid_grant: the refresh token is dead. Only the laptop can fix this.
            throw new SpotifyException(Status.NEEDS_REAUTH);
        }
        if (response.code != 200) {
            throw new SpotifyException(Status.UNKNOWN);
        }

        JSONObject json;
        String newAccessToken;
        long expiresInSeconds;
        try {
            json = new JSONObject(response.body);
            newAccessToken = json.getString("access_token");
            expiresInSeconds = json.optLong("expires_in", 3600L);
        } catch (JSONException e) {
            throw new SpotifyException(Status.UNKNOWN, e);
        }

        // Persist any rotated token BEFORE publishing the access token it came with.
        // has() rather than optString(name, null): Android's org.json and the JVM's
        // differ on what a missing key yields, and this decision must not be ambiguous.
        String rotated = json.has("refresh_token") ? json.optString("refresh_token", "") : "";
        if (rotated.length() > 0 && !rotated.equals(refreshToken)) {
            writeRefreshTokenAtomically(rotated);
        }

        accessToken = newAccessToken;
        accessTokenExpiresAtMs = clock.nowMs() + (expiresInSeconds * 1000L);
    }

    private String refreshBody(String refreshToken) {
        StringBuilder body = new StringBuilder();
        body.append("grant_type=refresh_token");
        body.append("&refresh_token=").append(encode(refreshToken));
        body.append("&client_id=").append(encode(clientId));
        return body.toString();
    }

    private static String encode(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (IOException e) {
            // UTF-8 is always present; this cannot happen.
            throw new IllegalStateException(e);
        }
    }

    private String readRefreshToken() throws SpotifyException {
        try {
            String contents = readFile(tokenFile).trim();
            if (contents.length() == 0) {
                throw new SpotifyException(Status.NEEDS_REAUTH);
            }
            return contents;
        } catch (IOException e) {
            throw new SpotifyException(Status.NEEDS_REAUTH, e);
        }
    }

    /**
     * Reads a small file without {@code java.nio.file}, which is API 26+ and would
     * throw NoClassDefFoundError on this API 22 device despite compiling cleanly here.
     */
    private static String readFile(File file) throws IOException {
        FileInputStream in = new FileInputStream(file);
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[1024];
            int read;
            while ((read = in.read(chunk)) != -1) {
                buffer.write(chunk, 0, read);
            }
            return new String(buffer.toByteArray(), UTF8);
        } finally {
            in.close();
        }
    }

    /**
     * Writes to a sibling temp file, then renames. Rename is atomic on the same
     * filesystem, so a reader can never observe a half-written token.
     */
    private void writeRefreshTokenAtomically(String token) throws SpotifyException {
        File temp = new File(tokenFile.getParentFile(), tokenFile.getName() + ".tmp");
        try {
            Writer writer = new OutputStreamWriter(new FileOutputStream(temp), UTF8);
            try {
                writer.write(token);
                writer.flush();
            } finally {
                writer.close();
            }
            if (!temp.renameTo(tokenFile)) {
                throw new IOException("rename failed: " + temp + " -> " + tokenFile);
            }
        } catch (IOException e) {
            temp.delete();
            throw new SpotifyException(Status.TOKEN_SAVE_FAILED, e);
        }
    }
}
