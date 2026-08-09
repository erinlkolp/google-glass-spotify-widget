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
