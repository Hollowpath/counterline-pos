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
        String digits = withoutTrunkZero(toInternationalDigits(SEPARATORS.matcher(input.strip()).replaceAll(""), defaultCountryCode),
                defaultCountryCode);
        return digits != null && E164_DIGITS.matcher(digits).matches() ? Optional.of("+" + digits) : Optional.empty();
    }

    /** "+60 011…" is "+6011…": the 0 that local numbers start with is not part of the number after the country code. */
    private static String withoutTrunkZero(String digits, String defaultCountryCode) {
        String trunk = defaultCountryCode + "0";
        return digits != null && digits.startsWith(trunk) ? defaultCountryCode + digits.substring(trunk.length()) : digits;
    }

    /** Digits to search for: "0112345" → "60112345", so a local-format search finds stored numbers. */
    static String searchDigits(String input, String defaultCountryCode) {
        String digits = input.replaceAll("\\D", "");
        String international = digits.startsWith("0") && !digits.startsWith("00") ? defaultCountryCode + digits.substring(1) : digits;
        String trunk = defaultCountryCode + "0";
        return international.startsWith(trunk) ? defaultCountryCode + international.substring(trunk.length()) : international;
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
