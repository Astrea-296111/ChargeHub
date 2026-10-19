package com.chargehub.app;

import com.chargehub.infrastructure.*;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.rocketmq.client.apis.ClientServiceProvider;
import org.apache.rocketmq.client.apis.producer.Producer;
import org.slf4j.*;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.Scheduled;

public final class MessagingPublisher {
    private static final Logger LOG=LoggerFactory.getLogger(MessagingPublisher.class);
    private final Outbox outbox; private final Producer producer; private final ClientServiceProvider provider;
    private final MessagingFailpoints failpoints; private final MeterRegistry metrics; private final String topic; private final boolean enabled;
    public MessagingPublisher(Outbox outbox,Producer producer,ClientServiceProvider provider,Environment env,MessagingFailpoints failpoints,MeterRegistry metrics) {
        this.outbox=outbox;this.producer=producer;this.provider=provider;this.failpoints=failpoints;this.metrics=metrics;
        topic=env.getProperty("chargehub.messaging.topic","ChargeHubWorkflowV1"); enabled=env.getProperty("chargehub.messaging.publish-enabled",Boolean.class,true);
    }
    @Scheduled(fixedDelayString="${chargehub.messaging.publish-delay-ms:250}")
    public void publish() {
        if(!enabled)return;
        for(int i=0;i<10;i++) {
            var source=outbox.claim();if(source==null)return;
            WorkflowMessage message;
            try { message=WorkflowMessage.fromOutbox(source); }
            catch(IllegalArgumentException invalid) {outbox.dead(source,"INVALID_MESSAGE_METADATA");metrics.counter("chargehub.messaging.quarantined","phase","publisher").increment();continue;}
            try {
                var wire=provider.newMessageBuilder().setTopic(topic).setTag(message.eventName()).setKeys(message.eventId())
                        .addProperty("eventId",message.eventId()).addProperty("bodyHash",message.hash()).setBody(message.encode()).build();
                var receipt=producer.send(wire);
                failpoints.awaitExternalKill("AFTER_SEND_BEFORE_MARK",message.eventId());
                if(!outbox.published(source,receipt.getMessageId().toString(),message.hash()))metrics.counter("chargehub.messaging.lease.lost","phase","publish").increment();
                metrics.counter("chargehub.messaging.published").increment();
            } catch(Exception failure) {
                if(failure instanceof InterruptedException)Thread.currentThread().interrupt();
                outbox.fail(source,failure);metrics.counter("chargehub.messaging.failure","phase","publish").increment();
                LOG.warn("Broker publish deferred eventId={} errorType={}",message.eventId(),failure.getClass().getSimpleName());
            }
        }
    }
}
