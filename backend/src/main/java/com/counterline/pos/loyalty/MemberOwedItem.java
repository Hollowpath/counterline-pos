package com.counterline.pos.loyalty;

import com.counterline.pos.promotions.Promotion;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Free items a member was owed but not given (e.g. the staff forgot the birthday can). Staff record
 * it on the dashboard; the member then gets that many of the promotion's items free on a later sale,
 * whatever that sale's own rules would allow.
 */
@Entity
@Table(name = "member_owed_item")
class MemberOwedItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long customerId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "promotion_id")
    private Promotion promotion;

    private short quantity;

    private short quantityUsed;

    private String reason;

    private String givenBy;

    private Instant createdAt;

    protected MemberOwedItem() {
    }

    MemberOwedItem(Long customerId, Promotion promotion, short quantity, String reason, String givenBy, Instant createdAt) {
        this.customerId = customerId;
        this.promotion = promotion;
        this.quantity = quantity;
        this.reason = reason;
        this.givenBy = givenBy;
        this.createdAt = createdAt;
    }

    int remaining() {
        return quantity - quantityUsed;
    }

    /** Uses up to {@code wanted}; returns how many it used. */
    int use(int wanted) {
        int used = Math.min(wanted, remaining());
        quantityUsed += (short) used;
        return used;
    }

    Long getId() {
        return id;
    }

    Promotion getPromotion() {
        return promotion;
    }

    String getReason() {
        return reason;
    }

    String getGivenBy() {
        return givenBy;
    }

    Instant getCreatedAt() {
        return createdAt;
    }
}
