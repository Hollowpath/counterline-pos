package com.counterline.pos.orders;

import com.counterline.pos.promotions.Promotion;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;

import java.math.BigDecimal;

/**
 * A promotional line made free: the promotion's name at the time, the amount deducted (the whole
 * line) and the points it costs the member, as printed on the receipt
 * ("1x MFOC PC BBQ  RM10.00 / MEMBER REDEMPTION  -RM10.00").
 */
@Entity
@Table(name = "sale_order_promotion")
public class SaleOrderPromotion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id")
    private SaleOrder order;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "line_id")
    private SaleOrderLine line;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "promotion_id")
    private Promotion promotion;

    private String name;

    @Enumerated(EnumType.STRING)
    private Promotion.Type type;

    private BigDecimal amount;

    private int points;

    protected SaleOrderPromotion() {
    }

    SaleOrderPromotion(SaleOrder order, SaleOrderLine line, Promotion promotion) {
        this.order = order;
        this.line = line;
        this.promotion = promotion;
        this.name = promotion.getName();
        this.type = promotion.getType();
        this.amount = line.getLineSubtotal();
        this.points = promotion.pointsPerItem() * line.getQuantity();
    }

    public SaleOrderLine getLine() {
        return line;
    }

    public Promotion getPromotion() {
        return promotion;
    }

    public String getName() {
        return name;
    }

    public Promotion.Type getType() {
        return type;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public int getPoints() {
        return points;
    }

    /** Identity first (before ids exist); by id otherwise, since a loaded reference may be a proxy. */
    boolean belongsTo(SaleOrderLine candidate) {
        return line == candidate
                || (candidate.getId() != null && candidate.getId().equals(line.getId()));
    }
}
