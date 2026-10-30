package com.counterline.pos.register;

import com.counterline.pos.common.BusinessRuleException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;

/**
 * Payments are only taken while the register is open.
 *
 * <p>Placing an order reads the open session row {@code FOR SHARE}; a payment or refund updates it
 * ({@link #lockForDrawerEvent}). Either way a register close (which locks that row) waits until the
 * transaction has finished, and one that starts after a close has committed no longer finds an open
 * session. A sale therefore always lands inside a session.
 */
@Component
public class RegisterGate {

    private final JdbcTemplate jdbc;
    private final Clock clock;

    RegisterGate(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /**
     * Takes the outlet's open session for one drawer event (a payment or a refund): the row is updated, so
     * it is held {@code FOR UPDATE} until the transaction ends, and its version advances, which makes a
     * close request made from an earlier view of the drawer stale. Returns the time of the event, read only
     * once the lock is held: a close that won the wait has already made the register closed (the update then
     * finds no open session), so the event cannot be dated into a session that closed while it waited.
     * Locks are taken in the order: the sale, the member's points, then this.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Instant lockForDrawerEvent(Long outletId) {
        int updated = jdbc.update(
                "UPDATE register_session SET version = version + 1 WHERE outlet_id = ? AND closed_at IS NULL", outletId);
        if (updated == 0) {
            throw new BusinessRuleException("Register closed");
        }
        return clock.instant();
    }

    /** The outlet's register must be open (to pay or place an order). Must run inside the transaction that records it. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void requireOpenForPayment(Long outletId) {
        boolean open = !jdbc.queryForList(
                "SELECT id FROM register_session WHERE outlet_id = ? AND closed_at IS NULL FOR SHARE",
                Long.class, outletId).isEmpty();
        if (!open) {
            throw new BusinessRuleException("Register closed");
        }
    }
}
