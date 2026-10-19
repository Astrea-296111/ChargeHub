package com.chargehub.payment;

import com.chargehub.infrastructure.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** State assertions exercise money invariants; the test store records immutable channel facts. */
class PaymentServiceTest {
    final String payNo=UUID.randomUUID().toString(),orderNo=UUID.randomUUID().toString();
    final Instant occurred=Instant.parse("2026-10-18T07:00:00Z");
    Db db;
    PaymentService service;
    Map<String,Object> payment;
    final Map<String,Map<String,Object>> callbacks=new LinkedHashMap<>(),refunds=new LinkedHashMap<>(),cash=new LinkedHashMap<>();

    @BeforeEach void setup(){
        payment=new LinkedHashMap<>(Map.of("pay_no",payNo,"order_no",orderNo,"amount_fen",10000L,"currency","CNY","status","PENDING",
                "captured_fen",0L,"refund_reserved_fen",0L,"refund_success_fen",0L));
        payment.put("provider_trade_no","channel-pay-1");
        db=mock(Db.class);
        when(db.one(anyString(),any(Object[].class))).thenAnswer(inv->{
            String sql=inv.getArgument(0); Object[] args=Arrays.copyOfRange(inv.getArguments(),1,inv.getArguments().length);
            return select(sql,args);
        });
        when(db.update(anyString(),any(Object[].class))).thenAnswer(inv->{
            String sql=inv.getArgument(0); Object[] args=Arrays.copyOfRange(inv.getArguments(),1,inv.getArguments().length);
            return mutate(sql,args);
        });
        service=new PaymentService(db,mock(Hmac.class),new MockEnvironment());
    }

    @Test void duplicateCaptureProducesOneImmutableCashFactAndHashConflictCannotChangeIt(){
        var event=captureEvent("event-1");
        assertEquals(true,service.capture(event).get("firstCapture"));
        assertEquals(false,service.capture(event).get("firstCapture"));
        assertEquals(10000L,payment.get("captured_fen"));
        assertEquals(1,cash.size());
        var corrupt=new LinkedHashMap<>(event);corrupt.put("amountFen",9999);
        BusinessException conflict=assertThrows(BusinessException.class,()->service.capture(corrupt));
        assertEquals("CHANNEL_EVENT_CONFLICT",conflict.code());
        assertEquals(10000L,cash.get(payNo).get("amount_fen"));
    }

    @Test void closedPaymentNotificationIsDeduplicatedAndCannotChangeItsPayload(){
        var event=captureEvent("closed");event.put("type","PAYMENT_CLOSED");
        assertEquals(true,service.applyChannelPayment(event).get("valid"));
        assertEquals(true,service.applyChannelPayment(event).get("valid"));
        assertEquals("CLOSED",payment.get("status"));
        assertEquals(0L,payment.get("captured_fen"));assertTrue(cash.isEmpty());
        var conflict=new LinkedHashMap<>(event);conflict.put("amountFen",9999L);
        assertEquals("CHANNEL_EVENT_CONFLICT",assertThrows(BusinessException.class,()->service.applyChannelPayment(conflict)).code());
    }

    @Test void mismatchedAmountAndFractionalAmountCannotCaptureMoney(){
        var event=captureEvent("wrong-amount");event.put("amountFen",9999L);
        assertEquals(false,service.capture(event).get("valid"));
        assertEquals("REJECTED",callbacks.get("wrong-amount").get("process_status"));
        assertEquals(0L,payment.get("captured_fen"));assertTrue(cash.isEmpty());
        var fractional=captureEvent("fractional");fractional.put("amountFen",10000.9d);
        assertEquals("INVALID_CHANNEL_AMOUNT",assertThrows(BusinessException.class,()->service.capture(fractional)).code());
        assertEquals(0L,payment.get("captured_fen"));assertTrue(cash.isEmpty());
    }

    @Test void lateDuplicateWithDifferentOccurrenceCannotRewriteCapturedFact(){
        service.capture(captureEvent("first"));
        var changed=captureEvent("different-event");changed.put("occurredAt",occurred.plusSeconds(1).toString());
        assertEquals(false,service.capture(changed).get("valid"));
        assertEquals(occurred,payment.get("captured_at"));assertEquals(1,cash.size());
    }

    @Test void refundReasonReservesOnceAndUnknownContinuesToHoldCapacity(){
        service.capture(captureEvent("paid"));
        var first=service.ensureRefund(orderNo,7900,"BALANCE");
        var second=service.ensureRefund(orderNo,7900,"BALANCE");
        assertEquals(first.get("refundNo"),second.get("refundNo"));
        assertEquals(7900L,payment.get("refund_reserved_fen"));
        assertEquals(1,refunds.size());
        assertEquals("REFUND_LIMIT",assertThrows(BusinessException.class,()->service.ensureRefund(orderNo,2101,"FULL_CANCEL")).code());
        assertEquals("REFUND_CONFLICT",assertThrows(BusinessException.class,()->service.ensureRefund(orderNo,7901,"BALANCE")).code());
        String refundNo=first.get("refundNo").toString();
        service.applyRefund(Map.of("refundNo",refundNo,"status","UNKNOWN"));
        assertEquals("UNKNOWN",refunds.get(refundNo).get("status"));
        assertEquals(7900L,payment.get("refund_reserved_fen"));
        assertEquals(0L,payment.get("refund_success_fen"));
    }

    @Test void successfulRefundTransfersReservationOnceAndNeverRegresses(){
        service.capture(captureEvent("paid"));
        String refundNo=service.ensureRefund(orderNo,7900,"BALANCE").get("refundNo").toString();
        var event=refundEvent(refundNo,"refund-success","REFUND_SUCCESS");
        assertEquals(true,service.refundSucceeded(event).get("firstRefund"));
        assertEquals(false,service.refundSucceeded(event).get("firstRefund"));
        assertEquals(0L,payment.get("refund_reserved_fen"));assertEquals(7900L,payment.get("refund_success_fen"));
        assertEquals(2,cash.size());
        var contradict=refundEvent(refundNo,"refund-failure-after-success","REFUND_FAILED");
        assertEquals(false,service.refundSucceeded(contradict).get("valid"));
        assertEquals("SUCCESS",refunds.get(refundNo).get("status"));
        assertEquals(7900L,payment.get("refund_success_fen"));assertEquals(0L,payment.get("refund_reserved_fen"));
    }

    @Test void retryableFailureReReservesSameRefundAndOldFailureCannotReleaseNewAttempt(){
        service.capture(captureEvent("paid"));
        String no=service.ensureRefund(orderNo,7900,"BALANCE").get("refundNo").toString();
        var failure=refundEvent(no,"retryable-failure","REFUND_FAILED");failure.put("retryable",true);failure.put("attempt",0);
        service.refundSucceeded(failure);assertEquals("FAILED_RETRYABLE",refunds.get(no).get("status"));assertEquals(0L,payment.get("refund_reserved_fen"));
        var query=new LinkedHashMap<>(failure);query.put("status","FAILED");
        service.prepareRefundRetry(no,query);assertEquals("INIT",refunds.get(no).get("status"));assertEquals(7900L,payment.get("refund_reserved_fen"));
        var stale=new LinkedHashMap<>(failure);stale.put("channelEventId","another-old-failure");
        service.refundSucceeded(stale);assertEquals(7900L,payment.get("refund_reserved_fen"));assertEquals("INIT",refunds.get(no).get("status"));
        var success=refundEvent(no,"retried-success","REFUND_SUCCESS");service.refundSucceeded(success);
        assertEquals("SUCCESS",refunds.get(no).get("status"));assertEquals(0L,payment.get("refund_reserved_fen"));assertEquals(7900L,payment.get("refund_success_fen"));
        assertEquals(1,refunds.size());assertEquals(2,cash.size());
        var delayed=new LinkedHashMap<>(failure);delayed.put("channelEventId","old-failure-after-retry-success");
        assertEquals(true,service.refundSucceeded(delayed).get("valid"));assertEquals("SUCCESS",refunds.get(no).get("status"));
        assertEquals(7900L,payment.get("refund_success_fen"));assertEquals(0L,payment.get("refund_reserved_fen"));
    }

    @Test void definitiveFailedRefundReleasesReservationOnceAndCannotBecomeSuccess(){
        service.capture(captureEvent("paid"));
        String refundNo=service.ensureRefund(orderNo,7900,"BALANCE").get("refundNo").toString();
        var failure=refundEvent(refundNo,"failure","REFUND_FAILED");
        service.refundSucceeded(failure);service.refundSucceeded(failure);
        assertEquals("FAILED_FINAL",refunds.get(refundNo).get("status"));
        assertEquals(0L,payment.get("refund_reserved_fen"));assertEquals(0L,payment.get("refund_success_fen"));
        assertEquals(1,cash.size());
        var impossible=refundEvent(refundNo,"success-after-final-rejection","REFUND_SUCCESS");
        assertEquals(false,service.refundSucceeded(impossible).get("valid"));
        assertEquals("FAILED_FINAL",refunds.get(refundNo).get("status"));
        assertEquals(1,cash.size());
    }

    Map<String,Object> captureEvent(String id){return new LinkedHashMap<>(Map.of("channelEventId",id,"type","PAYMENT_SUCCESS","payNo",payNo,
            "providerBizNo","channel-pay-1","amountFen",10000L,"currency","CNY","occurredAt",occurred.toString()));}
    Map<String,Object> refundEvent(String no,String id,String type){var e=captureEvent(id);e.put("type",type);e.put("refundNo",no);e.put("providerBizNo","channel-refund-1");e.put("amountFen",7900L);return e;}

    Map<String,Object> select(String sql,Object[] args){
        if(sql.contains("FROM payment_callback"))return callbacks.get(args[0].toString());
        if(sql.contains("FROM cash_entry"))return cash.get(args[0].toString());
        if(sql.contains("FROM payment_order")){
            if(sql.contains("provider_trade_no=?"))return Objects.equals(payment.get("provider_trade_no"),args[0])?payment:null;
            return Objects.equals(payment.get(sql.contains("order_no=?")?"order_no":"pay_no"),args[0])?payment:null;
        }
        if(sql.contains("FROM refund_order")){
            if(sql.contains("provider_refund_no=?"))return refunds.values().stream().filter(r->Objects.equals(r.get("provider_refund_no"),args[0])).findFirst().orElse(null);
            if(sql.contains("reason=?"))return refunds.values().stream().filter(r->Objects.equals(r.get("pay_no"),args[0])&&Objects.equals(r.get("reason"),args[1])).findFirst().orElse(null);
            return refunds.get(args[0].toString());
        }
        throw new AssertionError("Unmodeled read: "+sql);
    }

    int mutate(String sql,Object[] a){
        if(sql.startsWith("INSERT INTO payment_callback")){
            var row=new LinkedHashMap<String,Object>();row.put("payload_hash",a[4]);row.put("process_status","RECEIVED");callbacks.put(a[0].toString(),row);
        }else if(sql.startsWith("UPDATE payment_callback")){
            var row=callbacks.get(a[a.length-1].toString());row.put("process_status",sql.contains("'REJECTED'")?"REJECTED":"PROCESSED");
        }else if(sql.startsWith("INSERT INTO cash_entry")){
            var row=new LinkedHashMap<String,Object>();row.put("amount_fen",a[3]);row.put("provider_biz_no",a[2]);row.put("occurred_at",a[5]);
            if(cash.putIfAbsent(a[1].toString(),row)!=null)throw new AssertionError("Duplicate immutable cash entry");
        }else if(sql.startsWith("INSERT INTO refund_order")){
            var row=new LinkedHashMap<String,Object>();for(int i=0;i<5;i++)row.put(List.of("refund_no","pay_no","order_no","reason","amount_fen").get(i),a[i]);row.put("status","INIT");refunds.put(a[0].toString(),row);
        }else if(sql.startsWith("UPDATE payment_order")){
            if(sql.contains("status='SUCCESS'")){payment.put("status","SUCCESS");payment.put("provider_trade_no",a[0]);payment.put("captured_fen",a[1]);payment.put("captured_at",a[2]);}
            else if(sql.contains("SET status=?,provider_trade_no=?")){payment.put("status",a[0]);payment.put("provider_trade_no",a[1]);}
            else if(sql.contains("refund_success_fen=refund_success_fen+?")){delta("refund_reserved_fen",-((Number)a[0]).longValue());delta("refund_success_fen",((Number)a[1]).longValue());}
            else if(sql.contains("refund_reserved_fen=refund_reserved_fen+?"))delta("refund_reserved_fen",((Number)a[0]).longValue());
            else if(sql.contains("refund_reserved_fen=refund_reserved_fen-?"))delta("refund_reserved_fen",-((Number)a[0]).longValue());
            else throw new AssertionError("Unmodeled payment mutation: "+sql);
        }else if(sql.startsWith("UPDATE refund_order")){
            var row=refunds.get(a[a.length-1].toString());
            if(sql.contains("status='SUCCESS'")){row.put("status","SUCCESS");row.put("provider_refund_no",a[0]);}
            else if(sql.contains("status='INIT'")){row.put("status","INIT");row.put("retry_attempt",((Number)row.getOrDefault("retry_attempt",0L)).longValue()+1);}
            else if(sql.contains("status=?,provider_refund_no=?")){row.put("status",a[0]);row.put("provider_refund_no",a[1]);}
            else if(sql.contains("status=?"))row.put("status",a[0]);
            else throw new AssertionError("Unmodeled refund mutation: "+sql);
        }else throw new AssertionError("Unmodeled mutation: "+sql);
        return 1;
    }
    void delta(String key,long delta){payment.put(key,((Number)payment.get(key)).longValue()+delta);}
}
