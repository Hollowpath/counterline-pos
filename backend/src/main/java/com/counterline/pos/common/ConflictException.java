package com.counterline.pos.common;

/** The request conflicts with existing data (e.g. a duplicate phone number); mapped to HTTP 409. */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}
