package com.counterline.pos.catalog;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.math.BigDecimal;

@Entity
@Table(name = "modifier_option")
public class ModifierOption {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "group_id")
    private ModifierGroup group;

    private String name;

    /** Added to the item's unit price for each time the option is picked (usually 0). */
    private BigDecimal price;

    private short sortOrder;

    private boolean active;

    protected ModifierOption() {
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
