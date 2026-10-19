package com.chargehub.app;

import com.chargehub.infrastructure.*;
import java.util.*;

/** Reads persisted domain evidence after dispatch; never performs a network request or changes business facts. */
public final class MessagingCompletionProof {
    private final Db db;
    public MessagingCompletionProof(Db db) {this.db=db;}
    public Map<String,Object> read(WorkflowMessage message) {
        String order=String.valueOf(message.payload().get("orderNo"));
        var row=db.one("SELECT order_no,status,version FROM charge_order WHERE order_no=?",order);
        require(row!=null);
        var proof=new LinkedHashMap<String,Object>();proof.put("eventId",message.eventId());proof.put("eventName",message.eventName());
        proof.put("orderNo",order);proof.put("orderStatus",Db.str(row,"status"));proof.put("orderVersion",Db.num(row,"version"));
        switch(message.eventName()) {
            case "PaymentCreateRequested","PaymentCloseRequested" -> {
                var payment=db.one("SELECT pay_no,order_no,status,version FROM payment_order WHERE pay_no=?",message.payload().get("payNo"));
                if("CANCELED".equals(Db.str(row,"status")))break;
                require(payment!=null&&order.equals(Db.str(payment,"order_no")));
                require((message.eventName().equals("PaymentCloseRequested")?Set.of("CLOSED","SUCCESS"):Set.of("PENDING","SUCCESS","CLOSED","CLOSE_PENDING")).contains(Db.str(payment,"status")));
                proof.put("paymentNo",Db.str(payment,"pay_no"));proof.put("paymentStatus",Db.str(payment,"status"));proof.put("paymentVersion",Db.num(payment,"version"));
            }
            case "RefundRequested","RefundSucceeded" -> {
                var refund=db.one("SELECT refund_no,order_no,status,amount_fen FROM refund_order WHERE refund_no=?",message.payload().get("refundNo"));
                require(refund!=null&&order.equals(Db.str(refund,"order_no"))&&Set.of("PROCESSING","SUCCESS","FAILED_FINAL","FAILED_RETRYABLE").contains(Db.str(refund,"status")));
                if(message.eventName().equals("RefundSucceeded"))require("SUCCESS".equals(Db.str(refund,"status"))&&db.one("SELECT id FROM cash_entry WHERE biz_type='REFUND' AND biz_no=? AND order_no=?",message.payload().get("refundNo"),order)!=null);
                proof.put("refundNo",Db.str(refund,"refund_no"));proof.put("refundStatus",Db.str(refund,"status"));proof.put("amountFen",Db.num(refund,"amount_fen"));
            }
            case "PaymentCaptured" -> {var cash=db.one("SELECT biz_no,amount_fen FROM cash_entry WHERE order_no=? AND biz_type='CAPTURE'",order);require(cash!=null);proof.put("captureBizNo",Db.str(cash,"biz_no"));proof.put("amountFen",Db.num(cash,"amount_fen"));}
            case "BillFinalized" -> {var bill=db.one("SELECT bill_no,payable_fen FROM charge_bill WHERE order_no=? AND bill_no=?",order,message.payload().get("billNo"));require(bill!=null);proof.put("billNo",Db.str(bill,"bill_no"));proof.put("payableFen",Db.num(bill,"payable_fen"));}
            case "DeviceMeterReplayRequested" -> {var replay=db.one("SELECT request_no,status,response_hash FROM meter_replay_request WHERE request_no=? AND order_no=?",message.payload().get("requestNo"),order);require(replay!=null&&"DONE".equals(Db.str(replay,"status")));proof.put("requestNo",Db.str(replay,"request_no"));proof.put("responseHash",Db.str(replay,"response_hash"));}
            case "ChargeStartRequested","ChargeStopRequested","DeviceQueryRequested","SessionStopped" -> {
                var session=db.one("SELECT session_no,status,final_seq FROM charge_session WHERE order_no=?",order);
                if("CANCELED".equals(Db.str(row,"status"))&&session==null)break;
                require(session!=null);
                String state=Db.str(session,"status");
                require((Set.of("ChargeStopRequested","SessionStopped").contains(message.eventName())?Set.of("STOPPED","NOT_STARTED","REJECTED"):Set.of("CHARGING","STOPPED","NOT_STARTED","REJECTED")).contains(state));
                proof.put("sessionNo",Db.str(session,"session_no"));proof.put("sessionStatus",state);proof.put("finalSeq",session.get("final_seq"));
            }
            default -> throw new IllegalArgumentException("Unsupported workflow event");
        }
        return proof;
    }
    private static void require(boolean condition) {if(!condition)throw new IllegalStateException("Durable workflow completion not established");}
}
