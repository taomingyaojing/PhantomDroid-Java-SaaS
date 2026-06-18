package com.phantomdroid.util;

/**
 * ThreadLocal-based user context for the current request.
 * Set by JwtFilter, consumed by controllers / services.
 * Lightweight alternative to Spring Security SecurityContextHolder.
 */
public final class UserContext {

    private static final ThreadLocal<Long> userIdHolder = new ThreadLocal<>();
    private static final ThreadLocal<String> usernameHolder = new ThreadLocal<>();

    private UserContext() {}

    public static void set(Long userId, String username) {
        userIdHolder.set(userId);
        usernameHolder.set(username);
    }

    public static Long getCurrentUserId() {
        return userIdHolder.get();
    }

    public static String getCurrentUsername() {
        return usernameHolder.get();
    }

    public static void clear() {
        userIdHolder.remove();
        usernameHolder.remove();
    }
}
