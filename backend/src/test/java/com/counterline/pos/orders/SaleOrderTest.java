package com.counterline.pos.orders;

import com.counterline.pos.catalog.Category;
import com.counterline.pos.catalog.Item;
import com.counterline.pos.common.BusinessRuleException;
import com.counterline.pos.discounts.Discount;
import com.counterline.pos.staff.Staff;
import com.counterline.pos.staff.StaffRole;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Business rules of a sale, without a database. Catalogue entities are mocked: they have no public constructors. */
class SaleOrderTest {

    private static final Instant NOW = Instant.parse("2026-10-12T10:00:00Z");

    private final Staff cashier = new Staff("Cashier", "0".repeat(64), StaffRole.STAFF, 1L);
    private final PaymentMethod visa = method("Visa", PaymentMethod.Kind.CASHLESS);
    private final PaymentMethod duitNow = method("DuitNow QR", PaymentMethod.Kind.CASHLESS);
    private final PaymentMethod cash = method("Cash", PaymentMethod.Kind.CASH);
    private final SaleOrder order = new SaleOrder(1L, 1, "261012ABCDEFG", UUID.randomUUID(), null, cashier, NOW);

    @Test
    void totalIsTheSumOfLineTotalsAndLinesSnapshotTheItem() {
        order.addLine(item("Pouch Caramel", "14.50"), 3);
        order.addLine(item("Tin Cheese", "29.00"), 1);

        assertThat(order.getTotal()).isEqualByComparingTo("72.50");
        assertThat(order.getLines()).extracting(SaleOrderLine::getItemName, SaleOrderLine::getLineTotal)
                .containsExactly(
                        tuple("Pouch Caramel", new BigDecimal("43.50")),
                        tuple("Tin Cheese", new BigDecimal("29.00")));
    }

    @Test
    void closesWhenOneCashlessPaymentAndCashExactlyCoverTheTotal() {
        order.addLine(item("Tin Cheese", "29.00"), 2);
        order.addPayment(visa, new BigDecimal("40"), "4432-0330", null, cashier, NOW);
        order.addPayment(cash, new BigDecimal("18.00"), null, new BigDecimal("20"), cashier, NOW);

        order.close(NOW);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CLOSED);
        assertThat(order.getAmountPaid()).isEqualByComparingTo("58.00");
        assertThat(order.getPayments().get(1).getChange()).isEqualByComparingTo("2.00");
    }

    @Test
    void aSaleTakesOneCashlessMethodAtMost() {
        order.addLine(item("Tin Cheese", "29.00"), 2);
        order.addPayment(visa, new BigDecimal("30.00"), "4432-0330", null, cashier, NOW);

        assertThatThrownBy(() -> order.addPayment(duitNow, new BigDecimal("28.00"), "0716", null, cashier, NOW))
                .hasMessage("A sale takes one cashless payment; the rest can be paid in cash");
        assertThatThrownBy(() -> {
            order.addPayment(cash, new BigDecimal("10.00"), null, null, cashier, NOW);
            order.addPayment(cash, new BigDecimal("18.00"), null, null, cashier, NOW);
        }).hasMessage("A sale takes one cash payment");
    }

    @Test
    void cashlessPaymentsNeedTheTerminalReference() {
        order.addLine(item("Tin Cheese", "29.00"), 1);

        assertThatThrownBy(() -> order.addPayment(visa, new BigDecimal("29.00"), "  ", null, cashier, NOW))
                .hasMessage("Enter the Visa reference from the terminal");
    }

    @Test
    void cashReceivedCannotBeLessThanTheAmount() {
        order.addLine(item("Tin Cheese", "29.00"), 1);

        assertThatThrownBy(() -> order.addPayment(cash, new BigDecimal("29.00"), null, new BigDecimal("20.00"), cashier, NOW))
                .hasMessage("Cash received (RM20.00) is less than the amount (RM29.00)");
        assertThatThrownBy(() -> order.addPayment(visa, new BigDecimal("29.00"), "ref", new BigDecimal("50.00"), cashier, NOW))
                .hasMessage("Cash received only applies to cash payments");
    }

    @Test
    void aSaleTakesOneDiscountWhetherOnALineOrOnTheOrder() {
        Discount staff = new Discount("Staff Discount", Discount.Kind.PERCENT, new BigDecimal("30"), Discount.Scope.BOTH);
        order.addItemLine(item("Tin Cheese", "29.00"), List.of(), Map.of(), 1, null, List.of(staff));

        assertThatThrownBy(() -> order.addItemLine(item("Tin Caramel", "29.00"), List.of(), Map.of(), 1, null, List.of(staff)))
                .hasMessage("Only one discount can be applied to a sale");
        assertThatThrownBy(() -> order.applyOrderDiscounts(List.of(staff)))
                .hasMessage("Only one discount can be applied to a sale");
    }

    @Test
    void cannotCloseWhenUnderpaid() {
        order.addLine(item("Tin Cheese", "29.00"), 1);
        order.addPayment(visa, new BigDecimal("20.00"), "ref", null, cashier, NOW);

        assertThatThrownBy(() -> order.close(NOW))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessage("Payments (RM20.00) must equal the order total (RM29.00)");
        assertThat(order.getStatus()).isEqualTo(OrderStatus.OPEN);
    }

    @Test
    void cannotCloseWhenOverpaidBecauseNoChangeIsGiven() {
        order.addLine(item("Tin Cheese", "29.00"), 1);
        order.addPayment(visa, new BigDecimal("30.00"), "ref", null, cashier, NOW);

        assertThatThrownBy(() -> order.close(NOW)).isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void freeItemsCloseWithoutAnyPayment() {
        order.addLine(item("Sample Pouch", "0.00"), 2);

        order.close(NOW);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CLOSED);
    }

    @Test
    void anEmptySaleCannotBeClosed() {
        assertThatThrownBy(() -> order.close(NOW)).hasMessage("A sale needs at least one item");
    }

    @Test
    void aClosedOrderCannotBeChanged() {
        order.addLine(item("Sample Pouch", "0.00"), 1);
        order.close(NOW);

        assertThatThrownBy(() -> order.addLine(item("Tin Cheese", "29.00"), 1))
                .hasMessage("Order R1 is closed");
    }

    @Test
    void placingKeepsTheOrderOpenAndRefusesPayments() {
        order.addLine(item("Tin Cheese", "29.00"), 1);
        order.place();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.OPEN);

        SaleOrder paidUpFront = new SaleOrder(1L, 2, "261012ABCDEFH", UUID.randomUUID(), null, cashier, NOW);
        paidUpFront.addLine(item("Tin Cheese", "29.00"), 1);
        paidUpFront.addPayment(visa, new BigDecimal("29.00"), "ref", null, cashier, NOW);
        assertThatThrownBy(paidUpFront::place).hasMessage("Payments are not taken when placing an order");
    }

    @Test
    void anOpenOrderCanBeVoidedWithTheManagerAndReasonRecorded() {
        Staff manager = new Staff("Manager", "1".repeat(64), StaffRole.MANAGER, 1L);
        order.addLine(item("Tin Cheese", "29.00"), 1);
        order.place();

        order.voidOrder("Wrong key in", manager, NOW);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.VOIDED);
        assertThat(order.getVoidReason()).isEqualTo("Wrong key in");
        assertThat(order.getVoidedBy()).isSameAs(manager);
    }

    @Test
    void aPaidOrderIsRefundedNotVoided() {
        order.addLine(item("Tin Cheese", "29.00"), 1);
        order.addPayment(visa, new BigDecimal("29.00"), "ref", null, cashier, NOW);
        order.close(NOW);

        assertThatThrownBy(() -> order.voidOrder("x", cashier, NOW))
                .hasMessage("Order R1 has been paid; issue a refund instead");

        order.refund("Damaged tin", cashier, NOW);
        assertThat(order.isRefunded()).isTrue();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CLOSED);
        assertThatThrownBy(() -> order.refund("again", cashier, NOW))
                .hasMessage("Order R1 has already been refunded");
    }

    @Test
    void onlyAPaidOrderWithMoneyTakenCanBeRefunded() {
        order.addLine(item("Tin Cheese", "29.00"), 1);
        order.place();
        assertThatThrownBy(() -> order.refund("x", cashier, NOW)).hasMessage("Only a paid order can be refunded");

        SaleOrder free = new SaleOrder(1L, 3, "261012ABCDEFI", UUID.randomUUID(), null, cashier, NOW);
        free.addLine(item("Sample Pouch", "0.00"), 1);
        free.close(NOW);
        assertThatThrownBy(() -> free.refund("x", cashier, NOW)).hasMessage("Order R3 had nothing to refund");
    }

    private static PaymentMethod method(String name, PaymentMethod.Kind kind) {
        PaymentMethod method = mock(PaymentMethod.class);
        when(method.getName()).thenReturn(name);
        when(method.getKind()).thenReturn(kind);
        when(method.isCash()).thenReturn(kind == PaymentMethod.Kind.CASH);
        return method;
    }

    private static Item item(String name, String price) {
        Category category = mock(Category.class);
        when(category.getName()).thenReturn("Popcorn");
        Item item = mock(Item.class);
        when(item.getName()).thenReturn(name);
        when(item.getPrice()).thenReturn(new BigDecimal(price));
        when(item.getCategory()).thenReturn(category);
        return item;
    }
}
