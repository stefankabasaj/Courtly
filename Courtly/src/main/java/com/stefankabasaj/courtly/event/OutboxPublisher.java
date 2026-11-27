package com.stefankabasaj.courtly.event;

import com.stefankabasaj.courtly.entity.OutboxEvent;
import com.stefankabasaj.courtly.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Moves outbox rows to Kafka.
 *
 * At-least-once: a row is marked published only after Kafka acked it. If the
 * app dies between the ack and the commit, the row goes out again on the
 * next run - so consumers dedupe on eventId.
 *
 * Only one instance publishes at a time (advisory lock). With two instances
 * each grabbing a batch, BOOKING_CONFIRMED could reach Kafka before the
 * BOOKING_CREATED it follows. One publisher sending in insert order (seq), keyed by
 * booking id, keeps every booking's events in order.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxPublisher {

    static final int BATCH_SIZE = 100;
    static final long LOCK_KEY = 4_201_001L;   // Shared by every API instance.
    static final Duration KEEP_SENT = Duration.ofDays(7);

    private final OutboxEventRepository outbox;
    private final KafkaTemplate<String, String> kafka;

    @Value("${app.events.topic}")
    private String topic;

    @Scheduled(fixedDelayString = "${app.events.publish-interval-ms:1000}")
    @Transactional
    public void publishPending() {
        if (!outbox.tryLock(LOCK_KEY)) {
            return;
        }

        List<OutboxEvent> batch = outbox.findUnpublished(BATCH_SIZE);
        for (OutboxEvent event : batch) {
            try {
                // Mark published only after Kafka acknowledges the send.
                kafka.send(topic, event.getAggregateId().toString(), event.getPayload())
                        .get(10, TimeUnit.SECONDS);
            } catch (Exception e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                // Leave later events pending so they cannot overtake the failed event.
                log.warn("Kafka send failed for event {} ({}), retrying next run: {}",
                        event.getId(), event.getEventType(), e.getMessage());
                return;
            }
            event.setPublishedAt(LocalDateTime.now());
        }

        if (!batch.isEmpty()) {
            log.debug("Published {} event(s)", batch.size());
        }
    }

    // Retain published events temporarily for troubleshooting.
    @Scheduled(fixedDelay = 60 * 60 * 1000)
    @Transactional
    public void deleteOldSent() {
        int deleted = outbox.deletePublishedBefore(LocalDateTime.now().minus(KEEP_SENT));
        if (deleted > 0) {
            log.info("Deleted {} sent outbox event(s) older than {}", deleted, KEEP_SENT);
        }
    }
}
