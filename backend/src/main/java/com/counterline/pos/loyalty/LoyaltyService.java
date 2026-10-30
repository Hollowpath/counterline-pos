package com.counterline.pos.loyalty;

import com.counterline.pos.common.BusinessRuleException;
import com.counterline.pos.loyalty.LoyaltyEntry.Type;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Member points, as on the outlet's receipts: 1 point for every full RM10 paid, spent on Member
 * Redemption items. Points a sale earns can be spent in the same sale ("Previous 1, Redeemed −7,
 * Earned 7, Current 1").
 *
 * <p>Settling locks the member's account row, so two terminals cannot spend the same points.
 *
 * <p>Positive points expire {@link #EXPIRY} after the member's latest completed, unrefunded purchase (at any outlet). Every
 * such purchase starts the period again, whether it earns or spends points or not; manual adjustments, refunds and
 * reversals and every other ledger entry never do. Refunding the latest purchase moves the anchor back to the one before
 * it, and a member with no such purchase has no expiry. Zero and debt never expire. Expiry is applied under the account
 * lock, whenever the account is locked for a sale, a refund or an adjustment, or its history is read: the balance goes to
 * zero once, with an {@code EXPIRE} entry. Reads that cannot write (a cart's preview) show the balance as it stands once
 * expired.
 *
 * <p>The balance can be negative: a refund takes back every point its sale earned, also those spent since, and the
 * member's next purchases pay that off before they have points to spend. Spending points (a redemption) and a manual
 * take-away still need points to cover them, so nothing but a refund makes the balance go below zero.
 */
@Service
public class LoyaltyService {

    static final BigDecimal RINGGIT_PER_POINT = BigDecimal.TEN;

    /** How long positive points last after the latest purchase: 365 days of 24 hours. */
    static final Duration EXPIRY = Duration.ofDays(365);

    private final LoyaltyAccountRepository accountRepository;
    private final LoyaltyEntryRepository entryRepository;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    LoyaltyService(LoyaltyAccountRepository accountRepository, LoyaltyEntryRepository entryRepository,
                   JdbcTemplate jdbc, Clock clock) {
        this.accountRepository = accountRepository;
        this.entryRepository = entryRepository;
        this.jdbc = jdbc;
        this.clock = clock;
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

    /** The balance now: points that have run out of time count as gone, whether or not they have been recorded so yet. */
    @Transactional(readOnly = true)
    public int balance(Long customerId) {
        return balance(customerId, clock.instant());
    }

    @Transactional(readOnly = true)
    public int balance(Long customerId, Instant now) {
        return standing(customerId, now).balance();
    }

    /** A member's points as they stand at an instant, with when they expire. */
    public record Standing(int balance, Instant expiresAt) {
    }

    /**
     * The balance (points that have run out of time count as gone) and when it expires, from one read of the account
     * and, only for a positive balance, one of the member's latest purchase.
     */
    @Transactional(readOnly = true)
    public Standing standing(Long customerId, Instant now) {
        return accountRepository.findById(customerId).map(account -> standingOf(account, now, null)).orElse(new Standing(0, null));
    }

    private Standing standingOf(LoyaltyAccount account, Instant now, Long excludedOrderId) {
        Instant expiry = expiryOf(account, excludedOrderId);
        return expiry != null && !now.isBefore(expiry) ? new Standing(0, null) : new Standing(account.getPointsBalance(), expiry);
    }

    /** When the member's positive points expire, or null if they have none (or are in debt, which never expires). */
    @Transactional(readOnly = true)
    public Instant expiresAt(Long customerId) {
        return expiresAt(customerId, clock.instant());
    }

    @Transactional(readOnly = true)
    public Instant expiresAt(Long customerId, Instant now) {
        return standing(customerId, now).expiresAt();
    }

    /**
     * When the member's points expire once the sale of {@code settlement} is paid at {@code now}: the sale is the member's
     * latest purchase, whatever it earns or spends, so a year from now. Null when nothing positive is left.
     */
    public Instant expiresAfter(Settlement settlement, Instant now) {
        return settlement.after() > 0 ? now.plus(EXPIRY) : null;
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

    /** As {@link #preview(Long, BigDecimal, int)} for a member's {@link #standing} already read. */
    public static Settlement preview(Standing standing, BigDecimal amountPaid, int pointsToRedeem) {
        return new Settlement(standing.balance(), pointsEarned(amountPaid), pointsToRedeem);
    }

    /** Records the points a paid sale earns and spends. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Settlement settle(Long customerId, Long orderId, BigDecimal amountPaid,
                             int pointsToRedeem, Instant at) {
        // Whatever had expired by the time of this sale expires first: the sale itself, already closed, is not counted
        // as the purchase that kept the points alive.
        LoyaltyAccount account = lockAccount(customerId, at, orderId);
        Settlement settlement = new Settlement(account.getPointsBalance(), pointsEarned(amountPaid), pointsToRedeem);
        // Only spending needs points to be there: a member in debt from a refund can still buy, earning it back.
        if (settlement.redeemed() > 0) {
            requireEnough(settlement);
        }
        record(account, customerId, orderId, Type.EARN, settlement.earned(), at);
        record(account, customerId, orderId, Type.REDEEM, -settlement.redeemed(), at);
        return settlement;
    }

    /**
     * A full refund takes back the points the sale earned and gives back those it spent. The reversal is whole even if
     * the earned points have been spent since: the balance then goes below zero until later purchases earn it back.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void reverse(Long customerId, Long orderId, int earned, int redeemed, Instant at) {
        LoyaltyAccount account = lockAccount(customerId, at);
        record(account, customerId, orderId, Type.REVERSE_REDEEM, redeemed, at);
        record(account, customerId, orderId, Type.REVERSE_EARN, -earned, at);
    }

    /** One line of a member's points history. {@code orderNumber} for a sale's entries, {@code reason} for an adjustment. */
    public record PointsEntry(String type, int points, Instant at, String orderNumber, String reason, String adjustedBy) {
    }

    /** {@code expiresAt}: when the balance expires; null when it is zero or a debt. */
    public record PointsHistory(int balance, Instant expiresAt, List<PointsEntry> entries) {
    }

    /**
     * Staff correct a member's points (up or down) with a reason, e.g. points a sale should have
     * earned. A take-away can't leave the balance below zero (a member in debt from a refund can have points added,
     * and nothing taken away).
     */
    @Transactional
    public int adjust(Long customerId, String memberName, int points, String reason, String adjustedBy, Instant at) {
        if (points == 0) {
            throw new BusinessRuleException("Enter the points to add or take away");
        }
        LoyaltyAccount account = lockAccount(customerId, at);
        if (points < 0 && account.getPointsBalance() + points < 0) {
            throw new BusinessRuleException(account.getPointsBalance() < 0
                    ? memberName + " owes " + -account.getPointsBalance() + " points; nothing can be taken away"
                    : memberName + " has only " + account.getPointsBalance() + " points");
        }
        entryRepository.save(LoyaltyEntry.adjustment(customerId, points, reason, adjustedBy, at));
        account.add(points, at);
        return account.getPointsBalance();
    }

    /** The balance and the latest entries, newest first. Points that have run out of time are expired first (under the account lock). */
    @Transactional
    public PointsHistory history(Long customerId) {
        return history(customerId, clock.instant());
    }

    @Transactional
    public PointsHistory history(Long customerId, Instant now) {
        // Looked at without a lock first: only an account with points that have run out of time is locked (and re-checked).
        if (expiryDue(customerId, now)) {
            accountRepository.lock(customerId).ifPresent(account -> expireIfDue(account, now, null));
        }
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
        Standing standing = standing(customerId, now);
        return new PointsHistory(standing.balance(), standing.expiresAt(), entries);
    }

    /** Throws when the member cannot afford the redemption, even counting what the sale earns. */
    public static void requireEnough(Settlement settlement) {
        if (settlement.after() < 0) {
            throw new BusinessRuleException("Not enough points (" + settlement.redeemed() + " needed, "
                    + (settlement.before() + settlement.earned()) + " available)");
        }
    }

    /**
     * The member's latest completed purchase that has not been refunded, at any outlet, other than {@code excludedOrderId}
     * (null for none); null if there is none. One index lookup (sale_order_customer_purchase_idx).
     */
    private Instant lastPurchase(Long customerId, Long excludedOrderId) {
        return jdbc.query("""
                SELECT max(closed_at) FROM sale_order
                WHERE customer_id = ? AND status = 'CLOSED' AND refunded_at IS NULL AND id <> ?""",
                rs -> {
                    Timestamp latest = rs.next() ? rs.getTimestamp(1) : null;
                    return latest == null ? null : latest.toInstant();
                }, customerId, excludedOrderId == null ? 0L : excludedOrderId);
    }

    /**
     * When the account's points expire: a year after the latest purchase (other than {@code excludedOrderId}). Null for no
     * points or a debt (nothing is looked up then), or when there is no purchase to count from.
     */
    private Instant expiryOf(LoyaltyAccount account, Long excludedOrderId) {
        if (account.getPointsBalance() <= 0) {
            return null;
        }
        Instant lastPurchase = lastPurchase(account.getCustomerId(), excludedOrderId);
        return lastPurchase == null ? null : lastPurchase.plus(EXPIRY);
    }

    /** Whether the account's points have run out of time by {@code now}, read without locking it (a pre-check only). */
    private boolean expiryDue(Long customerId, Instant now) {
        Integer balance = jdbc.query("SELECT points_balance FROM loyalty_account WHERE customer_id = ?",
                rs -> rs.next() ? rs.getInt(1) : null, customerId);
        if (balance == null || balance <= 0) {
            return false;
        }
        Instant lastPurchase = lastPurchase(customerId, null);
        return lastPurchase != null && !now.isBefore(lastPurchase.plus(EXPIRY));
    }

    /** The locked account, with points that have run out of time by {@code now} already expired. */
    private LoyaltyAccount lockAccount(Long customerId, Instant now) {
        return lockAccount(customerId, now, null);
    }

    private LoyaltyAccount lockAccount(Long customerId, Instant now, Long excludedOrderId) {
        accountRepository.ensureExists(customerId, now);
        LoyaltyAccount account = accountRepository.lock(customerId).orElseThrow();
        expireIfDue(account, now, excludedOrderId);
        return account;
    }

    /**
     * Takes a positive balance to zero if it has expired by {@code now}, recording it as an EXPIRE entry dated at the
     * expiry instant. Only called with the account locked, and only ever acts on a positive balance, so however many
     * requests find the points expired, the first one expires them and the rest find nothing to do.
     */
    private void expireIfDue(LoyaltyAccount account, Instant now, Long excludedOrderId) {
        Long customerId = account.getCustomerId();
        Instant expiry = expiryOf(account, excludedOrderId);
        if (expiry != null && !now.isBefore(expiry)) {
            // Dated at the expiry instant, but never before an entry already on the member's history: a refund of the
            // latest purchase can move the expiry back to a moment that has long passed.
            Instant latestEntry = jdbc.query("SELECT max(created_at) FROM loyalty_entry WHERE customer_id = ?", rs -> {
                Timestamp latest = rs.next() ? rs.getTimestamp(1) : null;
                return latest == null ? null : latest.toInstant();
            }, customerId);
            Instant datedAt = latestEntry != null && latestEntry.isAfter(expiry) ? latestEntry : expiry;
            entryRepository.save(new LoyaltyEntry(customerId, null, Type.EXPIRE, -account.getPointsBalance(), datedAt));
            account.expire(now);
        }
    }

    private void record(LoyaltyAccount account, Long customerId, Long orderId, Type type, int points, Instant at) {
        if (points == 0) {
            return;
        }
        entryRepository.save(new LoyaltyEntry(customerId, orderId, type, points, at));
        account.add(points, at);
    }
}
