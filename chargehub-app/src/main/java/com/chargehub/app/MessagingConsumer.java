package com.chargehub.app;

import com.chargehub.infrastructure.*;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.rocketmq.client.apis.consumer.SimpleConsumer;
import org.apache.rocketmq.client.apis.message.MessageView;
import org.slf4j.*;
import org.springframework.context.SmartLifecycle;
import org.springframework.core.env.Environment;
import java.time.Duration;

/** Ack is the last step. A crash at either local-commit boundary permits safe redelivery. */
public final class MessagingConsumer implements SmartLifecycle {
    private static final Logger LOG=LoggerFactory.getLogger(MessagingConsumer.class);
    private final SimpleConsumer consumer;private final MessageDeliveryStore store;private final WorkflowMessageHandler handler;
    private final MessagingCompletionProof proof;private final MessagingFailpoints failpoints;private final MeterRegistry metrics;
    private final String group;private final int leaseSeconds,invisibleSeconds,maxAttempts;private final boolean enabled;
    private volatile boolean running;private Thread thread;
    public MessagingConsumer(SimpleConsumer consumer,MessageDeliveryStore store,WorkflowMessageHandler handler,MessagingCompletionProof proof,MessagingFailpoints failpoints,Environment env,MeterRegistry metrics) {
        this.consumer=consumer;this.store=store;this.handler=handler;this.proof=proof;this.failpoints=failpoints;this.metrics=metrics;
        group=env.getProperty("chargehub.messaging.consumer-group","chargehub-workflow-v1");leaseSeconds=env.getProperty("chargehub.messaging.lease-seconds",Integer.class,30);
        invisibleSeconds=env.getProperty("chargehub.messaging.invisible-seconds",Integer.class,45);maxAttempts=env.getProperty("chargehub.messaging.max-attempts",Integer.class,3);
        enabled=env.getProperty("chargehub.messaging.consume-enabled",Boolean.class,true);
        if(leaseSeconds<5||leaseSeconds>300||invisibleSeconds<leaseSeconds||invisibleSeconds>600||maxAttempts<1||maxAttempts>16)throw new IllegalStateException("Invalid messaging lease, visibility or retry configuration");
    }
    @Override public void start(){if(!enabled)return;running=true;thread=Thread.ofPlatform().daemon(true).name("chargehub-message-consumer").start(this::poll);}
    private void poll(){while(running&&!Thread.currentThread().isInterrupted())try{for(var message:consumer.receive(1,Duration.ofSeconds(invisibleSeconds)))consume(message);}catch(Exception failure){if(!running)return;LOG.warn("Broker receive deferred errorType={}",failure.getClass().getSimpleName());try{Thread.sleep(1000);}catch(InterruptedException stop){Thread.currentThread().interrupt();}}}
    private void consume(MessageView wire)throws Exception {
        byte[] body=new byte[wire.getBody().remaining()];wire.getBody().get(body);String brokerId=wire.getMessageId().toString();WorkflowMessage message;
        try{message=WorkflowMessage.decode(body);if(!wire.getKeys().contains(message.eventId())||!wire.getTag().orElse("").equals(message.eventName())||!message.hash().equals(wire.getProperties().get("bodyHash")))throw new IllegalArgumentException("Invalid broker metadata");}
        catch(RuntimeException invalid){store.quarantine(brokerId,body,null,"INVALID_MESSAGE_METADATA");consumer.ack(wire);metrics.counter("chargehub.messaging.quarantined","phase","consumer").increment();return;}
        var claim=store.claim(message,brokerId,group,leaseSeconds);
        switch(claim.state()) {
            case REJECTED -> {store.quarantine(brokerId,body,message.eventId(),"OUTBOX_SOURCE_MISMATCH");consumer.ack(wire);return;}
            case COMPLETED -> {consumer.ack(wire);store.acknowledged(message,brokerId,wire.getDeliveryAttempt(),true);metrics.counter("chargehub.messaging.duplicate").increment();return;}
            case DEAD,BUSY -> {return;} // No Ack: allow the broker visibility timeout / DLQ policy to run.
            case CLAIMED -> {}
        }
        boolean committed=false;
        var heartbeat=new MessagingHeartbeat(store,claim,consumer,wire,leaseSeconds,invisibleSeconds);
        try {
            failpoints.beforeBusiness(message.eventId());
            handler.handle(message.eventName(),message.payload());
            failpoints.awaitExternalKill("AFTER_BUSINESS_BEFORE_INBOX",message.eventId());
            heartbeat.close();
            if(heartbeat.leaseLost()){metrics.counter("chargehub.messaging.lease.lost","phase","heartbeat").increment();return;}
            committed=store.complete(claim,()->proof.read(message));
            if(!committed){metrics.counter("chargehub.messaging.lease.lost","phase","consume").increment();return;}
            failpoints.awaitExternalKill("AFTER_INBOX_BEFORE_ACK",message.eventId());
            consumer.ack(wire);store.acknowledged(message,brokerId,wire.getDeliveryAttempt(),false);metrics.counter("chargehub.messaging.consumed").increment();
        }catch(Exception failure){
            heartbeat.close();
            if(failure instanceof InterruptedException)Thread.currentThread().interrupt();
            if(!committed&&store.failed(claim,failure,maxAttempts))metrics.counter("chargehub.messaging.dead").increment();
            metrics.counter("chargehub.messaging.failure","phase",committed?"ack":"business").increment();
            LOG.warn("Message deferred eventId={} attempt={} errorType={}",message.eventId(),claim.attempt(),failure.getClass().getSimpleName());
        }finally{heartbeat.close();}
    }
    @Override public void stop(){running=false;if(thread!=null)thread.interrupt();}
    @Override public boolean isRunning(){return running;}
    @Override public int getPhase(){return Integer.MAX_VALUE-100;}
}
