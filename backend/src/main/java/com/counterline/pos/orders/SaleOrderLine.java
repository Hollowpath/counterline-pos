package com.counterline.pos.orders;

import com.counterline.pos.catalog.Item;
import com.counterline.pos.orders.ModifierSelection.Chosen;
import jakarta.persistence.CascadeType;
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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * An order line: a catalogue item (with any options picked per unit) or a custom amount.
 * Name, category and prices are copied at the time of sale.
 * <pre>
 *   lineSubtotal = (unitPrice + modifierUnitPrice) × quantity
 *   lineTotal    = lineSubtotal − discountTotal
 * </pre>
 */
@Entity
@Table(name = "sale_order_line")
public class SaleOrderLine {

    static final String CUSTOM_AMOUNT = "Custom Amount";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id")
    private SaleOrder order;

    private short lineNumber;

    /** Null for a custom amount. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "item_id")
    private Item item;

    private String itemName;
    private String categoryName;
    private String note;
    private BigDecimal unitPrice;
    private BigDecimal modifierUnitPrice;
    private int quantity;
    private BigDecimal lineSubtotal;
    private BigDecimal discountTotal;
    private BigDecimal lineTotal;

    /** What the catalogue said when it was sold: the promotions are judged by these, not by today's catalogue. */
    private boolean countsForPromotions;
    private boolean seasonal;
    private LocalDate seasonalFrom;
    private LocalDate seasonalUntil;

    @OneToMany(mappedBy = "line", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id")
    private List<SaleOrderLineModifier> modifiers = new ArrayList<>();

    protected SaleOrderLine() {
    }

    private SaleOrderLine(SaleOrder order, int lineNumber, Item item, String itemName, String categoryName,
                          BigDecimal unitPrice, int quantity, String note) {
        this.order = order;
        this.lineNumber = (short) lineNumber;
        this.item = item;
        this.itemName = itemName;
        this.categoryName = categoryName;
        this.unitPrice = unitPrice;
        this.modifierUnitPrice = BigDecimal.ZERO.setScale(2);
        this.quantity = quantity;
        this.note = note;
        this.discountTotal = BigDecimal.ZERO.setScale(2);
        recalculate();
    }

    static SaleOrderLine forItem(SaleOrder order, int lineNumber, Item item, List<Chosen> chosen, int quantity, String note) {
        SaleOrderLine line = new SaleOrderLine(order, lineNumber, item, item.getName(), item.getCategory().getName(),
                item.getPrice(), quantity, note);
        line.countsForPromotions = item.getCategory().isCountsForPromotions();
        line.seasonal = item.isSeasonal();
        line.seasonalFrom = item.getSeasonalFrom();
        line.seasonalUntil = item.getSeasonalUntil();
        chosen.forEach(c -> line.modifiers.add(new SaleOrderLineModifier(line, c.option(), c.quantity())));
        line.modifierUnitPrice = ModifierSelection.unitPrice(chosen);
        line.recalculate();
        return line;
    }

    static SaleOrderLine forCustomAmount(SaleOrder order, int lineNumber, BigDecimal amount, int quantity, String note) {
        return new SaleOrderLine(order, lineNumber, null, CUSTOM_AMOUNT, CUSTOM_AMOUNT, amount, quantity, note);
    }

    /** Sets the line's discount total (computed by the order from the applied discounts). */
    void setDiscountTotal(BigDecimal discountTotal) {
        this.discountTotal = discountTotal;
        recalculate();
    }

    private void recalculate() {
        lineSubtotal = unitPrice.add(modifierUnitPrice).multiply(BigDecimal.valueOf(quantity));
        lineTotal = lineSubtotal.subtract(discountTotal);
    }

    public Long getId() {
        return id;
    }

    public Long getOrderId() {
        return order.getId();
    }

    public short getLineNumber() {
        return lineNumber;
    }

    /** Whether the item's folder counted towards promotions when it was sold. */
    public boolean isCountsForPromotions() {
        return countsForPromotions;
    }

    /** Whether the item was a seasonal flavour on {@code day}, by the dates it had when it was sold. */
    public boolean isSeasonalOn(LocalDate day) {
        return seasonal && (seasonalFrom == null || !day.isBefore(seasonalFrom))
                && (seasonalUntil == null || !day.isAfter(seasonalUntil));
    }

    /** The catalogue item sold; null for a custom amount. */
    public Item getItem() {
        return item;
    }

    /** Null for a custom amount. */
    public Long getItemId() {
        return item == null ? null : item.getId();
    }

    public String getItemName() {
        return itemName;
    }

    public String getNote() {
        return note;
    }

    public BigDecimal getUnitPrice() {
        return unitPrice;
    }

    public BigDecimal getModifierUnitPrice() {
        return modifierUnitPrice;
    }

    public int getQuantity() {
        return quantity;
    }

    public BigDecimal getLineSubtotal() {
        return lineSubtotal;
    }

    public BigDecimal getDiscountTotal() {
        return discountTotal;
    }

    public BigDecimal getLineTotal() {
        return lineTotal;
    }

    public List<SaleOrderLineModifier> getModifiers() {
        return Collections.unmodifiableList(modifiers);
    }
}
