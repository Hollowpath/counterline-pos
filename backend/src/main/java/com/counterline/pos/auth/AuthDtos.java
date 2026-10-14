package com.counterline.pos.auth;

import com.counterline.pos.outlet.OutletService.OutletResponse;
import com.counterline.pos.staff.Staff;
import com.counterline.pos.staff.StaffRole;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;

public final class AuthDtos {

    private AuthDtos() {
    }

    /** Sets an iPad up as a till at {@code outletId}, with one of that outlet's managers' PIN. */
    public record TerminalSetupRequest(
            @NotNull @Pattern(regexp = "\\d{6}", message = "must be exactly 6 digits") String pin,
            @NotNull Long outletId) {

        /** Never let the PIN reach logs through an accidental toString(). */
        @Override
        public String toString() {
            return "TerminalSetupRequest[pin=******, outletId=" + outletId + "]";
        }
    }

    /**
     * The iPad's key and label (it keeps the key and sends it with every login) and its outlet.
     */
    public record TerminalSetupResponse(String deviceKey, String label, OutletResponse outlet) {

        @Override
        public String toString() {
            return "TerminalSetupResponse[deviceKey=******, label=" + label + ", outlet=" + outlet + "]";
        }
    }

    /** Staff PIN login on an iPad set up as a till ({@code deviceKey}, from the set-up). */
    public record LoginRequest(
            @NotNull @Pattern(regexp = "\\d{6}", message = "must be exactly 6 digits") String pin,
            @NotBlank @Size(max = 100) String deviceKey) {

        /** Never let the PIN or the key reach logs through an accidental toString(). */
        @Override
        public String toString() {
            return "LoginRequest[pin=******, deviceKey=******]";
        }
    }

    /** A manager's PIN at a logged-in terminal ("Special Access Required"). */
    public record PinRequest(
            @NotNull @Pattern(regexp = "\\d{6}", message = "must be exactly 6 digits") String pin) {

        @Override
        public String toString() {
            return "PinRequest[pin=******]";
        }
    }

    public record LoginResponse(String token, Instant expiresAt, StaffResponse staff, OutletResponse outlet) {
    }

    /**
     * Which PIN pad the login and set-up screens ask about: the till's PIN login ({@code deviceKey}),
     * or, before the iPad is set up, the outlet's iPad set-up ({@code outletId}).
     */
    public record PinLockRequest(@Size(max = 100) String deviceKey, Long outletId) {

        @Override
        public String toString() {
            return "PinLockRequest[deviceKey=******, outletId=" + outletId + "]";
        }
    }

    /** {@code lockedForSeconds}: until the PIN pad unlocks; 0 when it isn't locked (or was unlocked). */
    public record PinLockStatus(long lockedForSeconds) {
    }

    public record StaffResponse(Long id, String fullName, StaffRole role) {

        static StaffResponse from(Staff staff) {
            return new StaffResponse(staff.getId(), staff.getFullName(), staff.getRole());
        }
    }
}
