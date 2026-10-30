package com.counterline.pos.staff;

import com.counterline.pos.auth.AdminUserRepository;
import com.counterline.pos.auth.PasswordPolicy;
import com.counterline.pos.auth.PinHasher;
import com.counterline.pos.common.BadRequestException;
import com.counterline.pos.common.ConflictException;
import com.counterline.pos.common.DatabaseConflicts;
import com.counterline.pos.common.ResourceNotFoundException;
import com.counterline.pos.outlet.Outlet;
import com.counterline.pos.outlet.OutletService;
import com.counterline.pos.timecards.OpenShifts;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;

/**
 * Head office's staff management. Staff are never deleted (sales and timecards keep their identity);
 * they are deactivated, which stops them signing in at the till and, for managers, the dashboard.
 * PINs and passwords are only ever stored hashed and never returned or logged.
 */
@Service
@Transactional
public class StaffAdminService {

    private static final Logger log = LoggerFactory.getLogger(StaffAdminService.class);
    private static final java.util.regex.Pattern EMAIL = java.util.regex.Pattern.compile("[^@\\s]+@[^@\\s]+\\.[^@\\s]+");
    private static final String PIN_TAKEN = "That PIN is already in use. Choose another.";

    private final StaffRepository staffRepository;
    private final AdminUserRepository adminRepository;
    private final OutletService outletService;
    private final PinHasher pinHasher;
    private final PasswordEncoder passwordEncoder;
    private final OpenShifts openShifts;

    StaffAdminService(StaffRepository staffRepository, AdminUserRepository adminRepository, OutletService outletService,
                      PinHasher pinHasher, PasswordEncoder passwordEncoder, OpenShifts openShifts) {
        this.staffRepository = staffRepository;
        this.adminRepository = adminRepository;
        this.outletService = outletService;
        this.pinHasher = pinHasher;
        this.passwordEncoder = passwordEncoder;
        this.openShifts = openShifts;
    }

    /** No PIN, password or hash: {@code email} is the dashboard login, if the manager has one. */
    public record AdminStaff(Long id, String fullName, StaffRole role, Long outletId, boolean active, String email) {

        static AdminStaff from(Staff staff) {
            return new AdminStaff(staff.getId(), staff.getFullName(), staff.getRole(), staff.getOutletId(),
                    staff.isActive(), staff.getEmail());
        }
    }

    /** {@code role} STAFF is a cashier. {@code email} and {@code password} (a manager's dashboard login) come together or not at all. */
    public record StaffRequest(
            @NotBlank @Size(max = 120) String fullName,
            @NotNull StaffRole role,
            @NotNull Long outletId,
            @NotBlank @Pattern(regexp = "\\d{6}", message = "must be exactly 6 digits") String pin,
            @Size(max = 254) String email,
            @Size(max = 200) String password) {

        @Override
        public String toString() {
            return "StaffRequest[fullName=" + fullName + ", role=" + role + ", outletId=" + outletId
                    + ", pin=******, email=" + email + ", password=******]";
        }
    }

    /**
     * A manager without a dashboard login is given one with both {@code email} and {@code password}.
     * One with a login may change just the e-mail, just the password, or both; a blank field is left
     * unchanged, so a blank password never overwrites the stored one.
     */
    public record StaffEdit(
            @NotBlank @Size(max = 120) String fullName,
            @NotNull StaffRole role,
            @NotNull Long outletId,
            @Size(max = 254) String email,
            @Size(max = 200) String password) {

        @Override
        public String toString() {
            return "StaffEdit[fullName=" + fullName + ", role=" + role + ", outletId=" + outletId
                    + ", email=" + email + ", password=******]";
        }
    }

    public record PinReset(@NotBlank @Pattern(regexp = "\\d{6}", message = "must be exactly 6 digits") String pin) {

        @Override
        public String toString() {
            return "PinReset[pin=******]";
        }
    }

    /** Every staff member, deactivated ones included; one outlet's when {@code outletId} is given. */
    @Transactional(readOnly = true)
    public List<AdminStaff> list(Long outletId) {
        List<Staff> staff = outletId == null
                ? staffRepository.findAllByOrderByFullNameAscIdAsc()
                : staffRepository.findByOutletIdOrderByFullNameAscIdAsc(outletId);
        return staff.stream().map(AdminStaff::from).toList();
    }

    public AdminStaff create(StaffRequest request) {
        // Held open until this transaction ends: the outlet cannot be deactivated between this check and the insert.
        outletService.getActiveHeldOpen(request.outletId());
        String name = requireName(request.fullName());
        String email = login(request.role(), request.email(), request.password());
        String pinHmac = pinHasher.hash(request.pin());
        if (staffRepository.findByPinHmac(pinHmac).isPresent()) {
            throw new ConflictException(PIN_TAKEN);
        }
        if (email != null) {
            requireFreeEmail(email, null);
        }
        Staff staff = new Staff(name, pinHmac, request.role(), request.outletId());
        if (email != null) {
            staff.setDashboardLogin(email, passwordEncoder.encode(request.password()));
        }
        staff = save(staff);
        log.info("Head office created staff {} ({}) at outlet {}", staff.getId(), staff.getRole(), staff.getOutletId());
        return AdminStaff.from(staff);
    }

    /** Name, role and outlet; a cashier has no dashboard login, so demoting a manager removes theirs. */
    public AdminStaff update(Long id, StaffEdit request) {
        Staff staff = find(id);
        boolean moved = !request.outletId().equals(staff.getOutletId());
        boolean leavesAsManager = staff.isActive() && staff.getRole() == StaffRole.MANAGER
                && (moved || request.role() != StaffRole.MANAGER);
        // The outlets' locks first (lowest id first), then the person's, the order Clock In and an outlet's
        // deactivation use: the new outlet cannot be deactivated between this check and the move, and two
        // managers of one outlet cannot both be removed at once.
        Outlet leaving = holdOutlets(leavesAsManager ? staff.getOutletId() : null, moved ? request.outletId() : null);
        if (leavesAsManager) {
            requireAnotherManager(staff, leaving, moved ? "moving them to another outlet" : "changing their role");
        }
        if (moved) {
            requireNoOpenShift(staff, "moving them to another outlet");
        }
        String name = requireName(request.fullName());
        boolean hasEmail = present(request.email());
        boolean hasPassword = present(request.password());
        if (request.role() != StaffRole.MANAGER && (hasEmail || hasPassword)) {
            throw new BadRequestException("Only managers have a dashboard login");
        }
        String email = hasEmail ? normaliseEmail(request.email()) : null;
        if (hasPassword) {
            requireValidPassword(request.password());
        }
        boolean hasLogin = staff.getEmail() != null && request.role() == StaffRole.MANAGER && staff.getRole() == StaffRole.MANAGER;
        if (!hasLogin && (hasEmail || hasPassword) && !(hasEmail && hasPassword)) {
            throw new BadRequestException("Give both an e-mail and a password to add a dashboard login");
        }
        if (email != null && !email.equals(staff.getEmail())) {
            requireFreeEmail(email, staff);
        }
        String currentEmail = staff.getEmail();
        String currentHash = staff.getPasswordHash();
        boolean roleChanged = request.role() != staff.getRole();
        staff.edit(name, request.role(), request.outletId());
        if (moved || roleChanged || (email != null && !email.equals(currentEmail)) || hasPassword) {
            staff.revokeAuthSessions();
        }
        if (request.role() == StaffRole.STAFF) {
            staff.clearDashboardLogin();
        } else if (hasEmail || hasPassword) {
            staff.setDashboardLogin(email != null ? email : currentEmail,
                    hasPassword ? passwordEncoder.encode(request.password()) : currentHash);
        }
        staff = save(staff);
        log.info("Head office edited staff {}", id);
        return AdminStaff.from(staff);
    }

    /** Takes the dashboard login away (idempotent); the person keeps working at the till. */
    public AdminStaff removeDashboardLogin(Long id) {
        Staff staff = find(id);
        if (staff.getEmail() != null) {
            staff.clearDashboardLogin();
            staff.revokeAuthSessions();
            staff = save(staff);
            log.info("Head office removed the dashboard login of staff {}", id);
        }
        return AdminStaff.from(staff);
    }

    public AdminStaff deactivate(Long id) {
        Staff staff = find(id);
        if (staff.isActive()) {
            if (staff.getRole() == StaffRole.MANAGER) {
                requireAnotherManager(staff, outletService.lockForChange(staff.getOutletId()), "deactivating them");
            }
            requireNoOpenShift(staff, "deactivating them");
            staff.deactivate();
            staff.revokeAuthSessions();
            staff = save(staff);
            log.info("Head office deactivated staff {}", id);
        }
        return AdminStaff.from(staff);
    }

    /**
     * Locks the outlets a change touches, lowest id first so two changes cannot wait on each other:
     * {@code exclusiveId} (the outlet a manager leaves) exclusively, {@code sharedId} (the outlet someone
     * joins) held open. Either may be null.
     *
     * @return the exclusively locked outlet, or null
     */
    private Outlet holdOutlets(Long exclusiveId, Long sharedId) {
        if (exclusiveId == null) {
            if (sharedId != null) {
                outletService.getActiveHeldOpen(sharedId);
            }
            return null;
        }
        if (sharedId != null && sharedId < exclusiveId) {
            outletService.getActiveHeldOpen(sharedId);
            return outletService.lockForChange(exclusiveId);
        }
        Outlet leaving = outletService.lockForChange(exclusiveId);
        if (sharedId != null) {
            outletService.getActiveHeldOpen(sharedId);
        }
        return leaving;
    }

    /**
     * An open outlet always keeps a manager: the one who sets up its till and approves its voids and refunds.
     * Called with the outlet locked, so two managers cannot be removed at the same moment. A closed outlet is
     * not held to it, so it can always be wound down and brought back (reactivate it, then add or reactivate a manager).
     *
     * @throws ConflictException (409) if {@code manager} is the outlet's only active manager
     */
    private void requireAnotherManager(Staff manager, Outlet outlet, String action) {
        if (outlet.isActive() && staffRepository.countByOutletIdAndRoleAndActiveTrueAndIdNot(
                outlet.getId(), StaffRole.MANAGER, manager.getId()) == 0) {
            throw new ConflictException(manager.getFullName() + " is the only active manager of " + outlet.getName()
                    + ". Add or reactivate another manager before " + action + ".");
        }
    }

    /**
     * An open shift belongs to the outlet it was started at, so the person stays there (and stays able to
     * clock out) until it is closed. The row lock is the one Clock In/Out takes, so a clock-in cannot slip in
     * between this check and the change.
     *
     * @throws ConflictException (409) while the staff member is clocked in
     */
    private void requireNoOpenShift(Staff staff, String action) {
        staffRepository.lockById(staff.getId());
        if (openShifts.has(staff.getId())) {
            throw new ConflictException(staff.getFullName() + " is clocked in. Close the shift (Reports, Timecards) before "
                    + action + ".");
        }
    }

    /** @throws ConflictException (409) while the staff member's outlet is deactivated */
    public AdminStaff reactivate(Long id) {
        Staff staff = find(id);
        if (!staff.isActive()) {
            try {
                // Held open until this transaction ends, so the outlet cannot close between the check and the change.
                outletService.getActiveHeldOpen(staff.getOutletId());
            } catch (ResourceNotFoundException e) {
                throw new ConflictException("Reactivate the staff member's outlet first, or move them to an open outlet");
            }
            staff.reactivate();
            staff = save(staff);
            log.info("Head office reactivated staff {}", id);
        }
        return AdminStaff.from(staff);
    }

    /** The new PIN must not belong to anyone else, deactivated staff included. */
    public AdminStaff resetPin(Long id, PinReset request) {
        Staff staff = find(id);
        String pinHmac = pinHasher.hash(request.pin());
        Staff holder = staffRepository.findByPinHmac(pinHmac).orElse(null);
        if (holder != null && !holder.getId().equals(id)) {
            throw new ConflictException(PIN_TAKEN);
        }
        staff.setPinHmac(pinHmac);
        staff.revokeAuthSessions();
        staff = save(staff);
        log.info("Head office reset the PIN of staff {}", id);
        return AdminStaff.from(staff);
    }

    private Staff find(Long id) {
        return staffRepository.findById(id).orElseThrow(() -> new ResourceNotFoundException("Staff " + id + " not found"));
    }

    private static boolean present(String text) {
        return text != null && !text.isBlank();
    }

    private static String normaliseEmail(String email) {
        String normalised = email.strip().toLowerCase(Locale.ROOT);
        if (!EMAIL.matcher(normalised).matches()) {
            throw new BadRequestException("The e-mail address is not valid");
        }
        return normalised;
    }

    private static void requireValidPassword(String password) {
        String problem = PasswordPolicy.problem(password);
        if (problem != null) {
            throw new BadRequestException(problem);
        }
    }

    private static String requireName(String name) {
        String stripped = name.strip();
        if (stripped.isEmpty()) {
            throw new BadRequestException("The name is required");
        }
        return stripped;
    }

    /**
     * The normalised dashboard e-mail of the request, or null for none. Only managers have one, and
     * an e-mail and password come together.
     */
    private static String login(StaffRole role, String email, String password) {
        boolean hasEmail = present(email);
        boolean hasPassword = present(password);
        if (!hasEmail && !hasPassword) {
            return null;
        }
        if (role != StaffRole.MANAGER) {
            throw new BadRequestException("Only managers have a dashboard login");
        }
        if (hasEmail != hasPassword) {
            throw new BadRequestException("Give both an e-mail and a password for the dashboard login, or neither");
        }
        String normalised = normaliseEmail(email);
        requireValidPassword(password);
        return normalised;
    }

    /** E-mails stay reserved by deactivated accounts too, and head-office accounts share the namespace. */
    private void requireFreeEmail(String email, Staff self) {
        boolean taken = adminRepository.existsByEmail(email)
                || staffRepository.findByEmail(email).filter(other -> self == null || !other.getId().equals(self.getId())).isPresent();
        if (taken) {
            throw new ConflictException("That e-mail address is already in use");
        }
    }

    /**
     * Flushes now, so a conflict surfaces here as a 409: a concurrent request may have taken the PIN or e-mail
     * between the check and the write, or changed this person after they were read (optimistic version).
     */
    private Staff save(Staff staff) {
        try {
            return staffRepository.saveAndFlush(staff);
        } catch (DataIntegrityViolationException e) {
            if (DatabaseConflicts.violates(e, "staff_pin_hmac_uq")) {
                throw new ConflictException(PIN_TAKEN);
            }
            if (DatabaseConflicts.violates(e, "staff_email_uq")) {
                throw new ConflictException("That e-mail address is already in use");
            }
            if (DatabaseConflicts.isCollision(e)) {
                throw new ConflictException("That PIN or e-mail address is already in use");
            }
            throw e;
        }
    }
}
