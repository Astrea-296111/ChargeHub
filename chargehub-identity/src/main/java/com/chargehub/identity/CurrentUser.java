package com.chargehub.identity;

/** Convenient alias for application services. */
public final class CurrentUser {
    private CurrentUser() {}
    public static long id() { return Actor.id(); }
    public static boolean has(String role) { return Actor.has(role); }
}
