package mx.surtido.messaging;

import org.apache.kafka.clients.admin.NewTopic;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.util.backoff.ExponentialBackOff;
import mx.surtido.contracts.Topics;

@Configuration
@EnableScheduling
public class MessagingConfiguration {
    private static final Logger log = LoggerFactory.getLogger(MessagingConfiguration.class);

    @Bean
    NewTopic ordersTopic() {
        return TopicBuilder.name(Topics.ORDERS).partitions(3).build();
    }

    @Bean
    NewTopic inventoryTopic() {
        return TopicBuilder.name(Topics.INVENTORY).partitions(3).build();
    }

    @Bean
    NewTopic paymentsTopic() {
        return TopicBuilder.name(Topics.PAYMENTS).partitions(3).build();
    }

    @Bean
    NewTopic shippingTopic() {
        return TopicBuilder.name(Topics.SHIPPING).partitions(3).build();
    }

    /**
     * A handler that keeps failing is retried with backoff (0.5 s, 1 s, 2 s, 4 s),
     * then the message moves to `<topic>-dlt` instead of blocking its partition.
     */
    @Bean
    DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> template) {
        var backOff = new ExponentialBackOff(500, 2.0);
        backOff.setMaxElapsedTime(8_000);
        var recoverer = new DeadLetterPublishingRecoverer(template);
        var handler = new DefaultErrorHandler(
                (record, error) -> {
                    log.error("message {} on {} sent to the dead-letter topic", record.key(), record.topic(), error);
                    recoverer.accept(record, error);
                },
                backOff);
        handler.addNotRetryableExceptions(IllegalArgumentException.class);
        return handler;
    }
}
