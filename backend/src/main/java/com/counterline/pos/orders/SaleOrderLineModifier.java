package com.counterline.pos.orders;

import com.counterline.pos.catalog.ModifierOption;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.math.BigDecimal;

/** An option picked for each unit of a line (e.g. 3 × Caramel in a gift box), snapshotted at sale time. */
@Entity
@Table(name = "sale_order_line_modifier")
public class SaleOrderLineModifier {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "line_id")
    private SaleOrderLine line;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "option_id")
    private ModifierOption option;

    private String groupName;
    private String optionName;
    private BigDecimal unitPrice;
    private short quantity;

    protected SaleOrderLineModifier() {
    }

    SaleOrderLineModifier(SaleOrderLine line, ModifierOption option, int quantity) {
        this.line = line;
        this.option = option;
        this.groupName = option.getGroup().getName();
        this.optionName = option.getName();
        this.unitPrice = option.getPrice();
        this.quantity = (short) quantity;
    }

    /** The option picked: its current price, for editing a placed order. */
    public ModifierOption getOption() {
        return option;
    }

    public String getGroupName() {
        return groupName;
    }

    public String getOptionName() {
        return optionName;
    }

    public BigDecimal getUnitPrice() {
        return unitPrice;
    }

    public int getQuantity() {
        return quantity;
    }
}
