package com.counterline.pos.common;

/** Input that passed bean validation but is still unusable (e.g. a phone number that cannot be normalised); HTTP 400. */
public class BadRequestException extends RuntimeException {

    public BadRequestException(String message) {
        super(message);
    }
}
