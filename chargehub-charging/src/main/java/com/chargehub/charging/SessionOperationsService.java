package com.chargehub.charging;

import com.chargehub.infrastructure.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Clock;
import java.util.*;
import java.util.function.Supplier;

/** Human actions select authenticated immutable device facts; they cannot enter meter values. */
@Service
public class SessionOperationsService {
    private final Db db;
    private final SessionService sessions;
    private final Outbox outbox;
    private final TransactionTemplate tx;
    public SessionOperationsService(Db db, SessionService sessions, Outbox outbox, PlatformTransactionManager manager) {
        this.db=db; this.sessions=sessions; this.outbox=outbox; this.tx=new TransactionTemplate(manager);
    }

    @Transactional(readOnly=true)
    public Map<String,Object> review(String orderNo) {
        var order=requiredOrder(orderNo); var session=requiredSession(orderNo,false);
        var result=new LinkedHashMap<String,Object>();
        result.put("orderNo",orderNo); result.put("status",Db.str(order,"status"));
        result.put("financialStatus",Db.str(order,"financial_status"));
        result.put("exceptionCode",Db.str(order,"exception_code")); result.put("session",session);
        result.put("billImmutable",db.one("SELECT bill_no FROM charge_bill WHERE order_no=?",orderNo)!=null);
        var samples=sessions.effectiveSamples(session); result.put("effectiveSamples",samples);
        result.put("rawEvents",db.rows("SELECT id,event_id,event_type,seq_no,payload_hash,payload,process_status,error_code,occurred_at,received_at FROM device_event WHERE session_no=? ORDER BY id LIMIT 1000",Db.str(session,"session_no")));
        result.put("conflictingPayloads",db.rows("SELECT c.* FROM device_event_conflict c JOIN device_event e ON e.id=c.original_event_id WHERE e.session_no=? ORDER BY c.id LIMIT 1000",Db.str(session,"session_no")));
        result.put("unresolvedEvents",sessions.unresolvedReviews(session));
        result.put("missingRanges",missingRanges(samples,session));
        result.put("replayRequests",db.rows("SELECT * FROM meter_replay_request WHERE order_no=? ORDER BY id DESC LIMIT 100",orderNo));
        result.put("proposals",db.rows("SELECT p.*,d.decision,d.decided_by,d.reason decision_reason,d.created_at decided_at FROM meter_review_proposal p LEFT JOIN meter_review_decision d ON d.proposal_no=p.proposal_no WHERE p.order_no=? ORDER BY p.id DESC LIMIT 100",orderNo));
        result.put("selections",db.rows("SELECT * FROM meter_review_selection WHERE session_no=? ORDER BY id",Db.str(session,"session_no")));
        String validation="READY";
        try { validateSamples(order,session,samples); if(!sessions.unresolvedReviews(session).isEmpty()) validation="UNRESOLVED_CONFLICT"; }
        catch(SessionService.MeteringProblem issue){validation=issue.code;}
        catch(BusinessException issue){validation=issue.code();}
        result.put("validation",validation);
        result.put("policy",Map.of("manualAmountsAllowed",false,"secondReviewerRequired",true,
                "evidenceLimit",1000,"maxReplayRange",10000,"issuedBillCanBeChanged",false));
        return result;
    }

    @Transactional
    public Map<String,Object> requestReplay(long actor,String orderNo,String key,Map<String,Object> body) {
        var order=lockOrder(orderNo); var session=requiredSession(orderNo,true);
        return once(actor,"meter.replay/"+orderNo,key,body,()->{
            mutable(orderNo); String reason=reason(body); long from=integer(body.get("fromSeq")),to=integer(body.get("toSeq"));
            if(from<0||to<from||to-from>=10000)throw error("INVALID_REPLAY_RANGE","补传范围须为连续0至9999个序号跨度");
            long observed=Db.num(db.one("SELECT COALESCE(MAX(seq_no),0) n FROM device_event WHERE session_no=?",Db.str(session,"session_no")),"n");
            long endpoint=session.get("final_seq")==null?observed:Db.num(session,"final_seq");
            if(to>endpoint)throw error("INVALID_REPLAY_RANGE","补传不能超过设备已声明的最终序号或已观察到的序号");
            String requestNo=UUID.randomUUID().toString();
            db.insert("INSERT INTO meter_replay_request(request_no,order_no,session_no,from_seq,to_seq,requested_by,reason) VALUES(?,?,?,?,?,?,?)",
                    requestNo,orderNo,Db.str(session,"session_no"),from,to,actor,reason);
            outbox.enqueue("DeviceMeterReplayRequested",requestNo,Map.of("requestNo",requestNo,"orderNo",orderNo,"sessionNo",Db.str(session,"session_no"),"deviceId",Db.str(session,"device_id"),"connectorId",Long.toString(Db.num(session,"connector_id")),"fromSeq",from,"toSeq",to));
            audit(actor,"METER_REPLAY_REQUEST",orderNo,reason,null,Map.of("requestNo",requestNo,"fromSeq",from,"toSeq",to));
            return Map.of("requestNo",requestNo,"orderNo",orderNo,"status","PENDING","fromSeq",from,"toSeq",to);
        });
    }

    /** Called after the worker has queried the external device, with no DB transaction around the network. */
    public void fulfillReplay(String requestNo,Map<String,Object> response) {
        var request=db.one("SELECT * FROM meter_replay_request WHERE request_no=?",requestNo);
        if(request==null)throw new BusinessException(404,"REPLAY_NOT_FOUND","补传请求不存在");
        if("DONE".equals(Db.str(request,"status")))return;
        String orderNo=Db.str(request,"order_no"),sessionNo=Db.str(request,"session_no");
        String responseState=Objects.toString(response.get("status"),"UNKNOWN");
        Set<Long> received=new HashSet<>();
        if(response.get("events") instanceof List<?> events && !responseState.equals("UNKNOWN")) {
            for(Object source:events) {
                if(!(source instanceof Map<?,?> map))throw error("INVALID_DEVICE_RESPONSE","设备补传事实格式不正确");
                var event=new LinkedHashMap<String,Object>();map.forEach((k,v)->event.put(String.valueOf(k),v));
                if(!sessionNo.equals(event.get("sessionNo")))throw error("INVALID_DEVICE_RESPONSE","补传响应混入其他会话事件");
                long seq=integer(event.get("seqNo"));
                if(seq<Db.num(request,"from_seq")||seq>Db.num(request,"to_seq"))continue;
                sessions.ingest(event); received.add(seq);
            }
        }
        long expected=Db.num(request,"to_seq")-Db.num(request,"from_seq")+1;
        String state=responseState.equals("UNKNOWN")?"WAITING":received.size()==expected?"DONE":"PARTIAL";
        String code=state.equals("DONE")?"REPLAY_RECEIVED":responseState.equals("UNKNOWN")?"DEVICE_QUERY_UNKNOWN":"DEVICE_RANGE_INCOMPLETE";
        tx.executeWithoutResult(s->{
            lockOrder(orderNo);
            db.update("UPDATE meter_replay_request SET status=?,received_count=?,response_hash=?,result_code=? WHERE request_no=? AND status<>'DONE'",
                    state,received.size(),Json.hash(Json.write(response)),code,requestNo);
            audit(0,"METER_REPLAY_RESULT",orderNo,"设备原始计量补传结果",null,Map.of("requestNo",requestNo,"status",state,"receivedCount",received.size(),"responseHash",Json.hash(Json.write(response))));
        });
        sessions.tryFinalize(orderNo);
        if(!state.equals("DONE"))throw new BusinessException(503,code,"设备未完整返回已申请范围，保留补传请求等待重试");
    }

    @Transactional
    public Map<String,Object> propose(long actor,String orderNo,String key,Map<String,Object> body) {
        var order=lockOrder(orderNo); var session=requiredSession(orderNo,true);
        return once(actor,"meter.propose/"+orderNo,key,body,()->{
            mutable(orderNo); String reason=reason(body);
            if(!(body.get("selectedDeviceEventIds") instanceof List<?> ids)||ids.isEmpty()||ids.size()>100)throw error("INVALID_EVIDENCE","选择1至100条已有原始设备事件");
            var chosen=new ArrayList<Map<String,Object>>(); Set<Long> sequences=new HashSet<>();
            for(Object id:ids){
                var source=db.one("SELECT * FROM device_event WHERE id=?",integer(id));
                if(source==null)throw error("INVALID_EVIDENCE","选中的原始设备事件不存在");
                var sample=SessionService.evidenceSample(Json.read(Db.str(source,"payload")),session);
                long seq=Db.num(sample,"seq_no");
                if(!sequences.add(seq))throw error("INVALID_EVIDENCE","同一序号只能选择一条原始事实");
                if(session.get("final_seq")==null||seq>Db.num(session,"final_seq"))throw error("INVALID_EVIDENCE","人工选样不能改变设备已声明的会话最终序号");
                long maxEvent=Db.num(db.one("SELECT COALESCE(MAX(id),0) n FROM device_event WHERE session_no=? AND seq_no=?",Db.str(session,"session_no"),seq),"n");
                long maxConflict=Db.num(db.one("SELECT COALESCE(MAX(c.id),0) n FROM device_event_conflict c JOIN device_event e ON e.id=c.original_event_id WHERE e.session_no=? AND e.seq_no=?",Db.str(session,"session_no"),seq),"n");
                chosen.add(Map.of("seqNo",seq,"deviceEventId",Db.num(source,"id"),"payloadHash",Db.str(source,"payload_hash"),"coveredThroughEventId",maxEvent,"coveredThroughConflictId",maxConflict));
            }
            chosen.sort(Comparator.comparingLong(row->Db.num(row,"seqNo")));
            String proposal=UUID.randomUUID().toString(),hash=evidenceHash(session);
            db.insert("INSERT INTO meter_review_proposal(proposal_no,order_no,session_no,selected_evidence,evidence_hash,proposed_by,reason) VALUES(?,?,?,?,?,?,?)",proposal,orderNo,Db.str(session,"session_no"),Json.write(chosen),hash,actor,reason);
            audit(actor,"METER_REVIEW_PROPOSE",orderNo,reason,null,Map.of("proposalNo",proposal,"selectedEvidence",chosen,"evidenceHash",hash));
            return Map.of("proposalNo",proposal,"orderNo",orderNo,"status","PROPOSED","evidenceHash",hash,"selectedEvidence",chosen);
        });
    }

    @Transactional
    public Map<String,Object> decide(long actor,String orderNo,String proposalNo,String key,Map<String,Object> body) {
        var order=lockOrder(orderNo);var session=requiredSession(orderNo,true);
        return once(actor,"meter.decide/"+proposalNo,key,body,()->{
            mutable(orderNo);String reason=reason(body),decision=Objects.toString(body.get("decision"),"");
            if(!Set.of("APPROVE","REJECT").contains(decision))throw error("INVALID_DECISION","决定须为APPROVE或REJECT");
            var proposal=db.one("SELECT * FROM meter_review_proposal WHERE proposal_no=? AND order_no=? FOR UPDATE",proposalNo,orderNo);
            if(proposal==null)throw new BusinessException(404,"PROPOSAL_NOT_FOUND","复核提案不存在");
            if(Db.num(proposal,"proposed_by")==actor)throw new BusinessException(403,"SECOND_REVIEWER_REQUIRED","复核决定必须由另一位有权人员作出");
            if(db.one("SELECT id FROM meter_review_decision WHERE proposal_no=?",proposalNo)!=null)throw error("DECISION_EXISTS","该提案已有不可变决定");
            String hash=evidenceHash(session);
            if(!hash.equals(Db.str(proposal,"evidence_hash")))throw error("EVIDENCE_CHANGED","提案后出现新事实，请基于完整证据重新提案");
            var chosen=Json.list(Db.str(proposal,"selected_evidence"));
            if(decision.equals("APPROVE")){
                var projected=new TreeMap<Long,Map<String,Object>>();
                for(var sample:sessions.effectiveSamples(session))projected.put(Db.num(sample,"seq_no"),sample);
                Set<Long> covered=new HashSet<>();
                for(var choice:chosen){
                    var source=db.one("SELECT * FROM device_event WHERE id=?",Db.num(choice,"deviceEventId"));
                    if(source==null||!Objects.equals(Db.str(source,"payload_hash"),Db.str(choice,"payloadHash")))throw error("EVIDENCE_CHANGED","选中证据摘要已改变");
                    var sample=SessionService.evidenceSample(Json.read(Db.str(source,"payload")),session);
                    projected.put(Db.num(sample,"seq_no"),sample);covered.add(Db.num(sample,"seq_no"));
                }
                for(var unresolved:sessions.unresolvedReviews(session))if(!covered.contains(Db.num(unresolved,"seq_no")))throw error("INCOMPLETE_REVIEW","仍有未纳入本次决定的异常序号");
                try{validateSamples(order,session,new ArrayList<>(projected.values()));}
                catch(SessionService.MeteringProblem issue){throw error("INVALID_SELECTION",issue.code);}
                for(var choice:chosen)db.insert("INSERT INTO meter_review_selection(session_no,seq_no,proposal_no,selected_event_id,selected_payload_hash,covered_through_event_id,covered_through_conflict_id) VALUES(?,?,?,?,?,?,?)",
                        Db.str(session,"session_no"),Db.num(choice,"seqNo"),proposalNo,Db.num(choice,"deviceEventId"),Db.str(choice,"payloadHash"),Db.num(choice,"coveredThroughEventId"),Db.num(choice,"coveredThroughConflictId"));
            }
            db.insert("INSERT INTO meter_review_decision(proposal_no,decision,decided_by,reason,evidence_hash) VALUES(?,?,?,?,?)",proposalNo,decision,actor,reason,hash);
            audit(actor,"METER_REVIEW_"+decision,orderNo,reason,Map.of("proposalNo",proposalNo,"evidenceHash",hash),Map.of("decision",decision,"selectedEvidence",chosen));
            if(decision.equals("APPROVE"))sessions.tryFinalize(orderNo);
            return Map.of("proposalNo",proposalNo,"orderNo",orderNo,"decision",decision,"evidenceHash",hash,"billCreated",db.one("SELECT id FROM charge_bill WHERE order_no=?",orderNo)!=null);
        });
    }

    private void validateSamples(Map<String,Object> order,Map<String,Object> session,List<Map<String,Object>> samples){
        if(!"STOPPED".equals(Db.str(session,"status")))throw new SessionService.MeteringProblem("SESSION_NOT_STOPPED");
        if(session.get("final_seq")==null||Db.num(session,"final_seq")!=(long)samples.size()-1)throw new SessionService.MeteringProblem("MISSING_SEQUENCE");
        SessionService.segments(Json.read(Db.str(order,"tariff_snapshot")),samples);
    }
    private String evidenceHash(Map<String,Object> session){
        var payload=new LinkedHashMap<String,Object>();
        payload.put("events",db.rows("SELECT id,event_id,seq_no,payload_hash,error_code,process_status FROM device_event WHERE session_no=? ORDER BY id",Db.str(session,"session_no")));
        payload.put("conflicts",db.rows("SELECT c.id,c.original_event_id,c.payload_hash FROM device_event_conflict c JOIN device_event e ON e.id=c.original_event_id WHERE e.session_no=? ORDER BY c.id",Db.str(session,"session_no")));
        payload.put("selections",db.rows("SELECT id,selected_event_id,selected_payload_hash FROM meter_review_selection WHERE session_no=? ORDER BY id",Db.str(session,"session_no")));
        return Json.hash(Json.write(payload));
    }
    private List<Map<String,Object>> missingRanges(List<Map<String,Object>> samples,Map<String,Object> session){
        var result=new ArrayList<Map<String,Object>>();long expected=0;
        for(var sample:samples){long seq=Db.num(sample,"seq_no");if(seq>expected)result.add(Map.of("fromSeq",expected,"toSeq",seq-1));expected=seq+1;}
        if(session.get("final_seq")!=null&&expected<=Db.num(session,"final_seq"))result.add(Map.of("fromSeq",expected,"toSeq",Db.num(session,"final_seq")));
        return result;
    }
    private Map<String,Object> once(long actor,String scope,String key,Map<String,Object> body,Supplier<Map<String,Object>> work){
        if(key==null||!key.matches("[A-Za-z0-9._:-]{8,64}"))throw error("INVALID_IDEMPOTENCY_KEY","幂等编号须为8至64位");
        String hash=Json.hash(Json.write(new TreeMap<>(body)));
        var saved=db.one("SELECT * FROM idempotency_record WHERE principal_id=? AND scope=? AND idem_key=?",actor,scope,key);
        if(saved!=null){if(!hash.equals(Db.str(saved,"request_hash")))throw error("IDEMPOTENCY_CONFLICT","同一幂等编号绑定了不同请求");return Json.read(Db.str(saved,"response_json"));}
        var result=work.get();
        db.insert("INSERT INTO idempotency_record(principal_id,scope,idem_key,request_hash,status,http_status,response_json,expires_at) VALUES(?,?,?,?,'DONE',200,?,TIMESTAMPADD(HOUR,24,UTC_TIMESTAMP(3)))",actor,scope,key,hash,Json.write(result));
        return result;
    }
    private Map<String,Object> lockOrder(String no){var hint=requiredOrder(no);db.one("SELECT id FROM iam_user WHERE id=? FOR UPDATE",Db.num(hint,"user_id"));return db.one("SELECT * FROM charge_order WHERE order_no=? FOR UPDATE",no);}
    private Map<String,Object> requiredOrder(String no){var row=db.one("SELECT * FROM charge_order WHERE order_no=?",no);if(row==null)throw new BusinessException(404,"ORDER_NOT_FOUND","订单不存在");return row;}
    private Map<String,Object> requiredSession(String no,boolean lock){var row=db.one("SELECT * FROM charge_session WHERE order_no=?"+(lock?" FOR UPDATE":""),no);if(row==null)throw new BusinessException(404,"SESSION_NOT_FOUND","订单尚无设备会话");return row;}
    private void mutable(String no){if(db.one("SELECT id FROM charge_bill WHERE order_no=?",no)!=null)throw error("BILL_IMMUTABLE","正式账单已生成，禁止改变计量决定；请走独立财务差异处理");}
    private void audit(long actor,String action,String no,String reason,Object before,Object after){db.insert("INSERT INTO ops_audit(operator_id,action,target_type,target_no,reason,before_json,after_json,request_id) VALUES(?,?,'CHARGE_ORDER',?,?,?,?,?)",actor,action,no,reason,before==null?null:Json.write(before),Json.write(after),ApiResponse.requestIdValue());}
    private String reason(Map<String,Object> body){Object raw=body.get("reason");if(!(raw instanceof String text)||text.strip().length()<5||text.length()>255)throw error("REASON_REQUIRED","须填写5至255字可审计原因");return text.strip();}
    private static long integer(Object value){try{return new java.math.BigDecimal(Objects.toString(value,"")).longValueExact();}catch(RuntimeException issue){throw error("INVALID_INTEGER","序号及证据编号必须是整数");}}
    private static BusinessException error(String code,String message){return new BusinessException(409,code,message);}
}
