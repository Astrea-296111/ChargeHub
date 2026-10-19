package com.chargehub.app;

import com.chargehub.charging.ChargeService;
import com.chargehub.charging.SessionOperationsService;
import com.chargehub.charging.SessionService;
import com.chargehub.infrastructure.Db;
import com.chargehub.infrastructure.Json;
import com.chargehub.infrastructure.Outbox;
import com.chargehub.payment.ChannelFinancialFact;
import com.chargehub.payment.PaymentService;
import com.chargehub.reconcile.ReconciliationService;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.time.Clock;
import java.time.Instant;
import java.util.*;

/** Adapters run outside database transactions; every resulting fact re-enters a local application service. */
@Component
@Profile("worker")
public class WorkflowWorker {
    private static final Logger LOG=LoggerFactory.getLogger(WorkflowWorker.class);
    private final Db db;
    private final Outbox outbox;
    private final ChargeService orders;
    private final PaymentService payments;
    private final SessionService sessions;
    private final SessionOperationsService sessionOperations;
    private final DeviceClient device;
    private final Clock clock;
    private final ReconciliationService reconciliations;
    private final MeterRegistry metrics;
    private final String messagingMode;

    public WorkflowWorker(Db db,Outbox outbox,ChargeService orders,PaymentService payments,
                          SessionService sessions,SessionOperationsService sessionOperations,
                          DeviceClient device,Clock clock,ReconciliationService reconciliations,
                          MeterRegistry metrics,Environment environment){
        this.db=db;this.outbox=outbox;this.orders=orders;this.payments=payments;this.sessions=sessions;
        this.sessionOperations=sessionOperations;this.device=device;this.clock=clock;
        this.reconciliations=reconciliations;this.metrics=metrics;
        this.messagingMode=environment.getProperty("chargehub.messaging.mode","db");
        if(!Set.of("db","rocketmq").contains(messagingMode))throw new IllegalStateException("chargehub.messaging.mode must be db or rocketmq");
    }

    @Scheduled(fixedDelayString="${chargehub.worker-delay-ms:300}")
    public void work(){
        if(!messagingMode.equals("db"))return;
        for(int i=0;i<10;i++){
            Map<String,Object> event=outbox.claim();if(event==null)return;
            try{dispatch(Db.str(event,"event_name"),Json.read(Db.str(event,"payload")));outbox.ack(event);}
            catch(Exception failure){
                metrics.counter("chargehub.worker.failure","phase","outbox","type",failure.getClass().getSimpleName()).increment();
                LOG.warn("Outbox deferred eventId={} name={} errorType={}",event.get("event_id"),event.get("event_name"),failure.getClass().getSimpleName());
                outbox.fail(event,failure);
            }
        }
    }

    /** Shared dispatch boundary for direct database workers and the RocketMQ consumer. */
    public void dispatch(String eventName,Map<String,Object> payload){
        String orderNo=(String)payload.get("orderNo");
        switch(eventName){
            case "PaymentCreateRequested" -> createPayment(orderNo,String.valueOf(payload.get("payNo")));
            case "PaymentCloseRequested" -> {
                String payNo=String.valueOf(payload.get("payNo"));
                Map<String,Object> fact=payments.closePayment(payNo);
                paymentResult(payNo,fact);orders.closedPayment(orderNo,fact);
            }
            case "ChargeStartRequested" -> start(orderNo);
            case "ChargeStopRequested" -> stop(orderNo);
            case "DeviceQueryRequested" -> queryDevice(orderNo);
            case "DeviceMeterReplayRequested" -> {
                String requestNo=String.valueOf(payload.get("requestNo"));
                sessionOperations.fulfillReplay(requestNo,deviceSnapshot(orderNo));
            }
            case "RefundRequested" -> {
                String refundNo=String.valueOf(payload.get("refundNo"));
                try{refundResult(refundNo,payments.sendRefund(refundNo));}
                catch(Exception failure){payments.markRefundUnknown(refundNo,"RESULT_UNKNOWN");throw failure;}
            }
            case "SessionStopped" -> sessions.tryFinalize(orderNo);
            case "PaymentCaptured","BillFinalized","RefundSucceeded" -> {
                // The originating local transaction has already persisted the mandatory business effects.
            }
            default -> throw new IllegalArgumentException("Unsupported workflow event: "+eventName);
        }
    }

    private void createPayment(String orderNo,String payNo){
        Map<String,Object> order=db.one("SELECT status FROM charge_order WHERE order_no=?",orderNo);
        if(order==null)throw new IllegalArgumentException("Missing order for payment intent");
        if("CANCELED".equals(order.get("status")))return;
        try{paymentResult(payNo,payments.sendCreate(payNo));}
        catch(Exception failure){payments.markPaymentUnknown(payNo);throw failure;}
    }

    private void start(String orderNo){
        Map<String,Object> request=orders.prepareStart(orderNo);
        if(request==null){queryDevice(orderNo);return;}
        try{
            Map<String,Object> response=device.post("/mock-devices/v1/commands/start",request);
            replayEvents(response);
            if(Set.of("REJECTED","NOT_STARTED").contains(String.valueOf(response.get("status"))))orders.deviceNotStarted(orderNo);
            else queryDevice(orderNo);
        }catch(Exception failure){orders.deviceUnknown(orderNo,"START");throw failure;}
    }

    private void stop(String orderNo){
        Map<String,Object> command=db.one("SELECT request_payload FROM device_command WHERE order_no=? AND command_type='STOP'",orderNo);
        if(command==null)throw new IllegalStateException("Missing persisted stop intent");
        try{replayEvents(device.post("/mock-devices/v1/commands/stop",Json.read(Db.str(command,"request_payload"))));queryDevice(orderNo);}
        catch(Exception failure){orders.deviceUnknown(orderNo,"STOP");throw failure;}
    }

    @SuppressWarnings("unchecked")
    private void replayEvents(Map<String,Object> response){
        if(response.get("events") instanceof List<?> events)for(Object event:events)sessions.ingest((Map<String,Object>)event);
    }

    private void paymentResult(String payNo,Map<String,Object> response){
        if("NOT_FOUND".equals(response.get("status")))return;
        if("SUCCESS".equals(response.get("status"))){orders.acceptVerified(ChannelFinancialFact.parse(response,false).notification());return;}
        if(Boolean.FALSE.equals(payments.applyChannelPayment(response).get("valid")))orders.channelMismatch(payNo);
    }

    private void refundResult(String refundNo,Map<String,Object> response){
        if(Set.of("SUCCESS","FAILED").contains(String.valueOf(response.get("status")))){
            orders.acceptVerified(ChannelFinancialFact.parse(response,true).notification());
        }else payments.applyRefund(response);
    }

    private Map<String,Object> deviceSnapshot(String orderNo){
        Map<String,Object> session=db.one("SELECT s.*,o.start_deadline FROM charge_session s JOIN charge_order o ON o.order_no=s.order_no WHERE s.order_no=?",orderNo);
        if(session==null)return Map.of("status","NOT_STARTED","events",List.of());
        Map<String,Object> request=new LinkedHashMap<>();request.put("sessionNo",session.get("session_no"));
        request.put("deviceId",session.get("device_id"));request.put("connectorId",String.valueOf(session.get("connector_id")));
        Instant expires=Db.instant(session,"start_deadline");if(expires!=null)request.put("expiresAt",expires.toString());
        return device.post("/mock-devices/v1/sessions/query",request);
    }

    public void queryDevice(String orderNo){
        Map<String,Object> response=deviceSnapshot(orderNo);replayEvents(response);
        String state=String.valueOf(response.get("status"));
        if("NOT_STARTED".equals(state)){
            Map<String,Object> order=db.one("SELECT start_deadline FROM charge_order WHERE order_no=?",orderNo);
            if(order!=null&&order.get("start_deadline")!=null&&!clock.instant().isBefore(Db.instant(order,"start_deadline")))orders.deviceNotStarted(orderNo);
            else if(db.one("SELECT id FROM charge_session WHERE order_no=?",orderNo)==null)orders.finishCancel(orderNo);
        }else if("CHARGING".equals(state))orders.deviceCharging(orderNo);
        else if("STOPPED".equals(state))sessions.tryFinalize(orderNo);
    }

    @Scheduled(fixedDelayString="${chargehub.recovery-delay-ms:3000}")
    public void recover(){
        for(var order:db.rows("SELECT order_no FROM charge_order WHERE status='WAIT_PAY' AND payment_deadline<=? ORDER BY id LIMIT 50",clock.instant()))
            safely("payment-expiry",()->orders.expire(Db.str(order,"order_no")));
        for(var payment:db.rows("SELECT pay_no FROM payment_order WHERE status IN ('INIT','PENDING','UNKNOWN','CLOSE_PENDING') ORDER BY updated_at LIMIT 50"))
            safely("payment-query",()->{String no=Db.str(payment,"pay_no");paymentResult(no,payments.queryPayment(no));});
        for(var refund:db.rows("SELECT refund_no FROM refund_order WHERE status IN ('INIT','PROCESSING','UNKNOWN') ORDER BY updated_at LIMIT 50"))
            safely("refund-query",()->{String no=Db.str(refund,"refund_no");Map<String,Object> fact=payments.queryRefund(no);if("NOT_FOUND".equals(fact.get("status")))fact=payments.sendRefund(no);refundResult(no,fact);});
        for(var order:db.rows("SELECT order_no FROM charge_order WHERE status IN ('EXCEPTION','STOP_PENDING','CANCELING','START_PENDING') ORDER BY updated_at LIMIT 50"))
            safely("device-query",()->queryDevice(Db.str(order,"order_no")));
        for(var order:db.rows("SELECT o.order_no FROM charge_order o JOIN charge_session s ON s.order_no=o.order_no WHERE s.status='CHARGING' AND o.status IN ('CHARGING','REVIEW','EXCEPTION') ORDER BY o.updated_at LIMIT 50"))
            safely("prepaid-stoploss",()->{String no=Db.str(order,"order_no");queryDevice(no);sessions.enforcePrepaid(no);});
        for(var order:db.rows("SELECT order_no FROM charge_order WHERE status IN ('BILLING','REVIEW') AND (exception_code IS NULL OR exception_code NOT IN ('METER_CONFLICT','METER_ROLLBACK')) ORDER BY updated_at LIMIT 25"))
            safely("meter-recovery",()->{String no=Db.str(order,"order_no");queryDevice(no);sessions.tryFinalize(no);});
    }

    @Scheduled(fixedDelay=10000)
    public void reconcileRecovered(){
        Map<String,Object> actor=db.one("SELECT id FROM iam_user WHERE login_name='__system__' AND enabled=0");if(actor==null)return;
        for(var batch:db.rows("SELECT DISTINCT batch_id FROM reconcile_item WHERE diff_type='CHANNEL_ONLY' AND status='OPEN' ORDER BY batch_id LIMIT 20"))
            safely("reconciliation",()->reconciliations.refreshRecovered(Db.num(actor,"id"),Db.num(batch,"batch_id"),"后台渠道查单后核对一致，恢复已补录资金事实"));
        for(var order:db.rows("SELECT o.order_no FROM charge_order o WHERE financial_status='DIFF' AND exception_code='RECONCILIATION_DIFFERENCE' AND NOT EXISTS(SELECT 1 FROM reconcile_item r WHERE r.order_no=o.order_no AND r.status<>'RESOLVED') LIMIT 20"))
            safely("financial-close",()->orders.reconsiderFinancialClose(Db.str(order,"order_no")));
    }

    private void safely(String phase,Runnable work){
        try{work.run();}
        catch(Exception failure){metrics.counter("chargehub.worker.failure","phase",phase,"type",failure.getClass().getSimpleName()).increment();LOG.debug("Recovery deferred phase={} errorType={}",phase,failure.getClass().getSimpleName());}
    }
}
