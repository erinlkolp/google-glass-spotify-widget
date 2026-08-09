# Glass Spotify Widget Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A Google Glass app that appears in the Gesture Launcher as "Spotify" and controls playback on the phone — play, pause, next, previous, plus a now-playing title and artist.

**Architecture:** The Glass talks directly to the Spotify Web API over HTTPS/WiFi. Spotify Connect relays commands cloud-side to the phone, so there is no companion app and no Bluetooth. Two Gradle modules: `spotify-core` (plain `java-library`, all network and token logic, fully JVM-testable) and `app` (thin Android UI). Gesture recognition is reused from the Gesture Launcher's `gesture-core` via a path include.

**Tech Stack:** Java 8, Gradle Kotlin DSL, AGP 8.7.0, `compileSdk` 34 / `minSdk` 22 / `targetSdk` 22, `HttpsURLConnection`, `org.json` (platform-provided), JUnit 4.13.2. Python 3 stdlib for the one-time token bootstrap.

**Spec:** `docs/superpowers/specs/2026-08-07-glass-spotify-widget-design.md`

## Global Constraints

These apply to every task. Do not restate them per-task; do not violate them.

- **No third-party code in the APK.** `org.json` is `compileOnly` + `testImplementation` only — Android provides it at runtime. No OkHttp, no Gson, no Retrofit.
- **Java 8 source/target.** `spotify-core` uses `options.release.set(8)`. The `app` module uses `compileOptions { sourceCompatibility / targetCompatibility = JavaVersion.VERSION_1_8 }`, because AGP emits `-source`/`-target` rather than `--release`.
- **No `java.time`.** It is API 26+; this device is API 22. Use `long` milliseconds and the project's own `Clock` interface.
- **No `java.nio.file` in main source.** `Files`, `Path`, and `File.toPath()` are all API 26+. `spotify-core` compiles against the JDK, so these compile cleanly and then throw `NoClassDefFoundError` on the device — a failure that no JVM test can catch. Use `FileInputStream`/`FileOutputStream`. Test sources may use `java.nio.file` freely, since they only ever run on the JVM.
- **No `java.util.function`, no lambdas, no streams.** API 22 with Java 8 desugaring is unreliable here; use explicit anonymous classes and loops.
- **Enums are allowed** in `spotify-core` and `app`. Gotcha #5 (d8 NPE on JDK 21-compiled enums) applies only to the standalone `d8` binary used for spike code, not to AGP builds. Verified 2026-08-09.
- **Never weaken TLS.** No `ALLOW_ALL_HOSTNAME_VERIFIER`, no trust-everything `X509TrustManager`. The stock trust store works (measured 2026-08-09).
- **Display is pure black background, pure white text.** No mid-tones, no gradients, no album art. 320x180 dp usable.
- **All gesture math happens in touchpad-native units, never screen pixels.** This is delegated entirely to `gesture-core`; do not re-derive it.
- **An unrecognised gesture must be a no-op.** Never fall back to a default action.
- Package root is `dev.erinlkolp.glassspotify`. Application id `dev.erinlkolp.glassspotify`.
- Glass adb serial is `0123456789ABCDEF`. Enable WiFi with `adb -s 0123456789ABCDEF shell svc wifi enable` before any on-device network test.

---

## File Structure

```
google-glass-spotify-widget/
├── settings.gradle.kts                 # includes :gesture-core by path from the launcher repo
├── build.gradle.kts
├── gradle.properties
├── gradlew, gradlew.bat, gradle/wrapper/    # copied from the launcher
├── spotify-core/
│   ├── build.gradle.kts
│   └── src/
│       ├── main/java/dev/erinlkolp/glassspotify/core/
│       │   ├── Clock.java              # long nowMs(); avoids java.time (API 26+)
│       │   ├── Status.java             # enum + prism display string
│       │   ├── PlaybackState.java      # immutable snapshot
│       │   ├── HttpResponse.java       # code + body
│       │   ├── HttpTransport.java      # the seam that makes everything testable
│       │   ├── Tls.java                # SSLSocketFactory pinned to TLS 1.2
│       │   ├── UrlHttpTransport.java   # real HttpsURLConnection implementation
│       │   ├── TokenStore.java         # PKCE refresh + atomic rotation persistence
│       │   ├── SpotifyClient.java      # Web API calls, JSON parsing, 401 retry
│       │   └── PlayerController.java   # optimistic state, command sequencing
│       └── test/java/dev/erinlkolp/glassspotify/core/
│           ├── FakeHttpTransport.java
│           ├── FixedClock.java
│           ├── PlaybackStateTest.java
│           ├── TokenStoreTest.java
│           ├── SpotifyClientTest.java
│           └── PlayerControllerTest.java
├── app/
│   ├── build.gradle.kts
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/dev/erinlkolp/glassspotify/
│       │   ├── MotionEventAdapter.java # MotionEvent -> TouchSample
│       │   ├── NowPlayingView.java     # rendering only
│       │   └── ControllerActivity.java # window, gestures, threading, polling
│       └── res/values/strings.xml
├── tools/
│   └── bootstrap_token.py              # one-time laptop PKCE flow
└── spike/                              # existing, TLS probe — do not modify
```

**Responsibility boundaries.** `spotify-core` never imports an Android type, so every test in it runs on the JVM in milliseconds. `app` holds no network code at all. `HttpTransport` is the single seam: all tests drive `FakeHttpTransport`, and `UrlHttpTransport` is the only class that is not unit-tested (it is covered by the on-device bring-up in Task 12).

---

## Task 1: Gradle skeleton with gesture-core path include

**Files:**
- Create: `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`
- Create: `spotify-core/build.gradle.kts`
- Copy: `gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar`, `gradle/wrapper/gradle-wrapper.properties` from `../google-glass-gesture-launcher/`

**Interfaces:**
- Consumes: nothing.
- Produces: a Gradle build where `:spotify-core` and `:gesture-core` both resolve. Later tasks depend on `project(":gesture-core")` exposing `dev.erinlkolp.glasslauncher.gesture.*`.

- [ ] **Step 1: Copy the Gradle wrapper from the launcher**

```bash
cd /home/ekolp/workspace/google-glass-spotify-widget
cp ../google-glass-gesture-launcher/gradlew .
cp ../google-glass-gesture-launcher/gradlew.bat .
mkdir -p gradle/wrapper
cp ../google-glass-gesture-launcher/gradle/wrapper/gradle-wrapper.jar gradle/wrapper/
cp ../google-glass-gesture-launcher/gradle/wrapper/gradle-wrapper.properties gradle/wrapper/
chmod +x gradlew
```

- [ ] **Step 2: Write `settings.gradle.kts` with an explicit failure message**

The path include is the one thing that makes this repo non-self-contained. Fail loudly rather than with a Gradle-internal error.

```kotlin
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "glass-spotify-widget"

include(":spotify-core")
include(":app")

// gesture-core is the single source of truth for the touchpad anisotropy maths and
// the empirically-tuned gesture thresholds. It lives in the Gesture Launcher repo and
// is shared rather than copied, so a threshold fix in either project benefits both.
val gestureCore = file("../google-glass-gesture-launcher/gesture-core")
if (!gestureCore.isDirectory) {
    throw GradleException(
        "Cannot find gesture-core at ${gestureCore.absolutePath}.\n" +
        "This project shares gesture-core with the Gesture Launcher, so that repo must " +
        "be checked out beside this one:\n" +
        "  git clone git@github.com:erinlkolp/my-first-google-glass-project.git " +
        "../google-glass-gesture-launcher"
    )
}
include(":gesture-core")
project(":gesture-core").projectDir = gestureCore
```

- [ ] **Step 3: Write `build.gradle.kts` and `gradle.properties`**

`build.gradle.kts`:

```kotlin
plugins {
    id("com.android.application") version "8.7.0" apply false
}
```

`gradle.properties`:

```properties
org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8
android.useAndroidX=false
org.gradle.parallel=true
```

- [ ] **Step 4: Write `spotify-core/build.gradle.kts`**

```kotlin
plugins { id("java-library") }

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

tasks.withType<JavaCompile>().configureEach { options.release.set(8) }

dependencies {
    // Android provides org.json at runtime, so it must not be packaged.
    compileOnly("org.json:json:20231013")
    testImplementation("org.json:json:20231013")
    testImplementation("junit:junit:4.13.2")
}
```

- [ ] **Step 5: Verify both modules resolve**

Run: `./gradlew projects`
Expected: output lists `Project ':app'` is absent for now (not yet created — remove `include(":app")` temporarily if it blocks, or create the directory in Task 9). Lists `Project ':gesture-core'` and `Project ':spotify-core'`.

To keep this task self-contained, comment out `include(":app")` in `settings.gradle.kts` and re-enable it in Task 9.

Run: `./gradlew :gesture-core:test`
Expected: PASS — the launcher's existing gesture tests run from this build, proving the path include works.

- [ ] **Step 6: Verify the failure message works**

```bash
mv ../google-glass-gesture-launcher ../google-glass-gesture-launcher.bak
./gradlew projects 2>&1 | grep -q "Cannot find gesture-core" && echo "GUARD OK"
mv ../google-glass-gesture-launcher.bak ../google-glass-gesture-launcher
```

Expected: prints `GUARD OK`.

- [ ] **Step 7: Commit**

```bash
git add settings.gradle.kts build.gradle.kts gradle.properties spotify-core/build.gradle.kts gradlew gradlew.bat gradle/
git commit -m "build: gradle skeleton sharing gesture-core with the launcher"
```

---

## Task 2: Value types — Clock, Status, PlaybackState

**Files:**
- Create: `spotify-core/src/main/java/dev/erinlkolp/glassspotify/core/Clock.java`
- Create: `spotify-core/src/main/java/dev/erinlkolp/glassspotify/core/Status.java`
- Create: `spotify-core/src/main/java/dev/erinlkolp/glassspotify/core/PlaybackState.java`
- Test: `spotify-core/src/test/java/dev/erinlkolp/glassspotify/core/PlaybackStateTest.java`
- Test: `spotify-core/src/test/java/dev/erinlkolp/glassspotify/core/FixedClock.java`

**Interfaces:**
- Consumes: nothing.
- Produces:
  - `interface Clock { long nowMs(); }` and `Clock.SYSTEM`
  - `enum Status` with constants `OK, NOTHING_PLAYING, NO_DEVICE, NEEDS_REAUTH, NOT_PERMITTED, RATE_LIMITED, TLS_FAILED, NO_NETWORK, TOKEN_SAVE_FAILED, UNKNOWN` and `String displayText()`
  - `PlaybackState` with fields `Status status`, `String title`, `String artist`, `boolean playing`; statics `PlaybackState.playing(String,String,boolean)`, `PlaybackState.of(Status)`; instance method `PlaybackState withPlaying(boolean)`

- [ ] **Step 1: Write the failing test**

`PlaybackStateTest.java`:

```java
package dev.erinlkolp.glassspotify.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class PlaybackStateTest {

    @Test
    public void playingStateCarriesTrackDetails() {
        PlaybackState state = PlaybackState.playing("Black Hole Sun", "Soundgarden", true);
        assertEquals(Status.OK, state.status);
        assertEquals("Black Hole Sun", state.title);
        assertEquals("Soundgarden", state.artist);
        assertTrue(state.playing);
    }

    @Test
    public void errorStateHasNoTrackDetails() {
        PlaybackState state = PlaybackState.of(Status.NO_DEVICE);
        assertEquals(Status.NO_DEVICE, state.status);
        assertNull(state.title);
        assertNull(state.artist);
        assertFalse(state.playing);
    }

    @Test
    public void withPlayingFlipsOnlyThePlayingFlag() {
        PlaybackState state = PlaybackState.playing("Spoonman", "Soundgarden", true);
        PlaybackState flipped = state.withPlaying(false);

        assertFalse(flipped.playing);
        assertEquals("Spoonman", flipped.title);
        assertEquals("Soundgarden", flipped.artist);
        assertEquals(Status.OK, flipped.status);

        // The original must be untouched; this type is immutable.
        assertTrue(state.playing);
    }

    @Test
    public void everyStatusHasNonEmptyDisplayText() {
        Status[] all = Status.values();
        for (int i = 0; i < all.length; i++) {
            String text = all[i].displayText();
            assertTrue("empty displayText for " + all[i], text != null && text.length() > 0);
        }
    }

    @Test
    public void displayTextMatchesTheSpecTable() {
        assertEquals("Re-authorize on laptop", Status.NEEDS_REAUTH.displayText());
        assertEquals("Not permitted", Status.NOT_PERMITTED.displayText());
        assertEquals("No active device", Status.NO_DEVICE.displayText());
        assertEquals("Nothing playing", Status.NOTHING_PLAYING.displayText());
        assertEquals("Secure connection failed", Status.TLS_FAILED.displayText());
        assertEquals("No network", Status.NO_NETWORK.displayText());
        assertEquals("Token save failed", Status.TOKEN_SAVE_FAILED.displayText());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :spotify-core:test`
Expected: FAIL — `cannot find symbol: class PlaybackState`

- [ ] **Step 3: Write the implementation**

`Clock.java`:

```java
package dev.erinlkolp.glassspotify.core;

/**
 * Wall-clock milliseconds.
 *
 * <p>Exists because {@code java.time} is API 26+ and this project targets API 22.
 * Tests substitute a fixed implementation so token-expiry logic is deterministic.
 */
public interface Clock {

    long nowMs();

    Clock SYSTEM = new Clock() {
        @Override
        public long nowMs() {
            return System.currentTimeMillis();
        }
    };
}
```

`Status.java`:

```java
package dev.erinlkolp.glassspotify.core;

/**
 * Every outcome the controller can display, with the exact one-line string shown
 * on the prism.
 *
 * <p>Strings live here rather than in Android resources so the mapping is directly
 * unit-testable. This is a single-locale personal app; if that ever changes, move
 * these to {@code res/values/strings.xml} and key off the enum name.
 */
public enum Status {

    OK("Playing"),
    NOTHING_PLAYING("Nothing playing"),
    NO_DEVICE("No active device"),
    NEEDS_REAUTH("Re-authorize on laptop"),
    NOT_PERMITTED("Not permitted"),
    RATE_LIMITED("Slow down"),
    TLS_FAILED("Secure connection failed"),
    NO_NETWORK("No network"),
    TOKEN_SAVE_FAILED("Token save failed"),
    UNKNOWN("Something went wrong");

    private final String displayText;

    Status(String displayText) {
        this.displayText = displayText;
    }

    public String displayText() {
        return displayText;
    }
}
```

`PlaybackState.java`:

```java
package dev.erinlkolp.glassspotify.core;

/** Immutable snapshot of what the phone is doing, or why we cannot tell. */
public final class PlaybackState {

    public final Status status;
    /** Track title, or null when {@link #status} is not {@link Status#OK}. */
    public final String title;
    /** Artist name, or null when {@link #status} is not {@link Status#OK}. */
    public final String artist;
    public final boolean playing;

    private PlaybackState(Status status, String title, String artist, boolean playing) {
        this.status = status;
        this.title = title;
        this.artist = artist;
        this.playing = playing;
    }

    public static PlaybackState playing(String title, String artist, boolean playing) {
        return new PlaybackState(Status.OK, title, artist, playing);
    }

    /** A state carrying only an outcome, with no track information. */
    public static PlaybackState of(Status status) {
        return new PlaybackState(status, null, null, false);
    }

    /** @return a copy with the playing flag replaced. Used for the optimistic UI flip. */
    public PlaybackState withPlaying(boolean nowPlaying) {
        return new PlaybackState(status, title, artist, nowPlaying);
    }
}
```

`FixedClock.java` (test helper):

```java
package dev.erinlkolp.glassspotify.core;

/** A clock the tests move by hand. */
public final class FixedClock implements Clock {

    private long nowMs;

    public FixedClock(long startMs) {
        this.nowMs = startMs;
    }

    public void advance(long deltaMs) {
        nowMs += deltaMs;
    }

    @Override
    public long nowMs() {
        return nowMs;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :spotify-core:test`
Expected: PASS, 5 tests.

- [ ] **Step 5: Commit**

```bash
git add spotify-core/src
git commit -m "feat(core): Clock, Status, and immutable PlaybackState"
```

---

## Task 3: TokenStore with atomic refresh-token rotation

This task implements the spec's designated footgun. Spotify's PKCE flow returns a **new** refresh token on every refresh and invalidates the old one; a non-atomic write means permanent lockout.

**Files:**
- Create: `spotify-core/src/main/java/dev/erinlkolp/glassspotify/core/HttpResponse.java`
- Create: `spotify-core/src/main/java/dev/erinlkolp/glassspotify/core/HttpTransport.java`
- Create: `spotify-core/src/main/java/dev/erinlkolp/glassspotify/core/SpotifyException.java`
- Create: `spotify-core/src/main/java/dev/erinlkolp/glassspotify/core/TokenStore.java`
- Test: `spotify-core/src/test/java/dev/erinlkolp/glassspotify/core/FakeHttpTransport.java`
- Test: `spotify-core/src/test/java/dev/erinlkolp/glassspotify/core/TokenStoreTest.java`

**Interfaces:**
- Consumes: `Clock` (Task 2).
- Produces:
  - `final class HttpResponse { public final int code; public final String body; HttpResponse(int, String) }`
  - `interface HttpTransport { HttpResponse execute(String method, String url, String bearer, String formBody) throws IOException; }` — `bearer` and `formBody` may each be null
  - `class SpotifyException extends Exception { public final Status status; SpotifyException(Status) }`
  - `TokenStore(File tokenFile, String clientId, HttpTransport transport, Clock clock)`, methods `String accessToken() throws SpotifyException` and `void invalidateAccessToken()`
  - `FakeHttpTransport` test double with `enqueue(int code, String body)`, `List<String> urls()`, `List<String> bodies()`, `failWith(IOException)`

- [ ] **Step 1: Write the failing test**

`FakeHttpTransport.java`:

```java
package dev.erinlkolp.glassspotify.core;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;

/** Scripted HttpTransport. Every core test drives this instead of a network. */
public final class FakeHttpTransport implements HttpTransport {

    private final Queue<HttpResponse> responses = new LinkedList<HttpResponse>();
    private final List<String> methods = new ArrayList<String>();
    private final List<String> urls = new ArrayList<String>();
    private final List<String> bearers = new ArrayList<String>();
    private final List<String> bodies = new ArrayList<String>();
    private IOException failure;

    public void enqueue(int code, String body) {
        responses.add(new HttpResponse(code, body));
    }

    /** Makes every subsequent call throw, simulating a dead network. */
    public void failWith(IOException e) {
        this.failure = e;
    }

    public List<String> methods() {
        return methods;
    }

    public List<String> urls() {
        return urls;
    }

    public List<String> bearers() {
        return bearers;
    }

    public List<String> bodies() {
        return bodies;
    }

    @Override
    public HttpResponse execute(String method, String url, String bearer, String formBody)
            throws IOException {
        methods.add(method);
        urls.add(url);
        bearers.add(bearer);
        bodies.add(formBody);
        if (failure != null) {
            throw failure;
        }
        if (responses.isEmpty()) {
            throw new IllegalStateException("no response enqueued for " + method + " " + url);
        }
        return responses.remove();
    }
}
```

`TokenStoreTest.java`:

```java
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
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :spotify-core:test --tests '*TokenStoreTest'`
Expected: FAIL — `cannot find symbol: class TokenStore`

- [ ] **Step 3: Write the implementation**

`HttpResponse.java`:

```java
package dev.erinlkolp.glassspotify.core;

/** A completed HTTP exchange. */
public final class HttpResponse {

    public final int code;
    /** Response body, or the error body for a 4xx/5xx. Never null; may be empty. */
    public final String body;

    public HttpResponse(int code, String body) {
        this.code = code;
        this.body = body == null ? "" : body;
    }
}
```

`HttpTransport.java`:

```java
package dev.erinlkolp.glassspotify.core;

import java.io.IOException;

/**
 * The single network seam in this project.
 *
 * <p>Every class above this interface is unit-tested against a fake. Only
 * {@link UrlHttpTransport} performs real I/O.
 */
public interface HttpTransport {

    /**
     * @param method  HTTP verb, e.g. "GET", "PUT", "POST"
     * @param url     absolute https URL
     * @param bearer  OAuth access token, or null to send no Authorization header
     * @param formBody application/x-www-form-urlencoded body, or null to send none
     */
    HttpResponse execute(String method, String url, String bearer, String formBody)
            throws IOException;
}
```

`SpotifyException.java`:

```java
package dev.erinlkolp.glassspotify.core;

/** A failure already mapped to the status the prism should show. */
public final class SpotifyException extends Exception {

    private static final long serialVersionUID = 1L;

    public final Status status;

    public SpotifyException(Status status) {
        super(status.name());
        this.status = status;
    }

    public SpotifyException(Status status, Throwable cause) {
        super(status.name(), cause);
        this.status = status;
    }
}
```

`TokenStore.java`:

```java
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
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :spotify-core:test --tests '*TokenStoreTest'`
Expected: PASS, 11 tests.

If `anUnwritableTokenFileSurfacesAsTokenSaveFailed` passes when run as root, note that `setWritable(false)` does not constrain uid 0. Run the suite as a normal user; do not run Gradle under sudo.

- [ ] **Step 5: Commit**

```bash
git add spotify-core/src
git commit -m "feat(core): TokenStore with atomic PKCE refresh-token rotation"
```

---

## Task 4: Tls and UrlHttpTransport

**Files:**
- Create: `spotify-core/src/main/java/dev/erinlkolp/glassspotify/core/Tls.java`
- Create: `spotify-core/src/main/java/dev/erinlkolp/glassspotify/core/UrlHttpTransport.java`

**Interfaces:**
- Consumes: `HttpTransport`, `HttpResponse` (Task 3).
- Produces: `Tls.socketFactory()` returning `SSLSocketFactory`; `new UrlHttpTransport()` implementing `HttpTransport`.

`UrlHttpTransport` is deliberately not unit-tested — mocking `HttpsURLConnection` tests the mock, not the code. It is covered by the on-device bring-up in Task 12.

- [ ] **Step 1: Write `Tls.java`**

```java
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

    private Tls() {
    }

    public static SSLSocketFactory socketFactory() {
        return new Tls12SocketFactory((SSLSocketFactory) SSLSocketFactory.getDefault());
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
```

- [ ] **Step 2: Write `UrlHttpTransport.java`**

```java
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
```

- [ ] **Step 3: Verify it compiles**

Run: `./gradlew :spotify-core:compileJava`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add spotify-core/src
git commit -m "feat(core): TLS 1.2 socket factory and HttpsURLConnection transport"
```

---

## Task 5: SpotifyClient — reading playback state

**Files:**
- Create: `spotify-core/src/main/java/dev/erinlkolp/glassspotify/core/SpotifyClient.java`
- Test: `spotify-core/src/test/java/dev/erinlkolp/glassspotify/core/SpotifyClientTest.java`

**Interfaces:**
- Consumes: `HttpTransport`, `TokenStore`, `PlaybackState`, `Status`, `SpotifyException`.
- Produces: `SpotifyClient(HttpTransport, TokenStore)` with `PlaybackState currentState()`. Task 6 adds `Status play()`, `pause()`, `next()`, `previous()` to this same class.

- [ ] **Step 1: Write the failing test**

`SpotifyClientTest.java`:

```java
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
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :spotify-core:test --tests '*SpotifyClientTest'`
Expected: FAIL — `cannot find symbol: class SpotifyClient`

- [ ] **Step 3: Write the implementation**

```java
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

        // Failure codes must be mapped BEFORE the empty-body check. A 429 arrives with
        // an empty body, so checking emptiness first would report rate limiting as
        // "Nothing playing". mapFailure returns null only for 2xx, so this ordering
        // leaves every success path untouched.
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
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :spotify-core:test --tests '*SpotifyClientTest'`
Expected: PASS, 13 tests.

- [ ] **Step 5: Commit**

```bash
git add spotify-core/src
git commit -m "feat(core): SpotifyClient reads playback state and maps every failure"
```

---

## Task 6: SpotifyClient — transport commands

**Files:**
- Modify: `spotify-core/src/main/java/dev/erinlkolp/glassspotify/core/SpotifyClient.java`
- Modify: `spotify-core/src/test/java/dev/erinlkolp/glassspotify/core/SpotifyClientTest.java`

**Interfaces:**
- Consumes: everything from Task 5.
- Produces: `Status play()`, `Status pause()`, `Status next()`, `Status previous()` on `SpotifyClient`.

- [ ] **Step 1: Add the failing tests**

Append to `SpotifyClientTest.java`, inside the class:

```java
    @Test
    public void playIssuesAPutToThePlayEndpoint() throws Exception {
        http.enqueue(204, "");

        assertEquals(Status.OK, client.play());
        assertEquals("PUT", http.methods().get(1));
        assertEquals("https://api.spotify.com/v1/me/player/play", http.urls().get(1));
    }

    @Test
    public void pauseIssuesAPutToThePauseEndpoint() throws Exception {
        http.enqueue(204, "");

        assertEquals(Status.OK, client.pause());
        assertEquals("PUT", http.methods().get(1));
        assertEquals("https://api.spotify.com/v1/me/player/pause", http.urls().get(1));
    }

    @Test
    public void nextIssuesAPostToTheNextEndpoint() throws Exception {
        http.enqueue(204, "");

        assertEquals(Status.OK, client.next());
        assertEquals("POST", http.methods().get(1));
        assertEquals("https://api.spotify.com/v1/me/player/next", http.urls().get(1));
    }

    @Test
    public void previousIssuesAPostToThePreviousEndpoint() throws Exception {
        http.enqueue(204, "");

        assertEquals(Status.OK, client.previous());
        assertEquals("POST", http.methods().get(1));
        assertEquals("https://api.spotify.com/v1/me/player/previous", http.urls().get(1));
    }

    @Test
    public void a404OnACommandMeansNoActiveDevice() throws Exception {
        http.enqueue(404, "{\"error\":{\"reason\":\"NO_ACTIVE_DEVICE\"}}");

        assertEquals(Status.NO_DEVICE, client.next());
    }

    @Test
    public void a403OnACommandMeansNotPermitted() throws Exception {
        // What a Free-tier account receives for every control endpoint.
        http.enqueue(403, "{\"error\":{\"status\":403}}");

        assertEquals(Status.NOT_PERMITTED, client.play());
    }

    @Test
    public void a401OnACommandRefreshesTheTokenAndRetriesExactlyOnce() throws Exception {
        http.enqueue(401, "{\"error\":{\"status\":401}}");
        http.enqueue(200, "{\"access_token\":\"AT2\",\"expires_in\":3600}");
        http.enqueue(204, "");

        assertEquals(Status.OK, client.pause());

        // token refresh, failed PUT, second token refresh, retried PUT
        assertEquals(4, http.urls().size());
        assertEquals("AT1", http.bearers().get(1));
        assertEquals("AT2", http.bearers().get(3));
    }

    @Test
    public void aSecondConsecutive401IsNotRetriedAgain() throws Exception {
        http.enqueue(401, "");
        http.enqueue(200, "{\"access_token\":\"AT2\",\"expires_in\":3600}");
        http.enqueue(401, "");

        assertEquals(Status.NEEDS_REAUTH, client.pause());
        assertEquals(4, http.urls().size());
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :spotify-core:test --tests '*SpotifyClientTest'`
Expected: FAIL — `cannot find symbol: method play()`

- [ ] **Step 3: Add the command methods to `SpotifyClient`**

Insert after `currentState()`:

```java
    /** Resumes playback on the active device. */
    public Status play() {
        return command("PUT", PLAYER + "/play");
    }

    /** Pauses playback on the active device. */
    public Status pause() {
        return command("PUT", PLAYER + "/pause");
    }

    /** Skips to the next track. */
    public Status next() {
        return command("POST", PLAYER + "/next");
    }

    /** Skips to the previous track. */
    public Status previous() {
        return command("POST", PLAYER + "/previous");
    }

    private Status command(String method, String url) {
        HttpResponse response;
        try {
            response = send(method, url);
        } catch (SpotifyException e) {
            return e.status;
        }
        Status failure = mapFailure(response.code);
        return failure == null ? Status.OK : failure;
    }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :spotify-core:test`
Expected: PASS, 21 tests in `SpotifyClientTest`, all suites green.

- [ ] **Step 5: Commit**

```bash
git add spotify-core/src
git commit -m "feat(core): play, pause, next, previous with single 401 retry"
```

---

## Task 7: PlayerController — optimistic state and command sequencing

**Files:**
- Create: `spotify-core/src/main/java/dev/erinlkolp/glassspotify/core/PlayerController.java`
- Test: `spotify-core/src/test/java/dev/erinlkolp/glassspotify/core/PlayerControllerTest.java`

**Interfaces:**
- Consumes: `SpotifyClient`, `PlaybackState`, `Status`.
- Produces: `PlayerController(SpotifyClient)` with `PlaybackState last()`, `PlaybackState toggleOptimistic()`, `PlaybackState toggleCommit()`, `PlaybackState nextCommit()`, `PlaybackState previousCommit()`, `PlaybackState refresh()`.

The split matters: `toggleOptimistic()` is called on the main thread and never touches the network, so the prism updates instantly. `toggleCommit()` blocks and is called on a worker. `PlayerController` contains no Android types and no threading — the Activity owns both.

- [ ] **Step 1: Write the failing test**

```java
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
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :spotify-core:test --tests '*PlayerControllerTest'`
Expected: FAIL — `cannot find symbol: class PlayerController`

- [ ] **Step 3: Write the implementation**

```java
package dev.erinlkolp.glassspotify.core;

/**
 * Sequences user intent against the Web API and remembers the last known state.
 *
 * <p>Deliberately free of threading and of Android types. The Activity calls
 * {@link #toggleOptimistic()} on the main thread for instant feedback, then runs the
 * matching {@code *Commit} method on a worker. That split is what makes a control
 * with 200-500ms of cloud round-trip feel immediate.
 *
 * <p>Not thread-safe. The Activity must confine it to one worker at a time.
 */
public final class PlayerController {

    private final SpotifyClient client;

    private PlaybackState last = PlaybackState.of(Status.NOTHING_PLAYING);

    public PlayerController(SpotifyClient client) {
        if (client == null) {
            throw new IllegalArgumentException("client is required");
        }
        this.client = client;
    }

    /** @return the most recent state, without touching the network. */
    public PlaybackState last() {
        return last;
    }

    /** Fetches authoritative state. Blocking. */
    public PlaybackState refresh() {
        last = client.currentState();
        return last;
    }

    /**
     * Flips the play flag locally for immediate rendering. No network. Main thread.
     *
     * <p>The flipped value is also the intent: {@link #toggleCommit()} reads it to
     * decide whether to send play or pause.
     */
    public PlaybackState toggleOptimistic() {
        last = last.withPlaying(!last.playing);
        return last;
    }

    /** Sends the command implied by the optimistic flip, then refetches. Blocking. */
    public PlaybackState toggleCommit() {
        Status result = last.playing ? client.play() : client.pause();
        return settle(result);
    }

    /** Skips forward, then refetches. Blocking. */
    public PlaybackState nextCommit() {
        return settle(client.next());
    }

    /** Skips back, then refetches. Blocking. */
    public PlaybackState previousCommit() {
        return settle(client.previous());
    }

    /**
     * A successful command is followed by a refetch so the display reflects what the
     * phone actually did, reverting the optimistic guess if it was wrong. A failed
     * command skips the refetch — the failure is the news, and a second call would
     * just fail the same way.
     */
    private PlaybackState settle(Status commandResult) {
        if (commandResult != Status.OK) {
            last = PlaybackState.of(commandResult);
            return last;
        }
        return refresh();
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :spotify-core:test`
Expected: PASS, all suites green.

- [ ] **Step 5: Commit**

```bash
git add spotify-core/src
git commit -m "feat(core): PlayerController with optimistic toggle and refetch"
```

---

## Task 8: One-time PKCE token bootstrap

**Files:**
- Create: `tools/bootstrap_token.py`
- Create: `tools/README.md`

**Interfaces:**
- Consumes: nothing in code.
- Produces: a file `tools/refresh_token.txt` containing the refresh token, and a printed `adb push` command. Task 11 reads that token at `/data/local/tmp/spotify_bootstrap_token` on first run.

**Manual prerequisite — Erin must do this once, it cannot be scripted:**

1. Go to `https://developer.spotify.com/dashboard` and create an app (any name, e.g. "Glass Controller").
2. In its settings, add the redirect URI **exactly**: `http://127.0.0.1:8888/callback`
   Spotify requires HTTPS redirect URIs except for loopback literals, so `127.0.0.1` works but `localhost` does **not**.
3. Copy the Client ID. There is no client secret in the PKCE flow.

- [ ] **Step 1: Write `tools/bootstrap_token.py`**

```python
#!/usr/bin/env python3
"""One-time Spotify PKCE bootstrap for the Glass Spotify Widget.

Glass has no keyboard, so authorization happens here on the laptop exactly once.
The resulting refresh token is pushed to the device and rotated in place from then on.

Usage:
    python3 tools/bootstrap_token.py <client-id>
"""

import base64
import hashlib
import http.server
import json
import secrets
import sys
import threading
import urllib.parse
import urllib.request
import webbrowser

REDIRECT_URI = "http://127.0.0.1:8888/callback"
PORT = 8888
SCOPES = "user-read-playback-state user-modify-playback-state"
OUTPUT = "tools/refresh_token.txt"

_received = {}
_expected_state = None


class CallbackHandler(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        global _expected_state

        parsed = urllib.parse.urlparse(self.path)
        if parsed.path != "/callback":
            self.send_response(404)
            self.end_headers()
            return

        params = urllib.parse.parse_qs(parsed.query)
        returned_state = params.get("state", [None])[0]
        _received["code"] = params.get("code", [None])[0]
        _received["error"] = params.get("error", [None])[0]

        # The state parameter is worthless unless it is actually compared. A
        # mismatch (or an absent value, since _expected_state is always set)
        # discards the code so the exchange below cannot run.
        if returned_state != _expected_state:
            _received["code"] = None
            _received["state_mismatch"] = True
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.end_headers()
            body = "<h1>Failed.</h1><p>State mismatch: CSRF check failed.</p>"
            self.wfile.write(body.encode("utf-8"))
            return

        self.send_response(200)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.end_headers()
        if _received["code"]:
            body = "<h1>Authorized.</h1><p>You can close this tab.</p>"
        else:
            body = "<h1>Failed.</h1><p>%s</p>" % _received["error"]
        self.wfile.write(body.encode("utf-8"))

    def log_message(self, fmt, *args):
        pass  # keep the console clean


def make_verifier():
    verifier = base64.urlsafe_b64encode(secrets.token_bytes(64)).decode().rstrip("=")
    digest = hashlib.sha256(verifier.encode("ascii")).digest()
    challenge = base64.urlsafe_b64encode(digest).decode().rstrip("=")
    return verifier, challenge


def main():
    if len(sys.argv) != 2:
        print(__doc__)
        return 1
    client_id = sys.argv[1]

    global _expected_state

    verifier, challenge = make_verifier()
    state = secrets.token_urlsafe(16)
    _expected_state = state

    authorize_url = "https://accounts.spotify.com/authorize?" + urllib.parse.urlencode({
        "client_id": client_id,
        "response_type": "code",
        "redirect_uri": REDIRECT_URI,
        "scope": SCOPES,
        "code_challenge_method": "S256",
        "code_challenge": challenge,
        "state": state,
    })

    server = http.server.HTTPServer(("127.0.0.1", PORT), CallbackHandler)
    thread = threading.Thread(target=server.handle_request)
    thread.start()

    print("Opening browser. If it does not open, visit:\n\n%s\n" % authorize_url)
    webbrowser.open(authorize_url)
    thread.join(timeout=300)
    server.server_close()

    if _received.get("state_mismatch"):
        print("State mismatch: CSRF check failed. Authorization aborted.")
        return 1

    if not _received.get("code"):
        print("No authorization code received: %s" % _received.get("error"))
        return 1

    payload = urllib.parse.urlencode({
        "grant_type": "authorization_code",
        "code": _received["code"],
        "redirect_uri": REDIRECT_URI,
        "client_id": client_id,
        "code_verifier": verifier,
    }).encode("ascii")

    request = urllib.request.Request(
        "https://accounts.spotify.com/api/token",
        data=payload,
        headers={"Content-Type": "application/x-www-form-urlencoded"},
    )
    with urllib.request.urlopen(request) as response:
        token = json.loads(response.read().decode("utf-8"))

    refresh_token = token.get("refresh_token")
    if not refresh_token:
        # Print only the error fields. The raw dict can carry an access_token,
        # and this script must never emit a credential to stdout.
        error_msg = token.get("error") or token.get("error_description") or "unknown error"
        print("No refresh_token in response: %s" % error_msg)
        return 1

    with open(OUTPUT, "w", encoding="utf-8") as handle:
        handle.write(refresh_token)

    print("\nRefresh token written to %s\n" % OUTPUT)
    print("Now push it to the Glass:\n")
    print("  adb -s 0123456789ABCDEF push %s /data/local/tmp/spotify_bootstrap_token"
          % OUTPUT)
    print("\nThen launch Spotify from the Glass launcher. The app imports the token")
    print("into its private storage on first run and deletes the pushed copy.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
```

- [ ] **Step 2: Write `tools/README.md`**

```markdown
# Token bootstrap

Glass has no keyboard, so Spotify authorization happens once on the laptop.

## First time only

1. Create an app at https://developer.spotify.com/dashboard
2. Add redirect URI exactly: `http://127.0.0.1:8888/callback`
   (Loopback literals are the only non-HTTPS redirects Spotify still allows.
   `localhost` is rejected; it must be `127.0.0.1`.)
3. Copy the Client ID. PKCE uses no client secret.

## Run it

    python3 tools/bootstrap_token.py <client-id>
    adb -s 0123456789ABCDEF push tools/refresh_token.txt /data/local/tmp/spotify_bootstrap_token

Launch Spotify on the Glass. It imports the token and deletes the pushed copy.

`tools/refresh_token.txt` is gitignored. After the app has imported it once, the
copy on the laptop is stale — Spotify rotates the refresh token on every refresh.
Delete it.

## If you ever see "Re-authorize on laptop"

The refresh token was invalidated. Re-run the two commands above.
```

- [ ] **Step 3: Gitignore the token**

Append to `.gitignore`:

```
tools/refresh_token.txt
```

- [ ] **Step 4: Verify the script is syntactically valid**

Run: `python3 -m py_compile tools/bootstrap_token.py && echo "SYNTAX OK"`
Expected: `SYNTAX OK`

Run: `python3 tools/bootstrap_token.py`
Expected: prints the usage docstring and exits 1.

- [ ] **Step 5: Commit**

```bash
git add tools/ .gitignore
git commit -m "feat(tools): one-time PKCE bootstrap for the Glass refresh token"
```

---

## Task 9: Android app module and launcher card

At the end of this task the card reads "Spotify" in the Gesture Launcher, even though tapping it shows only a black screen. That is deliberately its own verifiable deliverable — it proves the zero-launcher-changes claim from the spec.

**Files:**
- Create: `app/build.gradle.kts`
- Create: `app/src/main/AndroidManifest.xml`
- Create: `app/src/main/res/values/strings.xml`
- Create: `app/src/main/java/dev/erinlkolp/glassspotify/ControllerActivity.java` (stub)
- Modify: `settings.gradle.kts` (re-enable `include(":app")`)

**Interfaces:**
- Consumes: `:spotify-core`, `:gesture-core`.
- Produces: an installable APK whose launcher activity is `dev.erinlkolp.glassspotify.ControllerActivity`, labelled "Spotify".

- [ ] **Step 1: Write `app/build.gradle.kts`**

```kotlin
plugins { id("com.android.application") }

android {
    namespace = "dev.erinlkolp.glassspotify"
    compileSdk = 34

    defaultConfig {
        applicationId = "dev.erinlkolp.glassspotify"
        minSdk = 22
        targetSdk = 22
        versionCode = 1
        versionName = "0.1"
    }

    compileOptions {
        // AGP emits -source/-target here, not --release, so options.release must not
        // be used in this module. spotify-core and gesture-core do use it.
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    buildTypes {
        release { isMinifyEnabled = false }
    }
}

dependencies {
    implementation(project(":spotify-core"))
    implementation(project(":gesture-core"))
    testImplementation("junit:junit:4.13.2")
}
```

- [ ] **Step 2: Write the manifest**

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />

    <application
        android:label="@string/app_name"
        android:theme="@android:style/Theme.Holo.NoActionBar.Fullscreen">
        <activity
            android:name=".ControllerActivity"
            android:label="@string/app_name"
            android:exported="true"
            android:launchMode="singleTask"
            android:excludeFromRecents="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
```

The `android:label` on the activity is what `AppRepository.load()` reads via
`ResolveInfo.loadLabel()`, so it is what appears on the launcher card.

- [ ] **Step 3: Write `strings.xml`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="app_name">Spotify</string>
</resources>
```

- [ ] **Step 4: Write the stub Activity**

```java
package dev.erinlkolp.glassspotify;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;

/** Placeholder; the real UI arrives in Tasks 10 and 11. */
public class ControllerActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        View black = new View(this);
        black.setBackgroundColor(Color.BLACK);
        setContentView(black);
        applyImmersiveMode();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            applyImmersiveMode();
        }
    }

    /**
     * Keeps edge swipes with this activity instead of the system.
     *
     * <p>The StatusBar window claims the top 38 px of the display as touchable, and
     * the touchpad's 187 vertical units map onto 360 px, so the top of the pad lands
     * inside that region. Without this, swipe-down-to-exit opens the notification
     * shade. LOW_PROFILE does not help — it only dims nav icons.
     */
    private void applyImmersiveMode() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }
}
```

- [ ] **Step 5: Re-enable `:app` and build**

Uncomment `include(":app")` in `settings.gradle.kts`.

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL, APK at `app/build/outputs/apk/debug/app-debug.apk`

- [ ] **Step 6: Install and verify the card appears**

```bash
adb -s 0123456789ABCDEF install -r app/build/outputs/apk/debug/app-debug.apk
adb -s 0123456789ABCDEF shell "pm list packages | grep glassspotify"
```

Expected: `package:dev.erinlkolp.glassspotify`

**Human verification:** put the Glass on, open the launcher, swipe to find a card reading **Spotify**. Tapping it should show a black screen; swiping down should exit. If swiping down opens the notification shade instead, immersive mode is not applying — that is the gotcha #2 failure and must be fixed before proceeding.

- [ ] **Step 7: Commit**

```bash
git add app/ settings.gradle.kts
git commit -m "feat(app): Android module appearing as a Spotify launcher card"
```

---

## Task 10: NowPlayingView

**Files:**
- Create: `app/src/main/java/dev/erinlkolp/glassspotify/NowPlayingView.java`

**Interfaces:**
- Consumes: `PlaybackState`, `Status` from `:spotify-core`.
- Produces: `NowPlayingView(Context)` with `void render(PlaybackState state)`.

- [ ] **Step 1: Write the view**

```java
package dev.erinlkolp.glassspotify;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.View;
import dev.erinlkolp.glassspotify.core.PlaybackState;
import dev.erinlkolp.glassspotify.core.Status;

/**
 * Draws the now-playing card.
 *
 * <p>Pure white on pure black, no mid-tones. The optic is see-through, so anything
 * between those two washes out to illegibility outdoors.
 */
public final class NowPlayingView extends View {

    private static final float TITLE_SP = 26.0f;
    private static final float ARTIST_SP = 18.0f;
    private static final float STATUS_SP = 16.0f;
    private static final float MARGIN_DP = 16.0f;

    private final Paint titlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint artistPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint statusPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float margin;

    private PlaybackState state = PlaybackState.of(Status.NOTHING_PLAYING);

    public NowPlayingView(Context context) {
        super(context);
        float density = context.getResources().getDisplayMetrics().density;
        float scaledDensity = context.getResources().getDisplayMetrics().scaledDensity;
        margin = MARGIN_DP * density;

        setBackgroundColor(Color.BLACK);

        titlePaint.setColor(Color.WHITE);
        titlePaint.setTextSize(TITLE_SP * scaledDensity);

        artistPaint.setColor(Color.WHITE);
        artistPaint.setTextSize(ARTIST_SP * scaledDensity);

        statusPaint.setColor(Color.WHITE);
        statusPaint.setTextSize(STATUS_SP * scaledDensity);
    }

    /** Replaces the displayed state and schedules a redraw. Main thread only. */
    public void render(PlaybackState newState) {
        this.state = newState;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        if (state.status != Status.OK) {
            // Errors and idle states get one centred line and nothing else.
            String message = state.status.displayText();
            float width = statusPaint.measureText(message);
            canvas.drawText(
                    message,
                    (getWidth() - width) / 2.0f,
                    getHeight() / 2.0f,
                    statusPaint);
            return;
        }

        float y = margin + titlePaint.getTextSize();
        canvas.drawText(ellipsize(state.title, titlePaint), margin, y, titlePaint);

        y += artistPaint.getTextSize() * 1.6f;
        canvas.drawText(ellipsize(state.artist, artistPaint), margin, y, artistPaint);

        y += statusPaint.getTextSize() * 2.2f;
        canvas.drawText(state.playing ? "> playing" : "|| paused", margin, y, statusPaint);
    }

    /** Truncates with an ellipsis rather than letting long titles run off the prism. */
    private String ellipsize(String text, Paint paint) {
        if (text == null) {
            return "";
        }
        float available = getWidth() - (margin * 2.0f);
        if (available <= 0.0f || paint.measureText(text) <= available) {
            return text;
        }
        String suffix = "...";
        float suffixWidth = paint.measureText(suffix);
        int end = text.length();
        while (end > 0 && paint.measureText(text, 0, end) + suffixWidth > available) {
            end--;
        }
        return text.substring(0, end) + suffix;
    }
}
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src
git commit -m "feat(app): NowPlayingView rendering white on black"
```

---

## Task 11: ControllerActivity — gestures, threading, polling, token import

**Files:**
- Create: `app/src/main/java/dev/erinlkolp/glassspotify/MotionEventAdapter.java`
- Modify: `app/src/main/java/dev/erinlkolp/glassspotify/ControllerActivity.java` (replace the stub entirely)

**Interfaces:**
- Consumes: `NowPlayingView` (Task 10), `PlayerController` / `SpotifyClient` / `TokenStore` / `UrlHttpTransport` / `Clock` (Tasks 2-7), `GlassGestureDetector` / `Gesture` / `TouchSample` / `TouchPhase` / `TouchpadGeometry` / `GestureOrientation` from `:gesture-core`.
- Produces: the finished app.

**Replace `CLIENT_ID` with the real Client ID from Task 8 before building.**

> **Two defects in the reference code below were found in review and fixed during
> implementation. The committed source is authoritative; this listing is not.**
>
> 1. **`importBootstrapTokenIfPresent` was not idempotent, and could cause a permanent
>    Spotify lockout.** It re-imported on every launch for as long as the pushed file
>    existed, and its javadoc wrongly claimed `pushed.delete()` prevented that.
>    `/data/local/tmp` carries the sticky bit, so the app's uid cannot reliably delete a
>    shell-pushed file — an avc denial for exactly this was observed on the device. Once
>    the delete fails, the next launch overwrites whatever `TokenStore` has rotated to,
>    and Spotify has already invalidated that original token. Fixed by fingerprinting the
>    pushed file's contents (SHA-256) into a `bootstrap_fingerprint` marker in
>    `getFilesDir()`, written with the same write-temp-then-rename discipline as
>    `TokenStore`, and skipping the import when the fingerprint matches. A genuinely new
>    pushed token still imports; an undeletable leftover never overwrites.
> 2. **`publish()` could render into a torn-down Activity.** `shutdownNow()` only
>    interrupts, and `HttpsURLConnection` blocking I/O ignores interruption, so a command
>    in flight when the user exits could call `view.render()` up to ~20s later. Fixed by
>    checking `isFinishing() || isDestroyed()` inside the posted Runnable, on the main
>    thread.
>
> The corrected code is deliberately not re-transcribed here — see
> `app/src/main/java/dev/erinlkolp/glassspotify/ControllerActivity.java`. Re-transcribing
> ~80 lines into a document that no longer drives implementation would only invite drift.

- [ ] **Step 1: Write `MotionEventAdapter.java`**

Same shape as the launcher's, retargeted to this package. `gesture-core` deliberately
has no Android dependency, so each host supplies its own adapter.

```java
package dev.erinlkolp.glassspotify;

import android.view.MotionEvent;
import dev.erinlkolp.glasslauncher.gesture.TouchPhase;
import dev.erinlkolp.glasslauncher.gesture.TouchSample;

/** Translates Android {@link MotionEvent}s into device-agnostic samples. */
public final class MotionEventAdapter {

    private MotionEventAdapter() {
    }

    /** @return a sample, or null if this event carries no useful phase. */
    public static TouchSample toSample(MotionEvent event) {
        TouchPhase phase;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                phase = TouchPhase.DOWN;
                break;
            case MotionEvent.ACTION_MOVE:
            case MotionEvent.ACTION_POINTER_DOWN:
            case MotionEvent.ACTION_POINTER_UP:
                phase = TouchPhase.MOVE;
                break;
            case MotionEvent.ACTION_UP:
                phase = TouchPhase.UP;
                break;
            case MotionEvent.ACTION_CANCEL:
                phase = TouchPhase.CANCEL;
                break;
            default:
                return null;
        }
        return new TouchSample(
                phase,
                event.getX(),
                event.getY(),
                event.getEventTime(),
                event.getPointerCount());
    }
}
```

- [ ] **Step 2: Replace `ControllerActivity.java`**

```java
package dev.erinlkolp.glassspotify;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import dev.erinlkolp.glasslauncher.gesture.Gesture;
import dev.erinlkolp.glasslauncher.gesture.GestureOrientation;
import dev.erinlkolp.glasslauncher.gesture.GlassGestureDetector;
import dev.erinlkolp.glasslauncher.gesture.TouchSample;
import dev.erinlkolp.glasslauncher.gesture.TouchpadGeometry;
import dev.erinlkolp.glassspotify.core.Clock;
import dev.erinlkolp.glassspotify.core.PlaybackState;
import dev.erinlkolp.glassspotify.core.PlayerController;
import dev.erinlkolp.glassspotify.core.SpotifyClient;
import dev.erinlkolp.glassspotify.core.Status;
import dev.erinlkolp.glassspotify.core.TokenStore;
import dev.erinlkolp.glassspotify.core.UrlHttpTransport;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The whole UI. Owns the window, the gesture detector, the worker thread, and the
 * poll timer.
 *
 * <p>All network work runs on a single-thread executor and results are posted back
 * to the main thread. {@link PlayerController} is confined to that one worker, which
 * is what its documented lack of thread-safety requires.
 */
public class ControllerActivity extends Activity {

    private static final String TAG = "GlassSpotify";

    /** From the Spotify developer dashboard. PKCE uses no client secret. */
    private static final String CLIENT_ID = "REPLACE_WITH_YOUR_CLIENT_ID";

    /** Where bootstrap_token.py's output is pushed before first run. */
    private static final String BOOTSTRAP_PATH = "/data/local/tmp/spotify_bootstrap_token";
    private static final String TOKEN_FILE = "refresh_token";

    private static final long POLL_INTERVAL_MS = 3000L;
    /** Long enough for Spotify Connect to reach the phone and settle. */
    private static final long SETTLE_DELAY_MS = 400L;

    private final Handler main = new Handler(Looper.getMainLooper());
    private ExecutorService worker;
    private GlassGestureDetector detector;
    private NowPlayingView view;
    private PlayerController controller;
    private boolean polling;

    private final Runnable pollTick = new Runnable() {
        @Override
        public void run() {
            if (!polling) {
                return;
            }
            submitRefresh();
            main.postDelayed(this, POLL_INTERVAL_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        view = new NowPlayingView(this);
        setContentView(view);
        applyImmersiveMode();

        detector = new GlassGestureDetector(TouchpadGeometry.GLASS, GestureOrientation.DEFAULT);
        worker = Executors.newSingleThreadExecutor();

        importBootstrapTokenIfPresent();

        File tokenFile = new File(getFilesDir(), TOKEN_FILE);
        TokenStore tokens = new TokenStore(
                tokenFile, CLIENT_ID, new UrlHttpTransport(), Clock.SYSTEM);
        controller = new PlayerController(new SpotifyClient(new UrlHttpTransport(), tokens));
    }

    /**
     * Moves a pushed bootstrap token into private storage, once.
     *
     * <p>Avoids chown gymnastics: adb pushes to /data/local/tmp, and the app adopts it
     * on first launch. The pushed copy is deleted so a stale token cannot later
     * overwrite a rotated one.
     */
    private void importBootstrapTokenIfPresent() {
        File pushed = new File(BOOTSTRAP_PATH);
        if (!pushed.isFile()) {
            return;
        }
        try {
            // Not java.nio.file.Files — that is API 26+ and this device is API 22.
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            InputStream in = new FileInputStream(pushed);
            try {
                byte[] chunk = new byte[1024];
                int read;
                while ((read = in.read(chunk)) != -1) {
                    buffer.write(chunk, 0, read);
                }
            } finally {
                in.close();
            }
            byte[] contents = buffer.toByteArray();

            File destination = new File(getFilesDir(), TOKEN_FILE);
            OutputStream out = new FileOutputStream(destination);
            try {
                out.write(contents);
                out.flush();
            } finally {
                out.close();
            }
            Log.i(TAG, "imported bootstrap token (" + contents.length + " bytes)");
            if (!pushed.delete()) {
                Log.w(TAG, "could not delete " + BOOTSTRAP_PATH + "; delete it by hand");
            }
        } catch (IOException e) {
            Log.e(TAG, "bootstrap token import failed", e);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        view.render(controller.last());
        polling = true;
        main.post(pollTick);
    }

    @Override
    protected void onPause() {
        // Stop the radio the moment the prism is not being read.
        polling = false;
        main.removeCallbacks(pollTick);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            applyImmersiveMode();
        }
    }

    /**
     * Keeps edge swipes with this activity instead of the system.
     *
     * <p>The StatusBar window claims the top 38 px of the display as touchable, and
     * the touchpad's 187 vertical units map onto 360 px, so the top of the pad lands
     * inside that region. Without this, swipe-down-to-exit opens the notification
     * shade. LOW_PROFILE does not help — it only dims nav icons.
     */
    private void applyImmersiveMode() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        TouchSample sample = MotionEventAdapter.toSample(event);
        if (sample == null) {
            return super.onTouchEvent(event);
        }
        Gesture gesture = detector.accept(sample);
        switch (gesture) {
            case TAP:
                onTap();
                return true;
            case SWIPE_FORWARD:
                submitCommand(Command.NEXT);
                return true;
            case SWIPE_BACKWARD:
                submitCommand(Command.PREVIOUS);
                return true;
            case SWIPE_DOWN:
                finish();
                return true;
            default:
                // Every other gesture, including NONE, is deliberately a no-op.
                return true;
        }
    }

    /** Flips the display immediately, then reconciles with the phone on the worker. */
    private void onTap() {
        view.render(controller.toggleOptimistic());
        submitCommand(Command.TOGGLE);
    }

    private enum Command { TOGGLE, NEXT, PREVIOUS }

    private void submitCommand(final Command command) {
        worker.submit(new Runnable() {
            @Override
            public void run() {
                PlaybackState state;
                switch (command) {
                    case TOGGLE:
                        state = controller.toggleCommit();
                        break;
                    case NEXT:
                        state = controller.nextCommit();
                        break;
                    default:
                        state = controller.previousCommit();
                        break;
                }
                publish(state);

                // A skip changes the track a moment after the command returns, so take
                // one more reading rather than showing the outgoing track.
                if (command != Command.TOGGLE && state.status == Status.OK) {
                    try {
                        Thread.sleep(SETTLE_DELAY_MS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    publish(controller.refresh());
                }
            }
        });
    }

    private void submitRefresh() {
        worker.submit(new Runnable() {
            @Override
            public void run() {
                publish(controller.refresh());
            }
        });
    }

    private void publish(final PlaybackState state) {
        main.post(new Runnable() {
            @Override
            public void run() {
                view.render(state);
            }
        });
    }
}
```

- [ ] **Step 3: Build**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Verify the full suite still passes**

Run: `./gradlew test`
Expected: PASS — `:spotify-core:test` and `:gesture-core:test` both green.

- [ ] **Step 5: Commit**

```bash
git add app/src
git commit -m "feat(app): gestures, polling, and worker threading in ControllerActivity"
```

---

## Task 12: On-device bring-up

Automated tests cannot certify this layer. Per gotcha #3, `adb shell input tap/swipe`
injects below the window manager and bypasses touchable regions entirely — on the
launcher project, 40 automated tests passed green while two real gesture bugs were
live. **Erin must drive this with real fingers.**

**Files:** none. This task produces a verified install and a `README.md`.

- [ ] **Step 1: Set the Client ID**

Edit `ControllerActivity.CLIENT_ID` to the value from the Spotify dashboard, then:

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 2: Bootstrap the token**

```bash
python3 tools/bootstrap_token.py <client-id>
adb -s 0123456789ABCDEF push tools/refresh_token.txt /data/local/tmp/spotify_bootstrap_token
```

Expected: browser opens, you authorize, the script prints the push command, and the
push reports one file transferred.

- [ ] **Step 3: Enable WiFi and install**

```bash
adb -s 0123456789ABCDEF shell svc wifi enable
adb -s 0123456789ABCDEF install -r app/build/outputs/apk/debug/app-debug.apk
```

Expected: `Success`. Confirm the Glass has an address:
`adb -s 0123456789ABCDEF shell "ip addr show wlan0" | grep "inet "`

- [ ] **Step 4: Start playback on the phone**

Play something in Spotify on the phone and leave the app backgrounded, not killed.
A force-killed app deregisters as a Connect device and the Web API cannot wake it —
that is the expected `No active device` case, not a bug.

- [ ] **Step 5: Verify the token import**

```bash
adb -s 0123456789ABCDEF logcat -c
# launch Spotify from the Glass launcher, then:
adb -s 0123456789ABCDEF logcat -d | grep GlassSpotify
adb -s 0123456789ABCDEF shell "ls /data/local/tmp/spotify_bootstrap_token"
```

Expected: a log line `imported bootstrap token (N bytes)`, and the bootstrap file is
gone (`No such file or directory`).

- [ ] **Step 6: Human verification checklist**

Wearing the Glass, with Spotify playing on the phone:

- [ ] The launcher shows a card reading **Spotify**
- [ ] Opening it shows the current track title and artist within ~3 seconds
- [ ] **Tap** pauses; the display flips immediately, not after a delay
- [ ] **Tap** again resumes
- [ ] **Swipe forward** skips to the next track and the title updates
- [ ] **Swipe backward** goes to the previous track
- [ ] **Swipe down** exits to the launcher — and does **not** open the notification shade
- [ ] Force-kill Spotify on the phone; the Glass shows `No active device`
- [ ] Turn off Glass WiFi (`svc wifi disable`); the Glass shows `No network` and does not crash
- [ ] Leave the app open for two minutes; it keeps updating and does not ANR

Anything that fails here is a real bug that the test suite structurally cannot catch.
Fix it before declaring the task complete.

- [ ] **Step 7: Write `README.md`**

```markdown
# Glass Spotify Widget

Controls Spotify playback on the phone from Google Glass, over WiFi.

The Glass talks to the Spotify Web API directly; Spotify Connect relays commands to
the phone cloud-side. There is no companion app and no Bluetooth, so replacing the
phone requires no work here — install Spotify, log in, done.

Requires Spotify Premium: the Web API returns 403 on every playback-control endpoint
for Free accounts.

## Controls

| Gesture | Action |
|---|---|
| Tap | Play / pause |
| Swipe forward | Next track |
| Swipe backward | Previous track |
| Swipe down | Exit |

## Build

This project shares `gesture-core` with the Gesture Launcher, which must be checked
out beside it:

    ~/workspace/google-glass-gesture-launcher/
    ~/workspace/google-glass-spotify-widget/

Then:

    ./gradlew :app:assembleDebug
    adb -s 0123456789ABCDEF install -r app/build/outputs/apk/debug/app-debug.apk

First run needs a token — see `tools/README.md`.

## Design

`docs/superpowers/specs/2026-08-07-glass-spotify-widget-design.md`

TLS against this ROM's 2015 trust store was measured working on 2026-08-09; see
`spike/`.
```

- [ ] **Step 8: Commit**

```bash
git add README.md
git commit -m "docs: README with controls, build steps, and the sibling-repo requirement"
```

---

## Self-Review

Run after the plan is written, before execution.

**Spec coverage:**

| Spec requirement | Task |
|---|---|
| Play / pause / next / previous | 6, 7, 11 |
| Now-playing title + artist | 5, 10 |
| Appears as "Spotify" in the launcher | 9 |
| No launcher changes | 9 (verified by install alone) |
| No companion app | Architecture; nothing to build |
| Single APK, two modules | 1, 9 |
| No third-party code in APK | 1 (`compileOnly` org.json) |
| PKCE bootstrap from laptop | 8 |
| Atomic rotated-token persistence | 3 |
| TLS 1.2 pinning, stock trust store | 4 |
| Optimistic UI then reconcile | 7, 11 |
| 3s polling only while resumed | 11 |
| Error table → prism strings | 2 (`Status`), 5, 6 |
| `No network` is routine, not a crash | 2, 5, 12 |
| Immersive mode in `onCreate` + `onWindowFocusChanged` | 9, 11 |
| Native-unit gesture maths | 1 (gesture-core reuse), 11 |
| Unrecognised gesture is a no-op | 11 (`default:` branch) |
| Real-finger testing | 12 |
| JVM-testable core | 2, 3, 5, 6, 7 |

No gaps.

**Placeholder scan:** `CLIENT_ID` is `REPLACE_WITH_YOUR_CLIENT_ID` — this is a genuine
external credential Erin must supply, flagged in Tasks 8, 11, and 12, not a plan
placeholder. No TBDs, no "add error handling", no "similar to Task N".

**Type consistency:** `PlaybackState.playing(String,String,boolean)` and
`PlaybackState.of(Status)` are used consistently in Tasks 2, 5, 7, 10, 11.
`Status` constants match between `Status.java`, the client's `mapFailure`, and the
view. `HttpTransport.execute(String,String,String,String)` has the same four-argument
shape in `FakeHttpTransport`, `UrlHttpTransport`, `TokenStore`, and `SpotifyClient`.
`PlayerController` method names (`toggleOptimistic`, `toggleCommit`, `nextCommit`,
`previousCommit`, `refresh`, `last`) match between Task 7 and Task 11.
