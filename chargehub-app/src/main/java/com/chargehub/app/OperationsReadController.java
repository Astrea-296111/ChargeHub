package com.chargehub.app;

import com.chargehub.infrastructure.*;
import jakarta.validation.constraints.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import java.util.*;

/** Staff views deliberately exclude account credentials and integration payloads. */
@RestController
@Validated
public class OperationsReadController {
    private final Db db;
    public OperationsReadController(Db db){this.db=db;}
    @GetMapping("/v1/admin/charge-orders")
    @PreAuthorize("hasAnyRole('OPS','FINANCE','ADMIN')")
    public ApiResponse orders(@RequestParam(required=false) @Min(1) Long stationId,@RequestParam(required=false) String status,@RequestParam(defaultValue="0") @Min(0) long after,@RequestParam(defaultValue="20") @Min(1) @Max(100) int limit){
        List<Object> args=new ArrayList<>();
        String sql="SELECT o.id,o.order_no,o.station_id,o.connector_id,o.status,o.financial_status,o.prepaid_fen,o.exception_code,o.created_at,s.session_no FROM charge_order o LEFT JOIN charge_session s ON s.order_no=o.order_no WHERE 1=1";
        if(after>0){
            var cursor=db.one("SELECT created_at FROM charge_order WHERE id=?",after);
            if(cursor==null)throw new BusinessException(400,"INVALID_CURSOR","订单游标不正确");
            sql+=" AND (o.created_at>? OR (o.created_at=? AND o.id>?))";
            args.add(cursor.get("created_at"));args.add(cursor.get("created_at"));args.add(after);
        }
        if(stationId!=null){sql+=" AND o.station_id=?";args.add(stationId);}
        if(status!=null){if(!Set.of("WAIT_PAY","CANCELING","START_PENDING","CHARGING","STOP_PENDING","BILLING","REVIEW","EXCEPTION","COMPLETED","CANCELED").contains(status))throw new BusinessException(400,"VALIDATION_ERROR","订单状态不正确");sql+=" AND o.status=?";args.add(status);}
        args.add(limit+1);return page(db.rows(sql+" ORDER BY o.created_at,o.id LIMIT ?",args.toArray()),limit);
    }
    @GetMapping("/v1/admin/audits")
    @PreAuthorize("hasAnyRole('FINANCE','ADMIN')")
    public ApiResponse audits(@RequestParam(defaultValue="0") @Min(0) long after,@RequestParam(defaultValue="20") @Min(1) @Max(100) int limit){return page(db.rows("SELECT id,operator_id,action,target_type,target_no,reason,request_id,created_at FROM ops_audit WHERE id>? ORDER BY id LIMIT ?",after,limit+1),limit);}
    private ApiResponse page(List<Map<String,Object>> rows,int limit){boolean more=rows.size()>limit;var page=more?rows.subList(0,limit):rows;Map<String,Object> response=new LinkedHashMap<>();response.put("items",page);response.put("nextCursor",more?page.getLast().get("id").toString():null);return ApiResponse.ok(response);}
}
