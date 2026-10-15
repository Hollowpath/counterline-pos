package com.counterline.pos.auth;

import java.time.Duration;

public class TooManyLoginAttemptsException extends RuntimeException {

    private final Duration retryAfter;

    public TooManyLoginAttemptsException(Duration retryAfter) {
        super("Too many failed PIN attempts");
        this.retryAfter = retryAfter;
    }

    /** Whole seconds, rounded up so clients never retry early. */
    public long retryAfterSeconds() {
        return Math.max(1, (retryAfter.toMillis() + 999) / 1000);
    }
}
