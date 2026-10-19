package com.chargehub.app;
import com.chargehub.identity.Actor;
import com.chargehub.infrastructure.*;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
import java.util.UUID;
@RestController
@PreAuthorize("hasRole('ADMIN')")
public class OutboxAdminController {
    private final Outbox outbox;private final Idempotency idempotency;private final Db db;
    public OutboxAdminController(Outbox outbox,Idempotency idempotency,Db db){this.outbox=outbox;this.idempotency=idempotency;this.db=db;}
    @PostMapping("/v1/admin/outbox/{id}/replay") public ApiResponse replay(@PathVariable UUID id,@Valid @RequestBody ChargingRequests.Reason request,@RequestHeader("Idempotency-Key") String key){return ApiResponse.ok(idempotency.execute(Actor.id(),"outbox.replay/"+id,key,Map.of("reason",request.reason()),()->outbox.replay(id.toString(),Actor.id(),request.reason())));}
    @GetMapping("/v1/admin/outbox") public ApiResponse list(@RequestParam(defaultValue="0") long after,@RequestParam(defaultValue="20") int limit,@RequestParam(defaultValue="DEAD") String status){
        if(after<0||limit<1||limit>100||!java.util.Set.of("READY","PROCESSING","PUBLISHED","DONE","DEAD").contains(status))throw new BusinessException(400,"VALIDATION_ERROR","任务筛选参数不正确");
        var rows=db.rows("SELECT id,event_id,event_name,aggregate_id,status,attempt_count,last_error,created_at FROM outbox_event WHERE status=? AND id>? ORDER BY id LIMIT ?",status,after,limit+1);boolean more=rows.size()>limit;var page=more?rows.subList(0,limit):rows;var result=new java.util.LinkedHashMap<String,Object>();result.put("items",page);result.put("nextCursor",more?page.getLast().get("id").toString():null);return ApiResponse.ok(result);
    }
}
