package com.chargehub.identity;

import com.chargehub.infrastructure.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/v1/auth")
public class AuthController {
    private final TokenService tokens;
    public AuthController(TokenService tokens) { this.tokens = tokens; }
    @PostMapping("/token")
    public ApiResponse login(@RequestBody Map<String, String> body, HttpServletRequest request) {
        String username = body.getOrDefault("username", body.get("loginName"));
        return ApiResponse.ok(tokens.login(username, body.get("password"), request.getRemoteAddr()));
    }
    @GetMapping("/me")
    public ApiResponse me() {
        var user = (UserIdentity) org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        return ApiResponse.ok(Map.of("id", Long.toString(user.id()), "username", user.username(), "roles", user.roles()));
    }
}
