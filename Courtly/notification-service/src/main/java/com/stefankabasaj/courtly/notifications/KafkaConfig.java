package com.stefankabasaj.courtly.notifications;

import com.fasterxml.jackson.core.JsonProcessingException;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
public class KafkaConfig {

    // the topic we read. the API declares the same one - whichever service starts
    // first creates it, and both ask for the same 3 partitions. if only the API
    // created it, this service could subscribe before it exists
    @Bean
    public NewTopic bookingEventsTopic(@Value("${app.events.topic}") String topic) {
        return TopicBuilder.name(topic).partitions(3).replicas(1).build();
    }

    // where messages go when they can't be handled. kept so nothing silently
    // disappears - they can be inspected and replayed by hand
    @Bean
    public NewTopic deadLetterTopic(@Value("${app.events.topic}") String topic) {
        return TopicBuilder.name(dltName(topic)).partitions(1).replicas(1).build();
    }

    /**
     * A failed send (mail server down) is retried 3 times, 2s apart, then the
     * message goes to the DLT so it doesn't block every event behind it.
     * Broken JSON or a missing eventId will never succeed, so those skip the
     * retries. Spring Boot hooks this bean into the listener container.
     */
    @Bean
    public DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<?, ?> template) {
        var recoverer = new DeadLetterPublishingRecoverer(template,
                // partition -1: let Kafka pick, the DLT has a single partition
                (record, ex) -> new TopicPartition(dltName(record.topic()), -1));
        var handler = new DefaultErrorHandler(recoverer, new FixedBackOff(2_000L, 3L));
        handler.addNotRetryableExceptions(JsonProcessingException.class, InvalidEventException.class);
        return handler;
    }

    static String dltName(String topic) {
        return topic + ".DLT";
    }
}
