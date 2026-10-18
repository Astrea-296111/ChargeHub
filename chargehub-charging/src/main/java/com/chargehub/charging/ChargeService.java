package com.chargehub.charging;

import com.chargehub.infrastructure.*;
import com.chargehub.station.StationService;
import com.chargehub.benefit.BenefitService;
import com.chargehub.payment.PaymentService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.sql.Timestamp;
import java.util.*;

/** Coordinates local application services in one MySQL transaction. No network I/O here. */
@Service
public class ChargeService {
    private final Db db; private final StationService station; private final BenefitService benefits;
    private final PaymentService payments; private final Outbox outbox; private final Clock clock;
    public ChargeService(Db db,StationService station,BenefitService benefits,PaymentService payments,Outbox outbox,Clock clock){this.db=db;this.station=station;this.benefits=benefits;this.payments=payments;this.outbox=outbox;this.clock=clock;}
    public static long number(Map<String,?> m,String key){try{Object v=m.get(key);if(v==null)throw new NumberFormatException();return Long.parseLong(v.toString());}catch(Exception e){throw new BusinessException(400,"VALIDATION_ERROR","字段必须为整数: "+key);}}
    private Map<String,Object> locked(String no){
        Map<String,Object> o=db.one("SELECT * FROM charge_order WHERE order_no=?",no);
        if(o==null)throw new BusinessException(404,"NOT_FOUND","订单不存在");
        db.one("SELECT id FROM iam_user WHERE id=? FOR UPDATE",o.get("user_id"));
        return db.one("SELECT * FROM charge_order WHERE order_no=? FOR UPDATE",no);
    }
    public Map<String,Object> owned(long user,String no){
        Map<String,Object> o=db.one("SELECT * FROM charge_order WHERE order_no=? AND user_id=?",no,user);
        if(o==null)throw new BusinessException(404,"NOT_FOUND","订单不存在");
        return view(o);
    }
    public Map<String,Object> view(Map<String,Object> o){
        Map<String,Object> m=new LinkedHashMap<>();
        for(String k:List.of("order_no","connector_id","station_id","status","financial_status","prepaid_fen","currency","payment_deadline","start_deadline","started_at","ended_at","exception_code","created_at"))m.put(k,o.get(k));
        Map<String,Object> s=db.one("SELECT session_no FROM charge_session WHERE order_no=?",o.get("order_no"));
        m.put("sessionNo",s==null?null:s.get("session_no"));
        Map<String,Object> t=Json.read(Db.str(o,"tariff_snapshot"));m.put("tariffVersion",t.get("tariffVersion"));return m;
    }
    public Map<String,Object> list(long user,Long cursor,int limit){
        List<Map<String,Object>> rows=db.rows("SELECT * FROM charge_order WHERE user_id=? AND id<? ORDER BY id DESC LIMIT ?",user,cursor==null?Long.MAX_VALUE:cursor,limit);
        Map<String,Object> p=new LinkedHashMap<>();p.put("items",rows.stream().map(this::view).toList());p.put("nextCursor",rows.size()==limit?rows.getLast().get("id").toString():null);return p;
    }
    @Transactional
    public Map<String,Object> create(long user,Map<String,Object> request){
        Map<String,Object> u=db.one("SELECT * FROM iam_user WHERE id=? FOR UPDATE",user);
        if(u.get("active_order_no")!=null)throw new BusinessException(409,"ACTIVE_ORDER_EXISTS","已有占用资源的订单");
        if(Db.num(db.one("SELECT COUNT(*) n FROM charge_order WHERE user_id=? AND financial_status IN ('SHORTFALL','DIFF')",user),"n")>0)throw new BusinessException(409,"UNRESOLVED_FUNDS","需先处理资金差异");
        long connector=number(request,"connectorId");long expected=number(request,"expectedTariffVersion");
        Map<String,Object> tariff=station.tariff(connector,expected);
        Map<String,Object> c=db.one("SELECT * FROM connector WHERE id=? FOR UPDATE",connector);
        if(c==null)throw new BusinessException(404,"NOT_FOUND","充电枪不存在");
        if(c.get("active_order_no")!=null||!"IDLE".equals(c.get("status")))throw new BusinessException(409,"CONNECTOR_BUSY","充电枪已占用");
        String no=UUID.randomUUID().toString();Instant now=clock.instant();
        db.update("INSERT INTO charge_order(order_no,user_id,station_id,connector_id,tariff_id,tariff_snapshot,payment_deadline,created_at) VALUES(?,?,?,?,?,?,?,?)",no,user,tariff.get("stationId"),connector,tariff.get("tariffId"),Json.write(tariff),Timestamp.from(now.plusSeconds(300)),Timestamp.from(now));
        if(db.update("UPDATE iam_user SET active_order_no=? WHERE id=? AND active_order_no IS NULL",no,user)!=1||db.update("UPDATE connector SET active_order_no=?,status='RESERVED',version=version+1 WHERE id=? AND active_order_no IS NULL AND status='IDLE'",no,connector)!=1)throw new BusinessException(409,"CONNECTOR_BUSY","资源竞争，请重试");
        Long coupon=request.get("couponId")==null?null:number(request,"couponId");
        Map<String,Object> reservation=benefits.reserve(user,no,Boolean.TRUE.equals(request.get("useMembership")),coupon);
        if(reservation.get("memberRuleSnapshot")!=null)db.update("UPDATE charge_order SET member_rule_snapshot=? WHERE order_no=?",Json.write(reservation.get("memberRuleSnapshot")),no);
        return owned(user,no);
    }
    @Transactional
    public Map<String,Object> payment(long user,String no){
        Map<String,Object> o=locked(no);checkOwner(o,user);
        Map<String,Object> p=payments.getByOrder(no);
        if(p==null){
            if(!"WAIT_PAY".equals(o.get("status"))||!clock.instant().isBefore(Db.instant(o,"payment_deadline")))throw new BusinessException(409,"INVALID_STATE","订单已不能付款");
            p=payments.create(no);outbox.enqueue("PaymentCreateRequested",no,Map.of("payNo",p.get("pay_no"),"orderNo",no));
        }
        return p;
    }
    private void checkOwner(Map<String,Object> o,long user){if(Db.num(o,"user_id")!=user)throw new BusinessException(404,"NOT_FOUND","订单不存在");}
    @Transactional
    public Map<String,Object> cancel(long user,String no){
        Map<String,Object> o=locked(no);checkOwner(o,user);
        if(Set.of("CANCELED","CANCELING").contains(o.get("status")))return view(o);
        if(!Set.of("WAIT_PAY","START_PENDING","EXCEPTION").contains(o.get("status")))throw new BusinessException(409,"INVALID_STATE","已开始服务，请请求停止");
        db.update("UPDATE charge_order SET status='CANCELING',version=version+1 WHERE order_no=?",no);
        Map<String,Object> command=db.one("SELECT * FROM device_command WHERE order_no=? AND command_type='START'",no);
        if(command!=null){outbox.enqueue("DeviceQueryRequested",no,Map.of("orderNo",no));}
        else if(payments.getByOrder(no)==null){finishCancel(no);}
        else{outbox.enqueue("PaymentCloseRequested",no,Map.of("orderNo",no,"payNo",payments.getByOrder(no).get("pay_no")));}
        return owned(user,no);
    }
    @Transactional
    public void expire(String no){Map<String,Object> o=locked(no);if("WAIT_PAY".equals(o.get("status"))&&!clock.instant().isBefore(Db.instant(o,"payment_deadline")))cancel(Db.num(o,"user_id"),no);}
    @Transactional
    public Map<String,Object> notification(Map<String,Object> event){
        String payNo=String.valueOf(event.get("payNo"));Map<String,Object> p=db.one("SELECT * FROM payment_order WHERE pay_no=?",payNo);
        if(p==null)throw new BusinessException(404,"PAYMENT_NOT_FOUND","支付单不存在");
        Map<String,Object> o=locked(Db.str(p,"order_no"));
        String type=String.valueOf(event.get("type"));Map<String,Object> result;
        if("PAYMENT_SUCCESS".equals(type)){
            result=payments.capture(event);
            if(Boolean.FALSE.equals(result.get("valid"))){db.update("UPDATE charge_order SET financial_status='DIFF',exception_code='PAYMENT_MISMATCH' WHERE order_no=?",o.get("order_no"));return result;}
            afterCapture(Db.str(o,"order_no"));
        }else if("REFUND_SUCCESS".equals(type)){
            result=payments.refundSucceeded(event);if(Boolean.FALSE.equals(result.get("valid")))db.update("UPDATE charge_order SET financial_status='DIFF' WHERE order_no=?",o.get("order_no"));else closeFinancial(Db.str(o,"order_no"));
        }else if("PAYMENT_CLOSED".equals(type)){
            Map<String,Object> fact=new LinkedHashMap<>(event);fact.put("status","CLOSED");Map<String,Object> verified=payments.applyChannelPayment(fact);if(Boolean.FALSE.equals(verified.get("valid"))){channelMismatch(payNo);return verified;}
            if("CANCELING".equals(o.get("status")))finishCancel(Db.str(o,"order_no"));result=Map.of("accepted",true);
        }else if("REFUND_FAILED".equals(type)){
            // A transport or unknown channel failure cannot release reserved refund funds.
            result=payments.refundSucceeded(event);if(Boolean.FALSE.equals(result.get("valid")))db.update("UPDATE charge_order SET financial_status='DIFF' WHERE order_no=?",o.get("order_no"));
        }else throw new BusinessException(400,"VALIDATION_ERROR","未知通知类型");
        return result;
    }
    private void afterCapture(String no){
        Map<String,Object> o=db.one("SELECT * FROM charge_order WHERE order_no=? FOR UPDATE",no);Map<String,Object> p=payments.getByOrder(no);
        Instant paid=Db.instant(p,"captured_at");Instant deadline=paid.plusSeconds(120);
        if("WAIT_PAY".equals(o.get("status"))&&paid.isBefore(Db.instant(o,"payment_deadline"))&&clock.instant().isBefore(deadline)&&!"DIFF".equals(o.get("financial_status"))){
            db.update("UPDATE charge_order SET status='START_PENDING',start_deadline=?,version=version+1 WHERE order_no=?",Timestamp.from(deadline),no);
            Map<String,Object> c=db.one("SELECT * FROM connector WHERE id=? FOR UPDATE",o.get("connector_id"));
            String session=UUID.randomUUID().toString(),command=UUID.randomUUID().toString();
            db.update("INSERT INTO charge_session(session_no,order_no,device_id,connector_id,status) VALUES(?,?,?,?,'START_PENDING')",session,no,c.get("device_id"),c.get("id"));
            Map<String,Object> req=new LinkedHashMap<>();req.put("commandId",command);req.put("orderNo",no);req.put("sessionNo",session);req.put("deviceId",c.get("device_id"));req.put("connectorId",String.valueOf(c.get("id")));req.put("prepaidFen",10000);req.put("tariffSnapshot",Json.read(Db.str(o,"tariff_snapshot")));req.put("expiresAt",deadline.toString());req.put("startedAt",clock.instant().toString());
            db.update("INSERT INTO device_command(command_id,order_no,device_id,command_type,request_payload,expires_at) VALUES(?,?,?,'START',?,?)",command,no,c.get("device_id"),Json.write(req),Timestamp.from(deadline));
            outbox.enqueue("ChargeStartRequested",no,Map.of("orderNo",no,"commandId",command));
        }else if(Set.of("WAIT_PAY","CANCELING","CANCELED").contains(o.get("status"))){
            db.update("UPDATE charge_order SET status='CANCELING' WHERE order_no=?",no);finishCancel(no);
        }
    }
    @Transactional
    public void finishCancel(String no){
        Map<String,Object> o=locked(no);
        Map<String,Object> s=db.one("SELECT * FROM charge_session WHERE order_no=?",no);
        if(s!=null&&!Set.of("NOT_STARTED","REJECTED").contains(s.get("status")))return;
        Map<String,Object> p=payments.getByOrder(no);
        if(p!=null&&Db.num(p,"captured_fen")==0&&!Set.of("CLOSED","NOT_FOUND").contains(p.get("status")))return;
        benefits.release(no);
        if(p!=null&&Db.num(p,"captured_fen")>0){Map<String,Object> r=payments.ensureRefund(no,Db.num(p,"captured_fen"),"FULL_CANCEL");outbox.enqueue("RefundRequested",Db.str(r,"refund_no"),Map.of("refundNo",r.get("refund_no"),"orderNo",no));}
        db.update("UPDATE charge_order SET status='CANCELED',financial_status=? WHERE order_no=?","DIFF".equals(o.get("financial_status"))?"DIFF":p!=null&&Db.num(p,"captured_fen")>Db.num(p,"refund_success_fen")?"REFUNDING":"CLOSED",no);
        release(o);
    }
    private void release(Map<String,Object> o){db.update("UPDATE connector SET active_order_no=NULL,status='IDLE',version=version+1 WHERE id=? AND active_order_no=?",o.get("connector_id"),o.get("order_no"));db.update("UPDATE iam_user SET active_order_no=NULL WHERE id=? AND active_order_no=?",o.get("user_id"),o.get("order_no"));}
    @Transactional
    public Map<String,Object> stop(long user,String no){
        Map<String,Object> o=locked(no);checkOwner(o,user);
        if(Set.of("BILLING","REVIEW","COMPLETED","CANCELED").contains(o.get("status")))return view(o);
        if(!Set.of("CHARGING","STOP_PENDING").contains(o.get("status")))throw new BusinessException(409,"INVALID_STATE","会话尚未开充");
        Map<String,Object> s=db.one("SELECT * FROM charge_session WHERE order_no=?",no);
        createStop(o,s);return owned(user,no);
    }
    public void createStop(Map<String,Object> o,Map<String,Object> s){
        String no=Db.str(o,"order_no"),id=UUID.randomUUID().toString();
        Map<String,Object> req=Map.of("commandId",id,"orderNo",no,"sessionNo",s.get("session_no"),"deviceId",s.get("device_id"),"connectorId",String.valueOf(s.get("connector_id")));
        db.update("INSERT INTO device_command(command_id,order_no,device_id,command_type,request_payload,expires_at) VALUES(?,?,?,'STOP',?,?) ON DUPLICATE KEY UPDATE id=id",id,no,s.get("device_id"),Json.write(req),Timestamp.from(clock.instant().plusSeconds(300)));
        db.update("UPDATE charge_order SET status='STOP_PENDING' WHERE order_no=?",no);outbox.enqueue("ChargeStopRequested",no,Map.of("orderNo",no));
    }
    @Transactional
    public Map<String,Object> prepareStart(String no){
        Map<String,Object> o=locked(no);Map<String,Object> c=db.one("SELECT * FROM device_command WHERE order_no=? AND command_type='START' FOR UPDATE",no);
        if(c==null)return null;
        if(!"START_PENDING".equals(o.get("status"))||!clock.instant().isBefore(Db.instant(o,"start_deadline")))return null;
        db.update("UPDATE device_command SET status='SENT',attempt_count=attempt_count+1 WHERE id=?",c.get("id"));return Json.read(Db.str(c,"request_payload"));
    }
    @Transactional
    public void deviceUnknown(String no,String phase){Map<String,Object> o=locked(no);if(!Set.of("COMPLETED","CANCELED","REVIEW","BILLING").contains(o.get("status"))&&!("START".equals(phase)&&"CHARGING".equals(o.get("status"))))db.update("UPDATE charge_order SET status='EXCEPTION',exception_code=? WHERE order_no=?",phase+"_RESULT_UNKNOWN",no);}
    @Transactional
    public void deviceCharging(String no){
        Map<String,Object> o=locked(no);Map<String,Object> s=db.one("SELECT * FROM charge_session WHERE order_no=?",no);
        if(s==null)return;
        if("CANCELING".equals(o.get("status"))){createStop(o,s);return;}
        if("EXCEPTION".equals(o.get("status"))){
            Map<String,Object> stop=db.one("SELECT id FROM device_command WHERE order_no=? AND command_type='STOP'",no);
            db.update("UPDATE charge_order SET status=?,exception_code=NULL WHERE order_no=?",stop==null?"CHARGING":"STOP_PENDING",no);
        }
    }
    @Transactional
    public void deviceNotStarted(String no){
        Map<String,Object> o=locked(no);
        if(!Set.of("START_PENDING","EXCEPTION","CANCELING").contains(o.get("status")))return;
        db.update("UPDATE charge_session SET status='NOT_STARTED' WHERE order_no=? AND started_at IS NULL",no);
        db.update("UPDATE charge_order SET status='CANCELING' WHERE order_no=?",no);finishCancel(no);
    }
    @Transactional
    public void channelMismatch(String payNo){Map<String,Object> p=db.one("SELECT order_no FROM payment_order WHERE pay_no=?",payNo);if(p==null)return;Map<String,Object> o=locked(Db.str(p,"order_no"));db.update("UPDATE charge_order SET financial_status='DIFF',exception_code='CHANNEL_FACT_MISMATCH' WHERE order_no=?",o.get("order_no"));}
    @Transactional
    public void closedPayment(String no,Map<String,Object> response){
        Map<String,Object> o=locked(no);Map<String,Object> p=payments.getByOrder(no);
        if("SUCCESS".equals(response.get("status")))return;
        if("CLOSED".equals(response.get("status"))&&Boolean.FALSE.equals(payments.applyChannelPayment(response).get("valid"))){channelMismatch(Db.str(p,"pay_no"));return;}
        if(Set.of("CLOSED","NOT_FOUND").contains(response.get("status"))&&p!=null){db.update("UPDATE payment_order SET status='CLOSED' WHERE pay_no=? AND captured_fen=0",p.get("pay_no"));if("CANCELING".equals(o.get("status")))finishCancel(no);}
    }
    @Transactional
    public void closeFinancial(String no){
        Map<String,Object> o=locked(no);
        if(!Set.of("COMPLETED","CANCELED").contains(o.get("status"))||Set.of("DIFF","SHORTFALL").contains(o.get("financial_status")))return;
        Map<String,Object> p=payments.getByOrder(no),b=db.one("SELECT payable_fen FROM charge_bill WHERE order_no=?",no);
        long expected=b==null?0:Db.num(b,"payable_fen");
        long net=Db.num(db.one("SELECT COALESCE(SUM(CASE WHEN biz_type='CAPTURE' THEN amount_fen ELSE -amount_fen END),0) n FROM cash_entry WHERE order_no=?",no),"n");
        if(net==expected&&(p==null||Db.num(p,"refund_reserved_fen")==0)&&Db.num(db.one("SELECT COUNT(*) n FROM reconcile_item WHERE order_no=? AND status<>'RESOLVED'",no),"n")==0)db.update("UPDATE charge_order SET financial_status='CLOSED' WHERE order_no=?",no);
    }
}
