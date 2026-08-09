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
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
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
    private static final Charset UTF8 = Charset.forName("UTF-8");

    /** From the Spotify developer dashboard. PKCE uses no client secret. */
    private static final String CLIENT_ID = "26b546a81db94a12a0a7621d37724b93";

    /** Where bootstrap_token.py's output is pushed before first run. */
    private static final String BOOTSTRAP_PATH = "/data/local/tmp/spotify_bootstrap_token";
    private static final String TOKEN_FILE = "refresh_token";
    /** Fingerprint of the last bootstrap token actually imported; guards re-import. */
    private static final String BOOTSTRAP_FINGERPRINT_FILE = "bootstrap_fingerprint";

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
     * Moves a pushed bootstrap token into private storage, but only once per distinct
     * token value — the delete below is best-effort, not the mechanism that
     * guarantees that.
     *
     * <p>Avoids chown gymnastics: adb pushes to /data/local/tmp, and the app adopts it
     * on first launch. Deleting the pushed copy is attempted so a stale file does not
     * linger, but /data/local/tmp conventionally has the sticky bit set, so this
     * app's uid may not be able to remove a file the shell uid pushed. Relying on
     * that delete succeeding would risk re-importing the same bootstrap token on a
     * later launch, overwriting whatever {@link TokenStore} has since rotated to —
     * and because Spotify invalidates a refresh token once it has been exchanged,
     * that is a silent, permanent lockout requiring a fresh laptop bootstrap.
     *
     * <p>So idempotence is enforced independently of the delete: a SHA-256
     * fingerprint of the last-imported token is kept alongside the real token file.
     * A pushed file whose fingerprint matches the stored one has already been
     * consumed and is skipped, even if it could not be deleted. A genuinely new
     * pushed token has a different fingerprint and is imported as usual.
     */
    private void importBootstrapTokenIfPresent() {
        File pushed = new File(BOOTSTRAP_PATH);
        if (!pushed.isFile()) {
            return;
        }
        try {
            byte[] contents = readFully(pushed);
            String fingerprint = sha256Hex(contents);

            File fingerprintFile = new File(getFilesDir(), BOOTSTRAP_FINGERPRINT_FILE);
            String storedFingerprint = readFileIfPresent(fingerprintFile);
            if (fingerprint.equals(storedFingerprint)) {
                Log.i(TAG, "bootstrap token already imported; skipping");
            } else {
                File destination = new File(getFilesDir(), TOKEN_FILE);
                writeAtomically(destination, contents);
                writeAtomically(fingerprintFile, fingerprint.getBytes(UTF8));
                Log.i(TAG, "imported bootstrap token (" + contents.length + " bytes)");
            }

            if (!pushed.delete()) {
                Log.w(TAG, "could not delete " + BOOTSTRAP_PATH + "; delete it by hand");
            }
        } catch (IOException e) {
            Log.e(TAG, "bootstrap token import failed", e);
        } catch (NoSuchAlgorithmException e) {
            Log.e(TAG, "bootstrap token import failed", e);
        }
    }

    /**
     * Reads a whole file without {@code java.nio.file} — that is API 26+ and would
     * throw {@code NoClassDefFoundError} on this API 22 device despite compiling
     * cleanly here.
     */
    private static byte[] readFully(File file) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        InputStream in = new FileInputStream(file);
        try {
            byte[] chunk = new byte[1024];
            int read;
            while ((read = in.read(chunk)) != -1) {
                buffer.write(chunk, 0, read);
            }
        } finally {
            in.close();
        }
        return buffer.toByteArray();
    }

    /** @return the file's contents as UTF-8, or null if it does not exist. */
    private static String readFileIfPresent(File file) throws IOException {
        if (!file.isFile()) {
            return null;
        }
        return new String(readFully(file), UTF8);
    }

    /**
     * Writes to a sibling temp file, then renames — the same discipline
     * {@link TokenStore} uses, so a half-written marker can never be read as valid.
     */
    private static void writeAtomically(File destination, byte[] contents) throws IOException {
        File temp = new File(destination.getParentFile(), destination.getName() + ".tmp");
        OutputStream out = new FileOutputStream(temp);
        try {
            out.write(contents);
            out.flush();
        } finally {
            out.close();
        }
        if (!temp.renameTo(destination)) {
            temp.delete();
            throw new IOException("rename failed: " + temp + " -> " + destination);
        }
    }

    private static String sha256Hex(byte[] data) throws NoSuchAlgorithmException {
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(data);
        StringBuilder hex = new StringBuilder(hash.length * 2);
        for (int i = 0; i < hash.length; i++) {
            String part = Integer.toHexString(0xff & hash[i]);
            if (part.length() == 1) {
                hex.append('0');
            }
            hex.append(part);
        }
        return hex.toString();
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
        PlaybackState flipped = controller.toggleOptimistic();
        view.render(flipped);
        // Capture the intent here, on the main thread, rather than letting the worker
        // re-read PlayerController.last(): a poll's queued refresh() can land on the
        // worker between this flip and the commit and overwrite last with the
        // server's stale view, which would silently discard this tap.
        submitToggle(flipped.playing);
    }

    private enum Command { NEXT, PREVIOUS }

    private void submitToggle(final boolean wantPlaying) {
        worker.submit(new Runnable() {
            @Override
            public void run() {
                settleAndPublish(controller.toggleCommit(wantPlaying));
            }
        });
    }

    private void submitCommand(final Command command) {
        worker.submit(new Runnable() {
            @Override
            public void run() {
                PlaybackState state;
                switch (command) {
                    case NEXT:
                        state = controller.nextCommit();
                        break;
                    default:
                        state = controller.previousCommit();
                        break;
                }
                settleAndPublish(state);
            }
        });
    }

    /**
     * Publishes the command's immediate result, then — on success — waits out
     * {@link #SETTLE_DELAY_MS} and takes one more reading before publishing again.
     *
     * <p>Applies to every command, not just skips: {@code /v1/me/player} is
     * eventually consistent for a few hundred milliseconds after any command
     * (toggle included), so refetching immediately commonly renders
     * correct → wrong → correct three seconds later at the next poll. Runs on the
     * worker; must not be called from the main thread.
     */
    private void settleAndPublish(PlaybackState state) {
        publish(state);
        if (state.status == Status.OK) {
            try {
                Thread.sleep(SETTLE_DELAY_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            publish(controller.refresh());
        }
    }

    private void submitRefresh() {
        worker.submit(new Runnable() {
            @Override
            public void run() {
                publish(controller.refresh());
            }
        });
    }

    /**
     * Posts a render to the main thread, unless the Activity is on its way out.
     *
     * <p>{@code worker.shutdownNow()} in {@link #onDestroy()} only interrupts a
     * running task; it cannot unblock {@link UrlHttpTransport}'s blocking
     * {@code HttpsURLConnection} I/O (up to 10s connect + 10s read), so a command
     * already in flight when the user exits can still be running when this posts.
     * Checking {@code isFinishing()}/{@code isDestroyed()} here — on the main thread,
     * right before touching the view — stops that stale result from rendering into
     * (and thereby keeping alive) a detached view.
     */
    private void publish(final PlaybackState state) {
        main.post(new Runnable() {
            @Override
            public void run() {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                view.render(state);
            }
        });
    }
}
