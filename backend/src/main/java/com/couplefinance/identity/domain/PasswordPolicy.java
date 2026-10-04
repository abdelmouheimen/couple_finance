package com.couplefinance.identity.domain;

/** Password policy (security.md section 3): 10 to 128 characters. The breached-password check is out of MVP scope. */
public final class PasswordPolicy {

    public static final int MIN_LENGTH = 10;
    public static final int MAX_LENGTH = 128;

    private PasswordPolicy() {
    }

    public static boolean isAcceptable(String password) {
        if (password == null) {
            return false;
        }
        int length = password.codePointCount(0, password.length());
        return length >= MIN_LENGTH && length <= MAX_LENGTH;
    }

    /** Domain guard: never hash a password that breaks the policy. The message never contains the password. */
    public static void requireAcceptable(String password) {
        if (!isAcceptable(password)) {
            throw new IllegalArgumentException(
                    "Password must be between " + MIN_LENGTH + " and " + MAX_LENGTH + " characters.");
        }
    }
}
