package com.chargehub.benefit;

import com.chargehub.identity.Actor;
import com.chargehub.infrastructure.ApiResponse;
import com.chargehub.infrastructure.Idempotency;
import com.chargehub.infrastructure.Json;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.function.Supplier;

@RestController
@Validated
@RequestMapping("/v1/admin")
@PreAuthorize("hasAnyRole('OPS','ADMIN')")
public class BenefitOperationsController {
    private final BenefitOperationsService benefits;
    private final Idempotency idempotency;
    private final BenefitWriteCoordinator writes;

    public BenefitOperationsController(BenefitOperationsService benefits, Idempotency idempotency, BenefitWriteCoordinator writes) {
        this.benefits = benefits;
        this.idempotency = idempotency;
        this.writes = writes;
    }

    @GetMapping("/coupon-templates")
    public Object templates(@RequestParam(required = false) Long cursor, @RequestParam(defaultValue = "20") int limit) {
        return ApiResponse.ok(benefits.templates(cursor, limit));
    }

    @GetMapping("/coupon-templates/{id}")
    public Object template(@PathVariable @Min(1) long id) { return ApiResponse.ok(benefits.template(id)); }

    @PutMapping("/coupon-templates/{id}")
    public Object updateTemplate(@PathVariable @Min(1) long id, @Valid @RequestBody BenefitCommands.TemplateInput body,
                          @RequestHeader("Idempotency-Key") String key) {
        return write("template.update/" + id, key, body, () -> benefits.updateTemplate(Actor.id(), id, body));
    }

    @GetMapping("/memberships/{userId}")
    public Object membership(@PathVariable @Min(1) long userId) { return ApiResponse.ok(benefits.membership(userId)); }

    @PutMapping("/memberships/{userId}")
    public Object grantMembership(@PathVariable @Min(1) long userId, @Valid @RequestBody BenefitCommands.MembershipInput body,
                           @RequestHeader("Idempotency-Key") String key) {
        return writeMembership(userId, "membership.grant/" + userId, key, body, () -> benefits.grantMembership(Actor.id(), userId, body));
    }

    @PostMapping("/memberships/{userId}/status")
    public Object membershipStatus(@PathVariable @Min(1) long userId, @Valid @RequestBody BenefitCommands.StatusInput body,
                            @RequestHeader("Idempotency-Key") String key) {
        return writeMembership(userId, "membership.status/" + userId, key, body, () -> benefits.membershipStatus(Actor.id(), userId, body));
    }

    private Object write(String scope, String key, Object body, Supplier<Map<String, Object>> action) {
        return ApiResponse.ok(idempotency.execute(Actor.id(), scope, key, Json.read(Json.write(body)), action));
    }

    private Object writeMembership(long userId, String scope, String key, Object body, Supplier<Map<String, Object>> action) {
        return ApiResponse.ok(writes.membership(Actor.id(), userId, scope, key, Json.read(Json.write(body)), action));
    }
}
