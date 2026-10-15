package com.counterline.pos.auth;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/**
 * An outlet's PIN pads, each throttled on its own: wrong PINs on one outlet's till never lock
 * another outlet's, and a lock is undone from the dashboard by that outlet's manager.
 */
public enum PinPad {

    /** Staff log in at the till, and managers approve actions there ("Special Access Required"). */
    TILL("PIN login"),
    CLOCK("Clock In/Out"),
    /** A manager sets an iPad up as the outlet's till. */
    SETUP("iPad set-up");

    private final String displayName;

    PinPad(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }

    /** The throttle key of this pad at {@code outletId}, e.g. "till:1". */
    public String key(Long outletId) {
        return prefix() + outletId;
    }

    public String prefix() {
        return name().toLowerCase(Locale.ROOT) + ":";
    }

    /** Which pad and outlet a throttle key is, if it is one of an outlet's pads. */
    public record At(PinPad pad, Long outletId) {
    }

    public static Optional<At> parse(String key) {
        return Arrays.stream(values())
                .filter(pad -> key.startsWith(pad.prefix()))
                .findFirst()
                .flatMap(pad -> {
                    try {
                        return Optional.of(new At(pad, Long.valueOf(key.substring(pad.prefix().length()))));
                    } catch (NumberFormatException e) {
                        return Optional.empty();
                    }
                });
    }
}
