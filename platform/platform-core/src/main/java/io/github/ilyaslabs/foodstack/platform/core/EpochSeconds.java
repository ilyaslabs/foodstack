package io.github.ilyaslabs.foodstack.platform.core;

import java.time.Instant;

/**
 * Date-times cross the API as UTC epoch seconds (TDD 6.2); they are stored as {@link Instant}.
 */
public final class EpochSeconds {

    private EpochSeconds() {
    }

    /** Epoch seconds of an instant, or {@code null} for {@code null}. */
    public static Long of(Instant instant) {
        return (instant != null) ? instant.getEpochSecond() : null;
    }

    /** The instant of epoch seconds, or {@code null} for {@code null}. */
    public static Instant toInstant(Long epochSeconds) {
        return (epochSeconds != null) ? Instant.ofEpochSecond(epochSeconds) : null;
    }
}
