package com.counterline.pos.common;

/**
 * A well-formed request that breaks a business rule (e.g. payments do not cover the total);
 * mapped to HTTP 422.
 */
public class BusinessRuleException extends RuntimeException {

    public BusinessRuleException(String message) {
        super(message);
    }
}
