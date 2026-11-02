package com.stefankabasaj.courtly.config;

import com.stefankabasaj.courtly.repository.OutboxEventRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.time.LocalDateTime;

@Configuration
public class MetricsConfig {

    /**
     * How long the oldest unsent outbox event has been waiting, in seconds (0 when
     * there's none). The one number that says whether booking emails are flowing:
     * normally a second or two, growing steadily means Kafka or the publisher is
     * stuck. Read from the DB on each scrape - an index-only query on the partial
     * index, so it's cheap, and both instances report the same value.
     */
    @Bean
    public MeterBinder outboxLagMetric(OutboxEventRepository outbox) {
        return registry -> Gauge.builder("courtly.outbox.oldest.unsent.age", outbox, repo -> repo.oldestUnpublished()
                        .map(oldest -> (double) Duration.between(oldest, LocalDateTime.now()).toSeconds())
                        .orElse(0.0))
                .description("Age of the oldest booking event not yet sent to Kafka")
                .baseUnit("seconds")
                .register(registry);
    }
}
