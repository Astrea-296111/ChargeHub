/* Uses the public APIs demonstrated by Apache RocketMQ's Apache-2.0 licensed
 * ProducerNormalMessageExample and SimpleConsumerExample at client tag java-5.2.2,
 * commit 6e2185bb638b639663bda240cf119804a595e099. No Spring context or business data. */
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import org.apache.rocketmq.client.apis.*;
import org.apache.rocketmq.client.apis.consumer.*;
import org.apache.rocketmq.client.apis.producer.*;

public final class RocketMqSmoke {
    public static void main(String[] args) throws Exception {
        String endpoints = args.length > 0 ? args[0] : "mq-broker:8081";
        String topic = "ChargeHubSmoke", key = UUID.randomUUID().toString();
        byte[] expected = ("ChargeHub native Java 5 smoke " + key).getBytes(StandardCharsets.UTF_8);
        var provider = ClientServiceProvider.loadService();
        var config = ClientConfiguration.newBuilder().setEndpoints(endpoints).enableSsl(false)
                .setRequestTimeout(Duration.ofSeconds(10)).build();
        try (var consumer = provider.newSimpleConsumerBuilder().setClientConfiguration(config)
                .setConsumerGroup("chargehub-smoke").setAwaitDuration(Duration.ofSeconds(2))
                .setSubscriptionExpressions(Map.of(topic, new FilterExpression("*", FilterExpressionType.TAG))).build();
             var producer = provider.newProducerBuilder().setClientConfiguration(config).setTopics(topic).build()) {
            var message = provider.newMessageBuilder().setTopic(topic).setTag("v1").setKeys(key).setBody(expected).build();
            var sent = producer.send(message);
            long deadline = System.nanoTime() + Duration.ofSeconds(50).toNanos();
            String received = null;
            int attempt = 0;
            while (System.nanoTime() < deadline && received == null) {
                for (var view : consumer.receive(8, Duration.ofSeconds(15))) {
                    byte[] bytes = new byte[view.getBody().remaining()]; view.getBody().get(bytes);
                    if (view.getKeys().contains(key)) {
                        if (!Arrays.equals(expected, bytes)) throw new IllegalStateException("Broker payload changed");
                        received = view.getMessageId().toString(); attempt = view.getDeliveryAttempt();
                    }
                    consumer.ack(view);
                }
            }
            if (received == null || !received.equals(sent.getMessageId().toString())) throw new IllegalStateException("No matching broker delivery");
            System.out.println("{\"nativeClient\":\"5.2.2\",\"broker\":\"5.5.0\",\"topic\":\"" + topic
                    + "\",\"eventKey\":\"" + key + "\",\"brokerMessageId\":\"" + received
                    + "\",\"deliveryAttempt\":" + attempt + ",\"bodyVerified\":true,\"acknowledged\":true}");
        }
    }
}
