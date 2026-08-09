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
