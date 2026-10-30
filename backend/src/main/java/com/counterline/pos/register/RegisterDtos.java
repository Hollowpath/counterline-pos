package com.counterline.pos.register;

import com.counterline.pos.register.RegisterMovement.Direction;
import com.counterline.pos.reports.ReportDtos.SalesSummary;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public final class RegisterDtos {

    private RegisterDtos() {
    }

    /**
     * Manage Register: whether the register is open and, if so, the current session. When closed,
     * {@code lastClosed} is the most recent session (its closing summary). {@code openOrders} are
     * placed, unpaid orders, which must be paid or voided before the register can close.
     */
    public record RegisterStatus(boolean open, SessionResponse session, SessionResponse lastClosed, long openOrders) {
    }

    /**
     * {@code openingFloat}: the cash the register was opened with. {@code expectedCash}: what the drawer
     * should hold now (or at closing) = opening float + cash collected − cash refunded + pay-ins −
     * pay-outs; below zero when refunds have taken out more than the drawer held.
     */
    public record SessionResponse(Long id, long version, Instant openedAt, String openedBy,
                                  Instant closedAt, String closedBy, String closingNote,
                                  List<MovementResponse> movements, BigDecimal payIns, BigDecimal payOuts,
                                  SalesSummary sales, BigDecimal openingFloat, BigDecimal expectedCash) {
    }

    /** {@code openingFloat}: optional, zero when left out; declared once, when the register is opened. */
    public record OpenRequest(@DecimalMin("0.00") @Digits(integer = 10, fraction = 2) BigDecimal openingFloat) {
    }

    public record MovementResponse(Long id, Direction direction, BigDecimal amount, String reason,
                                   String staffName, Instant createdAt) {

        static MovementResponse from(RegisterMovement movement) {
            return new MovementResponse(movement.getId(), movement.getDirection(), movement.getAmount(),
                    movement.getReason(), movement.getStaff().getFullName(), movement.getCreatedAt());
        }
    }

    public record MovementRequest(
            @NotNull Direction direction,
            @NotNull @DecimalMin("0.01") @Digits(integer = 10, fraction = 2) BigDecimal amount,
            @NotBlank @Size(max = 200) String reason) {
    }

    /**
     * {@code sessionId} and {@code version} are the session the cashier is looking at, so a stale screen cannot
     * close it: not a session opened since, nor one whose drawer has moved on (a payment, refund or pay in / out).
     */
    public record CloseRequest(@NotNull Long sessionId, @NotNull Long version, @Size(max = 500) String note) {
    }
}
