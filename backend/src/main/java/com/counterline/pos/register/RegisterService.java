package com.counterline.pos.register;

import com.counterline.pos.auth.Terminal;
import com.counterline.pos.common.BusinessRuleException;
import com.counterline.pos.common.ConflictException;
import com.counterline.pos.outlet.OutletProperties;
import com.counterline.pos.outlet.OutletService;
import com.counterline.pos.register.RegisterDtos.CloseRequest;
import com.counterline.pos.register.RegisterDtos.MovementRequest;
import com.counterline.pos.register.RegisterDtos.MovementResponse;
import com.counterline.pos.register.RegisterDtos.OpenRequest;
import com.counterline.pos.register.RegisterDtos.RegisterStatus;
import com.counterline.pos.register.RegisterDtos.SessionResponse;
import com.counterline.pos.register.RegisterMovement.Direction;
import com.counterline.pos.reports.ReportService;
import com.counterline.pos.staff.Staff;
import com.counterline.pos.staff.StaffRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

@Service
@Transactional
public class RegisterService {

    private static final Logger log = LoggerFactory.getLogger(RegisterService.class);

    private final RegisterSessionRepository sessionRepository;
    private final StaffRepository staffRepository;
    private final ReportService reports;
    private final OutletProperties outlet;
    private final OutletService outletService;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    RegisterService(RegisterSessionRepository sessionRepository, StaffRepository staffRepository,
                    ReportService reports, OutletProperties outlet, OutletService outletService, JdbcTemplate jdbc, Clock clock) {
        this.sessionRepository = sessionRepository;
        this.outletService = outletService;
        this.staffRepository = staffRepository;
        this.reports = reports;
        this.outlet = outlet;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** The terminal's outlet's register. */
    @Transactional(readOnly = true)
    public RegisterStatus status(Long outletId) {
        long openOrders = countOpenOrders(outletId);
        return sessionRepository.findByOutletIdAndClosedAtIsNull(outletId)
                .map(open -> new RegisterStatus(true, toResponse(open), null, openOrders))
                .orElseGet(() -> new RegisterStatus(false, null,
                        sessionRepository.findFirstByOutletIdAndClosedAtIsNotNullOrderByClosedAtDesc(outletId)
                                .map(this::toResponse).orElse(null),
                        openOrders));
    }

    /** Whether the outlet has a register session that has not been closed. */
    @Transactional(readOnly = true)
    public boolean hasOpenRegister(Long outletId) {
        return sessionRepository.findByOutletIdAndClosedAtIsNull(outletId).isPresent();
    }

    /** Opens the register with the declared float (none declared is RM0.00). Nothing carries over from an earlier session. */
    public SessionResponse open(OpenRequest request, Terminal terminal) {
        Long staffId = terminal.staffId();
        // The outlet must be open, and stays open (it cannot be deactivated, which refuses while a register is
        // open) until this transaction ends. Locking order everywhere: the outlet first, then the rest.
        outletService.getActiveHeldOpen(terminal.outletId());
        BigDecimal openingFloat = request == null || request.openingFloat() == null
                ? ZERO : request.openingFloat().setScale(2);
        if (sessionRepository.findByOutletIdAndClosedAtIsNull(terminal.outletId()).isPresent()) {
            throw new ConflictException("The register is already open");
        }
        try {
            RegisterSession session = sessionRepository.saveAndFlush(
                    new RegisterSession(terminal.outletId(), activeStaff(staffId), clock.instant(), openingFloat));
            log.info("Register opened (session {}) at outlet {} by staff {} with an opening float of {}", session.getId(),
                    terminal.outletId(), staffId, openingFloat);
            return toResponse(session);
        } catch (DataIntegrityViolationException e) {
            // Another terminal opened it at the same moment (register_session_one_open_uq).
            throw new ConflictException("The register is already open");
        }
    }

    /**
     * Pay In / Out. A pay-out may not take more than the register's expected cash. The open session is
     * locked first, for pay-ins too (as a close does, and as every payment and refund does), and its version
     * advances: the balance is read after every sale and pay-out already under way has finished, a close that
     * wins the wait makes the movement fail, and two pay-outs cannot spend the same cash.
     */
    public SessionResponse recordMovement(MovementRequest request, Terminal terminal) {
        int locked = jdbc.update(
                "UPDATE register_session SET version = version + 1 WHERE outlet_id = ? AND closed_at IS NULL",
                terminal.outletId());
        if (locked == 0) {
            throw new BusinessRuleException("The register is not open");
        }
        // Loaded after the lock, so it carries the version just written and nothing else can change it meanwhile.
        RegisterSession session = openSession(terminal.outletId());
        if (request.direction() == Direction.OUT) {
            BigDecimal expected = expectedCash(session, clock.instant());
            if (request.amount().compareTo(expected) > 0) {
                throw new ConflictException("Pay-out of " + money(request.amount()) + " is more than the register's expected cash of "
                        + money(expected));
            }
        }
        session.recordMovement(request.direction(), request.amount(), request.reason().strip(),
                activeStaff(terminal.staffId()), clock.instant());
        return toResponse(sessionRepository.saveAndFlush(session));
    }

    public SessionResponse close(CloseRequest request, Terminal terminal) {
        Long staffId = terminal.staffId();
        // Locked before anything is read or counted: a Place Order holds the row FOR SHARE and a payment, refund
        // or pay in / out updates it (RegisterGate), so each either commits first and is counted, or waits and
        // then finds the register closed. The id and version are compared under the lock, so they are current.
        List<long[]> held = jdbc.query(
                "SELECT id, version FROM register_session WHERE outlet_id = ? AND closed_at IS NULL FOR UPDATE",
                (rs, row) -> new long[] {rs.getLong(1), rs.getLong(2)}, terminal.outletId());
        if (held.isEmpty()) {
            throw new BusinessRuleException("The register is not open");
        }
        long[] open = held.get(0);
        if (request.sessionId() != open[0] || request.version() != open[1]) {
            throw new ObjectOptimisticLockingFailureException(RegisterSession.class, open[0]);
        }
        RegisterSession session = openSession(terminal.outletId());
        long openOrders = countOpenOrders(terminal.outletId());
        if (openOrders > 0) {
            throw new BusinessRuleException(openOrders + (openOrders == 1 ? " open order" : " open orders"));
        }
        String note = request.note() == null || request.note().isBlank() ? null : request.note().strip();
        session.close(activeStaff(staffId), note, clock.instant());
        RegisterSession closed = sessionRepository.saveAndFlush(session);
        log.info("Register closed (session {}) by staff {}", closed.getId(), staffId);
        return toResponse(closed);
    }

    /**
     * What the drawer should hold at {@code end}: opening float + cash collected − cash refunded + pay-ins
     * − pay-outs. Cash is the payments of a payment method of kind CASH, counted as the sales report counts
     * them (a sale by when it was paid, a refund by when it was made), in plain SQL so this package needn't
     * depend on orders. Card and other payments are not cash.
     */
    private BigDecimal expectedCash(RegisterSession session, Instant end) {
        BigDecimal[] cash = jdbc.queryForObject("""
                SELECT coalesce(sum(p.amount) FILTER (WHERE o.status = 'CLOSED' AND o.closed_at >= ? AND o.closed_at < ?), 0),
                       coalesce(sum(p.amount) FILTER (WHERE o.refunded_at >= ? AND o.refunded_at < ?), 0)
                FROM payment p
                JOIN sale_order o ON o.id = p.order_id
                JOIN payment_method m ON m.id = p.payment_method_id
                WHERE o.outlet_id = ? AND m.kind = 'CASH'""",
                (rs, row) -> new BigDecimal[] {rs.getBigDecimal(1), rs.getBigDecimal(2)},
                Timestamp.from(session.getOpenedAt()), Timestamp.from(end), Timestamp.from(session.getOpenedAt()),
                Timestamp.from(end), session.getOutletId());
        return session.getOpeningFloat().add(cash[0]).subtract(cash[1])
                .add(session.total(Direction.IN)).subtract(session.total(Direction.OUT)).setScale(2);
    }

    /** As the screens and printed reports show money: RM12.50, and (RM12.50) below zero. */
    private static String money(BigDecimal amount) {
        String text = "RM" + amount.abs().setScale(2);
        return amount.signum() < 0 ? "(" + text + ")" : text;
    }

    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2);

    /** The outlet's placed but unpaid orders (Past Orders → Open); plain SQL so this package needn't depend on orders. */
    private long countOpenOrders(Long outletId) {
        Long count = jdbc.queryForObject("SELECT count(*) FROM sale_order WHERE outlet_id = ? AND status = 'OPEN'",
                Long.class, outletId);
        return count == null ? 0 : count;
    }

    private RegisterSession openSession(Long outletId) {
        return sessionRepository.findByOutletIdAndClosedAtIsNull(outletId)
                .orElseThrow(() -> new BusinessRuleException("The register is not open"));
    }

    private Staff activeStaff(Long staffId) {
        return staffRepository.findByIdAndActiveTrue(staffId)
                .orElseThrow(() -> new BadCredentialsException("Staff account is no longer active"));
    }

    /** The session with its sales so far (or up to closing), from the reports queries. */
    private SessionResponse toResponse(RegisterSession session) {
        Instant end = session.getClosedAt() != null ? session.getClosedAt() : clock.instant();
        LocalDate from = LocalDate.ofInstant(session.getOpenedAt(), outlet.timeZone());
        LocalDate to = LocalDate.ofInstant(end, outlet.timeZone());
        return new SessionResponse(session.getId(), session.getVersion(), session.getOpenedAt(),
                session.getOpenedBy().getFullName(), session.getClosedAt(),
                session.getClosedBy() == null ? null : session.getClosedBy().getFullName(),
                session.getClosingNote(),
                session.getMovements().stream().map(MovementResponse::from).toList(),
                session.total(Direction.IN), session.total(Direction.OUT),
                reports.salesSummary(session.getOutletId(), from, to, session.getOpenedAt(), end),
                session.getOpeningFloat(), expectedCash(session, end));
    }
}
