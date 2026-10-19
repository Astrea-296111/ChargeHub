package com.chargehub.app;

import com.chargehub.infrastructure.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.mock.env.MockEnvironment;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

/** Real MySQL transaction/lease/tamper gate, separate from actual broker crash tests in scripts/mq-faults.py. */
@EnabledIfEnvironmentVariable(named="CHARGEHUB_MESSAGING_TEST_JDBC_URL",matches=".+")
class MessageMysqlTest {
    @Test void inboxProofRollbackFencingDedupTamperAndAuditedReplayUseOriginalEvent() throws Exception {
        var source=new DriverManagerDataSource(System.getenv("CHARGEHUB_MESSAGING_TEST_JDBC_URL"),
                System.getenv().getOrDefault("CHARGEHUB_MESSAGING_TEST_DB_USER","chargehub"),System.getenv("CHARGEHUB_MESSAGING_TEST_DB_PASSWORD"));
        var jdbc=new JdbcTemplate(source);var db=new Db(jdbc);var tm=new DataSourceTransactionManager(source);
        try(var connection=source.getConnection()){
            assertEquals("MySQL",connection.getMetaData().getDatabaseProductName());assertTrue(connection.getMetaData().getDatabaseMajorVersion()>=8);
            assertTrue(connection.getCatalog().endsWith("_test"),"Refuse a non-test database");
            assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()",Integer.class),"Use a fresh database; no existing records are erased");
        }
        Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate();
        jdbc.execute("CREATE TABLE message_test_effect(event_id CHAR(36) PRIMARY KEY,proof_value INT NOT NULL)");
        var env=new MockEnvironment().withProperty("chargehub.messaging.mode","rocketmq");
        var outbox=new Outbox(db,tm,env);var first=new MessageDeliveryStore(db,tm);var second=new MessageDeliveryStore(db,tm);
        WorkflowMessage event=enqueue(db,outbox);
        var claim=first.claim(event,"fixture-broker-1","chargehub-workflow-v1",30);assertEquals(MessageDeliveryStore.State.CLAIMED,claim.state());
        assertEquals(MessageDeliveryStore.State.BUSY,second.claim(event,"fixture-broker-duplicate","chargehub-workflow-v1",30).state());
        jdbc.update("UPDATE message_delivery SET lease_until=TIMESTAMPADD(SECOND,-1,UTC_TIMESTAMP(3)) WHERE event_id=?",event.eventId());
        var takeover=second.claim(event,"fixture-broker-redelivery","chargehub-workflow-v1",30);assertEquals(claim.token()+1,takeover.token());
        assertFalse(first.renew(claim,30));assertTrue(second.renew(takeover,30));
        AtomicBoolean staleProofCalled=new AtomicBoolean();
        assertFalse(first.complete(claim,()->{staleProofCalled.set(true);return Map.of("test",true);}));assertFalse(staleProofCalled.get());
        assertFalse(first.failed(claim,new IllegalStateException("stale"),1));
        assertThrows(IllegalStateException.class,()->second.complete(takeover,()->{jdbc.update("INSERT INTO message_test_effect VALUES(?,1)",event.eventId());throw new IllegalStateException("proof rollback");}));
        assertEquals(0,count(db,"consumer_inbox"));assertEquals(0,count(db,"message_test_effect"));
        assertTrue(second.complete(takeover,()->{jdbc.update("INSERT INTO message_test_effect VALUES(?,1)",event.eventId());return Map.of("persistedEffect",true);}));
        assertEquals(1,count(db,"consumer_inbox"));assertEquals(1,count(db,"message_test_effect"));
        assertFalse(second.renew(takeover,30));
        assertEquals("DONE",Db.str(db.one("SELECT status FROM outbox_event WHERE event_id=?",event.eventId()),"status"));
        assertEquals(MessageDeliveryStore.State.COMPLETED,first.claim(event,"fixture-redelivery-after-ack-loss","chargehub-workflow-v1",30).state());

        var forged=new WorkflowMessage(event.eventId(),event.eventName(),event.schemaVersion(),event.aggregateType(),event.aggregateId(),event.aggregateVersion(),event.traceId(),event.occurredAt(),Map.of("orderNo",UUID.randomUUID().toString()));
        assertEquals(MessageDeliveryStore.State.REJECTED,first.claim(forged,"fixture-forged","chargehub-workflow-v1",30).state());
        assertEquals(1,count(db,"consumer_inbox"));assertEquals(1,count(db,"message_test_effect"));

        WorkflowMessage tampered=enqueue(db,outbox);var tamperClaim=first.claim(tampered,"fixture-before-source-change","chargehub-workflow-v1",30);
        jdbc.update("UPDATE outbox_event SET payload=? WHERE event_id=?",Json.write(Map.of("orderNo",UUID.randomUUID().toString())),tampered.eventId());
        assertFalse(first.complete(tamperClaim,()->Map.of("notAllowed",true)));
        assertFalse(first.failed(tamperClaim,new IllegalStateException("no source authority"),1));
        assertFalse(first.renew(tamperClaim,30));

        WorkflowMessage poison=enqueue(db,outbox);String original=Db.str(db.one("SELECT payload FROM outbox_event WHERE event_id=?",poison.eventId()),"payload");
        var failing=first.claim(poison,"fixture-poison","chargehub-workflow-v1",30);assertTrue(first.failed(failing,new IllegalStateException("controlled local failure"),1));
        assertEquals("DEAD",Db.str(db.one("SELECT status FROM outbox_event WHERE event_id=?",poison.eventId()),"status"));
        outbox.replay(poison.eventId(),77,"隔离测试：按原事件号人工审计重放");
        assertEquals(original,Db.str(db.one("SELECT payload FROM outbox_event WHERE event_id=?",poison.eventId()),"payload"));
        assertEquals(1,Db.num(db.one("SELECT COUNT(*) n FROM ops_audit WHERE action='OUTBOX_REPLAY' AND target_no=?",poison.eventId()),"n"));
        assertEquals("RETRY",Db.str(db.one("SELECT status FROM message_delivery WHERE event_id=?",poison.eventId()),"status"));
        var replay=second.claim(poison,"fixture-original-id-replay","chargehub-workflow-v1",30);assertTrue(replay.token()>failing.token());
        assertTrue(second.complete(replay,()->Map.of("auditedReplay",true)));assertEquals(2,count(db,"consumer_inbox"));
    }
    private static long count(Db db,String table){return Db.num(db.one("SELECT COUNT(*) n FROM "+table),"n");}
    private static WorkflowMessage enqueue(Db db,Outbox outbox){String order=UUID.randomUUID().toString();outbox.enqueue("PaymentCaptured",order,Map.of("orderNo",order));return WorkflowMessage.fromOutbox(db.one("SELECT * FROM outbox_event WHERE aggregate_id=?",order));}
}
