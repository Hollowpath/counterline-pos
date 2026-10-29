package com.counterline.pos.catalog;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** An item's option group, e.g. "Mini Tin Flavours — CHOOSE 10": exactly that many picks in total. */
@Entity
@Table(name = "modifier_group")
public class ModifierGroup {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "item_id")
    private Item item;

    private String name;

    private short chooseExactly;

    private short sortOrder;

    @OneToMany(mappedBy = "group")
    @OrderBy("sortOrder")
    private List<ModifierOption> options = new ArrayList<>();

    protected ModifierGroup() {
    }

    /** A new group at the end of the item's groups (from the dashboard). */
    ModifierGroup(Item item, String name, int chooseExactly, int sortOrder) {
        this.item = item;
        this.name = name;
        this.chooseExactly = (short) chooseExactly;
        this.sortOrder = (short) sortOrder;
    }

    void edit(String name, int chooseExactly) {
        this.name = name;
        this.chooseExactly = (short) chooseExactly;
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

    public Long getItemId() {
        return item.getId();
    }

    public String getName() {
        return name;
    }

    public int getChooseExactly() {
        return chooseExactly;
    }

    public List<ModifierOption> getOptions() {
        return Collections.unmodifiableList(options);
    }
}
