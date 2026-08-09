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
