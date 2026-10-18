package com.chargehub.infrastructure;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
@Service
public class Idempotency {
    private final Db db; private final TransactionTemplate tx;
    public Idempotency(Db db,PlatformTransactionManager manager){this.db=db;tx=new TransactionTemplate(manager);}
    public Map<String,Object> execute(long user,String scope,String key,Map<String,?> request,Supplier<Map<String,Object>> action){
        if(key==null||key.length()<8||key.length()>64||!key.matches("[A-Za-z0-9._:-]+"))throw new BusinessException(400,"VALIDATION_ERROR","Idempotency-Key 需为8至64位字符");
        String hash=Json.hash(Json.write(new TreeMap<>(request)));
        return tx.execute(status->{
            if(db.one("SELECT id FROM iam_user WHERE id=? FOR UPDATE",user)==null)throw new BusinessException(401,"AUTH_REQUIRED","账号不存在");
            Map<String,Object> prior=db.one("SELECT * FROM idempotency_record WHERE principal_id=? AND scope=? AND idem_key=?",user,scope,key);
            if(prior!=null){
                if(!hash.equals(prior.get("request_hash")))throw new BusinessException(409,"IDEMPOTENCY_CONFLICT","同一请求编号不能用于不同参数");
                return Json.read(Db.str(prior,"response_json"));
            }
            db.update("INSERT INTO idempotency_record(principal_id,scope,idem_key,request_hash,status,expires_at) VALUES(?,?,?,?,'PROCESSING',TIMESTAMPADD(HOUR,24,UTC_TIMESTAMP(3)))",user,scope,key,hash);
            Map<String,Object> result=Json.read(Json.write(ApiResponse.sanitize(action.get())));
            db.update("UPDATE idempotency_record SET status='DONE',http_status=200,response_json=? WHERE principal_id=? AND scope=? AND idem_key=?",Json.write(result),user,scope,key);
            return result;
        });
    }
}
