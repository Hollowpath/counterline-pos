package com.counterline.pos.display;

import com.counterline.pos.auth.DashboardUser;
import com.counterline.pos.auth.DisplaySession;
import com.counterline.pos.auth.LoginAttemptLimiter;
import com.counterline.pos.auth.Terminal;
import com.counterline.pos.auth.TokenService;
import com.counterline.pos.auth.TokenService.IssuedToken;
import com.counterline.pos.common.BusinessRuleException;
import com.counterline.pos.common.ConflictException;
import com.counterline.pos.common.ResourceNotFoundException;
import com.counterline.pos.customers.Customer;
import com.counterline.pos.customers.CustomerConflictException;
import com.counterline.pos.customers.CustomerDtos.CustomerRequest;
import com.counterline.pos.customers.CustomerDtos.CustomerResponse;
import com.counterline.pos.customers.CustomerService;
import com.counterline.pos.display.CustomerDisplay.MemberResult;
import com.counterline.pos.display.DisplayDtos.CartView;
import com.counterline.pos.display.DisplayDtos.DisplayConnection;
import com.counterline.pos.display.DisplayDtos.DisplayPosters;
import com.counterline.pos.display.DisplayDtos.DisplayState;
import com.counterline.pos.display.DisplayDtos.DisplayStatus;
import com.counterline.pos.display.DisplayDtos.MemberState;
import com.counterline.pos.display.DisplayDtos.MemberStatus;
import com.counterline.pos.display.DisplayDtos.SignUpRequest;
import com.counterline.pos.display.DisplayDtos.Stage;
import com.counterline.pos.display.DisplayDtos.WaitingMember;
import com.counterline.pos.outlet.OutletService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Customer displays. The POS creates one (an access code), the display connects with the code and
 * gets its own long-lived token, then polls for the cart the POS publishes. While a sale is open on
 * the POS, a customer logs in by phone number or signs up on the display, and the POS adds them to
 * the sale (it polls for them; no cashier action needed).
 *
 * <p>State lives in the {@code customer_display} row, not in memory, so any API task can serve the
 * POS and the display; changes are serialised by locking the row.
 */
@Service
@Transactional
public class DisplayService {

    private static final Logger log = LoggerFactory.getLogger(DisplayService.class);

    /** The display is a fixed device; it stays connected until the POS disconnects it. */
    static final Duration DISPLAY_TOKEN_TTL = Duration.ofDays(30);
    /** "Last seen" is written at most this often, not on every poll. */
    private static final Duration SEEN_EVERY = Duration.ofSeconds(15);
    /** An access code can be entered for this long; then the display is gone. */
    static final Duration CODE_LIFETIME = Duration.ofMinutes(10);
    /** Codes waiting to be entered at one outlet at a time. */
    static final int MAX_WAITING_PER_OUTLET = 3;
    private static final long CREATION_LOCK = 0x5d15_0000_0000L;
    /** Throttle key prefix: wrong pairing codes are counted per client address. */
    private static final String CODE_THROTTLE = "display-code:";

    private final SecureRandom random = new SecureRandom();
    private final CustomerDisplayRepository displayRepository;
    private final CustomerService customerService;
    private final OutletService outletService;
    private final TokenService tokenService;
    private final LoginAttemptLimiter limiter;
    private final JsonMapper json;
    private final Clock clock;
    private final DisplayPosterService posters;

    DisplayService(CustomerDisplayRepository displayRepository, CustomerService customerService,
                   OutletService outletService, TokenService tokenService, LoginAttemptLimiter limiter,
                   JsonMapper json, Clock clock, DisplayPosterService posters) {
        this.displayRepository = displayRepository;
        this.customerService = customerService;
        this.outletService = outletService;
        this.tokenService = tokenService;
        this.limiter = limiter;
        this.json = json;
        this.clock = clock;
        this.posters = posters;
    }

    // ---- The POS ----

    /** A new display for this terminal: an access code for the display to connect with. */
    public DisplayStatus create(Terminal terminal) {
        // One creation per outlet at a time, so the cap below cannot be passed by two terminals at once.
        displayRepository.lockCreation(CREATION_LOCK + terminal.outletId());
        Instant cutoff = clock.instant().minus(CODE_LIFETIME);
        displayRepository.deleteExpiredWaiting(cutoff);
        if (displayRepository.countWaiting(terminal.outletId(), cutoff) >= MAX_WAITING_PER_OUTLET) {
            throw new ConflictException("Too many displays are waiting to connect. Connect or disconnect one first.");
        }
        String code;
        do {
            code = "%06d".formatted(random.nextInt(1_000_000));
        } while (displayRepository.existsByAccessCodeAndConnectedAtIsNullAndRevokedAtIsNull(code));
        try {
            CustomerDisplay display = displayRepository.saveAndFlush(
                    new CustomerDisplay(terminal.outletId(), code, terminal.staffId(), clock.instant()));
            log.info("Customer display {} created at outlet {} by staff {}", display.getId(), terminal.outletId(),
                    terminal.staffId());
            return toStatus(display);
        } catch (DataIntegrityViolationException e) {
            // Another terminal drew the same code at the same moment.
            throw new ConflictException("Could not create an access code. Try again.");
        }
    }

    @Transactional(readOnly = true)
    public DisplayStatus status(Long displayId, Long outletId) {
        Instant now = clock.instant();
        return toStatus(displayRepository.findById(displayId)
                .filter(display -> display.getOutletId().equals(outletId) && !display.isRevoked() && !expired(display, now))
                .orElseThrow(() -> notFound(displayId)));
    }

    /**
     * What the customer sees from now on. With no sale open (idle), a member still waiting is
     * dropped: they were for the sale that ended.
     */
    public void publishCart(Long displayId, Long outletId, CartView cart) {
        CustomerDisplay display = lockForPos(displayId, outletId);
        // A member deactivated while waiting is no longer waiting: the POS never has to deal with them.
        if (display.getPendingCustomerId() != null && customerService.findActiveMember(display.getPendingCustomerId()).isEmpty()) {
            display.cancelWaiting();
        }
        display.publishCart(json.writeValueAsString(cart), cart.stage() == Stage.IDLE);
    }

    /** The POS added the member waiting on the display to its sale. */
    public void confirmMember(Long displayId, Long outletId, Long customerId) {
        CustomerDisplay display = lockForPos(displayId, outletId);
        if (!customerId.equals(display.getPendingCustomerId()) || customerService.findActiveMember(customerId).isEmpty()) {
            throw new BusinessRuleException("That member is no longer waiting on the customer display");
        }
        display.answer(MemberResult.CONFIRMED);
    }

    /** Disconnects the display: its token stops working and it goes back to asking for a code. */
    public void revoke(Long displayId, Long outletId) {
        lockForPos(displayId, outletId).revoke(clock.instant());
        log.info("Customer display {} disconnected", displayId);
    }

    // ---- The dashboard ----

    /** A display as the dashboard lists it: {@code waiting} until its code is entered, then connected. */
    public record DisplayListing(Long id, Long outletId, String outletName, boolean connected, Instant createdAt,
                                 Instant connectedAt, Instant lastSeenAt) {
    }

    /** The displays at the outlets this user can see, connected or still waiting for their code. */
    @Transactional(readOnly = true)
    public List<DisplayListing> listFor(DashboardUser user) {
        return displayRepository.findLive(clock.instant().minus(CODE_LIFETIME)).stream()
                .filter(display -> user.canSee(display.getOutletId()))
                .map(display -> new DisplayListing(display.getId(), display.getOutletId(),
                        outletService.reference(display.getOutletId()).getName(), display.getConnectedAt() != null,
                        display.getCreatedAt(), display.getConnectedAt(), display.getDisplaySeenAt()))
                .toList();
    }

    /**
     * Disconnects a display from the dashboard (a lost one, say): its token stops working at once. Head office for any
     * outlet, a manager for their own; anything else is "not found".
     */
    public void disconnectFor(Long displayId, DashboardUser user) {
        Instant now = clock.instant();
        CustomerDisplay display = displayRepository.lock(displayId)
                .filter(found -> !found.isRevoked() && !expired(found, now) && user.canSee(found.getOutletId()))
                .orElseThrow(() -> notFound(displayId));
        display.revoke(now);
        log.info("Customer display {} disconnected from the dashboard", displayId);
    }

    // ---- The display ----

    /**
     * The display enters the code shown on the POS. Throttled per client address, so codes can't be
     * guessed.
     */
    public DisplayConnection connect(String accessCode, String client) {
        limiter.checkAllowed(CODE_THROTTLE + client);
        // Locked: of two displays entering the same code at once, one connects and the other is told it is wrong.
        CustomerDisplay display = displayRepository.lockWaiting(accessCode, clock.instant().minus(CODE_LIFETIME)).orElse(null);
        String outletName = null;
        if (display != null) {
            try {
                outletName = outletService.get(display.getOutletId()).name();
            } catch (ResourceNotFoundException e) {
                display = null; // a closed outlet's code is as good as a wrong one
            }
        }
        if (display == null) {
            // Wrong, expired, already used or for a closed outlet: the same answer to all of them.
            limiter.recordFailure(CODE_THROTTLE + client);
            throw new BusinessRuleException("Wrong access code. Check the code on the POS under Customer Display.");
        }
        display.connect(clock.instant());
        IssuedToken token = tokenService.issueDisplay(display.getId(), display.getOutletId(), DISPLAY_TOKEN_TTL);
        log.info("Customer display {} connected from {}", display.getId(), client);
        return new DisplayConnection(token.value(), token.expiresAt(), display.getId(), outletName);
    }

    /** The display's poll: empty when nothing has changed since {@code sinceVersion}. */
    public Optional<DisplayState> state(DisplaySession session, long sinceVersion) {
        CustomerDisplay display = active(session);
        Instant now = clock.instant();
        if (display.getDisplaySeenAt() == null || display.getDisplaySeenAt().plus(SEEN_EVERY).isBefore(now)) {
            displayRepository.markSeen(display.getId(), now);
        }
        return display.getVersion() == sinceVersion ? Optional.empty() : Optional.of(toState(display));
    }

    /**
     * The posters this display may show now. Needs a display still connected, at an outlet still open;
     * the answer is the same for every customer and carries nothing about any sale.
     */
    public DisplayPosters posters(DisplaySession session) {
        active(session);
        return posters.at(clock.instant());
    }

    /**
     * Member Login by phone number. Throttled per display, so the screen can't be used to find out
     * which numbers are members.
     */
    public MemberState memberLogin(DisplaySession session, String phone) {
        requireSaleOpen(session);
        String key = memberThrottleKey(session);
        limiter.checkAllowed(key);
        Customer member = customerService.findByPhone(phone).orElse(null);
        if (member == null) {
            limiter.recordFailure(key);
            throw new ResourceNotFoundException("No member has this phone number. Tap Register Member to join.");
        }
        lockActive(session).memberWaiting(member.getId(), false);
        return new MemberState(MemberStatus.WAITING, member.getFirstName());
    }

    /** Member Login and Sign Up share one throttle per display. */
    private static String memberThrottleKey(DisplaySession session) {
        return "display-member:" + session.displayId();
    }

    /** Member Sign Up on the display; the new member then waits for the cashier like a login. */
    public MemberState signUp(DisplaySession session, SignUpRequest request) {
        requireSaleOpen(session);
        // The screen faces the shop: never show whose number or email it is. A number that is already a
        // member counts against the same throttle as Member Login, so sign-up can't be used to find
        // out which numbers are members either.
        String key = memberThrottleKey(session);
        limiter.checkAllowed(key);
        if (customerService.findByPhone(request.phone()).isPresent()) {
            limiter.recordFailure(key);
            throw new ConflictException("This phone number is already a member. Tap Login instead.");
        }
        CustomerResponse created;
        try {
            created = customerService.create(new CustomerRequest(null, null, request.firstName(), request.lastName(),
                    null, request.dateOfBirth(), request.gender(), request.email(), request.phone(),
                    request.marketingEmail(), request.marketingSms(), request.marketingCall(),
                    null, null, null, null, null, null));
        } catch (CustomerConflictException e) {
            // However it came about (a race between two sign-ups, a deactivated member's number), a refused
            // sign-up counts against the same throttle as a failed login.
            limiter.recordFailure(key);
            throw new ConflictException(e.field() == CustomerConflictException.Field.EMAIL
                    ? "This email is already used by a member. Use another, or leave it blank."
                    : e.inactive() ? "This phone number can't be registered here. Please ask the cashier."
                    : "This phone number is already a member. Tap Login instead.");
        } catch (ConflictException e) {
            limiter.recordFailure(key);
            throw new ConflictException("This phone number or email can't be registered here. Please ask the cashier.");
        }
        lockActive(session).memberWaiting(created.id(), true);
        log.info("Customer {} signed up on display {}", created.id(), session.displayId());
        return new MemberState(MemberStatus.WAITING, created.firstName());
    }

    /** The customer changed their mind before the till added them. */
    public void cancelMember(DisplaySession session) {
        lockActive(session).cancelWaiting();
    }

    // ---- helpers ----

    /** Members log in for a sale: only while one is open on the POS (not on the posters). */
    private void requireSaleOpen(DisplaySession session) {
        CustomerDisplay display = active(session);
        CartView cart = display.getCart() == null ? null : json.readValue(display.getCart(), CartView.class);
        if (cart == null || cart.stage() != Stage.CART) {
            throw new BusinessRuleException("Please wait for the cashier to start your order.");
        }
    }

    private CustomerDisplay lockForPos(Long displayId, Long outletId) {
        return displayRepository.lock(displayId)
                .filter(display -> display.getOutletId().equals(outletId) && !display.isRevoked())
                .orElseThrow(() -> notFound(displayId));
    }

    /**
     * The display's own row, for every display endpoint: a disconnected display's token no longer works (401), and
     * neither does a display whose outlet is closed (404, the same whatever the endpoint; it works again if the
     * outlet reopens). Nothing about the outlet is said.
     */
    private CustomerDisplay active(DisplaySession session) {
        return openOutlet(displayRepository.findById(session.displayId())
                .filter(display -> !display.isRevoked())
                .orElseThrow(() -> new BadCredentialsException("This display has been disconnected")));
    }

    private CustomerDisplay lockActive(DisplaySession session) {
        return openOutlet(displayRepository.lock(session.displayId())
                .filter(display -> !display.isRevoked())
                .orElseThrow(() -> new BadCredentialsException("This display has been disconnected")));
    }

    private CustomerDisplay openOutlet(CustomerDisplay display) {
        try {
            outletService.getActive(display.getOutletId());
        } catch (ResourceNotFoundException e) {
            throw new ResourceNotFoundException("Display unavailable");
        }
        return display;
    }

    private static boolean expired(CustomerDisplay display, Instant now) {
        return display.getConnectedAt() == null && display.getCreatedAt().plus(CODE_LIFETIME).compareTo(now) <= 0;
    }

    private static ResourceNotFoundException notFound(Long displayId) {
        return new ResourceNotFoundException("Customer display " + displayId + " not found");
    }

    private DisplayStatus toStatus(CustomerDisplay display) {
        WaitingMember waiting = null;
        // A waiting member who has been deactivated since is simply not waiting.
        Customer member = customerService.findActiveMember(display.getPendingCustomerId()).orElse(null);
        if (member != null) {
            waiting = new WaitingMember(member.getId(), member.getFullName(), member.getPhone(),
                    display.isPendingNewMember());
        }
        boolean connected = display.getConnectedAt() != null;
        return new DisplayStatus(display.getId(), connected ? null : display.getAccessCode(), connected,
                display.getDisplaySeenAt(), waiting);
    }

    private DisplayState toState(CustomerDisplay display) {
        CartView cart = display.getCart() == null ? null : json.readValue(display.getCart(), CartView.class);
        MemberState member;
        Customer waiting = customerService.findActiveMember(display.getPendingCustomerId()).orElse(null);
        Customer confirmed = display.getMemberResult() == MemberResult.CONFIRMED
                ? customerService.findActiveMember(display.getResultCustomerId()).orElse(null) : null;
        if (waiting != null) {
            member = new MemberState(MemberStatus.WAITING, waiting.getFirstName());
        } else if (confirmed != null) {
            member = new MemberState(MemberStatus.CONFIRMED, confirmed.getFirstName());
        } else {
            member = new MemberState(MemberStatus.NONE, null);
        }
        return new DisplayState(display.getVersion(), outletService.get(display.getOutletId()).name(), cart, member);
    }

}
