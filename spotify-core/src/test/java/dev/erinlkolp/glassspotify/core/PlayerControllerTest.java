package dev.erinlkolp.glassspotify.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class PlayerControllerTest {

    private static final String PLAYING =
            "{\"is_playing\":true,\"item\":{\"name\":\"Black Hole Sun\","
            + "\"artists\":[{\"name\":\"Soundgarden\"}]}}";
    private static final String PAUSED =
            "{\"is_playing\":false,\"item\":{\"name\":\"Black Hole Sun\","
            + "\"artists\":[{\"name\":\"Soundgarden\"}]}}";

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private FakeHttpTransport http;
    private PlayerController controller;

    @Before
    public void setUp() throws IOException {
        http = new FakeHttpTransport();
        File tokenFile = new File(folder.getRoot(), "token");
        PrintWriter out = new PrintWriter(tokenFile, "UTF-8");
        out.print("refresh-token-1");
        out.close();

        TokenStore tokens = new TokenStore(
                tokenFile, "client-id", http, new FixedClock(1_000_000L));
        controller = new PlayerController(new SpotifyClient(http, tokens));

        http.enqueue(200, "{\"access_token\":\"AT1\",\"expires_in\":3600}");
    }

    @Test
    public void startsInNothingPlaying() {
        assertEquals(Status.NOTHING_PLAYING, controller.last().status);
    }

    @Test
    public void refreshStoresTheFetchedState() {
        http.enqueue(200, PLAYING);

        PlaybackState state = controller.refresh();

        assertEquals("Black Hole Sun", state.title);
        assertEquals("Black Hole Sun", controller.last().title);
    }

    @Test
    public void optimisticToggleFlipsImmediatelyWithoutAnyNetworkCall() {
        http.enqueue(200, PLAYING);
        controller.refresh();
        int callsBefore = http.urls().size();

        PlaybackState flipped = controller.toggleOptimistic();

        assertFalse(flipped.playing);
        assertEquals("Black Hole Sun", flipped.title);
        assertEquals("no network call", callsBefore, http.urls().size());
    }

    @Test
    public void commitAfterAnOptimisticPauseSendsPause() {
        http.enqueue(200, PLAYING);
        controller.refresh();
        controller.toggleOptimistic();

        http.enqueue(204, "");
        http.enqueue(200, PAUSED);
        controller.toggleCommit();

        assertEquals("https://api.spotify.com/v1/me/player/pause", http.urls().get(2));
    }

    @Test
    public void commitAfterAnOptimisticResumeSendsPlay() {
        http.enqueue(200, PAUSED);
        controller.refresh();
        controller.toggleOptimistic();

        http.enqueue(204, "");
        http.enqueue(200, PLAYING);
        controller.toggleCommit();

        assertEquals("https://api.spotify.com/v1/me/player/play", http.urls().get(2));
    }

    @Test
    public void commitRefetchesAndReturnsTheAuthoritativeState() {
        http.enqueue(200, PLAYING);
        controller.refresh();
        controller.toggleOptimistic();

        http.enqueue(204, "");
        http.enqueue(200, PAUSED);
        PlaybackState state = controller.toggleCommit();

        assertFalse(state.playing);
        assertEquals(Status.OK, state.status);
        assertEquals("https://api.spotify.com/v1/me/player", http.urls().get(3));
    }

    @Test
    public void aFailedCommandSurfacesItsStatusAndSkipsTheRefetch() {
        http.enqueue(200, PLAYING);
        controller.refresh();
        controller.toggleOptimistic();

        http.enqueue(404, "{\"error\":{\"reason\":\"NO_ACTIVE_DEVICE\"}}");
        PlaybackState state = controller.toggleCommit();

        assertEquals(Status.NO_DEVICE, state.status);
        assertEquals("no refetch after a failed command", 3, http.urls().size());
    }

    @Test
    public void anOptimisticFlipIsRevertedByTheAuthoritativeRefetch() {
        // The phone rejected the pause and is still playing. The UI must not stay wrong.
        http.enqueue(200, PLAYING);
        controller.refresh();
        controller.toggleOptimistic();
        assertFalse(controller.last().playing);

        http.enqueue(204, "");
        http.enqueue(200, PLAYING);
        controller.toggleCommit();

        assertTrue(controller.last().playing);
    }

    @Test
    public void nextSendsTheCommandThenRefetches() {
        http.enqueue(200, PLAYING);
        controller.refresh();

        http.enqueue(204, "");
        http.enqueue(200, PAUSED);
        controller.nextCommit();

        assertEquals("https://api.spotify.com/v1/me/player/next", http.urls().get(2));
        assertEquals("https://api.spotify.com/v1/me/player", http.urls().get(3));
    }

    @Test
    public void previousSendsTheCommandThenRefetches() {
        http.enqueue(200, PLAYING);
        controller.refresh();

        http.enqueue(204, "");
        http.enqueue(200, PAUSED);
        controller.previousCommit();

        assertEquals("https://api.spotify.com/v1/me/player/previous", http.urls().get(2));
    }

    @Test
    public void togglingWhileNothingIsPlayingSendsPlay() {
        // last() is NOTHING_PLAYING, so playing is false; the flip asks for play.
        controller.toggleOptimistic();

        http.enqueue(204, "");
        http.enqueue(200, PLAYING);
        controller.toggleCommit();

        assertEquals("https://api.spotify.com/v1/me/player/play", http.urls().get(1));
    }
}
