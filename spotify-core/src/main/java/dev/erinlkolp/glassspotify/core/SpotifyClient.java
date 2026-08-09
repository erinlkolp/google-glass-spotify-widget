package dev.erinlkolp.glassspotify.core;

import java.io.IOException;
import javax.net.ssl.SSLException;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Talks to the Spotify Web API.
 *
 * <p>Commands are relayed by Spotify Connect to whichever device is currently active,
 * so no device id is ever sent — the phone is simply whatever is playing.
 *
 * <p>Never throws for an expected failure. Everything the user could plausibly hit
 * comes back as a {@link Status} on a {@link PlaybackState}, because the caller's
 * only job is to render one line on the prism.
 */
public final class SpotifyClient {

    private static final String PLAYER = "https://api.spotify.com/v1/me/player";

    private final HttpTransport transport;
    private final TokenStore tokens;

    public SpotifyClient(HttpTransport transport, TokenStore tokens) {
        if (transport == null || tokens == null) {
            throw new IllegalArgumentException("transport and tokens are required");
        }
        this.transport = transport;
        this.tokens = tokens;
    }

    /** @return the current playback snapshot, or a state carrying why it is unavailable. */
    public PlaybackState currentState() {
        HttpResponse response;
        try {
            response = send("GET", PLAYER);
        } catch (SpotifyException e) {
            return PlaybackState.of(e.status);
        }

        Status failure = mapFailure(response.code);
        if (failure != null) {
            return PlaybackState.of(failure);
        }
        if (response.code == 204 || response.body.length() == 0) {
            return PlaybackState.of(Status.NOTHING_PLAYING);
        }

        try {
            JSONObject root = new JSONObject(response.body);
            JSONObject item = root.optJSONObject("item");
            if (item == null) {
                return PlaybackState.of(Status.NOTHING_PLAYING);
            }
            String title = item.optString("name", "");
            String artist = firstArtist(item);
            boolean playing = root.optBoolean("is_playing", false);
            return PlaybackState.playing(title, artist, playing);
        } catch (JSONException e) {
            return PlaybackState.of(Status.UNKNOWN);
        }
    }

    private static String firstArtist(JSONObject item) {
        JSONArray artists = item.optJSONArray("artists");
        if (artists == null || artists.length() == 0) {
            return "";
        }
        JSONObject first = artists.optJSONObject(0);
        return first == null ? "" : first.optString("name", "");
    }

    /**
     * Performs the request, refreshing the access token and retrying exactly once on a
     * 401. One retry only: a second 401 means the refresh itself is not helping.
     */
    private HttpResponse send(String method, String url) throws SpotifyException {
        HttpResponse response = sendOnce(method, url);
        if (response.code == 401) {
            tokens.invalidateAccessToken();
            response = sendOnce(method, url);
        }
        return response;
    }

    private HttpResponse sendOnce(String method, String url) throws SpotifyException {
        String bearer = tokens.accessToken();
        try {
            return transport.execute(method, url, bearer, null);
        } catch (SSLException e) {
            throw new SpotifyException(Status.TLS_FAILED, e);
        } catch (IOException e) {
            throw new SpotifyException(Status.NO_NETWORK, e);
        }
    }

    /** @return the status for a failing code, or null if the code is a success. */
    private static Status mapFailure(int code) {
        if (code >= 200 && code < 300) {
            return null;
        }
        switch (code) {
            case 401:
                return Status.NEEDS_REAUTH;
            case 403:
                return Status.NOT_PERMITTED;
            case 404:
                return Status.NO_DEVICE;
            case 429:
                return Status.RATE_LIMITED;
            default:
                return Status.UNKNOWN;
        }
    }
}
