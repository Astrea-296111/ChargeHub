package com.chargehub.app;

import com.chargehub.charging.*;
import com.chargehub.identity.Actor;
import com.chargehub.infrastructure.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import java.util.*;
import java.util.function.Supplier;

@RestController
@Validated
public class ChargingController {
    private final ChargeService orders;
    private final SessionService sessions;
    private final ChargingQueryService queries;
    private final Idempotency idempotency;
    public ChargingController(ChargeService orders,SessionService sessions,ChargingQueryService queries,Idempotency idempotency){this.orders=orders;this.sessions=sessions;this.queries=queries;this.idempotency=idempotency;}
    @PostMapping("/v1/charge-quotes") public ApiResponse quote(@Valid @RequestBody ChargingRequests.Quote request){return ApiResponse.ok(queries.quote(Actor.id(),request));}
    @PostMapping("/v1/charge-orders") @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse create(@Valid @RequestBody ChargingRequests.CreateOrder request,@RequestHeader("Idempotency-Key") String key){
        Map<String,Object> command=request.command();return ApiResponse.ok(idempotency.execute(Actor.id(),"orders.create",key,command,()->orders.create(Actor.id(),command)));
    }
    @GetMapping("/v1/charge-orders") public ApiResponse list(@RequestParam(required=false) String cursor,@RequestParam(defaultValue="20") @Min(1) @Max(100) int limit){return ApiResponse.ok(orders.listPage(Actor.id(),cursor,limit));}
    @GetMapping("/v1/charge-orders/{no}") public ApiResponse get(@PathVariable UUID no){return ApiResponse.ok(orders.owned(Actor.id(),no.toString()));}
    @GetMapping("/v1/charge-orders/{no}/bill") public ApiResponse bill(@PathVariable UUID no){orders.owned(Actor.id(),no.toString());return ApiResponse.ok(sessions.bill(no.toString()));}
    @GetMapping("/v1/charge-orders/{no}/funds") public ApiResponse funds(@PathVariable UUID no){return ApiResponse.ok(queries.funds(Actor.id(),no.toString()));}
    @PostMapping("/v1/charge-orders/{no}/payment") @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse payment(@PathVariable UUID no,@RequestHeader("Idempotency-Key") String key,@RequestBody(required=false) Map<String,Object> body){
        emptyBody(body);Map<String,Object> payment=write("payment/"+no,key,()->orders.payment(Actor.id(),no.toString()));
        Map<String,Object> result=new LinkedHashMap<>();for(String field:List.of("payNo","orderNo","status","amountFen","currency","checkoutUrl"))result.put(field,payment.get(field));return ApiResponse.ok(result);
    }
    @PostMapping("/v1/charge-orders/{no}/cancel") @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse cancel(@PathVariable UUID no,@RequestHeader("Idempotency-Key") String key,@RequestBody(required=false) Map<String,Object> body){emptyBody(body);return ApiResponse.ok(write("cancel/"+no,key,()->orders.cancel(Actor.id(),no.toString())));}
    @PostMapping("/v1/charge-orders/{no}/stop") @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse stop(@PathVariable UUID no,@RequestHeader("Idempotency-Key") String key,@RequestBody(required=false) Map<String,Object> body){emptyBody(body);return ApiResponse.ok(write("stop/"+no,key,()->orders.stop(Actor.id(),no.toString())));}
    private Map<String,Object> write(String scope,String key,Supplier<Map<String,Object>> action){return idempotency.execute(Actor.id(),scope,key,Map.of(),action);}
    public static void emptyBody(Map<String,Object> body){if(body!=null&&!body.isEmpty())throw new BusinessException(400,"VALIDATION_ERROR","此操作不接受业务参数，金额与身份由服务端确定");}
}
