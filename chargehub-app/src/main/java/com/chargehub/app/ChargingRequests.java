package com.chargehub.app;

import jakarta.validation.constraints.*;
import java.util.LinkedHashMap;
import java.util.Map;

/** External input types carry units and keep user identity and prices server-owned. */
public final class ChargingRequests {
    private ChargingRequests() {}
    public record CreateOrder(
            @NotNull @Pattern(regexp="[1-9][0-9]{0,18}") String connectorId,
            @NotNull @Min(1) Long expectedTariffVersion,
            @NotNull Boolean useMembership,
            @Pattern(regexp="[1-9][0-9]{0,18}") String couponId) {
        public Map<String,Object> command(){
            Map<String,Object> result=new LinkedHashMap<>();
            result.put("connectorId",connectorId);result.put("expectedTariffVersion",expectedTariffVersion);
            result.put("useMembership",useMembership);result.put("couponId",couponId);return result;
        }
    }
    public record Quote(
            @NotNull @Pattern(regexp="[1-9][0-9]{0,18}") String connectorId,
            @NotNull @Min(1) @Max(1000000) Long estimatedEnergyWh,
            @NotNull Boolean useMembership,
            @Pattern(regexp="[1-9][0-9]{0,18}") String couponId) {}
    public record Reason(@NotBlank @Size(min=5,max=255) String reason) {}
}
