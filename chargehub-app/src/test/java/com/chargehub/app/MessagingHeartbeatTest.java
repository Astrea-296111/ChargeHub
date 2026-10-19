package com.chargehub.app;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class MessagingHeartbeatTest {
    @Test void longHandlerKeepsDatabaseAndBrokerLeasesAliveThenStopsAllRenewals() throws Exception {
        var database=new AtomicInteger();var broker=new AtomicInteger();var renewed=new CountDownLatch(3);
        var heartbeat=new MessagingHeartbeat(()->{database.incrementAndGet();return true;},()->{broker.incrementAndGet();renewed.countDown();},Duration.ofMillis(15));
        assertTrue(renewed.await(2,TimeUnit.SECONDS));heartbeat.close();
        int beforeDatabase=database.get(),beforeBroker=broker.get();Thread.sleep(60);
        assertEquals(beforeDatabase,database.get());assertEquals(beforeBroker,broker.get());assertFalse(heartbeat.leaseLost());
    }
    @Test void lostTokenStopsBrokerVisibilityExtensionsAndCannotReviveClaim() throws Exception {
        var database=new AtomicInteger();var broker=new AtomicInteger();var attempted=new CountDownLatch(1);
        try(var heartbeat=new MessagingHeartbeat(()->{database.incrementAndGet();attempted.countDown();return false;},broker::incrementAndGet,Duration.ofMillis(10))) {
            assertTrue(attempted.await(2,TimeUnit.SECONDS));Thread.sleep(50);
            assertTrue(heartbeat.leaseLost());assertEquals(1,database.get());assertEquals(0,broker.get());
        }
    }
    @Test void transientBrokerVisibilityFailureDoesNotAbandonDatabaseLeaseRenewal() throws Exception {
        var renewals=new CountDownLatch(3);var broker=new AtomicInteger();
        try(var heartbeat=new MessagingHeartbeat(()->true,()->{renewals.countDown();if(broker.incrementAndGet()==1)throw new IllegalStateException("controlled transient transport failure");},Duration.ofMillis(10))) {
            assertTrue(renewals.await(2,TimeUnit.SECONDS));assertFalse(heartbeat.leaseLost());assertTrue(broker.get()>=3);
        }
    }
}
