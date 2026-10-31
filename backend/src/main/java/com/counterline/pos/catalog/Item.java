package com.counterline.pos.catalog;

import com.counterline.pos.promotions.Promotion;
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
import java.time.LocalDate;

@Entity
@Table(name = "item")
public class Item {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Optimistic lock: a save made from a copy older than the row is refused (see CatalogAdminService). */
    @Version
    private long version;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "category_id")
    private Category category;

    private String name;

    private BigDecimal price;

    private String imageUrl;

    private int sortOrder;

    private boolean active;

    /** Set on promotional items: they are free when the promotion applies, and need a member. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "promotion_id")
    private Promotion promotion;

    /**
     * A seasonal flavour, between {@code seasonalFrom} and {@code seasonalUntil} (inclusive; null for
     * no start / no end): it earns the seasonal-flavour promotion's free item. Outside those days it
     * is an ordinary item.
     */
    private boolean seasonal;

    private LocalDate seasonalFrom;

    private LocalDate seasonalUntil;

    public long getVersion() {
        return version;
    }

    protected Item() {
    }

    /** A new item at the end of its folder (from the dashboard). */
    Item(Category category, String name, BigDecimal price, int sortOrder) {
        this.category = category;
        this.name = name;
        this.price = price;
        this.sortOrder = sortOrder;
        this.active = true;
    }

    /** Head office edits an item; past sales keep the name and price they were sold at. */
    void edit(String name, BigDecimal price, boolean active) {
        this.name = name;
        this.price = price;
        this.active = active;
    }

    /** Moves the item to the end of another folder. */
    void moveTo(Category category, int sortOrder) {
        this.category = category;
        this.sortOrder = sortOrder;
    }

    void setSortOrder(int sortOrder) {
        this.sortOrder = sortOrder;
    }

    /** Marks the item a seasonal flavour for those days, or not at all ({@code seasonal} false). */
    void setSeasonal(boolean seasonal, LocalDate from, LocalDate until) {
        this.seasonal = seasonal;
        this.seasonalFrom = seasonal ? from : null;
        this.seasonalUntil = seasonal ? until : null;
    }

    public Long getId() {
        return id;
    }

    public Category getCategory() {
        return category;
    }

    public String getName() {
        return name;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public String getImageUrl() {
        return imageUrl;
    }

    public int getSortOrder() {
        return sortOrder;
    }

    public boolean isActive() {
        return active;
    }

    public Promotion getPromotion() {
        return promotion;
    }

    /** Marked as a seasonal flavour, whatever the dates (see {@link #isSeasonalOn}). */
    public boolean isSeasonal() {
        return seasonal;
    }

    public LocalDate getSeasonalFrom() {
        return seasonalFrom;
    }

    public LocalDate getSeasonalUntil() {
        return seasonalUntil;
    }

    /** A seasonal flavour on that day (in the outlet's time zone). */
    public boolean isSeasonalOn(LocalDate day) {
        return seasonal
                && (seasonalFrom == null || !day.isBefore(seasonalFrom))
                && (seasonalUntil == null || !day.isAfter(seasonalUntil));
    }
}
