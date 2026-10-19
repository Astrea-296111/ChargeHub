package com.chargehub.infrastructure;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;
import java.util.function.Supplier;

/** Database ownership, completion and poison-message evidence; this class never calls the broker or business adapters. */
@Service
public class MessageDeliveryStore {
    public enum State { CLAIMED, BUSY, COMPLETED, DEAD, REJECTED }
    public record Claim(State state, WorkflowMessage message, long token, int attempt) {}
    private final Db db;
    private final TransactionTemplate tx;
    private final String owner = UUID.randomUUID().toString();

    public MessageDeliveryStore(Db db, PlatformTransactionManager manager) { this.db = db; this.tx = new TransactionTemplate(manager); }

    public Claim claim(WorkflowMessage message, String brokerId, String group, int leaseSeconds) {
        if (leaseSeconds < 5 || leaseSeconds > 300 || !group.matches("[A-Za-z0-9._-]{1,80}")) throw new IllegalArgumentException("Invalid message consumer configuration");
        return tx.execute(status -> {
            var source = db.one("SELECT * FROM outbox_event WHERE event_id=? FOR UPDATE", message.eventId());
            if (source == null || !matchesSource(message, source)) return new Claim(State.REJECTED, message, 0, 0);
            db.update("INSERT INTO message_delivery(event_id,consumer_name,payload_hash,broker_message_id) VALUES (?,?,?,?) ON DUPLICATE KEY UPDATE event_id=event_id",
                    message.eventId(), group, message.hash(), brokerId);
            var delivery = db.one("SELECT *,lease_until>UTC_TIMESTAMP(3) AS lease_valid FROM message_delivery WHERE event_id=? FOR UPDATE", message.eventId());
            if (!message.hash().equals(Db.str(delivery, "payload_hash")) || !group.equals(Db.str(delivery, "consumer_name"))) return new Claim(State.REJECTED, message, 0, 0);
            if (db.one("SELECT event_id FROM consumer_inbox WHERE consumer_name=? AND event_id=?", group, message.eventId()) != null) {
                db.update("UPDATE outbox_event SET status='DONE',lease_until=NULL WHERE event_id=? AND status<>'DEAD'", message.eventId());
                return new Claim(State.COMPLETED, message, Db.num(delivery, "lease_token"), (int) Db.num(delivery, "attempt_count"));
            }
            if ("DEAD".equals(Db.str(delivery, "status")) || "DEAD".equals(Db.str(source, "status"))) return new Claim(State.DEAD, message, 0, (int) Db.num(delivery, "attempt_count"));
            if ("PROCESSING".equals(Db.str(delivery, "status")) && Db.num(delivery, "lease_valid") == 1) return new Claim(State.BUSY, message, 0, (int) Db.num(delivery, "attempt_count"));
            db.update("UPDATE message_delivery SET status='PROCESSING',attempt_count=attempt_count+1,lease_owner=?,lease_token=lease_token+1,lease_until=TIMESTAMPADD(SECOND,?,UTC_TIMESTAMP(3)),broker_message_id=? WHERE event_id=?",
                    owner, leaseSeconds, brokerId, message.eventId());
            var claimed = db.one("SELECT lease_token,attempt_count FROM message_delivery WHERE event_id=?", message.eventId());
            return new Claim(State.CLAIMED, message, Db.num(claimed, "lease_token"), (int) Db.num(claimed, "attempt_count"));
        });
    }

    /** The proof and inbox are committed together AFTER external calls and their domain transactions have succeeded. */
    public boolean complete(Claim claim, Supplier<Map<String, Object>> completionProof) {
        return Boolean.TRUE.equals(tx.execute(status -> {
            var source = db.one("SELECT * FROM outbox_event WHERE event_id=? FOR UPDATE", claim.message.eventId());
            var delivery = db.one("SELECT *,lease_until>UTC_TIMESTAMP(3) AS lease_valid FROM message_delivery WHERE event_id=? FOR UPDATE", claim.message.eventId());
            if (!owned(claim, delivery) || Db.num(delivery, "lease_valid") != 1 || source == null || "DEAD".equals(Db.str(source, "status")) || !matchesSource(claim.message, source)) return false;
            Map<String, Object> proof = completionProof.get();
            if (proof == null || proof.isEmpty()) throw new IllegalStateException("Missing persisted completion proof");
            db.update("INSERT INTO consumer_inbox(consumer_name,event_id) VALUES (?,?)", Db.str(delivery, "consumer_name"), claim.message.eventId());
            db.update("UPDATE message_delivery SET status='COMPLETED',completion_json=?,completed_at=UTC_TIMESTAMP(3),lease_until=NULL,last_error=NULL WHERE event_id=? AND lease_owner=? AND lease_token=? AND status='PROCESSING'",
                    Json.write(proof), claim.message.eventId(), owner, claim.token);
            db.update("UPDATE outbox_event SET status='DONE',lease_until=NULL,last_error=NULL WHERE event_id=?", claim.message.eventId());
            return true;
        }));
    }

    /** Renew only a still-live owner/token, never revive an expired claim or a changed source payload. */
    public boolean renew(Claim claim,int leaseSeconds) {
        if(leaseSeconds<5||leaseSeconds>300)throw new IllegalArgumentException("Invalid consumer lease duration");
        return Boolean.TRUE.equals(tx.execute(status->{
            var source=db.one("SELECT * FROM outbox_event WHERE event_id=? FOR UPDATE",claim.message.eventId());
            if(source==null||"DEAD".equals(Db.str(source,"status"))||!matchesSource(claim.message,source))return false;
            return db.update("UPDATE message_delivery SET lease_until=TIMESTAMPADD(SECOND,?,UTC_TIMESTAMP(3)) WHERE event_id=? AND lease_owner=? AND lease_token=? AND status='PROCESSING' AND lease_until>UTC_TIMESTAMP(3)",
                    leaseSeconds,claim.message.eventId(),owner,claim.token)==1;
        }));
    }

    /** A failed task remains unacknowledged; the broker redelivers or moves it to its consumer-group DLQ. */
    public boolean failed(Claim claim, Exception failure, int maxAttempts) {
        return Boolean.TRUE.equals(tx.execute(status -> {
            var source=db.one("SELECT * FROM outbox_event WHERE event_id=? FOR UPDATE", claim.message.eventId());
            var delivery = db.one("SELECT *,lease_until>UTC_TIMESTAMP(3) AS lease_valid FROM message_delivery WHERE event_id=? FOR UPDATE", claim.message.eventId());
            if (source==null || !matchesSource(claim.message,source) || !owned(claim, delivery) || Db.num(delivery,"lease_valid")!=1) return false;
            boolean dead = claim.attempt >= maxAttempts;
            String code = failure.getClass().getSimpleName();
            if (code.length() > 64) code = "MESSAGE_PROCESSING_FAILED";
            db.update("UPDATE message_delivery SET status=?,last_error=?,lease_until=NULL WHERE event_id=? AND lease_owner=? AND lease_token=? AND status='PROCESSING'",
                    dead ? "DEAD" : "RETRY", code, claim.message.eventId(), owner, claim.token);
            if (dead) db.update("UPDATE outbox_event SET status='DEAD',lease_until=NULL,last_error=? WHERE event_id=? AND status<>'DONE'", code, claim.message.eventId());
            return dead;
        }));
    }

    public void quarantine(String brokerId, byte[] body, String eventId, String reason) {
        if (eventId != null && !eventId.matches("[0-9a-f-]{36}")) eventId = null;
        db.update("INSERT INTO message_quarantine(event_id,broker_message_id,body_hash,reason) VALUES(?,?,?,?) ON DUPLICATE KEY UPDATE id=id",
                eventId, brokerId, Json.hash(body), reason);
    }

    /** Observability after the actual broker Ack, not an acknowledgement substitute. */
    public void acknowledged(WorkflowMessage message,String brokerId,int deliveryAttempt,boolean duplicate) {
        var source=db.one("SELECT * FROM outbox_event WHERE event_id=?",message.eventId());
        if(source==null||!matchesSource(message,source))return;
        db.update("INSERT INTO message_ack_evidence(event_id,broker_message_id,delivery_attempt,duplicate_delivery) VALUES(?,?,?,?) ON DUPLICATE KEY UPDATE id=id",
                message.eventId(),brokerId,deliveryAttempt,duplicate?1:0);
    }

    public List<Map<String, Object>> deadLetters(long after, int limit) {
        if (after < 0 || limit < 1 || limit > 100) throw new BusinessException(400, "INVALID_REQUEST", "分页参数不正确");
        return db.rows("SELECT o.id,o.event_id,o.event_name,d.attempt_count,d.last_error,d.updated_at FROM message_delivery d JOIN outbox_event o ON o.event_id=d.event_id WHERE d.status='DEAD' AND o.id>? ORDER BY o.id LIMIT ?", after, limit);
    }

    private boolean owned(Claim claim, Map<String, Object> row) {
        return row != null && "PROCESSING".equals(Db.str(row, "status")) && owner.equals(Db.str(row, "lease_owner")) && claim.token == Db.num(row, "lease_token");
    }

    private static boolean matchesSource(WorkflowMessage message, Map<String, Object> source) {
        try { return message.hash().equals(WorkflowMessage.fromOutbox(source).hash()); }
        catch (RuntimeException invalid) { return false; }
    }
}
