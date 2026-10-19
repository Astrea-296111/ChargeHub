package com.chargehub.app;

import com.chargehub.infrastructure.*;
import com.chargehub.identity.Actor;
import com.chargehub.station.StationService;
import com.chargehub.benefit.BenefitService;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.access.prepost.PreAuthorize;
import java.util.*;
import java.util.function.Supplier;

@RestController
@PreAuthorize("hasAnyRole('OPS','ADMIN')")
public class PromotionAdminController {
    private final StationService stations;private final BenefitService benefits;private final Idempotency idempotency;private final Db db;
    public PromotionAdminController(StationService stations,BenefitService benefits,Idempotency idempotency,Db db){this.stations=stations;this.benefits=benefits;this.idempotency=idempotency;this.db=db;}
    @PostMapping("/v1/admin/tariffs") public ApiResponse tariff(@RequestBody Map<String,Object> body,@RequestHeader("Idempotency-Key") String key,@RequestHeader(value="X-Operation-Reason",defaultValue="创建费率草稿") String reason){return write("tariffs.create",key,body,reason,"CREATE_TARIFF",()->stations.createTariff(body));}
    @PostMapping("/v1/admin/tariffs/{id}/publish") public ApiResponse publishTariff(@PathVariable long id,@RequestHeader("Idempotency-Key") String key,@RequestHeader(value="X-Operation-Reason",defaultValue="发布不可变费率版本") String reason){return write("tariffs.publish/"+id,key,Map.of(),reason,"PUBLISH_TARIFF",()->stations.publishTariff(id));}
    @PostMapping("/v1/admin/coupon-templates") public ApiResponse template(@RequestBody Map<String,Object> body,@RequestHeader("Idempotency-Key") String key,@RequestHeader(value="X-Operation-Reason",defaultValue="创建服务费优惠券草稿") String reason){return write("templates.create",key,body,reason,"CREATE_COUPON_TEMPLATE",()->benefits.createTemplate(body));}
    @PostMapping("/v1/admin/coupon-templates/{id}/publish") public ApiResponse publishTemplate(@PathVariable long id,@RequestHeader("Idempotency-Key") String key,@RequestHeader(value="X-Operation-Reason",defaultValue="发布不可变优惠活动") String reason){return write("templates.publish/"+id,key,Map.of(),reason,"PUBLISH_COUPON_TEMPLATE",()->benefits.publishTemplate(id));}
    private ApiResponse write(String scope,String key,Map<String,Object> body,String reason,String action,Supplier<Map<String,Object>> operation){
        if(reason==null||reason.isBlank()||reason.length()>255)throw new BusinessException(400,"VALIDATION_ERROR","操作原因不能为空且最多255字");
        Map<String,Object> digest=new LinkedHashMap<>(body);digest.put("_operationReason",reason);
        return ApiResponse.ok(idempotency.execute(Actor.id(),scope,key,digest,()->{
            Map<String,Object> result=operation.get();Object target=result.getOrDefault("tariffId",result.getOrDefault("templateId",result.getOrDefault("id","DRAFT")));
            db.update("INSERT INTO ops_audit(operator_id,action,target_type,target_no,reason,after_json,request_id) VALUES(?,?,'PROMOTION',?,?,?,?)",Actor.id(),action,target.toString(),reason,Json.write(ApiResponse.sanitize(result)),ApiResponse.requestIdValue());return result;
        }));
    }
}
