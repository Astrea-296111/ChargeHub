package com.chargehub.payment;

import com.chargehub.infrastructure.*;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

import java.io.InputStream;
import java.net.URI;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

import static com.chargehub.infrastructure.Db.*;

/** Payment facts and reserved refunds. External I/O deliberately runs outside DB transactions. */
@Service
public class PaymentService {
    private final Db db;
    private final Hmac hmac;
    private final Environment environment;

    public PaymentService(Db db, Hmac hmac, Environment environment) {
        this.db = db; this.hmac = hmac; this.environment = environment;
    }

    @Transactional
    public Map<String,Object> create(String orderNo) {
        var existing = db.one("SELECT * FROM payment_order WHERE order_no=? FOR UPDATE", orderNo);
        if (existing != null) return paymentView(existing);
        var order = db.one("SELECT * FROM charge_order WHERE order_no=?", orderNo);
        if (order == null) throw error(404,"ORDER_NOT_FOUND","订单不存在");
        String payNo = UUID.randomUUID().toString();
        db.update("INSERT INTO payment_order(pay_no,order_no,amount_fen,currency) VALUES(?,?,?,?)",
                payNo,orderNo,num(order,"prepaid_fen"),str(order,"currency"));
        return getByOrder(orderNo);
    }

    public Map<String,Object> getByOrder(String orderNo) {
        var payment = db.one("SELECT * FROM payment_order WHERE order_no=?",orderNo);
        if (payment == null) return null;
        return paymentView(payment);
    }

    @Transactional
    public Map<String,Object> capture(Map<String,Object> input) {
        var event = normalized(input, false);
        var payment = payment(str(event,"payNo"), true);
        boolean duplicate = callback(event);
        String orderNo = str(payment,"order_no");
        if (duplicate) {
            var saved = db.one("SELECT * FROM payment_callback WHERE channel='MOCK' AND channel_event_id=?",str(event,"channelEventId"));
            return result(payment,false,"PROCESSED".equals(str(saved,"process_status")),str(event,"occurredAt"));
        }
        String provider = str(event,"providerBizNo");
        String knownProvider = str(payment,"provider_trade_no");
        boolean valid = "PAYMENT_SUCCESS".equals(str(event,"type")) && num(event,"amountFen") == num(payment,"amount_fen")
                && Objects.equals(str(event,"currency"),str(payment,"currency")) && provider != null && !provider.isBlank()
                && (knownProvider == null || knownProvider.equals(provider));
        var providerOwner = db.one("SELECT pay_no FROM payment_order WHERE channel='MOCK' AND provider_trade_no=?",provider);
        valid &= providerOwner == null || Objects.equals(str(providerOwner,"pay_no"),str(payment,"pay_no"));
        Instant occurred = eventTime(event);
        if (num(payment,"captured_fen") > 0) {
            valid &= num(payment,"captured_fen") == num(event,"amountFen")
                    && instant(payment,"captured_at").truncatedTo(ChronoUnit.MILLIS).equals(occurred.truncatedTo(ChronoUnit.MILLIS));
        }
        if (!valid) {
            reject(event,"PAYMENT_FACT_MISMATCH");
            return result(payment,false,false,str(event,"occurredAt"));
        }
        boolean first = num(payment,"captured_fen") == 0;
        if (first) {
            db.update("INSERT INTO cash_entry(order_no,biz_type,biz_no,channel,provider_biz_no,amount_fen,currency,occurred_at) VALUES(?,'CAPTURE',?,'MOCK',?,?,?,?)",
                    orderNo,str(payment,"pay_no"),provider,num(event,"amountFen"),str(event,"currency"),occurred);
            db.update("UPDATE payment_order SET status='SUCCESS',provider_trade_no=?,captured_fen=?,captured_at=?,version=version+1 WHERE pay_no=?",
                    provider,num(event,"amountFen"),occurred,str(payment,"pay_no"));
        }
        processed(event);
        return result(payment(str(payment,"pay_no"),false),first,true,str(event,"occurredAt"));
    }

    @Transactional
    public Map<String,Object> ensureRefund(String orderNo, long amountFen, String reason) {
        if (amountFen <= 0 || !Set.of("FULL_CANCEL","BALANCE").contains(reason))
            throw error(400,"INVALID_REFUND","退款金额或原因不合法");
        var payment = db.one("SELECT * FROM payment_order WHERE order_no=? FOR UPDATE",orderNo);
        if (payment == null) throw error(404,"PAYMENT_NOT_FOUND","支付单不存在");
        var existing = db.one("SELECT * FROM refund_order WHERE pay_no=? AND reason=?",str(payment,"pay_no"),reason);
        if (existing != null) {
            if (num(existing,"amount_fen") != amountFen) throw error(409,"REFUND_CONFLICT","相同退款原因已绑定其他金额");
            return refundView(existing);
        }
        if (amountFen > num(payment,"captured_fen") - num(payment,"refund_reserved_fen") - num(payment,"refund_success_fen"))
            throw error(409,"REFUND_LIMIT","退款超过已支付可退额度");
        String refundNo = UUID.randomUUID().toString();
        db.update("UPDATE payment_order SET refund_reserved_fen=refund_reserved_fen+?,version=version+1 WHERE pay_no=?",amountFen,str(payment,"pay_no"));
        db.update("INSERT INTO refund_order(refund_no,pay_no,order_no,reason,amount_fen) VALUES(?,?,?,?,?)",refundNo,str(payment,"pay_no"),orderNo,reason,amountFen);
        return refundView(refund(refundNo,false));
    }

    @Transactional
    public Map<String,Object> refundSucceeded(Map<String,Object> input) {
        var event = normalized(input,true);
        var initial = refund(str(event,"refundNo"),false);
        var payment = payment(str(initial,"pay_no"),true);
        var refund = refund(str(event,"refundNo"),true);
        boolean duplicate = callback(event);
        if (duplicate) {
            var saved = db.one("SELECT * FROM payment_callback WHERE channel='MOCK' AND channel_event_id=?",str(event,"channelEventId"));
            return refundResult(refund,false,"PROCESSED".equals(str(saved,"process_status")));
        }
        String provider = str(event,"providerBizNo");
        String known = str(refund,"provider_refund_no");
        boolean valid = Objects.equals(str(refund,"pay_no"),str(event,"payNo"))
                && num(refund,"amount_fen") == num(event,"amountFen")
                && Objects.equals(str(payment,"currency"),str(event,"currency"))
                && provider != null && !provider.isBlank() && (known == null || known.equals(provider));
        var providerOwner = db.one("SELECT refund_no FROM refund_order WHERE provider_refund_no=?",provider);
        valid &= providerOwner == null || Objects.equals(str(providerOwner,"refund_no"),str(refund,"refund_no"));
        Instant occurred = eventTime(event);
        if (!valid) {
            reject(event,"REFUND_FACT_MISMATCH");
            return refundResult(refund,false,false);
        }
        String type = str(event,"type");
        boolean first = false;
        if ("REFUND_SUCCESS".equals(type)) {
            if ("FAILED_FINAL".equals(str(refund,"status"))) {
                // A previous definitive rejection released this amount. A later contradictory success is a reconciliation issue.
                reject(event,"REFUND_FINAL_STATE_CONFLICT");
                return refundResult(refund,false,false);
            }
            first = !"SUCCESS".equals(str(refund,"status"));
            if (first) {
                db.update("INSERT INTO cash_entry(order_no,biz_type,biz_no,channel,provider_biz_no,amount_fen,currency,occurred_at) VALUES(?,'REFUND',?,'MOCK',?,?,?,?)",
                        str(refund,"order_no"),str(refund,"refund_no"),provider,num(refund,"amount_fen"),str(event,"currency"),occurred);
                db.update("UPDATE payment_order SET refund_reserved_fen=refund_reserved_fen-?,refund_success_fen=refund_success_fen+?,version=version+1 WHERE pay_no=?",
                        num(refund,"amount_fen"),num(refund,"amount_fen"),str(payment,"pay_no"));
                db.update("UPDATE refund_order SET status='SUCCESS',provider_refund_no=?,last_error=NULL,next_query_at=NULL WHERE refund_no=?",provider,str(refund,"refund_no"));
            } else {
                var cash = db.one("SELECT * FROM cash_entry WHERE biz_type='REFUND' AND biz_no=?",str(refund,"refund_no"));
                if (cash == null || !instant(cash,"occurred_at").truncatedTo(ChronoUnit.MILLIS).equals(occurred.truncatedTo(ChronoUnit.MILLIS))) {
                    reject(event,"REFUND_FACT_MISMATCH");
                    return refundResult(refund,false,false);
                }
            }
        } else if ("REFUND_FAILED".equals(type)) {
            if ("SUCCESS".equals(str(refund,"status"))) {
                reject(event,"REFUND_FINAL_STATE_CONFLICT");
                return refundResult(refund,false,false);
            }
            if (!Set.of("SUCCESS","FAILED_FINAL").contains(str(refund,"status"))) {
                db.update("UPDATE payment_order SET refund_reserved_fen=refund_reserved_fen-?,version=version+1 WHERE pay_no=?",num(refund,"amount_fen"),str(payment,"pay_no"));
                db.update("UPDATE refund_order SET status='FAILED_FINAL',provider_refund_no=?,last_error='CHANNEL_REJECTED',next_query_at=NULL WHERE refund_no=?",provider,str(refund,"refund_no"));
            }
        } else {
            reject(event,"INVALID_REFUND_TYPE");
            return refundResult(refund,false,false);
        }
        processed(event);
        return refundResult(refund(str(refund,"refund_no"),false),first,true);
    }

    public Map<String,Object> funds(String orderNo) {
        var funds = new LinkedHashMap<String,Object>();
        funds.put("payment",getByOrder(orderNo));
        funds.put("refunds",db.rows("SELECT * FROM refund_order WHERE order_no=? ORDER BY id",orderNo).stream().map(this::refundView).toList());
        funds.put("entries",db.rows("SELECT * FROM cash_entry WHERE order_no=? ORDER BY id",orderNo));
        return funds;
    }

    @Transactional
    public Map<String,Object> applyChannelPayment(Map<String,Object> response) {
        String payNo = first(response,"payNo","outTradeNo");
        String status = str(response,"status");
        if (status == null && str(response,"type") != null) status = str(response,"type").replace("PAYMENT_", "");
        if ("SUCCESS".equals(status)) return capture(response);
        var payment = payment(payNo,true);
        Map<String,Object> closeEvent = "CLOSED".equals(status) ? normalized(response,false) : null;
        if (closeEvent != null && callback(closeEvent)) {
            var saved = db.one("SELECT * FROM payment_callback WHERE channel='MOCK' AND channel_event_id=?",str(closeEvent,"channelEventId"));
            return result(payment,false,"PROCESSED".equals(str(saved,"process_status")),str(response,"occurredAt"));
        }
        String provider = first(response,"providerTradeNo","providerBizNo");
        if (amount(response.get("amountFen")) != num(payment,"amount_fen") || !Objects.equals(str(response,"currency"),str(payment,"currency"))
                || provider == null || (!Objects.equals(provider,str(payment,"provider_trade_no")) && str(payment,"provider_trade_no") != null)) {
            if (closeEvent != null) reject(closeEvent,"PAYMENT_FACT_MISMATCH");
            return result(payment,false,false,str(response,"occurredAt"));
        }
        if (!"SUCCESS".equals(str(payment,"status"))) {
            String next = "CLOSED".equals(status) ? "CLOSED" : "PENDING";
            if (!"CLOSED".equals(str(payment,"status")))
                db.update("UPDATE payment_order SET status=?,provider_trade_no=?,version=version+1 WHERE pay_no=?",next,provider,payNo);
        }
        if (closeEvent != null) processed(closeEvent);
        return result(payment(payNo,false),false,true,str(response,"occurredAt"));
    }

    @Transactional
    public Map<String,Object> applyRefund(Map<String,Object> response) {
        String refundNo = first(response,"refundNo","outRefundNo");
        String status = str(response,"status");
        if (Set.of("SUCCESS","FAILED").contains(status)) return refundSucceeded(response);
        var initial = refund(refundNo,false);
        payment(str(initial,"pay_no"),true);
        var refund = refund(refundNo,true);
        if (!Set.of("SUCCESS","FAILED_FINAL").contains(str(refund,"status")))
            db.update("UPDATE refund_order SET status=?,next_query_at=DATE_ADD(UTC_TIMESTAMP(3),INTERVAL 5 SECOND) WHERE refund_no=?",
                    "PROCESSING".equals(status) ? "PROCESSING" : "UNKNOWN",refundNo);
        return refundResult(refund(refundNo,false),false,true);
    }

    @Transactional
    public void markPaymentUnknown(String payNo) {
        db.update("UPDATE payment_order SET status='UNKNOWN',version=version+1 WHERE pay_no=? AND status NOT IN ('SUCCESS','CLOSED')",payNo);
    }

    @Transactional
    public void markRefundUnknown(String refundNo, String lastError) {
        var initial = refund(refundNo,false);
        payment(str(initial,"pay_no"),true);
        db.update("UPDATE refund_order SET status='UNKNOWN',last_error=?,next_query_at=DATE_ADD(UTC_TIMESTAMP(3),INTERVAL 5 SECOND) WHERE refund_no=? AND status NOT IN ('SUCCESS','FAILED_FINAL')",
                lastError == null ? null : lastError.substring(0,Math.min(128,lastError.length())),refundNo);
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public Map<String,Object> sendCreate(String payNo) {
        var p = payment(payNo,false);
        var order = db.one("SELECT * FROM charge_order WHERE order_no=?",str(p,"order_no"));
        var request = new LinkedHashMap<String,Object>();
        request.put("outTradeNo",payNo); request.put("amountFen",num(p,"amount_fen")); request.put("currency",str(p,"currency"));
        request.put("expiresAt",instant(order,"payment_deadline").toString());
        request.put("notificationUrl",environment.getProperty("chargehub.callback-url","http://127.0.0.1:8080") + "/integrations/payments/mock/notifications");
        return invoke("/mock-gateway/v1/payments",request);
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public Map<String,Object> queryPayment(String payNo) { return invoke("/mock-gateway/v1/payments/query",Map.of("outTradeNo",payNo)); }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public Map<String,Object> closePayment(String payNo) { return invoke("/mock-gateway/v1/payments/close",Map.of("outTradeNo",payNo)); }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public Map<String,Object> sendRefund(String refundNo) {
        var r = refund(refundNo,false);
        return invoke("/mock-gateway/v1/refunds",Map.of("outRefundNo",refundNo,"outTradeNo",str(r,"pay_no"),"amountFen",num(r,"amount_fen"),"currency","CNY"));
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public Map<String,Object> queryRefund(String refundNo) { return invoke("/mock-gateway/v1/refunds/query",Map.of("outRefundNo",refundNo)); }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public Map<String,Object> exportBill(String businessDate,String billType,String timezone) {
        return invoke("/mock-gateway/v1/bills/export",Map.of("businessDate",businessDate,"billType",billType,"timezone",timezone));
    }

    private Map<String,Object> invoke(String path,Map<String,Object> request) {
        String body = Json.write(request);
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) URI.create(environment.getProperty("chargehub.payment-url","http://127.0.0.1:18081") + path).toURL().openConnection();
            connection.setRequestMethod("POST"); connection.setConnectTimeout(2000); connection.setReadTimeout(2000);
            connection.setInstanceFollowRedirects(false); connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type","application/json");
            var activeConnection = connection;
            hmac.headers("POST",path,body,environment.getProperty("chargehub.payment-key-id","payment")).forEach(activeConnection::setRequestProperty);
            byte[] requestBody = body.getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(requestBody.length);
            try(var output = connection.getOutputStream()) { output.write(requestBody); }
            int status = connection.getResponseCode();
            InputStream responseStream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            if (responseStream == null) throw error(502,"CHANNEL_RESPONSE_INVALID","渠道响应格式错误");
            try (InputStream stream = responseStream) {
                byte[] bytes = stream.readNBytes(12 * 1024 * 1024 + 1);
                if (bytes.length > 12 * 1024 * 1024) throw error(502,"CHANNEL_RESPONSE_TOO_LARGE","渠道响应超过限制");
                var envelope = Json.read(new String(bytes,StandardCharsets.UTF_8));
                if (status < 200 || status >= 300)
                    throw error(status < 500 ? 422 : 503,"CHANNEL_" + str(envelope,"code"),"支付渠道请求未成功，请查单确认");
                Object data = envelope.get("data");
                if (!(data instanceof Map<?,?>)) throw error(502,"CHANNEL_RESPONSE_INVALID","渠道响应格式错误");
                return new LinkedHashMap<>((Map<String,Object>)data);
            }
        } catch (BusinessException e) { throw e; }
        catch (Exception e) { throw error(503,"CHANNEL_UNKNOWN","渠道结果未知，请按原业务编号查单"); }
        finally { if (connection != null) connection.disconnect(); }
    }

    private Map<String,Object> normalized(Map<String,Object> input,boolean refund) {
        var event = new LinkedHashMap<String,Object>();
        String payNo = first(input,"payNo","outTradeNo");
        String refundNo = first(input,"refundNo","outRefundNo");
        String type = str(input,"type");
        if (type == null) type = (refund ? "REFUND_" : "PAYMENT_") + str(input,"status");
        String provider = refund ? first(input,"providerBizNo","providerRefundNo") : first(input,"providerBizNo","providerTradeNo");
        String eventId = str(input,"channelEventId");
        if (eventId == null || eventId.isBlank()) eventId = "query-" + (refund ? refundNo : payNo) + "-" + type;
        if (payNo == null || provider == null || str(input,"occurredAt") == null || eventId.length() > 80)
            throw error(400,"INVALID_CHANNEL_EVENT","渠道通知缺少资金事实字段");
        try { UUID.fromString(payNo); if(refund) UUID.fromString(refundNo); }
        catch (Exception e) { throw error(400,"INVALID_CHANNEL_EVENT","渠道业务编号格式错误"); }
        event.put("channelEventId",eventId); event.put("type",type); event.put("payNo",payNo);
        if(refund) event.put("refundNo",refundNo);
        event.put("providerBizNo",provider); event.put("amountFen",amount(input.get("amountFen")));
        event.put("currency",str(input,"currency")); event.put("occurredAt",str(input,"occurredAt"));
        eventTime(event);
        return event;
    }

    private boolean callback(Map<String,Object> event) {
        String hash = Json.hash(Json.write(event));
        var existing = db.one("SELECT * FROM payment_callback WHERE channel='MOCK' AND channel_event_id=?",str(event,"channelEventId"));
        if (existing != null) {
            if (!hash.equals(str(existing,"payload_hash"))) throw error(409,"CHANNEL_EVENT_CONFLICT","相同渠道事件编号的内容冲突");
            return true;
        }
        db.update("INSERT INTO payment_callback(channel,channel_event_id,event_type,pay_no,refund_no,payload_hash,payload) VALUES('MOCK',?,?,?,?,?,?)",
                str(event,"channelEventId"),str(event,"type"),str(event,"payNo"),str(event,"refundNo"),hash,Json.write(event));
        return false;
    }

    private void processed(Map<String,Object> event) { db.update("UPDATE payment_callback SET process_status='PROCESSED',processed_at=UTC_TIMESTAMP(3) WHERE channel='MOCK' AND channel_event_id=?",str(event,"channelEventId")); }
    private void reject(Map<String,Object> event,String code) { db.update("UPDATE payment_callback SET process_status='REJECTED',error_code=?,processed_at=UTC_TIMESTAMP(3) WHERE channel='MOCK' AND channel_event_id=?",code,str(event,"channelEventId")); }
    private long amount(Object value) {
        try {
            if (!(value instanceof Number)) throw new NumberFormatException();
            long result = new java.math.BigDecimal(value.toString()).longValueExact();
            if (result <= 0) throw new NumberFormatException();
            return result;
        } catch (ArithmeticException | NumberFormatException e) { throw error(400,"INVALID_CHANNEL_AMOUNT","渠道金额必须为正整数分"); }
    }
    private Instant eventTime(Map<String,Object> event) { try { return Instant.parse(str(event,"occurredAt")).truncatedTo(ChronoUnit.MILLIS); } catch(Exception e) { throw error(400,"INVALID_CHANNEL_TIME","渠道发生时间必须为UTC时间"); } }
    private Map<String,Object> payment(String payNo,boolean lock) { var p=db.one("SELECT * FROM payment_order WHERE pay_no=?"+(lock?" FOR UPDATE":""),payNo); if(p==null)throw error(404,"PAYMENT_NOT_FOUND","支付单不存在"); return p; }
    private Map<String,Object> refund(String refundNo,boolean lock) { var r=db.one("SELECT * FROM refund_order WHERE refund_no=?"+(lock?" FOR UPDATE":""),refundNo); if(r==null)throw error(404,"REFUND_NOT_FOUND","退款单不存在"); return r; }
    private String first(Map<String,Object> map,String one,String two) { return str(map,one) != null ? str(map,one) : str(map,two); }
    private BusinessException error(int status,String code,String message) { return new BusinessException(status,code,message); }

    private Map<String,Object> result(Map<String,Object> payment,boolean first,boolean valid,String occurred) {
        var result=paymentView(payment); result.put("firstCapture",first); result.put("valid",valid); result.put("occurredAt",occurred); return result;
    }
    private Map<String,Object> refundResult(Map<String,Object> refund,boolean first,boolean valid) {
        var result=refundView(refund);result.put("firstRefund",first);result.put("valid",valid);return result;
    }
    private Map<String,Object> paymentView(Map<String,Object> p) {
        var view=new LinkedHashMap<String,Object>(p);
        view.put("payNo",str(p,"pay_no"));view.put("orderNo",str(p,"order_no"));view.put("status",str(p,"status"));
        view.put("providerTradeNo",str(p,"provider_trade_no"));view.put("amountFen",num(p,"amount_fen"));view.put("currency",str(p,"currency"));
        view.put("capturedFen",num(p,"captured_fen"));view.put("refundReservedFen",num(p,"refund_reserved_fen"));view.put("refundSuccessFen",num(p,"refund_success_fen"));
        view.put("capturedAt",instant(p,"captured_at"));return view;
    }
    private Map<String,Object> refundView(Map<String,Object> r) {
        var view=new LinkedHashMap<String,Object>(r);
        view.put("refundNo",str(r,"refund_no"));view.put("payNo",str(r,"pay_no"));view.put("orderNo",str(r,"order_no"));
        view.put("reason",str(r,"reason"));view.put("amountFen",num(r,"amount_fen"));view.put("status",str(r,"status"));
        view.put("providerRefundNo",str(r,"provider_refund_no"));view.put("lastError",str(r,"last_error"));return view;
    }
}
