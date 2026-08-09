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
