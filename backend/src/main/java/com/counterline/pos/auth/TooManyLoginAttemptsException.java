package com.counterline.pos.auth;

import java.time.Duration;

public class TooManyLoginAttemptsException extends RuntimeException {

    private final Duration retryAfter;
    private final boolean managerCanUnlock;

    /** @param managerCanUnlock an outlet's PIN pad, which its manager can unlock from the dashboard */
    public TooManyLoginAttemptsException(Duration retryAfter, boolean managerCanUnlock) {
        super("Too many failed PIN attempts");
        this.retryAfter = retryAfter;
        this.managerCanUnlock = managerCanUnlock;
    }

    /** Whole seconds, rounded up so clients never retry early. */
    public long retryAfterSeconds() {
        return Math.max(1, (retryAfter.toMillis() + 999) / 1000);
    }

    public boolean managerCanUnlock() {
        return managerCanUnlock;
    }
}
