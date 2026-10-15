package com.counterline.pos.auth;

/**
 * The PIN entered for special access is wrong or not a manager's. Reported as 403, not 401:
 * the terminal's own session is still valid and must not be logged out.
 */
public class SpecialAccessDeniedException extends RuntimeException {

    static final String MESSAGE = "Manager PIN";

    public SpecialAccessDeniedException() {
        super(MESSAGE);
    }

    public SpecialAccessDeniedException(String message) {
        super(message);
    }
}
