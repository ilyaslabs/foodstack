package io.github.ilyaslabs.foodstack.platform.core;

/**
 * A per-user limit was reached (TDD 8.1): answered with {@code 429}, code {@code RATE_LIMITED} and a
 * {@code Retry-After} header. Thrown only inside the module that serves the request.
 */
public class TooManyRequestsException extends RuntimeException {

    public static final String CODE = "RATE_LIMITED";

    private final long retryAfterSeconds;

    public TooManyRequestsException(String message, long retryAfterSeconds) {
        super(message);
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long retryAfterSeconds() {
        return this.retryAfterSeconds;
    }
}
