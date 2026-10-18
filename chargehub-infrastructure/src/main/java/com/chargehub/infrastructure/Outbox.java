package com.chargehub.infrastructure;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import java.util.*;

/** Durable short leases; network effects run after claim transaction commits. */
@Service
public class Outbox {
    private final Db db;
    private final TransactionTemplate tx;
    private final String owner = UUID.randomUUID().toString();
    public Outbox(Db db, PlatformTransactionManager manager) { this.db=db; this.tx=new TransactionTemplate(manager); }
    public void enqueue(String name,String aggregateId,Map<String,?> payload) {
        db.update("INSERT INTO outbox_event(event_id,event_name,aggregate_type,aggregate_id,aggregate_version,payload,trace_id) VALUES(?,?,'ORDER',?,1,?,?) ON DUPLICATE KEY UPDATE id=id",
            UUID.randomUUID().toString(),name,aggregateId,Json.write(payload),UUID.randomUUID().toString());
    }
    public Map<String,Object> claim() {
        return tx.execute(s -> {
            Map<String,Object> e=db.one("SELECT * FROM outbox_event WHERE (status='READY' AND next_attempt_at<=UTC_TIMESTAMP(3)) OR (status='PROCESSING' AND lease_until<UTC_TIMESTAMP(3)) ORDER BY id LIMIT 1 FOR UPDATE SKIP LOCKED");
            if(e==null)return null;
            db.update("UPDATE outbox_event SET status='PROCESSING',lease_owner=?,lease_token=lease_token+1,lease_until=TIMESTAMPADD(SECOND,30,UTC_TIMESTAMP(3)),attempt_count=attempt_count+1 WHERE id=?",owner,e.get("id"));
            return db.one("SELECT * FROM outbox_event WHERE id=?",e.get("id"));
        });
    }
    public boolean ack(Map<String,Object> e) {
        return db.update("UPDATE outbox_event SET status='DONE',published_at=UTC_TIMESTAMP(3),lease_until=NULL WHERE id=? AND lease_owner=? AND lease_token=? AND status='PROCESSING'",e.get("id"),owner,e.get("lease_token"))==1;
    }
    public void fail(Map<String,Object> e,Exception failure) {
        long attempt=Db.num(e,"attempt_count");
        String error=failure.getClass().getSimpleName(); // never persist request bodies or credentials
        db.update("UPDATE outbox_event SET status=?,next_attempt_at=TIMESTAMPADD(SECOND,?,UTC_TIMESTAMP(3)),lease_until=NULL,last_error=? WHERE id=? AND lease_owner=? AND lease_token=? AND status='PROCESSING'",attempt>=10?"DEAD":"READY",Math.min(60,1L<<Math.min(attempt,6)),error,e.get("id"),owner,e.get("lease_token"));
    }
    public Map<String,Object> replay(String eventId,long actor,String reason) {
        return tx.execute(s -> {
            Map<String,Object> e=db.one("SELECT * FROM outbox_event WHERE event_id=? FOR UPDATE",eventId);
            if(e==null)throw new BusinessException(404,"NOT_FOUND","任务不存在");
            if(!"DEAD".equals(e.get("status")))throw new BusinessException(409,"INVALID_STATE","仅允许重放死信");
            db.update("UPDATE outbox_event SET status='READY',attempt_count=0,next_attempt_at=UTC_TIMESTAMP(3),lease_token=lease_token+1 WHERE event_id=?",eventId);
            db.update("INSERT INTO ops_audit(operator_id,action,target_type,target_no,reason,before_json,after_json,request_id) VALUES(?,'OUTBOX_REPLAY','EVENT',?,?,?, ?,?)",actor,eventId,reason,Json.write(Map.of("status","DEAD")),Json.write(Map.of("status","READY")),UUID.randomUUID().toString());
            return Map.of("resourceNo",eventId,"status","READY");
        });
    }
}
