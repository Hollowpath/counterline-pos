package com.counterline.pos.loyalty;

import com.counterline.pos.common.BusinessRuleException;
import com.counterline.pos.loyalty.LoyaltyEntry.Type;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Member points, as on the outlet's receipts: 1 point for every full RM10 paid, spent on Member
 * Redemption items. Points a sale earns can be spent in the same sale ("Previous 1, Redeemed −7,
 * Earned 7, Current 1").
 *
 * <p>Settling locks the member's account row, so two terminals cannot spend the same points.
 */
@Service
public class LoyaltyService {

    static final BigDecimal RINGGIT_PER_POINT = BigDecimal.TEN;

    private final LoyaltyAccountRepository accountRepository;
    private final LoyaltyEntryRepository entryRepository;
    private final JdbcTemplate jdbc;

    LoyaltyService(LoyaltyAccountRepository accountRepository, LoyaltyEntryRepository entryRepository,
                   JdbcTemplate jdbc) {
        this.accountRepository = accountRepository;
        this.entryRepository = entryRepository;
        this.jdbc = jdbc;
    }

    /** What a sale changes: the receipt's LOYALTY PROGRAM block. */
    public record Settlement(int before, int earned, int redeemed) {

        public int after() {
            return before + earned - redeemed;
        }
    }

    public static int pointsEarned(BigDecimal amountPaid) {
        return amountPaid.divide(RINGGIT_PER_POINT, 0, RoundingMode.DOWN).intValueExact();
    }

    @Transactional(readOnly = true)
    public int balance(Long customerId) {
        return accountRepository.findById(customerId).map(LoyaltyAccount::getPointsBalance).orElse(0);
    }

    /**
     * Locks the member's account for the rest of the transaction and returns the balance. A sale
     * for a member takes this first, so its checks (points, one-off rewards) cannot race another
     * terminal's sale for the same member.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public int lock(Long customerId, Instant now) {
        return lockAccount(customerId, now).getPointsBalance();
    }

    /** What settling would record, without recording it (for the cart's preview). */
    @Transactional(readOnly = true)
    public Settlement preview(Long customerId, BigDecimal amountPaid, int pointsToRedeem) {
        return new Settlement(balance(customerId), pointsEarned(amountPaid), pointsToRedeem);
    }

    /** Records the points a paid sale earns and spends. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Settlement settle(Long customerId, Long orderId, BigDecimal amountPaid,
                             int pointsToRedeem, Instant at) {
        LoyaltyAccount account = lockAccount(customerId, at);
        Settlement settlement = new Settlement(account.getPointsBalance(), pointsEarned(amountPaid), pointsToRedeem);
        requireEnough(settlement);
        record(account, customerId, orderId, Type.EARN, settlement.earned(), at);
        record(account, customerId, orderId, Type.REDEEM, -settlement.redeemed(), at);
        return settlement;
    }

    /** A full refund takes back the points the sale earned and gives back those it spent. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void reverse(Long customerId, Long orderId, int earned, int redeemed, Instant at) {
        LoyaltyAccount account = lockAccount(customerId, at);
        record(account, customerId, orderId, Type.REVERSE_REDEEM, redeemed, at);
        // Points the sale earned may have been spent since: take back what is there, never below zero.
        record(account, customerId, orderId, Type.REVERSE_EARN, -Math.min(earned, account.getPointsBalance()), at);
    }

    /** One line of a member's points history. {@code orderNumber} for a sale's entries, {@code reason} for an adjustment. */
    public record PointsEntry(String type, int points, Instant at, String orderNumber, String reason, String adjustedBy) {
    }

    public record PointsHistory(int balance, List<PointsEntry> entries) {
    }

    /**
     * Staff correct a member's points (up or down) with a reason, e.g. points a sale should have
     * earned. The balance can't go below zero.
     */
    @Transactional
    public int adjust(Long customerId, String memberName, int points, String reason, String adjustedBy, Instant at) {
        if (points == 0) {
            throw new BusinessRuleException("Enter the points to add or take away");
        }
        LoyaltyAccount account = lockAccount(customerId, at);
        if (account.getPointsBalance() + points < 0) {
            throw new BusinessRuleException(memberName + " has only " + account.getPointsBalance() + " points");
        }
        entryRepository.save(LoyaltyEntry.adjustment(customerId, points, reason, adjustedBy, at));
        account.add(points, at);
        return account.getPointsBalance();
    }

    /** The balance and the latest entries, newest first. */
    @Transactional(readOnly = true)
    public PointsHistory history(Long customerId) {
        List<PointsEntry> entries = jdbc.query("""
                SELECT e.type, e.points, e.created_at, 'R' || o.order_number AS order_number, e.reason, e.adjusted_by
                FROM loyalty_entry e
                LEFT JOIN sale_order o ON o.id = e.order_id
                WHERE e.customer_id = ?
                ORDER BY e.created_at DESC, e.id DESC
                LIMIT 50
                """, (rs, i) -> new PointsEntry(rs.getString("type"), rs.getInt("points"),
                        rs.getObject("created_at", OffsetDateTime.class).toInstant(), rs.getString("order_number"),
                        rs.getString("reason"), rs.getString("adjusted_by")), customerId);
        return new PointsHistory(balance(customerId), entries);
    }

    /** Throws when the member cannot afford the redemption, even counting what the sale earns. */
    public static void requireEnough(Settlement settlement) {
        if (settlement.after() < 0) {
            throw new BusinessRuleException("Not enough points (" + settlement.redeemed() + " needed, "
                    + (settlement.before() + settlement.earned()) + " available)");
        }
    }

    private LoyaltyAccount lockAccount(Long customerId, Instant now) {
        accountRepository.ensureExists(customerId, now);
        return accountRepository.lock(customerId).orElseThrow();
    }

    private void record(LoyaltyAccount account, Long customerId, Long orderId, Type type, int points, Instant at) {
        if (points == 0) {
            return;
        }
        entryRepository.save(new LoyaltyEntry(customerId, orderId, type, points, at));
        account.add(points, at);
    }
}
