package com.chargehub.identity;

import com.chargehub.infrastructure.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/v1/auth")
public class AuthController {
    private final TokenService tokens;
    private final SessionRevocationService sessions;

    public AuthController(TokenService tokens, SessionRevocationService sessions) {
        this.tokens = tokens;
        this.sessions = sessions;
    }

    @PostMapping("/token")
    public ApiResponse login(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        IdentityValidation.only(body, "username", "password");
        return ApiResponse.ok(tokens.login(IdentityValidation.text(body, "username", 1, 80),
                IdentityValidation.text(body, "password", 1, 128), request.getRemoteAddr()));
    }

    @GetMapping("/me")
    public ApiResponse me() {
        var user = (UserIdentity) org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        return ApiResponse.ok(Map.of("id", Long.toString(user.id()), "username", user.username(), "roles", user.roles()));
    }

    @PostMapping("/logout")
    public ApiResponse logout(@RequestBody Map<String, Object> body, @RequestHeader("Idempotency-Key") String key,
                              HttpServletRequest request) {
        IdentityValidation.only(body, "allSessions", "reason");
        Object all = body.getOrDefault("allSessions", false);
        if (!(all instanceof Boolean allSessions)) throw IdentityValidation.invalid();
        return ApiResponse.ok(sessions.logout(Actor.id(), BearerTokenFilter.token(request), allSessions,
                IdentityValidation.reason(body), key));
    }
}
