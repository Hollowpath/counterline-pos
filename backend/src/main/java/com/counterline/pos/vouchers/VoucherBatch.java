package com.counterline.pos.vouchers;

import com.counterline.pos.discounts.Discount;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDate;

/** A print run of physical vouchers: same value, same expiry, one code each. */
@Entity
@Table(name = "voucher_batch")
class VoucherBatch {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;

    /** What a voucher takes off the sale (scope VOUCHER). */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "discount_id")
    private Discount discount;

    private LocalDate expiresOn;

    private String createdBy;

    private Instant createdAt;

    protected VoucherBatch() {
    }

    VoucherBatch(String name, Discount discount, LocalDate expiresOn, String createdBy, Instant createdAt) {
        this.name = name;
        this.discount = discount;
        this.expiresOn = expiresOn;
        this.createdBy = createdBy;
        this.createdAt = createdAt;
    }

    Long getId() {
        return id;
    }

    String getName() {
        return name;
    }

    Discount getDiscount() {
        return discount;
    }

    LocalDate getExpiresOn() {
        return expiresOn;
    }

    String getCreatedBy() {
        return createdBy;
    }

    Instant getCreatedAt() {
        return createdAt;
    }
}
