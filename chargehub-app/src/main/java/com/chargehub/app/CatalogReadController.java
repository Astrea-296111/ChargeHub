package com.chargehub.app;

import com.chargehub.infrastructure.*;
import com.chargehub.identity.Actor;
import com.chargehub.station.StationService;
import com.chargehub.benefit.BenefitService;
import org.springframework.web.bind.annotation.*;
import org.springframework.validation.annotation.Validated;
import jakarta.validation.constraints.*;

@RestController
@Validated
public class CatalogReadController {
    private final StationService stations;
    private final BenefitService benefits;
    private final Idempotency idempotency;
    public CatalogReadController(StationService stations,BenefitService benefits,Idempotency idempotency){this.stations=stations;this.benefits=benefits;this.idempotency=idempotency;}
    @GetMapping("/v1/stations") public ApiResponse stations(@RequestParam(required=false) @Min(1) Long cursor,@RequestParam(defaultValue="20") @Min(1) @Max(100) int limit){return ApiResponse.ok(stations.list(cursor,limit));}
    @GetMapping("/v1/me/benefits") public ApiResponse benefits(){return ApiResponse.ok(benefits.benefits(Actor.id()));}
    @GetMapping("/v1/coupon-templates") public ApiResponse templates(@RequestParam(required=false) @Min(1) Long cursor,@RequestParam(defaultValue="20") @Min(1) @Max(100) int limit){return ApiResponse.ok(benefits.templates(cursor,limit));}
    @GetMapping("/v1/me/coupons") public ApiResponse coupons(@RequestParam(required=false) @Min(1) Long cursor,@RequestParam(defaultValue="20") @Min(1) @Max(100) int limit){return ApiResponse.ok(benefits.coupons(Actor.id(),cursor,limit));}
    @PostMapping("/v1/coupon-templates/{id}/claims") public ApiResponse claim(@PathVariable @Min(1) long id,@RequestHeader("Idempotency-Key") String key,@RequestBody(required=false) java.util.Map<String,Object> body){
        ChargingController.emptyBody(body);return ApiResponse.ok(idempotency.execute(Actor.id(),"claim/"+id,key,java.util.Map.of(),()->benefits.claim(Actor.id(),id)));
    }
}
