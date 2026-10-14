package com.counterline.pos.auth;

/** No active staff member has the submitted PIN. Deliberately says nothing more specific. */
public class InvalidPinException extends RuntimeException {

    public InvalidPinException() {
        super("Invalid PIN");
    }

    public InvalidPinException(String message) {
        super(message);
    }
}
