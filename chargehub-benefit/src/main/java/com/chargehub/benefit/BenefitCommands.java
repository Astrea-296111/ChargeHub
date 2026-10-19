package com.chargehub.benefit;

import jakarta.validation.constraints.*;
import java.time.Instant;

public final class BenefitCommands {
    private BenefitCommands() {}

    public record TemplateInput(
            @NotBlank @Size(max = 120) String name,
            @NotNull Instant claimFrom,
            @NotNull Instant claimUntil,
            @NotNull Instant useValidFrom,
            @NotNull Instant useValidUntil,
            @NotNull @Min(0) @Max(10000) Long thresholdFen,
            @NotNull @Min(1) @Max(10000) Long discountFen,
            @NotNull @Min(0) @Max(1000000) Integer totalCount,
            @NotBlank @Pattern(regexp = "ALL_STATIONS") String scope,
            @NotBlank @Size(min = 5, max = 255) String reason) {}

    public record MembershipInput(
            @NotBlank @Pattern(regexp = "DEMO_MONTHLY") String planCode,
            @NotNull Instant validFrom,
            @NotNull Instant validUntil,
            @NotBlank @Size(min = 5, max = 255) String reason) {}

    public record StatusInput(
            @NotNull Boolean enabled,
            @NotBlank @Size(min = 5, max = 255) String reason) {}
}
