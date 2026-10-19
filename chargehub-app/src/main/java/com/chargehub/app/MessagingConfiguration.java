package com.chargehub.app;

import com.chargehub.infrastructure.*;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.rocketmq.client.apis.*;
import org.apache.rocketmq.client.apis.consumer.*;
import org.apache.rocketmq.client.apis.producer.Producer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;

import java.time.Duration;
import java.util.Map;

/** Native Java client only; the default db mode does not initialize a broker connection. */
@Configuration
@Profile("worker")
@ConditionalOnProperty(name = "chargehub.messaging.mode", havingValue = "rocketmq")
public class MessagingConfiguration {
    @Bean ClientServiceProvider messagingClientProvider() { return ClientServiceProvider.loadService(); }
    @Bean ClientConfiguration messagingClientConfiguration(Environment env) {
        var builder = ClientConfiguration.newBuilder().setEndpoints(env.getProperty("chargehub.messaging.endpoints", "mq-broker:8081"))
                .enableSsl(env.getProperty("chargehub.messaging.tls-enabled", Boolean.class, true))
                .setRequestTimeout(Duration.ofSeconds(10));
        String access = env.getProperty("chargehub.messaging.access-key"), secret = env.getProperty("chargehub.messaging.secret-key");
        if (access != null || secret != null) {
            if (access == null || access.isBlank() || secret == null || secret.isBlank()) throw new IllegalStateException("Both messaging credential bindings are required");
            builder.setCredentialProvider(new StaticSessionCredentialsProvider(access, secret));
        }
        return builder.build();
    }
    @Bean(destroyMethod = "close") Producer messagingProducer(ClientServiceProvider provider, ClientConfiguration configuration, Environment env) throws ClientException {
        return provider.newProducerBuilder().setClientConfiguration(configuration)
                .setTopics(env.getProperty("chargehub.messaging.topic", "ChargeHubWorkflowV1")).setMaxAttempts(3).build();
    }
    @Bean(destroyMethod = "close") SimpleConsumer messagingSimpleConsumer(ClientServiceProvider provider, ClientConfiguration configuration, Environment env) throws ClientException {
        return provider.newSimpleConsumerBuilder().setClientConfiguration(configuration)
                .setConsumerGroup(env.getProperty("chargehub.messaging.consumer-group", "chargehub-workflow-v1"))
                .setAwaitDuration(Duration.ofSeconds(2)).setSubscriptionExpressions(Map.of(
                        env.getProperty("chargehub.messaging.topic", "ChargeHubWorkflowV1"), new FilterExpression("*", FilterExpressionType.TAG))).build();
    }
    @Bean WorkflowMessageHandler messagingHandler(WorkflowWorker worker) { return worker::dispatch; }
    @Bean MessagingCompletionProof messagingCompletionProof(Db db) { return new MessagingCompletionProof(db); }
    @Bean MessagingFailpoints messagingFailpoints(Db db, Environment env) { return new MessagingFailpoints(db, env); }
    @Bean MessagingPublisher messagingPublisher(Outbox outbox, Producer producer, ClientServiceProvider provider, Environment env, MessagingFailpoints failpoints, MeterRegistry metrics) {
        return new MessagingPublisher(outbox, producer, provider, env, failpoints, metrics);
    }
    @Bean MessagingConsumer messagingConsumer(SimpleConsumer consumer, MessageDeliveryStore store, WorkflowMessageHandler handler,
                                             MessagingCompletionProof proof, MessagingFailpoints failpoints, Environment env, MeterRegistry metrics) {
        return new MessagingConsumer(consumer, store, handler, proof, failpoints, env, metrics);
    }
}
