package com.chargehub.station;

import com.chargehub.identity.Actor;
import com.chargehub.infrastructure.ApiResponse;
import com.chargehub.infrastructure.Idempotency;
import com.chargehub.infrastructure.Json;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.function.Supplier;

@RestController
@Validated
public class CatalogOperationsController {
    private final CatalogOperationsService operations;
    private final StationService stations;
    private final Idempotency idempotency;

    public CatalogOperationsController(CatalogOperationsService operations, StationService stations, Idempotency idempotency) {
        this.operations = operations;
        this.stations = stations;
        this.idempotency = idempotency;
    }

    @GetMapping("/v1/stations/{id}")
    public Object station(@PathVariable @Min(1) long id) { return ApiResponse.ok(stations.get(id)); }

    @GetMapping("/v1/connectors/{id}/tariff")
    public Object tariff(@PathVariable @Min(1) long id) { return ApiResponse.ok(stations.displayTariff(id)); }

    @GetMapping("/v1/admin/stations")
    @PreAuthorize("hasAnyRole('OPS','ADMIN')")
    public Object stations(@RequestParam(required = false) Long cursor, @RequestParam(defaultValue = "20") int limit) {
        return ApiResponse.ok(operations.stations(cursor, limit));
    }

    @GetMapping("/v1/admin/stations/{id}/connectors")
    @PreAuthorize("hasAnyRole('OPS','ADMIN')")
    public Object connectors(@PathVariable @Min(1) long id) { return ApiResponse.ok(Map.of("items", operations.connectors(id))); }

    @PostMapping("/v1/admin/stations")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('OPS','ADMIN')")
    public Object createStation(@Valid @RequestBody CatalogCommands.StationInput body, @RequestHeader("Idempotency-Key") String key) {
        return write("station.create", key, body, () -> operations.createStation(Actor.id(), body));
    }

    @GetMapping("/v1/admin/tariffs/{id}")
    @PreAuthorize("hasAnyRole('OPS','ADMIN')")
    public Object tariffVersion(@PathVariable @Min(1) long id) { return ApiResponse.ok(stations.tariffView(id)); }

    @PutMapping("/v1/admin/tariffs/{id}")
    @PreAuthorize("hasAnyRole('OPS','ADMIN')")
    public Object updateTariff(@PathVariable @Min(1) long id, @Valid @RequestBody CatalogCommands.TariffInput body,
                        @RequestHeader("Idempotency-Key") String key) {
        return write("tariff.update/" + id, key, body, () -> operations.updateTariff(Actor.id(), id, body));
    }

    @PutMapping("/v1/admin/stations/{id}")
    @PreAuthorize("hasAnyRole('OPS','ADMIN')")
    public Object updateStation(@PathVariable @Min(1) long id, @Valid @RequestBody CatalogCommands.StationInput body,
                         @RequestHeader("Idempotency-Key") String key) {
        return write("station.update/" + id, key, body, () -> operations.updateStation(Actor.id(), id, body));
    }

    @PostMapping("/v1/admin/stations/{id}/status")
    @PreAuthorize("hasAnyRole('OPS','ADMIN')")
    public Object stationStatus(@PathVariable @Min(1) long id, @Valid @RequestBody CatalogCommands.StatusInput body,
                         @RequestHeader("Idempotency-Key") String key) {
        return write("station.status/" + id, key, body, () -> operations.stationStatus(Actor.id(), id, body));
    }

    @PostMapping("/v1/admin/connectors")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('OPS','ADMIN')")
    public Object createConnector(@Valid @RequestBody CatalogCommands.ConnectorInput body, @RequestHeader("Idempotency-Key") String key) {
        return write("connector.create", key, body, () -> operations.createConnector(Actor.id(), body));
    }

    @PutMapping("/v1/admin/connectors/{id}")
    @PreAuthorize("hasAnyRole('OPS','ADMIN')")
    public Object updateConnector(@PathVariable @Min(1) long id, @Valid @RequestBody CatalogCommands.ConnectorInput body,
                           @RequestHeader("Idempotency-Key") String key) {
        return write("connector.update/" + id, key, body, () -> operations.updateConnector(Actor.id(), id, body));
    }

    @PostMapping("/v1/admin/connectors/{id}/status")
    @PreAuthorize("hasAnyRole('OPS','ADMIN')")
    public Object connectorStatus(@PathVariable @Min(1) long id, @Valid @RequestBody CatalogCommands.StatusInput body,
                           @RequestHeader("Idempotency-Key") String key) {
        return write("connector.status/" + id, key, body, () -> operations.connectorStatus(Actor.id(), id, body));
    }

    private Object write(String scope, String key, Object input, Supplier<Map<String, Object>> action) {
        return ApiResponse.ok(idempotency.execute(Actor.id(), scope, key, Json.read(Json.write(input)), action));
    }
}
