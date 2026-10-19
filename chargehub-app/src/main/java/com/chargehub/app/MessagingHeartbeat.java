package com.chargehub.app;

import com.chargehub.infrastructure.MessageDeliveryStore;
import org.apache.rocketmq.client.apis.consumer.SimpleConsumer;
import org.apache.rocketmq.client.apis.message.MessageView;
import org.slf4j.*;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

/** Extends a live claim during long bounded handlers, with no renewal possible after close returns. */
public final class MessagingHeartbeat implements AutoCloseable {
    private static final Logger LOG=LoggerFactory.getLogger(MessagingHeartbeat.class);
    @FunctionalInterface interface VisibilityRenewal {void renew() throws Exception;}
    private final Object gate=new Object();
    private final ScheduledExecutorService executor;
    private final BooleanSupplier databaseRenewal;
    private final VisibilityRenewal visibilityRenewal;
    private volatile boolean lost;
    private boolean closed;

    public MessagingHeartbeat(MessageDeliveryStore store,MessageDeliveryStore.Claim claim,SimpleConsumer consumer,MessageView wire,int leaseSeconds,int invisibleSeconds) {
        this(()->store.renew(claim,leaseSeconds),()->consumer.changeInvisibleDuration(wire,Duration.ofSeconds(invisibleSeconds)),Duration.ofMillis(leaseSeconds*1000L/3));
    }
    MessagingHeartbeat(BooleanSupplier databaseRenewal,VisibilityRenewal visibilityRenewal,Duration interval) {
        this.databaseRenewal=databaseRenewal;this.visibilityRenewal=visibilityRenewal;
        if(interval.isZero()||interval.isNegative())throw new IllegalArgumentException("Heartbeat interval must be positive");
        executor=Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon(true).name("chargehub-message-heartbeat").factory());
        executor.scheduleWithFixedDelay(this::tick,interval.toMillis(),interval.toMillis(),TimeUnit.MILLISECONDS);
    }
    private void tick() {
        synchronized(gate) {
            if(closed||lost)return;
            try {
                if(!databaseRenewal.getAsBoolean()){lost=true;return;}
                visibilityRenewal.renew();
            }catch(Exception failure){LOG.warn("Messaging heartbeat deferred errorType={}",failure.getClass().getSimpleName());}
        }
    }
    public boolean leaseLost(){return lost;}
    @Override public void close(){synchronized(gate){closed=true;}executor.shutdownNow();}
}
