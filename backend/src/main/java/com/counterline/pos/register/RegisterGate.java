package com.counterline.pos.register;

import com.counterline.pos.common.BusinessRuleException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Payments are only taken while the register is open.
 *
 * <p>The open session row is read {@code FOR SHARE}, so a register close (which updates that row)
 * waits until the payment's transaction has finished, and a payment that starts after a close has
 * committed no longer finds an open session. A sale therefore always lands inside a session.
 */
@Component
public class RegisterGate {

    private final JdbcTemplate jdbc;

    RegisterGate(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The outlet's register must be open. Must run inside the transaction that records the payment. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void requireOpenForPayment(Long outletId) {
        boolean open = !jdbc.queryForList(
                "SELECT id FROM register_session WHERE outlet_id = ? AND closed_at IS NULL FOR SHARE",
                Long.class, outletId).isEmpty();
        if (!open) {
            throw new BusinessRuleException("The register is closed. Open it in Manage Register before taking payment.");
        }
    }
}
