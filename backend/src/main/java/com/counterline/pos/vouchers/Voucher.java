package com.counterline.pos.vouchers;

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

/** One physical voucher: used once, until its batch's expiry date. */
@Entity
@Table(name = "voucher")
public class Voucher {

    public enum Status {
        UNUSED,
        USED,
        EXPIRED,
        CANCELLED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "batch_id")
    private VoucherBatch batch;

    private String code;

    private Long usedOrderId;

    private Instant usedAt;

    private Instant cancelledAt;

    protected Voucher() {
    }

    Voucher(VoucherBatch batch, String code) {
        this.batch = batch;
        this.code = code;
    }

    Status statusOn(LocalDate day) {
        if (cancelledAt != null) {
            return Status.CANCELLED;
        }
        if (usedOrderId != null) {
            return Status.USED;
        }
        return day.isAfter(batch.getExpiresOn()) ? Status.EXPIRED : Status.UNUSED;
    }

    void use(Long orderId, Instant at) {
        usedOrderId = orderId;
        usedAt = at;
    }

    /** The sale it was used on was voided: the voucher can be used again. */
    void release() {
        usedOrderId = null;
        usedAt = null;
    }

    Long getId() {
        return id;
    }

    VoucherBatch getBatch() {
        return batch;
    }

    public String getCode() {
        return code;
    }

    Long getUsedOrderId() {
        return usedOrderId;
    }

    Instant getUsedAt() {
        return usedAt;
    }
}
