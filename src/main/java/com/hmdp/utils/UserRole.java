package com.hmdp.utils;

/**
 * Minimal Phase 1 role model. Privileged writes require MERCHANT or ADMIN.
 */
public final class UserRole {
    public static final String USER = "USER";
    public static final String MERCHANT = "MERCHANT";
    public static final String ADMIN = "ADMIN";

    private UserRole() {
    }

    public static boolean isPrivileged(String role) {
        return ADMIN.equals(role) || MERCHANT.equals(role);
    }

    public static String normalize(String role) {
        if (role == null || role.isEmpty()) {
            return USER;
        }
        return role;
    }
}
