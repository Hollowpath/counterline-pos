package com.counterline.pos.outlet;

import com.counterline.pos.common.BadRequestException;
import com.counterline.pos.common.ConflictException;
import com.counterline.pos.common.DatabaseConflicts;
import com.counterline.pos.common.ResourceNotFoundException;
import com.counterline.pos.register.RegisterService;
import com.counterline.pos.staff.StaffRepository;
import com.counterline.pos.timecards.OpenShifts;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;

/**
 * Head office's outlet management. Outlets are never deleted (sales, registers and clock-ins refer
 * to them) and the code never changes: it is how the terminal set-up screen and reports name the outlet.
 */
@Service
@Transactional
public class OutletAdminService {

    private static final Logger log = LoggerFactory.getLogger(OutletAdminService.class);

    private final OutletRepository outlets;
    private final RegisterService registers;
    private final StaffRepository staffRepository;
    private final OpenShifts openShifts;

    OutletAdminService(OutletRepository outlets, RegisterService registers, StaffRepository staffRepository,
                       OpenShifts openShifts) {
        this.outlets = outlets;
        this.registers = registers;
        this.staffRepository = staffRepository;
        this.openShifts = openShifts;
    }

    public record AdminOutlet(Long id, String code, String name, String address, boolean active) {

        static AdminOutlet from(Outlet outlet) {
            return new AdminOutlet(outlet.getId(), outlet.getCode(), outlet.getName(), outlet.getAddress(), outlet.isActive());
        }
    }

    /** The code is letters, digits, hyphen and underscore; stored upper case. */
    public record OutletRequest(
            @NotBlank @Size(max = 10) @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9_-]*", message = "must be letters, digits, - or _") String code,
            @NotBlank @Size(max = 80) String name,
            @Size(max = 200) String address) {
    }

    public record OutletEdit(@NotBlank @Size(max = 80) String name, @Size(max = 200) String address) {
    }

    @Transactional(readOnly = true)
    public List<AdminOutlet> list() {
        return outlets.findAllByOrderByCodeAsc().stream().map(AdminOutlet::from).toList();
    }

    public AdminOutlet create(OutletRequest request) {
        String code = request.code().strip().toUpperCase(Locale.ROOT);
        String name = request.name().strip();
        if (name.isEmpty()) {
            throw new BadRequestException("The outlet name is required");
        }
        if (outlets.existsByCodeIgnoreCase(code)) {
            throw new ConflictException("An outlet with code " + code + " already exists");
        }
        if (outlets.existsByNameIgnoreCase(name)) {
            throw new ConflictException("An outlet named " + name + " already exists");
        }
        Outlet outlet = save(new Outlet(code, name, blankToNull(request.address())));
        log.info("Head office created outlet {} ({})", outlet.getId(), code);
        return AdminOutlet.from(outlet);
    }

    /** Name and address only; the code cannot be changed. */
    public AdminOutlet update(Long id, OutletEdit request) {
        Outlet outlet = find(id);
        String name = request.name().strip();
        if (name.isEmpty()) {
            throw new BadRequestException("The outlet name is required");
        }
        if (outlets.existsByNameIgnoreCaseAndIdNot(name, id)) {
            throw new ConflictException("An outlet named " + name + " already exists");
        }
        outlet.edit(name, blankToNull(request.address()));
        save(outlet);
        log.info("Head office edited outlet {}", id);
        return AdminOutlet.from(outlet);
    }

    /** @throws ConflictException (409) while the outlet has an open register */
    public AdminOutlet deactivate(Long id) {
        // The outlet's lock first, as Clock In takes it (then the staff member's): a clock-in in progress is
        // waited for and seen below; one that comes after is refused because the outlet is no longer open.
        Outlet outlet = outlets.lockForUpdate(id).orElseThrow(() -> new ResourceNotFoundException("Outlet " + id + " not found"));
        if (outlet.isActive()) {
            if (registers.hasOpenRegister(id)) {
                throw new ConflictException("Outlet " + outlet.getCode()
                        + " has an open register. Close it before deactivating the outlet.");
            }
            if (openShifts.at(id)) {
                throw new ConflictException("Outlet " + outlet.getCode()
                        + " has staff clocked in. Close their shifts (Reports, Timecards) before deactivating the outlet.");
            }
            outlet.setActive(false);
            save(outlet);
            // Everyone's sessions there end for good: reopening the outlet does not revive them. (Versioned: a
            // staff edit read before this fails with a conflict instead of restoring the old session version.)
            staffRepository.revokeAuthSessions(id);
            log.info("Head office deactivated outlet {}", id);
        }
        return AdminOutlet.from(outlet);
    }

    public AdminOutlet reactivate(Long id) {
        Outlet outlet = find(id);
        if (!outlet.isActive()) {
            outlet.setActive(true);
            save(outlet);
            log.info("Head office reactivated outlet {}", id);
        }
        return AdminOutlet.from(outlet);
    }

    private Outlet find(Long id) {
        return outlets.findById(id).orElseThrow(() -> new ResourceNotFoundException("Outlet " + id + " not found"));
    }

    /**
     * Flushes now, so a conflict surfaces here as a 409: a concurrent request may have taken the code or name
     * (names compare ignoring case) between the check and the write, or changed this outlet after it was read.
     */
    private Outlet save(Outlet outlet) {
        try {
            return outlets.saveAndFlush(outlet);
        } catch (DataIntegrityViolationException e) {
            if (DatabaseConflicts.violates(e, "outlet_code_uq")) {
                throw new ConflictException("An outlet with that code already exists");
            }
            if (DatabaseConflicts.violates(e, "outlet_name_uq") || DatabaseConflicts.violates(e, "outlet_name_lower_uq")) {
                throw new ConflictException("An outlet with that name already exists");
            }
            if (DatabaseConflicts.isCollision(e)) {
                throw new ConflictException("An outlet with that code or name already exists");
            }
            throw e;
        }
    }

    private static String blankToNull(String text) {
        return text == null || text.isBlank() ? null : text.strip();
    }
}
