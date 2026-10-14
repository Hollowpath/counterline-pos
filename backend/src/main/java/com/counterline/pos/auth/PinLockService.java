package com.counterline.pos.auth;

import com.counterline.pos.common.BadRequestException;
import com.counterline.pos.common.ResourceNotFoundException;
import com.counterline.pos.outlet.OutletService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Outlets' PIN pads locked by too many wrong PINs in a row, for the dashboard: head office sees
 * every outlet's, a manager their own outlet's, and either can unlock them so the shop isn't kept
 * waiting.
 */
@Service
public class PinLockService {

    private static final Logger log = LoggerFactory.getLogger(PinLockService.class);

    private final LoginAttemptLimiter limiter;
    private final OutletService outletService;
    private final TillDeviceService tillDevices;

    PinLockService(LoginAttemptLimiter limiter, OutletService outletService, TillDeviceService tillDevices) {
        this.limiter = limiter;
        this.outletService = outletService;
        this.tillDevices = tillDevices;
    }

    /** {@code padName}: "PIN login", "Clock In/Out" or "iPad set-up". */
    public record PinLockResponse(Long outletId, String outletName, PinPad pad, String padName, Instant lockedUntil) {
    }

    public List<PinLockResponse> locks(DashboardUser user) {
        Map<Long, String> outletNames = outletService.activeOutlets().stream()
                .collect(Collectors.toMap(OutletService.OutletResponse::id, OutletService.OutletResponse::name));
        return Arrays.stream(PinPad.values())
                .flatMap(pad -> limiter.locked(pad.prefix()).stream())
                .flatMap(lock -> PinPad.parse(lock.key()).stream()
                        .filter(at -> user.canSee(at.outletId()) && outletNames.containsKey(at.outletId()))
                        .map(at -> new PinLockResponse(at.outletId(), outletNames.get(at.outletId()), at.pad(),
                                at.pad().displayName(), lock.lockedUntil())))
                .sorted(Comparator.comparing(PinLockResponse::outletName).thenComparing(PinLockResponse::pad))
                .toList();
    }

    /**
     * For the login and set-up screens, before anyone has logged in: the till's PIN login (by its
     * iPad's key), or else the outlet's iPad set-up. An unknown or reset iPad is never locked here
     * (its login is refused anyway).
     */
    public long lockedForSeconds(String deviceKey, Long outletId) {
        if (deviceKey != null && !deviceKey.isBlank()) {
            return tillDevices.findActive(deviceKey)
                    .map(device -> limiter.lockedForSeconds(PinPad.TILL.key(device.getOutletId())))
                    .orElse(0L);
        }
        return outletId == null ? 0 : limiter.lockedForSeconds(PinPad.SETUP.key(outletId));
    }

    /** For a logged-in terminal: its outlet's PIN login (manager approvals) or Clock In/Out. */
    public long lockedForSeconds(Terminal terminal, PinPad pad) {
        if (pad == PinPad.SETUP) {
            throw new BadRequestException("A logged-in till has no iPad set-up pad");
        }
        return limiter.lockedForSeconds(pad.key(terminal.outletId()));
    }

    /** Unlocks the pad at once; its wrong PINs are forgotten. Unlocking a pad that isn't locked is harmless. */
    public void unlock(Long outletId, PinPad pad, DashboardUser user, String unlockedBy) {
        if (!user.canSee(outletId)) {
            throw new ResourceNotFoundException("Outlet " + outletId + " not found");
        }
        limiter.unlock(pad.key(outletId));
        log.info("{} at outlet {} unlocked from the dashboard by {}", pad.displayName(), outletId,
                unlockedBy == null || unlockedBy.isBlank() ? "the dashboard" : unlockedBy);
    }
}
