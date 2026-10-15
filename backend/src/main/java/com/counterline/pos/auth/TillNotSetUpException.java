package com.counterline.pos.auth;

/**
 * The iPad's key is unknown or the iPad was reset from the dashboard: it has to be set up again by
 * a manager. Reported as 410 Gone, which tells the till to forget its outlet and show the set-up
 * screen.
 */
public class TillNotSetUpException extends RuntimeException {

    public TillNotSetUpException() {
        super("iPad reset");
    }
}
