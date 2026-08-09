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
