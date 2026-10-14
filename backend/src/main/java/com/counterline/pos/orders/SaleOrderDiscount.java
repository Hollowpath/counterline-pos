package com.counterline.pos.orders;

import com.counterline.pos.discounts.Discount;
import com.counterline.pos.staff.Staff;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.math.BigDecimal;

/**
 * A discount as applied: its terms at the time and the amount it took off. Belongs to a line,
 * or to the whole order when {@code line} is null.
 */
@Entity
@Table(name = "sale_order_discount")
public class SaleOrderDiscount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id")
    private SaleOrder order;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "line_id")
    private SaleOrderLine line;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "discount_id")
    private Discount discount;

    private String name;

    @Enumerated(EnumType.STRING)
    private Discount.Kind kind;

    private BigDecimal value;

    private BigDecimal amount;

    /** The manager who approved it, for a discount that needs approval. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "approved_by")
    private Staff approvedBy;

    protected SaleOrderDiscount() {
    }

    SaleOrderDiscount(SaleOrder order, SaleOrderLine line, Discount discount, BigDecimal amount) {
        this.order = order;
        this.line = line;
        this.discount = discount;
        this.name = discount.getName();
        this.kind = discount.getKind();
        this.value = discount.getValue();
        this.amount = amount;
    }

    public Discount getDiscount() {
        return discount;
    }

    public Staff getApprovedBy() {
        return approvedBy;
    }

    void approve(Staff manager) {
        this.approvedBy = manager;
    }

    public SaleOrderLine getLine() {
        return line;
    }

    /** Identity first (before ids exist); by id otherwise, since a loaded reference may be a proxy. */
    boolean belongsTo(SaleOrderLine candidate) {
        return line == candidate
                || (line != null && candidate.getId() != null && candidate.getId().equals(line.getId()));
    }

    public String getName() {
        return name;
    }

    public BigDecimal getAmount() {
        return amount;
    }
}
