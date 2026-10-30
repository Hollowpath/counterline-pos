package com.counterline.pos.loyalty;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** One change to a member's points: tied to the sale that caused it, or a staff adjustment with its reason. */
@Entity
@Table(name = "loyalty_entry")
class LoyaltyEntry {

    enum Type {
        EARN,
        REDEEM,
        /** A refund takes back the points the sale earned... */
        REVERSE_EARN,
        /** ...and gives back the points it spent. */
        REVERSE_REDEEM,
        /** Staff corrected the balance (e.g. points a sale should have earned), with a reason. */
        ADJUST,
        /** The positive balance ran out of time (365 days after the last entry) and went to zero. Not a sale's or a staff member's. */
        EXPIRE
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long customerId;

    private Long orderId;

    @Enumerated(EnumType.STRING)
    private Type type;

    /** Positive for EARN and REVERSE_REDEEM, negative for REDEEM, REVERSE_EARN and EXPIRE, either for ADJUST. */
    private int points;

    private Instant createdAt;

    private String reason;

    private String adjustedBy;

    protected LoyaltyEntry() {
    }

    static LoyaltyEntry adjustment(Long customerId, int points, String reason, String adjustedBy, Instant at) {
        LoyaltyEntry entry = new LoyaltyEntry(customerId, null, Type.ADJUST, points, at);
        entry.reason = reason;
        entry.adjustedBy = adjustedBy;
        return entry;
    }

    LoyaltyEntry(Long customerId, Long orderId, Type type, int points, Instant createdAt) {
        this.customerId = customerId;
        this.orderId = orderId;
        this.type = type;
        this.points = points;
        this.createdAt = createdAt;
    }
}
