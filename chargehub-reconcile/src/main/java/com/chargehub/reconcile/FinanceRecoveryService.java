package com.chargehub.reconcile;

import com.chargehub.infrastructure.*;
import com.chargehub.payment.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** Finance recovery queries independent facts, preserves evidence, and calls the order application's verified boundary. */
@Service
@Transactional(propagation=Propagation.NOT_SUPPORTED)
public class FinanceRecoveryService {
    private final Db db;private final PaymentService payments;private final ReconciliationService reconciliation;
    private final FinanceOperationStore operations;private final FinancialFactHandler facts;private final TransactionTemplate tx;
    public FinanceRecoveryService(Db db,PaymentService payments,ReconciliationService reconciliation,FinanceOperationStore operations,FinancialFactHandler facts,PlatformTransactionManager manager){
        this.db=db;this.payments=payments;this.reconciliation=reconciliation;this.operations=operations;this.facts=facts;tx=new TransactionTemplate(manager);
    }
    public Map<String,Object> importCsv(long actor,String key,String channel,String date,String type,String timezone,int version,String csv,String reason){
        var request=new TreeMap<String,Object>();request.put("channel",channel);request.put("date",date);request.put("type",type);request.put("timezone",timezone);request.put("version",version);request.put("fileHash",Json.hash(csv));request.put("reason",reason);
        return operations.execute(actor,"finance.import",key,request,reason,operation->tx.execute(status->{
            var result=reconciliation.importCsv(actor,channel,date,type,timezone,version,csv,reason);
            long batch=Long.parseLong(result.get("batchId").toString());
            for(String order:reconciliation.diffOrderNos(batch))facts.financialDifference(order,"RECONCILIATION_DIFFERENCE");
            return result;
        }));
    }
    public Map<String,Object> recoverBatch(long actor,long batch,String key,String reason){
        return operations.execute(actor,"finance.batch.recover",key,Map.of("batchId",batch,"reason",reason),reason,operation->recover(operation,actor,batch,reason));
    }
    private Map<String,Object> recover(FinanceOperationStore.Operation operation,long actor,long batchId,String reason){
        var batch=required("SELECT * FROM reconcile_batch WHERE id=?",batchId);
        var items=db.rows("SELECT * FROM reconcile_item WHERE batch_id=? AND status<>'RESOLVED' ORDER BY id LIMIT 100",batchId);
        int repaired=0,deferred=0;
        for(var item:items){
            String business=Db.str(item,"biz_no"),order=Db.str(item,"order_no");long itemId=Db.num(item,"id");
            boolean refund="REFUND".equals(Db.str(batch,"bill_type"));
            Map<String,Object> response;
            try{response=refund?payments.queryRefund(business):payments.queryPayment(business);}
            catch(BusinessException error){operations.evidence(operation,batchId,itemId,business,"CHANNEL_QUERY",Map.of("bizNo",Objects.toString(business,"")),null,false,error.code());deferred++;continue;}
            if(!"SUCCESS".equals(response.get("status"))){operations.evidence(operation,batchId,itemId,business,"CHANNEL_QUERY",Map.of("bizNo",business),response,false,"CHANNEL_NOT_SUCCESS");deferred++;continue;}
            ChannelFinancialFact fact;
            try{fact=ChannelFinancialFact.parse(response,refund);}catch(BusinessException error){operations.evidence(operation,batchId,itemId,business,"CHANNEL_QUERY",Map.of("bizNo",business),response,false,error.code());deferred++;continue;}
            String mismatch=matchesExpected(batch,item,fact);
            long evidence=operations.evidence(operation,batchId,itemId,business,"CHANNEL_QUERY",Map.of("bizNo",business),response,mismatch==null,mismatch==null?"VERIFIED_SUCCESS":mismatch);
            if(mismatch!=null){if(order!=null)facts.financialDifference(order,mismatch);deferred++;continue;}
            if("LOCAL_ONLY".equals(Db.str(item,"diff_type"))){
                // An authoritative successful query does not put the omitted row into the original file.
                deferred++;continue;
            }
            if(!"CHANNEL_ONLY".equals(Db.str(item,"diff_type"))){deferred++;continue;}
            try{
                var notification=verifiedNotification(fact);
                var applied=facts.acceptVerified(notification);
                if(Boolean.FALSE.equals(applied.get("valid"))){if(order!=null)facts.financialDifference(order,"RECOVERY_FACT_MISMATCH");deferred++;continue;}
                if(resolveExact(actor,batch,item,evidence,"QUERY_RECOVERED")){repaired++;if(order!=null)facts.reconsiderFinancialClose(order);}else deferred++;
            }catch(BusinessException error){operations.evidence(operation,batchId,itemId,business,"FACT_APPLICATION",Map.of("evidenceId",evidence),null,false,error.code());deferred++;}
        }
        // Confirm whether this exact uploaded file was the independent channel's complete export.
        try{
            var exported=payments.exportBill(Db.str(batch,"business_date"),Db.str(batch,"bill_type"),Db.str(batch,"source_timezone"));
            String content=Db.str(exported,"content");boolean complete=Boolean.TRUE.equals(exported.get("complete"))&&content!=null&&Json.hash(content).equals(Db.str(exported,"fileHash"));
            var summary=new LinkedHashMap<>(exported);summary.remove("content");
            boolean same=complete&&Db.str(batch,"file_hash").equals(Json.hash(content));
            operations.evidence(operation,batchId,null,null,"BILL_EXPORT",Map.of("businessDate",Db.str(batch,"business_date"),"billType",Db.str(batch,"bill_type")),summary,same,same?"COMPLETE_SAME_FILE":"NEW_OR_INCOMPLETE_FILE");
            if(same)db.update("UPDATE reconcile_batch SET source_complete=1 WHERE id=?",batchId);
        }catch(BusinessException error){operations.evidence(operation,batchId,null,null,"BILL_EXPORT",Map.of("batchId",batchId),null,false,error.code());}
        refreshBatch(batchId);
        return Map.of("batchId",Long.toString(batchId),"repairedCount",repaired,"deferredCount",deferred,"status",Db.str(required("SELECT * FROM reconcile_batch WHERE id=?",batchId),"status"));
    }
    /** Only verified, exact matches can be disposed. A note never changes a financial fact. */
    public Map<String,Object> disposition(long actor,long itemId,String key,String action,Long replacement,String reason){
        var request=new LinkedHashMap<String,Object>();request.put("itemId",itemId);request.put("action",action);request.put("replacementBatchId",replacement);request.put("reason",reason);
        return operations.execute(actor,"finance.item.disposition",key,request,reason,operation->{
            var item=required("SELECT * FROM reconcile_item WHERE id=?",itemId);long batchId=Db.num(item,"batch_id");
            if("RESOLVED".equals(Db.str(item,"status")))return Map.of("itemId",Long.toString(itemId),"status","RESOLVED");
            if("KEEP_OPEN".equals(action)){operations.evidence(operation,batchId,itemId,Db.str(item,"biz_no"),"MANUAL_REVIEW",request,Map.of("reason",reason),false,"REVIEWED_KEEP_OPEN");return Map.of("itemId",Long.toString(itemId),"status",Db.str(item,"status"));}
            if(!"CONFIRM_SUPERSEDED".equals(action)||replacement==null)throw new BusinessException(400,"INVALID_DISPOSITION","仅支持KEEP_OPEN或以更完整渠道账单CONFIRM_SUPERSEDED，不允许人工调平");
            var original=required("SELECT * FROM reconcile_batch WHERE id=?",batchId);var next=required("SELECT * FROM reconcile_batch WHERE id=?",replacement);
            if(Db.num(next,"version_no")<=Db.num(original,"version_no")||!"MATCHED".equals(Db.str(next,"status"))||Db.num(next,"source_complete")!=1
                    ||!Objects.equals(Db.str(original,"channel"),Db.str(next,"channel"))||!Objects.equals(Db.str(original,"business_date"),Db.str(next,"business_date"))||!Objects.equals(Db.str(original,"bill_type"),Db.str(next,"bill_type"))||!Objects.equals(Db.str(original,"source_timezone"),Db.str(next,"source_timezone")))
                throw new BusinessException(409,"REPLACEMENT_NOT_VERIFIED","必须引用同渠道日期类型时区、更高版本、已完成独立完整性校验的匹配批次");
            var export=ChannelCsv.parse(Db.str(next,"channel"),Db.str(next,"business_date"),Db.str(next,"bill_type"),Db.str(next,"source_timezone"),(int)Db.num(next,"version_no"),Db.str(next,"source_content"));
            var row=export.rows().stream().filter(r->r.bizNo().equals(Db.str(item,"biz_no"))&&r.providerBizNo().equals(Db.str(item,"provider_biz_no"))).findFirst().orElseThrow(()->new BusinessException(409,"REPLACEMENT_ROW_MISSING","替代账单没有相同业务资金行"));
            String type="PAYMENT".equals(export.billType())?"CAPTURE":"REFUND";
            var cash=db.one("SELECT * FROM cash_entry WHERE biz_type=? AND biz_no=? AND channel=?",type,row.bizNo(),export.channel());
            if(cash==null||row.amountFen()!=Db.num(cash,"amount_fen")||!row.currency().equals(Db.str(cash,"currency"))||!row.providerBizNo().equals(Db.str(cash,"provider_biz_no"))||!sameTime(row.occurredAt(),Db.instant(cash,"occurred_at")))throw new BusinessException(409,"REPLACEMENT_FACT_MISMATCH","替代账单与原始本地资金事实不一致");
            var queriedResponse="REFUND".equals(export.billType())?payments.queryRefund(row.bizNo()):payments.queryPayment(row.bizNo());
            var queried=ChannelFinancialFact.parse(queriedResponse,"REFUND".equals(export.billType()));
            boolean queryMatches="SUCCESS".equals(queried.status())&&row.payNo().equals(queried.payNo())&&Objects.equals(row.refundNo(),queried.refundNo())
                    &&row.amountFen()==queried.amountFen()&&row.currency().equals(queried.currency())&&row.providerBizNo().equals(queried.providerBizNo())&&sameTime(row.occurredAt(),queried.occurredAt());
            operations.evidence(operation,batchId,itemId,row.bizNo(),"CHANNEL_QUERY",Map.of("replacementBatchId",replacement),queriedResponse,queryMatches,queryMatches?"REPLACEMENT_QUERY_MATCH":"REPLACEMENT_QUERY_MISMATCH");
            if(!queryMatches){if(Db.str(item,"order_no")!=null)facts.financialDifference(Db.str(item,"order_no"),"REPLACEMENT_QUERY_MISMATCH");throw new BusinessException(409,"REPLACEMENT_QUERY_MISMATCH","重新查单与替代账单矛盾，继续挂起");}
            long evidence=operations.evidence(operation,batchId,itemId,row.bizNo(),"SUPERSEDING_BILL",request,Map.of("replacementBatchId",replacement,"fileHash",Db.str(next,"file_hash")),true,"VERIFIED_REPLACEMENT");
            tx.executeWithoutResult(status->{db.update("UPDATE reconcile_item SET status='RESOLVED',resolution=?,resolved_by=?,resolved_at=UTC_TIMESTAMP(3) WHERE id=? AND status<>'RESOLVED'","SUPERSEDED_BY_BATCH:"+replacement+" EVIDENCE:"+evidence,actor,itemId);});
            refreshBatch(batchId);String order=Db.str(item,"order_no");if(order!=null)facts.reconsiderFinancialClose(order);
            return Map.of("itemId",Long.toString(itemId),"status","RESOLVED","evidenceId",Long.toString(evidence));
        });
    }
    public Map<String,Object> refund(long actor,String refundNo,String key,String reason,boolean retry){
        return operations.execute(actor,retry?"finance.refund.retry":"finance.refund.recover",key,Map.of("refundNo",refundNo,"reason",reason),reason,operation->{
            var local=required("SELECT * FROM refund_order WHERE refund_no=?",refundNo);Map<String,Object> result;
            try{result=payments.queryRefund(refundNo);}catch(BusinessException error){operations.evidence(operation,null,null,refundNo,"REFUND_QUERY",Map.of("refundNo",refundNo),null,false,error.code());throw error;}
            if("NOT_FOUND".equals(result.get("status"))){
                operations.evidence(operation,null,null,refundNo,"REFUND_QUERY",Map.of("refundNo",refundNo),result,false,"NOT_FOUND");
                if(!retry) return Map.of("refundNo",refundNo,"status",Db.str(local,"status"),"channelStatus","NOT_FOUND");
                if(Set.of("FAILED_FINAL","FAILED_RETRYABLE","SUCCESS").contains(Db.str(local,"status")))throw new BusinessException(409,"REFUND_QUERY_CONTRADICTION","渠道查无原单与本地终态矛盾，不允许新号退款");
            }else{
                var queried=ChannelFinancialFact.parse(result,true);boolean owned=refundNo.equals(queried.refundNo())&&Db.str(local,"pay_no").equals(queried.payNo())&&Db.num(local,"amount_fen")==queried.amountFen()&&"CNY".equals(queried.currency())&&(Db.str(local,"provider_refund_no")==null||Db.str(local,"provider_refund_no").equals(queried.providerBizNo()));
                operations.evidence(operation,null,null,refundNo,"REFUND_QUERY",Map.of("refundNo",refundNo),result,owned,owned?"VERIFIED":"OWNERSHIP_MISMATCH");
                if(!owned){facts.financialDifference(Db.str(local,"order_no"),"REFUND_QUERY_MISMATCH");throw new BusinessException(409,"REFUND_QUERY_MISMATCH","渠道查单金额或关系不一致");}
                if(Set.of("SUCCESS","FAILED").contains(queried.status()))facts.acceptVerified(verifiedNotification(queried));else payments.applyRefund(result);
                if("SUCCESS".equals(queried.status()))return Map.of("refundNo",refundNo,"status","SUCCESS");
                if(!retry || !"FAILED".equals(queried.status()))return payments.funds(Db.str(local,"order_no"));
                payments.prepareRefundRetry(refundNo,result);
            }
            try{
                var sent=payments.sendRefund(refundNo);var finalFact=ChannelFinancialFact.parse(sent,true);
                operations.evidence(operation,null,null,refundNo,"REFUND_SEND",Map.of("refundNo",refundNo),sent,true,"RESULT_OBSERVED");
                if(Set.of("SUCCESS","FAILED").contains(finalFact.status()))facts.acceptVerified(verifiedNotification(finalFact));else payments.applyRefund(sent);
            }catch(BusinessException error){payments.markRefundUnknown(refundNo,error.code());operations.evidence(operation,null,null,refundNo,"REFUND_SEND",Map.of("refundNo",refundNo),null,false,error.code());}
            return payments.funds(Db.str(local,"order_no"));
        });
    }
    public Map<String,Object> item(long id){var row=required("SELECT * FROM reconcile_item WHERE id=?",id);var result=new LinkedHashMap<>(row);result.put("evidence",db.rows("SELECT * FROM finance_evidence WHERE item_id=? ORDER BY id",id));return result;}
    public Map<String,Object> batch(long id){var row=required("SELECT * FROM reconcile_batch WHERE id=?",id);var result=new LinkedHashMap<>(row);result.remove("source_content");result.put("evidence",db.rows("SELECT * FROM finance_evidence WHERE batch_id=? ORDER BY id",id));return result;}
    private String matchesExpected(Map<String,Object> batch,Map<String,Object> item,ChannelFinancialFact fact){
        boolean refund="REFUND".equals(Db.str(batch,"bill_type"));String business=refund?fact.refundNo():fact.payNo();
        if(!Objects.equals(business,Db.str(item,"biz_no"))||!Objects.equals(fact.providerBizNo(),Db.str(item,"provider_biz_no")))return "QUERY_BUSINESS_MISMATCH";
        var expected=refund?db.one("SELECT r.amount_fen,r.pay_no,p.currency,r.provider_refund_no provider FROM refund_order r JOIN payment_order p ON p.pay_no=r.pay_no WHERE r.refund_no=?",business):db.one("SELECT amount_fen,pay_no,currency,provider_trade_no provider FROM payment_order WHERE pay_no=?",business);
        if(expected==null||!fact.payNo().equals(Db.str(expected,"pay_no")))return "QUERY_OWNERSHIP_MISMATCH";
        if(fact.amountFen()!=Db.num(expected,"amount_fen")||!fact.currency().equals(Db.str(expected,"currency")))return "QUERY_AMOUNT_MISMATCH";
        if(Db.str(expected,"provider")!=null&&!fact.providerBizNo().equals(Db.str(expected,"provider")))return "QUERY_PROVIDER_MISMATCH";
        if(!fact.occurredAt().atZone(ZoneId.of(Db.str(batch,"source_timezone"))).toLocalDate().equals(LocalDate.parse(Db.str(batch,"business_date"))))return "QUERY_DATE_MISMATCH";
        String snapshot=Db.str(item,"channel_snapshot");
        if(snapshot!=null){var original=Json.read(snapshot);if(fact.amountFen()!=Db.num(original,"amountFen")||!fact.currency().equals(Db.str(original,"currency"))||!sameTime(fact.occurredAt(),Instant.parse(Db.str(original,"occurredAt"))))return "QUERY_FILE_MISMATCH";}
        if("LOCAL_ONLY".equals(Db.str(item,"diff_type"))){var cash=db.one("SELECT * FROM cash_entry WHERE biz_type=? AND biz_no=?",refund?"REFUND":"CAPTURE",business);if(cash==null||Db.num(cash,"amount_fen")!=fact.amountFen()||!sameTime(Db.instant(cash,"occurred_at"),fact.occurredAt()))return "QUERY_LOCAL_MISMATCH";}
        return null;
    }
    private boolean resolveExact(long actor,Map<String,Object> batch,Map<String,Object> item,long evidence,String action){
        return tx.execute(status->{var cash=db.one("SELECT * FROM cash_entry WHERE channel=? AND biz_type=? AND biz_no=?",Db.str(batch,"channel"),"PAYMENT".equals(Db.str(batch,"bill_type"))?"CAPTURE":"REFUND",Db.str(item,"biz_no"));
            if(cash==null||Db.num(cash,"amount_fen")!=Db.num(item,"channel_amount_fen")||!Objects.equals(Db.str(cash,"provider_biz_no"),Db.str(item,"provider_biz_no"))||!Objects.equals(Db.str(cash,"currency"),Db.str(item,"currency")))return false;
            return db.update("UPDATE reconcile_item SET status='RESOLVED',local_amount_fen=?,resolution=?,resolved_by=?,resolved_at=UTC_TIMESTAMP(3) WHERE id=? AND status<>'RESOLVED'",Db.num(cash,"amount_fen"),action+" EVIDENCE:"+evidence,actor,Db.num(item,"id"))==1;});
    }
    private void refreshBatch(long batch){tx.executeWithoutResult(status->{var current=required("SELECT * FROM reconcile_batch WHERE id=? FOR UPDATE",batch);long open=Db.num(db.one("SELECT COUNT(*) n FROM reconcile_item WHERE batch_id=? AND status<>'RESOLVED'",batch),"n");long missing=Db.num(db.one("SELECT COUNT(*) n FROM reconcile_item WHERE batch_id=? AND status<>'RESOLVED' AND diff_type='LOCAL_ONLY'",batch),"n");db.update("UPDATE reconcile_batch SET status=?,diff_count=? WHERE id=?",open==0?"MATCHED":missing>0?"INCOMPLETE":"DIFF",open,batch);});}
    private static Map<String,Object> verifiedNotification(ChannelFinancialFact fact){var event=fact.notification();event.put("channelEventId","verified-query-"+Json.hash(fact.payNo()+"|"+fact.refundNo()+"|"+fact.providerBizNo()+"|"+fact.status()+"|"+fact.occurredAt()+"|"+fact.attempt()).substring(0,64));return event;}
    private static boolean sameTime(Instant one,Instant two){return one!=null&&two!=null&&one.truncatedTo(ChronoUnit.MILLIS).equals(two.truncatedTo(ChronoUnit.MILLIS));}
    private Map<String,Object> required(String sql,Object arg){var row=db.one(sql,arg);if(row==null)throw new BusinessException(404,"FINANCE_RESOURCE_NOT_FOUND","财务记录不存在");return row;}
}
