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
