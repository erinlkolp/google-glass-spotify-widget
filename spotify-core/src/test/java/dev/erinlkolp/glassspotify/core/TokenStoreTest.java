package dev.erinlkolp.glassspotify.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.Charset;
import java.nio.file.Files;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class TokenStoreTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private File tokenFile;
    private FakeHttpTransport http;
    private FixedClock clock;

    @Before
    public void setUp() throws IOException {
        tokenFile = new File(folder.getRoot(), "token");
        http = new FakeHttpTransport();
        clock = new FixedClock(1_000_000L);
        write(tokenFile, "refresh-token-1");
    }

    private static void write(File file, String contents) throws IOException {
        PrintWriter out = new PrintWriter(file, "UTF-8");
        out.print(contents);
        out.close();
    }

    private static String read(File file) throws IOException {
        return new String(Files.readAllBytes(file.toPath()), Charset.forName("UTF-8")).trim();
    }

    private TokenStore newStore() {
        return new TokenStore(tokenFile, "client-id", http, clock);
    }

    @Test
    public void firstCallRefreshesAndReturnsTheAccessToken() throws Exception {
        http.enqueue(200, "{\"access_token\":\"AT1\",\"expires_in\":3600}");

        TokenStore store = newStore();

        assertEquals("AT1", store.accessToken());
        assertEquals("https://accounts.spotify.com/api/token", http.urls().get(0));
        assertEquals("POST", http.methods().get(0));
        assertTrue(http.bodies().get(0).contains("grant_type=refresh_token"));
        assertTrue(http.bodies().get(0).contains("refresh_token=refresh-token-1"));
        assertTrue(http.bodies().get(0).contains("client_id=client-id"));
    }

    @Test
    public void aRotatedRefreshTokenIsPersisted() throws Exception {
        http.enqueue(200,
                "{\"access_token\":\"AT1\",\"expires_in\":3600,\"refresh_token\":\"refresh-token-2\"}");

        newStore().accessToken();

        assertEquals("refresh-token-2", read(tokenFile));
    }

    @Test
    public void anAbsentRefreshTokenInTheResponseLeavesTheStoredOneAlone() throws Exception {
        http.enqueue(200, "{\"access_token\":\"AT1\",\"expires_in\":3600}");

        newStore().accessToken();

        assertEquals("refresh-token-1", read(tokenFile));
    }

    @Test
    public void theAccessTokenIsCachedUntilItNearsExpiry() throws Exception {
        http.enqueue(200, "{\"access_token\":\"AT1\",\"expires_in\":3600}");
        TokenStore store = newStore();

        assertEquals("AT1", store.accessToken());
        clock.advance(3000L * 1000L);
        assertEquals("AT1", store.accessToken());

        assertEquals("one refresh only", 1, http.urls().size());
    }

    @Test
    public void theAccessTokenIsRefreshedOnceInsideTheExpirySkew() throws Exception {
        http.enqueue(200, "{\"access_token\":\"AT1\",\"expires_in\":3600}");
        http.enqueue(200, "{\"access_token\":\"AT2\",\"expires_in\":3600}");
        TokenStore store = newStore();

        assertEquals("AT1", store.accessToken());
        // 3600s lifetime, 60s skew: at 3550s the cached token is inside the skew.
        clock.advance(3550L * 1000L);

        assertEquals("AT2", store.accessToken());
        assertEquals(2, http.urls().size());
    }

    @Test
    public void invalidateForcesTheNextCallToRefresh() throws Exception {
        http.enqueue(200, "{\"access_token\":\"AT1\",\"expires_in\":3600}");
        http.enqueue(200, "{\"access_token\":\"AT2\",\"expires_in\":3600}");
        TokenStore store = newStore();

        assertEquals("AT1", store.accessToken());
        store.invalidateAccessToken();

        assertEquals("AT2", store.accessToken());
    }

    @Test
    public void aRejectedRefreshTokenSurfacesAsNeedsReauth() throws Exception {
        http.enqueue(400, "{\"error\":\"invalid_grant\"}");

        try {
            newStore().accessToken();
            fail("expected SpotifyException");
        } catch (SpotifyException e) {
            assertEquals(Status.NEEDS_REAUTH, e.status);
        }
    }

    @Test
    public void aMissingTokenFileSurfacesAsNeedsReauth() throws Exception {
        assertTrue(tokenFile.delete());

        try {
            newStore().accessToken();
            fail("expected SpotifyException");
        } catch (SpotifyException e) {
            assertEquals(Status.NEEDS_REAUTH, e.status);
        }
    }

    @Test
    public void aDeadNetworkSurfacesAsNoNetwork() throws Exception {
        http.failWith(new java.net.UnknownHostException("accounts.spotify.com"));

        try {
            newStore().accessToken();
            fail("expected SpotifyException");
        } catch (SpotifyException e) {
            assertEquals(Status.NO_NETWORK, e.status);
        }
    }

    @Test
    public void anUnwritableTokenFileSurfacesAsTokenSaveFailedAndNotAsSuccess() throws Exception {
        // A rotated token that cannot be persisted must be fatal. Silently continuing
        // would leave the next run holding an already-invalidated refresh token.
        http.enqueue(200,
                "{\"access_token\":\"AT1\",\"expires_in\":3600,\"refresh_token\":\"refresh-token-2\"}");
        File readOnlyDir = folder.newFolder("locked");
        File lockedToken = new File(readOnlyDir, "token");
        write(lockedToken, "refresh-token-1");
        assertTrue(readOnlyDir.setWritable(false, false));

        TokenStore store = new TokenStore(lockedToken, "client-id", http, clock);
        try {
            store.accessToken();
            fail("expected SpotifyException");
        } catch (SpotifyException e) {
            assertEquals(Status.TOKEN_SAVE_FAILED, e.status);
        } finally {
            readOnlyDir.setWritable(true, false);
        }
    }

    @Test
    public void theTemporaryFileIsNotLeftBehindOnSuccess() throws Exception {
        http.enqueue(200,
                "{\"access_token\":\"AT1\",\"expires_in\":3600,\"refresh_token\":\"refresh-token-2\"}");

        newStore().accessToken();

        File[] leftovers = folder.getRoot().listFiles();
        assertEquals("only the token file should remain", 1, leftovers.length);
        assertEquals("token", leftovers[0].getName());
    }
}
