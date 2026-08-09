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
