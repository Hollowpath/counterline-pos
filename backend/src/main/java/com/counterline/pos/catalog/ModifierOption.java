package com.counterline.pos.catalog;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;

@Entity
@Table(name = "modifier_option")
public class ModifierOption {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Optimistic lock: a save made from a copy older than the row is refused (see ModifierAdminService). */
    @Version
    private long version;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "group_id")
    private ModifierGroup group;

    private String name;

    /** Added to the item's unit price for each time the option is picked (usually 0). */
    private BigDecimal price;

    private short sortOrder;

    private boolean active;

    public long getVersion() {
        return version;
    }

    protected ModifierOption() {
    }

    /** A new, active option at the end of the group's options (from the dashboard). */
    ModifierOption(ModifierGroup group, String name, BigDecimal price, int sortOrder) {
        this.group = group;
        this.name = name;
        this.price = price;
        this.sortOrder = (short) sortOrder;
        this.active = true;
    }

    void edit(String name, BigDecimal price, boolean active) {
        this.name = name;
        this.price = price;
        this.active = active;
    }

    void setSortOrder(int sortOrder) {
        this.sortOrder = (short) sortOrder;
    }

    public int getSortOrder() {
        return sortOrder;
    }

    public Long getId() {
        return id;
    }

    public ModifierGroup getGroup() {
        return group;
    }

    public String getName() {
        return name;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public boolean isActive() {
        return active;
    }
}
