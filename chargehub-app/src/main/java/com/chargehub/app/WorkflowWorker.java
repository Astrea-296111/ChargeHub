package com.chargehub.app;
import com.chargehub.infrastructure.*;
import com.chargehub.charging.*;
import com.chargehub.payment.PaymentService;
import com.chargehub.reconcile.ReconciliationService;
import org.springframework.stereotype.Component;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import java.util.*;import java.time.*;import java.sql.Timestamp;
import org.slf4j.Logger;import org.slf4j.LoggerFactory;

@Component @Profile("worker")
public class WorkflowWorker {
 private static final Logger log=LoggerFactory.getLogger(WorkflowWorker.class);
 private final Db db;private final Outbox outbox;private final ChargeService orders;private final PaymentService payments;private final SessionService sessions;private final DeviceClient device;private final Clock clock;private final TransactionTemplate tx;private final ReconciliationService reconcile;
 public WorkflowWorker(Db db,Outbox outbox,ChargeService orders,PaymentService payments,SessionService sessions,DeviceClient device,Clock clock,PlatformTransactionManager tm,ReconciliationService reconcile){this.db=db;this.outbox=outbox;this.orders=orders;this.payments=payments;this.sessions=sessions;this.device=device;this.clock=clock;tx=new TransactionTemplate(tm);this.reconcile=reconcile;}
 @Scheduled(fixedDelayString="${chargehub.worker-delay-ms:300}")
 public void work(){for(int i=0;i<10;i++){Map<String,Object> e=outbox.claim();if(e==null)return;try{dispatch(Db.str(e,"event_name"),Json.read(Db.str(e,"payload")));outbox.ack(e);}catch(Exception error){log.warn("outbox failure eventId={} type={}",e.get("event_id"),error.getClass().getSimpleName());outbox.fail(e,error);}}}
 private void dispatch(String name,Map<String,Object> p){
  String no=(String)p.get("orderNo");
  switch(name){
   case "PaymentCreateRequested" -> {String pay=String.valueOf(p.get("payNo"));Map<String,Object> o=db.one("SELECT status FROM charge_order WHERE order_no=?",no);if("CANCELED".equals(o.get("status")))break; try{Map<String,Object> r=payments.sendCreate(pay);paymentResult(pay,r);}catch(Exception e){payments.markPaymentUnknown(pay);throw e;}}
   case "PaymentCloseRequested" -> {String pay=String.valueOf(p.get("payNo"));Map<String,Object> r=payments.closePayment(pay);paymentResult(pay,r);orders.closedPayment(no,r);}
   case "ChargeStartRequested" -> {Map<String,Object> req=orders.prepareStart(no);if(req==null){queryDevice(no);break;}try{Map<String,Object> r=device.post("/mock-devices/v1/commands/start",req);replayEvents(r);if(Set.of("REJECTED","NOT_STARTED").contains(String.valueOf(r.get("status"))))orders.deviceNotStarted(no);else queryDevice(no);}catch(Exception ex){orders.deviceUnknown(no,"START");throw ex;}}
   case "ChargeStopRequested" -> {Map<String,Object> c=db.one("SELECT * FROM device_command WHERE order_no=? AND command_type='STOP'",no);if(c==null)throw new IllegalStateException("missing stop intent");try{Map<String,Object> r=device.post("/mock-devices/v1/commands/stop",Json.read(Db.str(c,"request_payload")));replayEvents(r);queryDevice(no);}catch(Exception ex){orders.deviceUnknown(no,"STOP");throw ex;}}
   case "DeviceQueryRequested" -> queryDevice(no);
   case "RefundRequested" -> {String refund=String.valueOf(p.get("refundNo"));try{Map<String,Object> r=payments.sendRefund(refund);refundResult(refund,r);}catch(Exception e){db.update("UPDATE refund_order SET status='UNKNOWN',last_error='RESULT_UNKNOWN' WHERE refund_no=? AND status NOT IN ('SUCCESS','FAILED_FINAL')",refund);throw e;}}
   case "SessionStopped" -> sessions.tryFinalize(no);
   case "PaymentCaptured","BillFinalized","RefundSucceeded" -> {} // local transaction already persisted required effects
   default -> throw new IllegalArgumentException("unknown event type");
  }
 }
 @SuppressWarnings("unchecked") private void replayEvents(Map<String,Object> r){Object ev=r.get("events");if(ev instanceof List<?> list)for(Object event:list)sessions.ingest((Map<String,Object>)event);}
 private void paymentResult(String pay,Map<String,Object> r){
  if(!"SUCCESS".equals(r.get("status"))){ if(!"NOT_FOUND".equals(r.get("status"))&&Boolean.FALSE.equals(payments.applyChannelPayment(r).get("valid")))orders.channelMismatch(pay); return;}
  Map<String,Object> event=new LinkedHashMap<>();event.put("channelEventId","query-pay-"+pay);event.put("type","PAYMENT_SUCCESS");event.put("payNo",pay);event.put("providerBizNo",r.get("providerTradeNo"));event.put("amountFen",r.get("amountFen"));event.put("currency",r.get("currency"));event.put("occurredAt",r.getOrDefault("capturedAt",r.get("occurredAt")));orders.notification(event);
 }
 private void refundResult(String refund,Map<String,Object> r){
  if(!Set.of("SUCCESS","FAILED").contains(String.valueOf(r.get("status")))){ payments.applyRefund(r); return;}
  Map<String,Object> local=db.one("SELECT * FROM refund_order WHERE refund_no=?",refund);
  Map<String,Object> event=new LinkedHashMap<>();event.put("channelEventId","query-refund-"+refund);event.put("type","FAILED".equals(r.get("status"))?"REFUND_FAILED":"REFUND_SUCCESS");event.put("payNo",local.get("pay_no"));event.put("refundNo",refund);event.put("providerBizNo",r.get("providerRefundNo"));event.put("amountFen",r.get("amountFen"));event.put("currency",r.getOrDefault("currency","CNY"));event.put("occurredAt",r.getOrDefault("succeededAt",r.get("occurredAt")));orders.notification(event);
 }
 public void queryDevice(String no){
  Map<String,Object> s=db.one("SELECT * FROM charge_session WHERE order_no=?",no);if(s==null){orders.finishCancel(no);return;}
  Map<String,Object> r=device.post("/mock-devices/v1/sessions/query",Map.of("sessionNo",s.get("session_no"),"deviceId",s.get("device_id"),"connectorId",String.valueOf(s.get("connector_id"))));
  replayEvents(r);String state=String.valueOf(r.get("status"));
  if("NOT_STARTED".equals(state)){ Map<String,Object> o=db.one("SELECT * FROM charge_order WHERE order_no=?",no); if(o.get("start_deadline")!=null&&!clock.instant().isBefore(Db.instant(o,"start_deadline")))orders.deviceNotStarted(no); }
  if("CHARGING".equals(state))orders.deviceCharging(no);
  if("STOPPED".equals(state))sessions.tryFinalize(no);
 }
 @Scheduled(fixedDelayString="${chargehub.recovery-delay-ms:3000}")
 public void recover(){
  for(Map<String,Object> o:db.rows("SELECT order_no FROM charge_order WHERE status='WAIT_PAY' AND payment_deadline<=? ORDER BY id LIMIT 50",Timestamp.from(clock.instant())))safe(()->orders.expire(Db.str(o,"order_no")));
  for(Map<String,Object> p:db.rows("SELECT pay_no FROM payment_order WHERE status IN ('INIT','PENDING','UNKNOWN','CLOSE_PENDING') ORDER BY updated_at LIMIT 50"))safe(()->{String n=Db.str(p,"pay_no");paymentResult(n,payments.queryPayment(n));});
  for(Map<String,Object> r:db.rows("SELECT refund_no FROM refund_order WHERE status IN ('INIT','PROCESSING','UNKNOWN') ORDER BY updated_at LIMIT 50"))safe(()->{String n=Db.str(r,"refund_no");Map<String,Object> fact=payments.queryRefund(n);if("NOT_FOUND".equals(fact.get("status")))fact=payments.sendRefund(n);refundResult(n,fact);});
  for(Map<String,Object> o:db.rows("SELECT order_no FROM charge_order WHERE status IN ('EXCEPTION','STOP_PENDING','CANCELING','START_PENDING') ORDER BY updated_at LIMIT 50"))safe(()->queryDevice(Db.str(o,"order_no")));
  for(Map<String,Object> o:db.rows("SELECT o.order_no FROM charge_order o JOIN charge_session s ON s.order_no=o.order_no WHERE s.status='CHARGING' AND o.status IN ('CHARGING','REVIEW','EXCEPTION') ORDER BY o.updated_at LIMIT 50"))safe(()->{queryDevice(Db.str(o,"order_no"));sessions.enforcePrepaid(Db.str(o,"order_no"));});
  for(Map<String,Object> o:db.rows("SELECT order_no FROM charge_order WHERE status IN ('BILLING','REVIEW') AND (exception_code IS NULL OR exception_code NOT IN ('METER_CONFLICT','METER_ROLLBACK')) ORDER BY updated_at LIMIT 25"))safe(()->{queryDevice(Db.str(o,"order_no"));sessions.tryFinalize(Db.str(o,"order_no"));});
 }
 @Scheduled(fixedDelay=10000)
 public void reconcileRecovered(){
  Map<String,Object> system=db.one("SELECT id FROM iam_user WHERE login_name='__system__' AND enabled=0");if(system==null)return;
  for(Map<String,Object> b:db.rows("SELECT DISTINCT batch_id FROM reconcile_item WHERE diff_type='CHANNEL_ONLY' AND status='OPEN' ORDER BY batch_id LIMIT 20"))safe(()->reconcile.refreshRecovered(Db.num(system,"id"),Db.num(b,"batch_id"),"后台渠道查单后核对一致，恢复已补录资金事实"));
  for(Map<String,Object> o:db.rows("SELECT o.order_no FROM charge_order o WHERE financial_status='DIFF' AND exception_code='RECONCILIATION_DIFFERENCE' AND NOT EXISTS(SELECT 1 FROM reconcile_item r WHERE r.order_no=o.order_no AND r.status<>'RESOLVED') LIMIT 20"))safe(()->{String no=Db.str(o,"order_no");tx.executeWithoutResult(st->{Map<String,Object> row=db.one("SELECT user_id FROM charge_order WHERE order_no=?",no);db.one("SELECT id FROM iam_user WHERE id=? FOR UPDATE",row.get("user_id"));db.one("SELECT id FROM charge_order WHERE order_no=? FOR UPDATE",no);db.update("UPDATE charge_order SET financial_status=CASE WHEN EXISTS(SELECT 1 FROM payment_order p WHERE p.order_no=? AND p.refund_reserved_fen>0) THEN 'REFUNDING' ELSE 'OPEN' END,exception_code=NULL WHERE order_no=? AND exception_code='RECONCILIATION_DIFFERENCE'",no,no);});orders.closeFinancial(no);});
 }
 private void safe(Runnable r){try{r.run();}catch(Exception e){log.debug("recovery deferred type={}",e.getClass().getSimpleName());}}
}
