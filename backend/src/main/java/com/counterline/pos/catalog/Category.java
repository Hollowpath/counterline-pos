package com.counterline.pos.catalog;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * A product category. When placed on a quickgrid tab it is shown as a folder at a fixed grid cell;
 * otherwise it only appears in the library and reports.
 */
@Entity
@Table(name = "category")
public class Category {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tab_id")
    private QuickgridTab tab;

    private Short gridRow;

    private Short gridCol;

    /** Its items count towards "Buy 3" promotions (a gift box counts as one item). */
    private boolean countsForPromotions;

    protected Category() {
    }

    /** A folder at the given cell (1 to 5 each) of {@code tab}. */
    public Category(String name, QuickgridTab tab, short gridRow, short gridCol) {
        this.name = name;
        this.tab = tab;
        this.gridRow = gridRow;
        this.gridCol = gridCol;
    }

    void rename(String name) {
        this.name = name;
    }

    void placeOn(QuickgridTab tab, short gridRow, short gridCol) {
        this.tab = tab;
        this.gridRow = gridRow;
        this.gridCol = gridCol;
    }

    /** Takes the folder off the till grid; it still exists in the library and reports. */
    void removeFromGrid() {
        this.tab = null;
        this.gridRow = null;
        this.gridCol = null;
    }

    void setCountsForPromotions(boolean countsForPromotions) {
        this.countsForPromotions = countsForPromotions;
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public QuickgridTab getTab() {
        return tab;
    }

    public Short getGridRow() {
        return gridRow;
    }

    public Short getGridCol() {
        return gridCol;
    }

    public boolean isCountsForPromotions() {
        return countsForPromotions;
    }
}
