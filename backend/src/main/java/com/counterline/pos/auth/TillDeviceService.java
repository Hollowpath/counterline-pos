package com.counterline.pos.auth;

import com.counterline.pos.common.ConflictException;
import com.counterline.pos.common.ResourceNotFoundException;
import com.counterline.pos.outlet.OutletService;
import com.counterline.pos.staff.Staff;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/**
 * The iPads set up as tills. An iPad is set up once, for one outlet, by that outlet's manager; it
 * then logs staff in there only. An outlet has one till. Moving or replacing it is done by resetting it from the dashboard
 * (head office, or the outlet's manager), after which it has to be set up again.
 */
@Service
@Transactional
public class TillDeviceService {

    private static final Logger log = LoggerFactory.getLogger(TillDeviceService.class);
    private static final int KEY_BYTES = 32;

    private final TillDeviceRepository deviceRepository;
    private final OutletService outletService;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    TillDeviceService(TillDeviceRepository deviceRepository, OutletService outletService, Clock clock) {
        this.deviceRepository = deviceRepository;
        this.outletService = outletService;
        this.clock = clock;
    }

    /** {@code key}: given to the iPad once, at set-up; only its hash is kept. */
    record Registered(String key, TillDevice device) {
    }

    /** An iPad in use, as the dashboard lists it. */
    public record DeviceResponse(Long id, String label, Long outletId, String outletName, String setUpBy,
                                 Instant setUpAt, Instant lastLoginAt) {
    }

    /**
     * @throws ConflictException if the outlet already has a till: it has to be reset on the dashboard
     *                           first (an outlet has one till iPad)
     */
    Registered register(Long outletId, Staff manager) {
        deviceRepository.findActiveAt(outletId).stream().findFirst().ifPresent(existing -> {
            throw alreadyHasTill(existing);
        });
        String key = newKey();
        String label = "iPad " + (deviceRepository.countByOutletId(outletId) + 1);
        // Read before the insert: a refused insert leaves the transaction unusable for any query after it.
        String outletName = outletService.reference(outletId).getName();
        try {
            TillDevice device = deviceRepository.saveAndFlush(new TillDevice(outletId, label, hash(key), manager, clock.instant()));
            return new Registered(key, device);
        } catch (DataIntegrityViolationException e) {
            // Another iPad was set up for the outlet at the same moment (till_device_one_per_outlet_uq).
            throw new ConflictException(outletName
                    + " already has its till iPad. Reset it on the dashboard (iPads) to set up this one.");
        }
    }

    private ConflictException alreadyHasTill(TillDevice existing) {
        return new ConflictException(outletService.reference(existing.getOutletId()).getName() + " already has its till ("
                + existing.getLabel() + ", set up by " + existing.getSetUpBy().getFullName()
                + "). Reset it on the dashboard (iPads) to set up this iPad instead.");
    }

    /**
     * The iPad behind a key, if it is still set up; notes the login.
     *
     * @throws TillNotSetUpException if the key is unknown or the iPad has been reset
     */
    /** The iPad with this key, if it is still set up; nothing is recorded. */
    Optional<TillDevice> findActive(String key) {
        return deviceRepository.findByKeyHash(hash(key)).filter(TillDevice::isActive);
    }

    TillDevice requireActive(String key) {
        TillDevice device = deviceRepository.findByKeyHash(hash(key))
                .filter(TillDevice::isActive)
                .orElseThrow(TillNotSetUpException::new);
        device.recordLogin(clock.instant());
        return device;
    }

    /** The iPads in use at the outlets this dashboard user can see. */
    @Transactional(readOnly = true)
    public List<DeviceResponse> devices(DashboardUser user) {
        List<TillDevice> devices = user.admin() ? deviceRepository.findActive() : deviceRepository.findActiveAt(user.outletId());
        return devices.stream().map(this::toResponse).toList();
    }

    /** Resets an iPad: its key stops working, and a manager has to set it up again. */
    public void reset(Long deviceId, DashboardUser user, String resetBy) {
        TillDevice device = deviceRepository.findById(deviceId)
                .filter(TillDevice::isActive)
                .filter(found -> user.canSee(found.getOutletId()))
                .orElseThrow(() -> new ResourceNotFoundException("iPad " + deviceId + " not found"));
        device.reset(resetBy == null || resetBy.isBlank() ? "Dashboard" : resetBy, clock.instant());
        log.info("Till device {} ({}) at outlet {} reset from the dashboard", device.getId(), device.getLabel(),
                device.getOutletId());
    }

    private DeviceResponse toResponse(TillDevice device) {
        return new DeviceResponse(device.getId(), device.getLabel(), device.getOutletId(),
                outletService.reference(device.getOutletId()).getName(), device.getSetUpBy().getFullName(), device.getSetUpAt(),
                device.getLastLoginAt());
    }

    private String newKey() {
        byte[] bytes = new byte[KEY_BYTES];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** The key is random and long, so a plain SHA-256 is enough (no salt or stretching needed). */
    private static String hash(String key) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
