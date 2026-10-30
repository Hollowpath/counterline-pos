package com.counterline.pos.loyalty;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * A member's points balance: the sum of their {@link LoyaltyEntry} rows. When positive points expire is not stored here:
 * it follows from the member's purchases (see {@link LoyaltyService}).
 */
@Entity
@Table(name = "loyalty_account")
class LoyaltyAccount {

    @Id
    private Long customerId;

    private int pointsBalance;

    private Instant updatedAt;

    protected LoyaltyAccount() {
    }

    Long getCustomerId() {
        return customerId;
    }

    int getPointsBalance() {
        return pointsBalance;
    }

    void add(int points, Instant at) {
        this.pointsBalance += points;
        this.updatedAt = at;
    }

    /** The points ran out of time: nothing left. */
    void expire(Instant at) {
        this.pointsBalance = 0;
        this.updatedAt = at;
    }
}
