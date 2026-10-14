package com.counterline.pos.orders;

import com.counterline.pos.staff.Staff;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;

@Entity
@Table(name = "payment")
public class Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id")
    private SaleOrder order;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "payment_method_id")
    private PaymentMethod paymentMethod;

    /** The amount applied to the order. */
    private BigDecimal amount;

    private String reference;

    /** Cash only: what the customer handed over (at least {@link #amount}); null when not recorded. */
    private BigDecimal cashTendered;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "staff_id")
    private Staff staff;

    private Instant createdAt;

    protected Payment() {
    }

    Payment(SaleOrder order, PaymentMethod paymentMethod, BigDecimal amount, String reference,
            BigDecimal cashTendered, Staff staff, Instant createdAt) {
        this.order = order;
        this.paymentMethod = paymentMethod;
        // Amounts are validated to at most 2 decimal places, so this never rounds.
        this.amount = amount.setScale(2, RoundingMode.UNNECESSARY);
        this.reference = reference;
        this.cashTendered = cashTendered == null ? null : cashTendered.setScale(2, RoundingMode.UNNECESSARY);
        this.staff = staff;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public PaymentMethod getPaymentMethod() {
        return paymentMethod;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getReference() {
        return reference;
    }

    public BigDecimal getCashTendered() {
        return cashTendered;
    }

    /** Change given back for a cash payment, or null. */
    public BigDecimal getChange() {
        return cashTendered == null ? null : cashTendered.subtract(amount);
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
