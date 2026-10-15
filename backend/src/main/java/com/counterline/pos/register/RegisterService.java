package com.counterline.pos.register;

import com.counterline.pos.auth.Terminal;
import com.counterline.pos.common.BusinessRuleException;
import com.counterline.pos.common.ConflictException;
import com.counterline.pos.outlet.OutletProperties;
import com.counterline.pos.register.RegisterDtos.CloseRequest;
import com.counterline.pos.register.RegisterDtos.MovementRequest;
import com.counterline.pos.register.RegisterDtos.MovementResponse;
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

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;

@Service
@Transactional
public class RegisterService {

    private static final Logger log = LoggerFactory.getLogger(RegisterService.class);

    private final RegisterSessionRepository sessionRepository;
    private final StaffRepository staffRepository;
    private final ReportService reports;
    private final OutletProperties outlet;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    RegisterService(RegisterSessionRepository sessionRepository, StaffRepository staffRepository,
                    ReportService reports, OutletProperties outlet, JdbcTemplate jdbc, Clock clock) {
        this.sessionRepository = sessionRepository;
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

    public SessionResponse open(Terminal terminal) {
        Long staffId = terminal.staffId();
        if (sessionRepository.findByOutletIdAndClosedAtIsNull(terminal.outletId()).isPresent()) {
            throw new ConflictException("The register is already open");
        }
        try {
            RegisterSession session = sessionRepository.saveAndFlush(
                    new RegisterSession(terminal.outletId(), activeStaff(staffId), clock.instant()));
            log.info("Register opened (session {}) at outlet {} by staff {}", session.getId(), terminal.outletId(), staffId);
            return toResponse(session);
        } catch (DataIntegrityViolationException e) {
            // Another terminal opened it at the same moment (register_session_one_open_uq).
            throw new ConflictException("The register is already open");
        }
    }

    public SessionResponse recordMovement(MovementRequest request, Terminal terminal) {
        RegisterSession session = openSession(terminal.outletId());
        session.recordMovement(request.direction(), request.amount(), request.reason().strip(),
                activeStaff(terminal.staffId()), clock.instant());
        return toResponse(sessionRepository.saveAndFlush(session));
    }

    public SessionResponse close(CloseRequest request, Terminal terminal) {
        Long staffId = terminal.staffId();
        RegisterSession session = openSession(terminal.outletId());
        if (request.version() != session.getVersion()) {
            throw new ObjectOptimisticLockingFailureException(RegisterSession.class, session.getId());
        }
        // Locked before counting: a Place Order or payment holds the row FOR SHARE (RegisterGate), so it
        // either commits first and is counted, or waits and then finds the register closed.
        jdbc.queryForObject("SELECT id FROM register_session WHERE id = ? FOR UPDATE", Long.class, session.getId());
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
                reports.salesSummary(session.getOutletId(), from, to, session.getOpenedAt(), end));
    }
}
