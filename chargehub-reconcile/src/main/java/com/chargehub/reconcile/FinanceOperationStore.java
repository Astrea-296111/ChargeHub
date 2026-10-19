package com.chargehub.reconcile;

import com.chargehub.infrastructure.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import java.util.function.Function;

/** Durable idempotency with short transactions. No user/payment lock is held during channel I/O. */
@Service
public class FinanceOperationStore {
    private final Db db;private final TransactionTemplate tx;
    public FinanceOperationStore(Db db,PlatformTransactionManager manager){this.db=db;this.tx=new TransactionTemplate(manager);}
    public record Operation(long id,long token,int attempt){}
    public Map<String,Object> execute(long actor,String scope,String key,Map<String,?> request,String reason,Function<Operation,Map<String,Object>> action){
        if(actor<=0)throw new BusinessException(401,"AUTH_REQUIRED","需要已认证财务身份");
        if(reason==null||reason.isBlank()||reason.length()>255)throw new BusinessException(400,"REASON_REQUIRED","需要1至255字操作原因");
        if(key==null||!key.matches("[A-Za-z0-9._:-]{8,64}"))throw new BusinessException(400,"INVALID_IDEMPOTENCY_KEY","需要8至64位Idempotency-Key");
        String payload=Json.write(new TreeMap<>(request)),hash=Json.hash(payload);
        Map.Entry<Operation,Map<String,Object>> claim=tx.execute(status->{
            long id=db.insert("INSERT INTO finance_operation(operator_id,scope,idem_key,request_hash,request_payload) VALUES(?,?,?,?,?) ON DUPLICATE KEY UPDATE id=LAST_INSERT_ID(id)",actor,scope,key,hash,payload);
            var prior=db.one("SELECT * FROM finance_operation WHERE id=? FOR UPDATE",id);
            if(!hash.equals(Db.str(prior,"request_hash")))throw new BusinessException(409,"IDEMPOTENCY_CONFLICT","相同财务请求编号已有其他参数");
            if("DONE".equals(Db.str(prior,"status")))return new AbstractMap.SimpleEntry<Operation,Map<String,Object>>(null,Json.read(Db.str(prior,"response_payload")));
            if("PROCESSING".equals(Db.str(prior,"status"))&&Db.num(db.one("SELECT CASE WHEN lease_until>UTC_TIMESTAMP(3) THEN 1 ELSE 0 END active FROM finance_operation WHERE id=?",id),"active")==1)
                throw new BusinessException(409,"FINANCE_OPERATION_BUSY","相同财务操作处理中，请按原请求编号重试");
            db.update("UPDATE finance_operation SET status='PROCESSING',attempt_count=attempt_count+1,lease_token=lease_token+1,lease_until=TIMESTAMPADD(MINUTE,5,UTC_TIMESTAMP(3)),last_error=NULL WHERE id=?",id);
            return new AbstractMap.SimpleEntry<>(new Operation(id,Db.num(prior,"lease_token")+1,(int)Db.num(prior,"attempt_count")+1),null);
        });
        if(claim.getKey()==null)return claim.getValue();
        Operation operation=claim.getKey();
        try{
            var result=action.apply(operation);
            tx.executeWithoutResult(status->{
                if(db.update("UPDATE finance_operation SET status='DONE',response_payload=?,lease_until=NULL WHERE id=? AND lease_token=? AND status='PROCESSING'",Json.write(result),operation.id,operation.token)!=1)
                    throw new BusinessException(409,"FINANCE_OPERATION_LEASE_LOST","财务操作租约已接管，请查询原操作结果");
                db.update("INSERT INTO ops_audit(operator_id,action,target_type,target_no,reason,before_json,after_json,request_id) VALUES(?,'FINANCE_OPERATION','FINANCE',?,?,?, ?,?)",
                        actor,Long.toString(operation.id),reason.trim(),Json.write(Map.of("scope",scope,"attempt",operation.attempt)),Json.write(result),ApiResponse.requestIdValue());
            });
            return result;
        }catch(RuntimeException error){
            tx.executeWithoutResult(status->{
                String code=error instanceof BusinessException b?b.code():error.getClass().getSimpleName();
                db.update("UPDATE finance_operation SET status='RETRYABLE',lease_until=NULL,last_error=? WHERE id=? AND lease_token=?",code,operation.id,operation.token);
                db.update("INSERT INTO ops_audit(operator_id,action,target_type,target_no,reason,after_json,request_id) VALUES(?,'FINANCE_OPERATION_REJECTED','FINANCE',?,?,?,?)",actor,Long.toString(operation.id),reason.trim(),Json.write(Map.of("scope",scope,"errorCode",code)),ApiResponse.requestIdValue());
            });
            throw error;
        }
    }
    public long evidence(Operation operation,Long batch,Long item,String biz,String type,Object request,Object response,boolean verified,String outcome){
        String json=response==null?null:Json.write(response);
        return db.insert("INSERT INTO finance_evidence(operation_id,operation_attempt,batch_id,item_id,biz_no,evidence_type,request_payload,response_payload,response_hash,verified,outcome) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                operation.id,operation.attempt,batch,item,biz,type,Json.write(request),json,json==null?null:Json.hash(json),verified?1:0,outcome);
    }
}
