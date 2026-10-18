package com.chargehub.identity;

import com.chargehub.infrastructure.BusinessException;
import org.springframework.security.core.context.SecurityContextHolder;

/** Identity always comes from authenticated server-side context. */
public final class Actor {
    private Actor() {}
    public static long id() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof UserIdentity user))
            throw new BusinessException(401, "UNAUTHENTICATED", "请先登录");
        return user.id();
    }
    public static boolean has(String role) {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.getAuthorities().stream()
                .anyMatch(authority -> authority.getAuthority().equals("ROLE_" + role));
    }
}
