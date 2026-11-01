package com.stefankabasaj.courtly.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaConfig {

    // created on startup if it doesn't exist. 3 partitions so a second consumer
    // instance would have work to share; events are keyed by booking id, so one
    // booking's events always land in the same partition, in order
    @Bean
    public NewTopic bookingEventsTopic(@Value("${app.events.topic}") String topic) {
        return TopicBuilder.name(topic).partitions(3).replicas(1).build();
    }
}
