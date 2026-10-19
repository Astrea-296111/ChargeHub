package com.chargehub.identity;

import com.chargehub.infrastructure.ApiResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/v1/admin/users")
@PreAuthorize("hasRole('ADMIN')")
public class IdentityAdminController {
    private final IdentityAdminService users;

    public IdentityAdminController(IdentityAdminService users) { this.users = users; }

    @GetMapping
    public ApiResponse users(@RequestParam(required = false) String cursor,
                             @RequestParam(defaultValue = "20") int limit) {
        return ApiResponse.ok(users.users(Actor.id(), cursor, limit));
    }

    @RequestMapping(path = "/{id}/roles", method = {RequestMethod.POST, RequestMethod.PUT})
    public ApiResponse roles(@PathVariable String id, @RequestBody Map<String, Object> body,
                             @RequestHeader("Idempotency-Key") String key) {
        IdentityValidation.only(body, "roles", "reason");
        return ApiResponse.ok(users.changeRoles(Actor.id(), IdentityValidation.positiveId(id),
                IdentityValidation.roles(body), IdentityValidation.reason(body), key));
    }

    @PostMapping("/{id}/tokens/revoke")
    public ApiResponse revoke(@PathVariable String id, @RequestBody Map<String, Object> body,
                              @RequestHeader("Idempotency-Key") String key) {
        IdentityValidation.only(body, "reason");
        return ApiResponse.ok(users.revokeTokens(Actor.id(), IdentityValidation.positiveId(id),
                IdentityValidation.reason(body), key));
    }
}
