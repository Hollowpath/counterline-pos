package com.counterline.pos.auth;

/**
 * The one action a manager's approval token ("Special Access Required") authorises. The token is
 * issued for exactly one of these, and only that action accepts it.
 */
public enum ApprovalOperation {
    /** A department comp: one completed sale, with as many comped lines as it holds. */
    DISCOUNT,
    /** Voiding one unpaid order. */
    VOID,
    /** Refunding one paid order. */
    REFUND;

    /** The operation a token's claim names; null when it is missing or not one of these, exactly spelled. */
    static ApprovalOperation of(Object claim) {
        if (claim == null) {
            return null;
        }
        for (ApprovalOperation operation : values()) {
            if (operation.name().equals(claim.toString())) {
                return operation;
            }
        }
        return null;
    }
}
