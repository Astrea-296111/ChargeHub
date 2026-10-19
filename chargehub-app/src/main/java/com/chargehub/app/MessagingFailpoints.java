package com.chargehub.app;

import com.chargehub.infrastructure.Db;
import com.chargehub.infrastructure.WorkflowMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.*;
import java.util.Set;

/** Opt-in dev-only barriers. A fault driver observes the row, then kills the process externally. */
public final class MessagingFailpoints {
    private static final Logger LOG = LoggerFactory.getLogger(MessagingFailpoints.class);
    private final Db db;
    private final String phase, eventId, instance, orderNo, eventName;
    public MessagingFailpoints(Db db, Environment env) {
        this.db=db; phase=env.getProperty("chargehub.messaging.failpoint-phase", "");
        eventId=env.getProperty("chargehub.messaging.failpoint-event-id", "");
        orderNo=env.getProperty("chargehub.messaging.failpoint-order-no", "");
        eventName=env.getProperty("chargehub.messaging.failpoint-event-name", "");
        instance=env.getProperty("HOSTNAME", java.util.UUID.randomUUID().toString());
        if (!phase.isBlank() && (!env.acceptsProfiles(Profiles.of("dev", "test")) || !Set.of("AFTER_SEND_BEFORE_MARK", "AFTER_BUSINESS_BEFORE_INBOX", "AFTER_INBOX_BEFORE_ACK", "FAIL_BEFORE_BUSINESS").contains(phase)
                || !(eventId.matches("[0-9a-f-]{36}") || orderNo.matches("[0-9a-f-]{36}")&&WorkflowMessage.EVENT_NAMES.contains(eventName)))) throw new IllegalStateException("Messaging failpoints require an explicit dev/test phase and event or order/event-name target");
    }
    public void beforeBusiness(String currentEvent) {
        if(!phase.equals("FAIL_BEFORE_BUSINESS")||!matches(currentEvent))return;
        db.update("INSERT INTO message_fault_observation(event_id,phase,instance_id) VALUES(?,?,?) ON DUPLICATE KEY UPDATE id=id",currentEvent,phase,instance);
        throw new IllegalStateException("Explicit dev-only message failure");
    }
    public void awaitExternalKill(String currentPhase, String currentEvent) throws InterruptedException {
        if (!phase.equals(currentPhase) || !matches(currentEvent)) return;
        db.update("INSERT INTO message_fault_observation(event_id,phase,instance_id) VALUES(?,?,?) ON DUPLICATE KEY UPDATE id=id", currentEvent, phase, instance);
        LOG.warn("Messaging fault barrier reached phase={} eventId={} instance={}", phase, currentEvent, instance);
        while (!Thread.currentThread().isInterrupted()) Thread.sleep(1000);
        throw new InterruptedException("Messaging fault barrier interrupted");
    }
    private boolean matches(String currentEvent) {
        if(!eventId.isBlank())return eventId.equals(currentEvent);
        if(orderNo.isBlank())return false;
        var source=db.one("SELECT event_name,JSON_UNQUOTE(JSON_EXTRACT(payload,'$.orderNo')) AS order_no FROM outbox_event WHERE event_id=?",currentEvent);
        return source!=null&&orderNo.equals(Db.str(source,"order_no"))&&eventName.equals(Db.str(source,"event_name"));
    }
}
