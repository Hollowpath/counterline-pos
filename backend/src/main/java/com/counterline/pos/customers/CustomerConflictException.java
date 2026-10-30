package com.counterline.pos.customers;

import com.counterline.pos.common.ConflictException;

/**
 * A phone number, email or reference ID that another member already has.
 * {@code inactive}: that member is deactivated (a manager can reactivate them; no new member is made).
 */
public class CustomerConflictException extends ConflictException {

    public enum Field {
        PHONE, EMAIL, REFERENCE
    }

    private final Field field;
    private final boolean inactive;

    CustomerConflictException(Field field, boolean inactive, String message) {
        super(message);
        this.field = field;
        this.inactive = inactive;
    }

    public Field field() {
        return field;
    }

    public boolean inactive() {
        return inactive;
    }
}
