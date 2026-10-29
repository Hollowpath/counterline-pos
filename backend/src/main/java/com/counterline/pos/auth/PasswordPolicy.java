package com.counterline.pos.auth;

import java.nio.charset.StandardCharsets;

/** The dashboard password contract: at least 12 characters and at most 72 bytes in UTF-8 (BCrypt's limit). */
public final class PasswordPolicy {

    public static final int MIN_LENGTH = 12;
    /** BCrypt ignores (or rejects) anything beyond 72 bytes, so a longer password is refused. */
    public static final int MAX_BYTES = 72;

    private PasswordPolicy() {
    }

    /** What is wrong with {@code password}, or null if it is acceptable. */
    public static String problem(String password) {
        if (password == null || password.isBlank()) {
            return "A password is required";
        }
        if (password.codePointCount(0, password.length()) < MIN_LENGTH) {
            return "The password must be at least " + MIN_LENGTH + " characters";
        }
        if (password.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            return "The password must be at most " + MAX_BYTES + " bytes in UTF-8";
        }
        return null;
    }
}
