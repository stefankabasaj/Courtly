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
 * BOOKING_CREATED it follows. One publisher sending oldest-first, keyed by
 * booking id, keeps every booking's events in order.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxPublisher {

    static final int  BATCH_SIZE    = 100;
    static final long LOCK_KEY      = 4_201_001L;   // any constant, just has to be the same everywhere
    static final Duration KEEP_SENT = Duration.ofDays(7);

    private final OutboxEventRepository        outbox;
    private final KafkaTemplate<String, String> kafka;

    @Value("${app.events.topic}")
    private String topic;

    @Scheduled(fixedDelayString = "${app.events.publish-interval-ms:1000}")
    @Transactional
    public void publishPending() {
        if (!outbox.tryLock(LOCK_KEY)) {
            return;   // the other instance is on it
        }

        List<OutboxEvent> batch = outbox.findUnpublished(BATCH_SIZE);
        for (OutboxEvent event : batch) {
            try {
                // wait for the ack - marking it published on a send that later fails would lose the event
                kafka.send(topic, event.getAggregateId().toString(), event.getPayload())
                        .get(10, TimeUnit.SECONDS);
            } catch (Exception e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                // stop here and keep what's been sent so far. later rows stay
                // unpublished, so nothing jumps ahead of the one that failed
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

    // sent rows are only kept for debugging - a week is plenty
    @Scheduled(fixedDelay = 60 * 60 * 1000)
    @Transactional
    public void deleteOldSent() {
        int deleted = outbox.deletePublishedBefore(LocalDateTime.now().minus(KEEP_SENT));
        if (deleted > 0) {
            log.info("Deleted {} sent outbox event(s) older than {}", deleted, KEEP_SENT);
        }
    }
}
