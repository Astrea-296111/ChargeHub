package com.chargehub.station;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;
import jakarta.validation.Valid;
import java.util.List;

/** Explicit API input contracts; IDs, audit reasons and operational state are never inferred. */
public final class CatalogCommands {
    private CatalogCommands() {}

    public record StationInput(
            @NotBlank @Pattern(regexp = "[A-Za-z0-9._:-]{1,40}") String stationCode,
            @NotBlank @Size(max = 120) String name,
            @NotBlank @Size(max = 255) String address,
            @NotBlank @Pattern(regexp = "Asia/Shanghai") String timezone,
            @NotBlank @Size(min = 5, max = 255) String reason) {}

    public record ConnectorInput(
            @NotBlank @Pattern(regexp = "[1-9][0-9]{0,18}") String stationId,
            @NotBlank @Pattern(regexp = "[A-Za-z0-9._:-]{1,64}") String deviceId,
            @NotBlank @Pattern(regexp = "[A-Za-z0-9._:-]{1,40}") String connectorCode,
            @NotBlank @Size(min = 5, max = 255) String reason) {}

    public record StatusInput(
            @NotNull Boolean enabled,
            @NotBlank @Size(min = 5, max = 255) String reason) {}

    public record TariffSlot(
            @NotNull @Min(0) @Max(1439) Integer startMinute,
            @NotNull @Min(1) @Max(1440) Integer endMinute,
            @NotNull @Min(0) @Max(10000) Integer energyRateFenPerKwh,
            @NotNull @Min(0) @Max(10000) Integer serviceRateFenPerKwh) {}

    public record TariffInput(
            @NotNull @Size(min = 1, max = 48) List<@Valid TariffSlot> slots,
            @NotBlank @Size(min = 5, max = 255) String reason) {}
}
