package com.chargehub.charging;

import com.chargehub.identity.Actor;
import com.chargehub.infrastructure.ApiResponse;
import com.chargehub.infrastructure.BusinessException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/v1/admin/charge-orders/{orderNo}")
public class SessionOperationsController {
    private final SessionOperationsService operations;
    public SessionOperationsController(SessionOperationsService operations){this.operations=operations;}
    @GetMapping("/meter-review") @PreAuthorize("hasAnyRole('OPS','FINANCE','ADMIN')")
    Object review(@PathVariable String orderNo){return ApiResponse.ok(operations.review(orderNo));}
    @PostMapping("/meter-replay") @PreAuthorize("hasAnyRole('OPS','FINANCE','ADMIN')")
    Object replay(@PathVariable String orderNo,@RequestHeader("Idempotency-Key")String key,@RequestBody Map<String,Object> body){only(body,"reason","fromSeq","toSeq");return ApiResponse.ok(operations.requestReplay(Actor.id(),orderNo,key,body));}
    @PostMapping("/meter-review/proposals") @PreAuthorize("hasAnyRole('OPS','ADMIN')")
    Object proposal(@PathVariable String orderNo,@RequestHeader("Idempotency-Key")String key,@RequestBody Map<String,Object> body){only(body,"reason","selectedDeviceEventIds");return ApiResponse.ok(operations.propose(Actor.id(),orderNo,key,body));}
    @PostMapping("/meter-review/proposals/{proposalNo}/decision") @PreAuthorize("hasAnyRole('FINANCE','ADMIN')")
    Object decision(@PathVariable String orderNo,@PathVariable String proposalNo,@RequestHeader("Idempotency-Key")String key,@RequestBody Map<String,Object> body){only(body,"reason","decision");return ApiResponse.ok(operations.decide(Actor.id(),orderNo,proposalNo,key,body));}
    private static void only(Map<String,Object> body,String...allowed){if(!Set.of(allowed).containsAll(body.keySet()))throw new BusinessException(400,"VALIDATION_ERROR","请求包含不支持的字段；禁止手工填写电量或金额");}
}
