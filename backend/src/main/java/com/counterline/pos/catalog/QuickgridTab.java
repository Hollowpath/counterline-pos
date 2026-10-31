package com.counterline.pos.catalog;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/** A bottom-bar tab on the sell screen (e.g. "Selling Items") holding a grid of category folders. */
@Entity
@Table(name = "quickgrid_tab")
public class QuickgridTab {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Optimistic lock: a save made from a copy older than the row is refused (see CatalogAdminService). */
    @Version
    private long version;

    private String name;

    private short sortOrder;

    public long getVersion() {
        return version;
    }

    protected QuickgridTab() {
    }

    public QuickgridTab(String name, short sortOrder) {
        this.name = name;
        this.sortOrder = sortOrder;
    }

    void rename(String name) {
        this.name = name;
    }

    void setSortOrder(short sortOrder) {
        this.sortOrder = sortOrder;
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public short getSortOrder() {
        return sortOrder;
    }
}
