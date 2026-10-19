package com.chargehub.reconcile;

import com.chargehub.infrastructure.Db;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;

/** Local durable recovery queue; all external work follows the committed fenced claim. */
@Component @Profile("worker")
public class FinanceRecoveryWorker {
    private final Db db;private final FinanceRecoveryService recovery;private final TransactionTemplate tx;
    public FinanceRecoveryWorker(Db db,FinanceRecoveryService recovery,PlatformTransactionManager manager){this.db=db;this.recovery=recovery;tx=new TransactionTemplate(manager);}
    @Scheduled(fixedDelayString="${chargehub.finance-recovery-delay-ms:5000}")
    public void recover(){
        Map<String,Object> task=tx.execute(status->{
            var row=db.one("SELECT * FROM reconcile_recovery_task WHERE (status='READY' AND next_attempt_at<=UTC_TIMESTAMP(3)) OR (status='PROCESSING' AND lease_until<UTC_TIMESTAMP(3)) ORDER BY batch_id LIMIT 1 FOR UPDATE SKIP LOCKED");
            if(row==null)return null;
            db.update("UPDATE reconcile_recovery_task SET status='PROCESSING',attempt_count=attempt_count+1,lease_token=lease_token+1,lease_until=TIMESTAMPADD(MINUTE,5,UTC_TIMESTAMP(3)) WHERE batch_id=?",Db.num(row,"batch_id"));
            return db.one("SELECT * FROM reconcile_recovery_task WHERE batch_id=?",Db.num(row,"batch_id"));
        });
        if(task==null)return;
        long batch=Db.num(task,"batch_id"),token=Db.num(task,"lease_token"),attempt=Db.num(task,"attempt_count");
        try{
            var result=recovery.recoverBatch(Db.num(task,"operator_id"),batch,"auto-batch-"+batch+"-"+token,Db.str(task,"reason"));
            boolean progress=result.get("repairedCount") instanceof Number n&&n.intValue()>0;
            String next="MATCHED".equals(result.get("status"))?"DONE":attempt>=6&&!progress?"DEAD":"READY";
            db.update("UPDATE reconcile_recovery_task SET status=?,attempt_count=?,lease_until=NULL,next_attempt_at=TIMESTAMPADD(SECOND,?,UTC_TIMESTAMP(3)),last_error=? WHERE batch_id=? AND lease_token=?",
                    next,progress?0:attempt,Math.min(60,1L<<Math.min(attempt,6)),"DONE".equals(next)?null:"UNRESOLVED_DIFFERENCES",batch,token);
        }catch(RuntimeException error){db.update("UPDATE reconcile_recovery_task SET status=?,lease_until=NULL,next_attempt_at=TIMESTAMPADD(SECOND,30,UTC_TIMESTAMP(3)),last_error=? WHERE batch_id=? AND lease_token=?",attempt>=6?"DEAD":"READY",error instanceof com.chargehub.infrastructure.BusinessException b?b.code():error.getClass().getSimpleName(),batch,token);}
    }
}
