package com.counterline.pos.auth;

import com.counterline.pos.auth.AuthDtos.LoginResponse;
import com.counterline.pos.auth.AuthDtos.StaffResponse;
import com.counterline.pos.auth.AuthDtos.TerminalSetupResponse;
import com.counterline.pos.auth.TokenService.IssuedToken;
import com.counterline.pos.outlet.OutletService;
import com.counterline.pos.outlet.OutletService.OutletResponse;
import com.counterline.pos.staff.Staff;
import com.counterline.pos.staff.StaffRepository;
import com.counterline.pos.staff.StaffRole;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;

@Service
@Transactional(readOnly = true)
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final StaffRepository staffRepository;
    private final PinHasher pinHasher;
    private final TokenService tokenService;
    private final LoginAttemptLimiter limiter;
    private final OutletService outletService;
    private final TillDeviceService tillDevices;
    private final Duration specialAccessTtl;

    AuthService(StaffRepository staffRepository, PinHasher pinHasher, TokenService tokenService,
                LoginAttemptLimiter limiter, OutletService outletService, TillDeviceService tillDevices,
                SecurityProperties properties) {
        this.staffRepository = staffRepository;
        this.pinHasher = pinHasher;
        this.tokenService = tokenService;
        this.limiter = limiter;
        this.outletService = outletService;
        this.tillDevices = tillDevices;
        this.specialAccessTtl = properties.specialAccessTtl();
    }

    /**
     * PIN login on an iPad set up as a till ({@code deviceKey}), at that iPad's outlet. Staff log in
     * only at their own outlet; the token records it, and everything the session records belongs to it.
     *
     * @param client the caller's address, for the log
     * @throws TillNotSetUpException if the iPad was reset (or never set up): it must be set up again
     * @throws com.counterline.pos.common.ResourceNotFoundException if the outlet is closed
     * @throws TooManyLoginAttemptsException if the outlet's PIN login is locked (too many wrong PINs in a row)
     * @throws InvalidPinException if no active staff member has this PIN, or they work elsewhere
     */
    @Transactional
    public LoginResponse login(String pin, String deviceKey, String client) {
        // The iPad first: without a till's key nothing is tried, so nobody else can lock an outlet out.
        TillDevice device = tillDevices.requireActive(deviceKey);
        OutletResponse outlet = outletService.get(device.getOutletId());
        String pad = PinPad.TILL.key(outlet.id());
        limiter.checkAllowed(pad);

        Staff staff = staffRepository.findByPinHmacAndActiveTrue(pinHasher.hash(pin)).orElse(null);
        if (staff == null) {
            log.warn("Failed PIN login at outlet {} from {}", outlet.id(), client);
            throw new InvalidPinException(limiter.withTriesLeft("Invalid PIN", limiter.recordFailure(pad)));
        }

        if (!staff.worksAt(outlet.id())) {
            // Counted as a failure, like a wrong PIN.
            log.warn("Staff {} tried to log in at outlet {} from {}", staff.getId(), outlet.id(), client);
            // Answered like a wrong PIN, so the pad never confirms that a PIN is someone's.
            throw new InvalidPinException(limiter.withTriesLeft("Invalid PIN",
                    limiter.recordFailure(pad)));
        }
        IssuedToken token = tokenService.issue(staff, outlet.id(), device.getId());
        log.info("Staff {} logged in at outlet {}", staff.getId(), outlet.id());
        return new LoginResponse(token.value(), token.expiresAt(), StaffResponse.from(staff), outlet);
    }

    /**
     * Sets an iPad up as a till at an outlet: one of that outlet's managers enters their PIN on the
     * set-up screen. The iPad is registered and given a key, which it keeps and logs staff in with
     * from then on; only a reset from the dashboard undoes it. Throttled per outlet, apart from its
     * till's PIN login.
     *
     * @throws SpecialAccessDeniedException if the PIN is wrong or not a manager's
     */
    @Transactional
    public TerminalSetupResponse setUpTerminal(String pin, Long outletId, String client) {
        OutletResponse outlet = outletService.get(outletId);
        Staff manager = requireManager(pin, outlet.id(), PinPad.SETUP, client);
        TillDeviceService.Registered registered = tillDevices.register(outlet.id(), manager);
        log.info("Manager {} set up till device {} ({}) for outlet {} from {}", manager.getId(),
                registered.device().getId(), registered.device().getLabel(), outlet.id(), client);
        return new TerminalSetupResponse(registered.key(), registered.device().getLabel(), outlet);
    }

    /**
     * "Special Access Required": a manager enters their PIN at a logged-in terminal to approve one
     * restricted action. Returns a short-lived token for that manager at the terminal's outlet; the
     * terminal's own session is unchanged. Shares the till's PIN login throttle, so it cannot be used
     * to guess PINs either.
     *
     * @throws SpecialAccessDeniedException if the PIN is wrong or not a manager's
     */
    public LoginResponse specialAccess(String pin, ApprovalOperation operation, String client, Terminal requestedBy,
                                       Long deviceId) {
        Staff manager = requireManager(pin, requestedBy.outletId(), PinPad.TILL, client);
        IssuedToken token = tokenService.issueApproval(manager, requestedBy.outletId(), deviceId, specialAccessTtl, operation);
        log.info("Manager {} granted {} approval to the session of staff {}", manager.getId(), operation,
                requestedBy.staffId());
        return new LoginResponse(token.value(), token.expiresAt(), StaffResponse.from(manager),
                outletService.get(requestedBy.outletId()));
    }

    /** The staff member behind a valid token; rejected if they have since been deactivated. */
    public StaffResponse currentStaff(Long staffId) {
        return staffRepository.findByIdAndActiveTrue(staffId)
                .map(StaffResponse::from)
                .orElseThrow(() -> new BadCredentialsException("Staff account is no longer active"));
    }

    /** A manager of {@code outletId}, on that outlet's {@code pad}. */
    private Staff requireManager(String pin, Long outletId, PinPad pad, String client) {
        String key = pad.key(outletId);
        limiter.checkAllowed(key);
        Staff manager = staffRepository.findByPinHmacAndActiveTrue(pinHasher.hash(pin))
                .filter(staff -> staff.getRole() == StaffRole.MANAGER && staff.worksAt(outletId))
                .orElse(null);
        if (manager == null) {
            log.warn("Failed manager PIN at outlet {} from {}", outletId, client);
            throw new SpecialAccessDeniedException(limiter.withTriesLeft(SpecialAccessDeniedException.MESSAGE,
                    limiter.recordFailure(key)));
        }
        return manager;
    }
}
