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
