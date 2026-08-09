package dev.erinlkolp.glassspotify.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class SpotifyClientTest {

    private static final String PLAYING_JSON =
            "{\"is_playing\":true,\"item\":{\"name\":\"Black Hole Sun\","
            + "\"artists\":[{\"name\":\"Soundgarden\"},{\"name\":\"Chris Cornell\"}]}}";

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private FakeHttpTransport http;
    private SpotifyClient client;

    @Before
    public void setUp() throws IOException {
        http = new FakeHttpTransport();
        File tokenFile = new File(folder.getRoot(), "token");
        PrintWriter out = new PrintWriter(tokenFile, "UTF-8");
        out.print("refresh-token-1");
        out.close();

        TokenStore tokens = new TokenStore(
                tokenFile, "client-id", http, new FixedClock(1_000_000L));
        client = new SpotifyClient(http, tokens);

        // Every test starts by satisfying the token refresh.
        http.enqueue(200, "{\"access_token\":\"AT1\",\"expires_in\":3600}");
    }

    @Test
    public void parsesTitleArtistAndPlayingFlag() throws Exception {
        http.enqueue(200, PLAYING_JSON);

        PlaybackState state = client.currentState();

        assertEquals(Status.OK, state.status);
        assertEquals("Black Hole Sun", state.title);
        assertEquals("Soundgarden", state.artist);
        assertTrue(state.playing);
    }

    @Test
    public void usesTheFirstArtistWhenThereAreSeveral() throws Exception {
        http.enqueue(200, PLAYING_JSON);

        assertEquals("Soundgarden", client.currentState().artist);
    }

    @Test
    public void sendsTheBearerTokenToTheCorrectEndpoint() throws Exception {
        http.enqueue(200, PLAYING_JSON);

        client.currentState();

        assertEquals("https://api.spotify.com/v1/me/player", http.urls().get(1));
        assertEquals("GET", http.methods().get(1));
        assertEquals("AT1", http.bearers().get(1));
    }

    @Test
    public void pausedPlaybackReportsNotPlayingButKeepsTheTrack() throws Exception {
        http.enqueue(200,
                "{\"is_playing\":false,\"item\":{\"name\":\"Spoonman\","
                + "\"artists\":[{\"name\":\"Soundgarden\"}]}}");

        PlaybackState state = client.currentState();

        assertEquals(Status.OK, state.status);
        assertEquals("Spoonman", state.title);
        assertEquals(false, state.playing);
    }

    @Test
    public void a204MeansNothingIsPlaying() throws Exception {
        http.enqueue(204, "");

        assertEquals(Status.NOTHING_PLAYING, client.currentState().status);
    }

    @Test
    public void anEmptyBodyWithA200AlsoMeansNothingIsPlaying() throws Exception {
        // Spotify occasionally answers 200 with an empty body instead of 204.
        http.enqueue(200, "");

        assertEquals(Status.NOTHING_PLAYING, client.currentState().status);
    }

    @Test
    public void a200WithNoItemMeansNothingIsPlaying() throws Exception {
        http.enqueue(200, "{\"is_playing\":false}");

        assertEquals(Status.NOTHING_PLAYING, client.currentState().status);
    }

    @Test
    public void a404MeansNoActiveDevice() throws Exception {
        http.enqueue(404, "{\"error\":{\"status\":404,\"reason\":\"NO_ACTIVE_DEVICE\"}}");

        assertEquals(Status.NO_DEVICE, client.currentState().status);
    }

    @Test
    public void a403MeansNotPermitted() throws Exception {
        http.enqueue(403, "{\"error\":{\"status\":403}}");

        assertEquals(Status.NOT_PERMITTED, client.currentState().status);
    }

    @Test
    public void a429MeansRateLimited() throws Exception {
        http.enqueue(429, "");

        assertEquals(Status.RATE_LIMITED, client.currentState().status);
    }

    @Test
    public void aDeadNetworkMeansNoNetwork() throws Exception {
        http.failWith(new java.net.UnknownHostException("api.spotify.com"));

        assertEquals(Status.NO_NETWORK, client.currentState().status);
    }

    @Test
    public void aTlsFailureIsReportedDistinctlyFromAGeneralNetworkFailure() throws Exception {
        http.failWith(new javax.net.ssl.SSLHandshakeException("no trust anchor"));

        assertEquals(Status.TLS_FAILED, client.currentState().status);
    }

    @Test
    public void malformedJsonDoesNotEscapeAsAnException() throws Exception {
        http.enqueue(200, "{not json at all");

        assertEquals(Status.UNKNOWN, client.currentState().status);
    }
}
