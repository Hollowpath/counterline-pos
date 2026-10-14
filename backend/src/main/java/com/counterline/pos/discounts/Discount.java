package com.counterline.pos.discounts;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** A discount offered at the till: a percentage ("Staff Discount 30%") or a fixed amount ("RM10 Voucher"). */
@Entity
@Table(name = "discount")
public class Discount {

    public enum Kind {
        PERCENT,
        AMOUNT
    }

    /** Where it is offered: the item dialog, the order's % tab, or both. */
    public enum Scope {
        ITEM,
        ORDER,
        BOTH,
        /** A voucher batch's: applied by keying in a voucher code, never offered on a list. */
        VOUCHER;

        public boolean allows(Scope target) {
            return this == target || (this == BOTH && target != VOUCHER);
        }
    }

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;

    @Enumerated(EnumType.STRING)
    private Kind kind;

    private BigDecimal value;

    @Enumerated(EnumType.STRING)
    private Scope scope;

    private short sortOrder;

    /** A manager must approve it at the till (the department comps). */
    private boolean requiresApproval;

    /** Can't be given on a member's sale (the staff discount). */
    private boolean notForMembers;

    /**
     * A paper voucher keyed in at the till (the "RM10 Voucher"): its sale gives no free items, as with
     * a dashboard voucher. See {@link #isVoucher()}.
     */
    private boolean countsAsVoucher;

    private boolean active;

    protected Discount() {
    }

    /** For tests and seeding; discounts are otherwise configuration. */
    public Discount(String name, Kind kind, BigDecimal value, Scope scope) {
        this.name = name;
        this.kind = kind;
        this.value = value;
        this.scope = scope;
        this.active = true;
    }

    /** Head office edits a discount; past sales keep the terms they were given with. */
    public void edit(String name, Kind kind, BigDecimal value, Scope scope, boolean requiresApproval,
                     boolean notForMembers, boolean countsAsVoucher, boolean active) {
        this.name = name;
        this.kind = kind;
        this.value = value;
        this.scope = scope;
        this.requiresApproval = requiresApproval;
        this.notForMembers = notForMembers;
        this.countsAsVoucher = countsAsVoucher;
        this.active = active;
    }

    public boolean isNotForMembers() {
        return notForMembers;
    }

    public boolean isCountsAsVoucher() {
        return countsAsVoucher;
    }

    /** A voucher: a dashboard voucher batch's discount, or one that counts as a voucher. */
    public boolean isVoucher() {
        return scope == Scope.VOUCHER || countsAsVoucher;
    }

    void placeAt(short sortOrder) {
        this.sortOrder = sortOrder;
    }

    /** What this discount alone takes off {@code base}, before any cap: percentages round half-up to the sen. */
    public BigDecimal amountOf(BigDecimal base) {
        return kind == Kind.PERCENT
                ? base.multiply(value).divide(HUNDRED, 2, RoundingMode.HALF_UP)
                : value.setScale(2, RoundingMode.UNNECESSARY);
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public Kind getKind() {
        return kind;
    }

    public BigDecimal getValue() {
        return value;
    }

    public Scope getScope() {
        return scope;
    }

    public short getSortOrder() {
        return sortOrder;
    }

    public boolean isRequiresApproval() {
        return requiresApproval;
    }

    public boolean isActive() {
        return active;
    }
}
