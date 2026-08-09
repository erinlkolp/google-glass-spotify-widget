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

    // volatile for cross-thread visibility only: the worker (refresh/settle) writes
    // it and the main thread (onResume's last(), and toggleOptimistic()) reads and
    // writes it, and Executor.submit only establishes happens-before in the
    // main-to-worker direction. This does not make compound read-modify-write
    // sequences atomic — the class is still documented as not thread-safe, and
    // confining it to one worker plus the main-thread optimistic flip remains the
    // design; volatile just guarantees the main thread never sees a stale value.
    private volatile PlaybackState last = PlaybackState.of(Status.NOTHING_PLAYING);

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
     * <p>The returned value carries the intent, but the caller must capture it and
     * pass it to {@link #toggleCommit(boolean)} explicitly rather than relying on
     * {@link #last}: a poll's queued {@link #refresh()} can land on the worker between
     * this call and the commit and overwrite {@link #last} with the server's stale
     * view, silently discarding the tap if the intent were re-read from the field.
     */
    public PlaybackState toggleOptimistic() {
        last = last.withPlaying(!last.playing);
        return last;
    }

    /**
     * Sends the command the caller decided on, then refetches. Blocking.
     *
     * @param wantPlaying the intent captured from {@link #toggleOptimistic()}'s return
     *     value at tap time, not re-read from {@link #last} — see that method's javadoc.
     */
    public PlaybackState toggleCommit(boolean wantPlaying) {
        Status result = wantPlaying ? client.play() : client.pause();
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
