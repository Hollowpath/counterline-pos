package com.counterline.pos.customers;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Phone numbers are stored in E.164 form (+60112345678) so the same customer is found however
 * the number was typed. Local numbers ("011-2345 678") get the outlet's country code.
 */
final class PhoneNumbers {

    private static final Pattern SEPARATORS = Pattern.compile("[\\s\\-().]");
    private static final Pattern E164_DIGITS = Pattern.compile("[1-9]\\d{7,14}");

    private PhoneNumbers() {
    }

    /** @return the E.164 number, or empty if the input cannot be a valid phone number */
    static Optional<String> normalize(String input, String defaultCountryCode) {
        String digits = toInternationalDigits(SEPARATORS.matcher(input.strip()).replaceAll(""), defaultCountryCode);
        return digits != null && E164_DIGITS.matcher(digits).matches() ? Optional.of("+" + digits) : Optional.empty();
    }

    /** Digits to search for: "0112345" → "60112345", so a local-format search finds stored numbers. */
    static String searchDigits(String input, String defaultCountryCode) {
        String digits = input.replaceAll("\\D", "");
        return digits.startsWith("0") && !digits.startsWith("00") ? defaultCountryCode + digits.substring(1) : digits;
    }

    private static String toInternationalDigits(String compact, String defaultCountryCode) {
        if (compact.startsWith("+")) {
            return digitsOnly(compact.substring(1));
        }
        if (compact.startsWith("00")) {
            return digitsOnly(compact.substring(2));
        }
        if (compact.startsWith("0")) {
            String local = digitsOnly(compact.substring(1));
            return local == null ? null : defaultCountryCode + local;
        }
        return digitsOnly(compact);
    }

    private static String digitsOnly(String value) {
        return value.chars().allMatch(Character::isDigit) ? value : null;
    }
}
