package dev.erinlkolp.glassspotify.core;

/**
 * Wall-clock milliseconds.
 *
 * <p>Exists because {@code java.time} is API 26+ and this project targets API 22.
 * Tests substitute a fixed implementation so token-expiry logic is deterministic.
 */
public interface Clock {

    long nowMs();

    Clock SYSTEM = new Clock() {
        @Override
        public long nowMs() {
            return System.currentTimeMillis();
        }
    };
}
