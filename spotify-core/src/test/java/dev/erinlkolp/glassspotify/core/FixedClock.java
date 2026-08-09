package dev.erinlkolp.glassspotify.core;

/** A clock the tests move by hand. */
public final class FixedClock implements Clock {

    private long nowMs;

    public FixedClock(long startMs) {
        this.nowMs = startMs;
    }

    public void advance(long deltaMs) {
        nowMs += deltaMs;
    }

    @Override
    public long nowMs() {
        return nowMs;
    }
}
