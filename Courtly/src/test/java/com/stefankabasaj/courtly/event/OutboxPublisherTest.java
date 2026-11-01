package com.stefankabasaj.courtly.event;

import com.stefankabasaj.courtly.entity.OutboxEvent;
import com.stefankabasaj.courtly.repository.OutboxEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("OutboxPublisher")
class OutboxPublisherTest {

    private static final String TOPIC = "courtly.booking-events";

    @Mock OutboxEventRepository outbox;
    @Mock KafkaTemplate<String, String> kafka;

    private OutboxPublisher publisher;

    @BeforeEach
    void setUp() {
        publisher = new OutboxPublisher(outbox, kafka);
        ReflectionTestUtils.setField(publisher, "topic", TOPIC);
    }

    private OutboxEvent event(String type) {
        return OutboxEvent.builder().id(UUID.randomUUID()).aggregateId(UUID.randomUUID())
                .eventType(type).payload("{\"type\":\"" + type + "\"}")
                .createdAt(LocalDateTime.now()).build();
    }

    private static CompletableFuture<SendResult<String, String>> acked() {
        return CompletableFuture.completedFuture(null);
    }

    @Test
    @DisplayName("sends each event keyed by booking id and marks it published")
    void publishes() {
        OutboxEvent created = event("BOOKING_CREATED");
        OutboxEvent confirmed = event("BOOKING_CONFIRMED");
        when(outbox.tryLock(OutboxPublisher.LOCK_KEY)).thenReturn(true);
        when(outbox.findUnpublished(OutboxPublisher.BATCH_SIZE)).thenReturn(List.of(created, confirmed));
        when(kafka.send(anyString(), anyString(), anyString())).thenReturn(acked());

        publisher.publishPending();

        verify(kafka).send(TOPIC, created.getAggregateId().toString(), created.getPayload());
        verify(kafka).send(TOPIC, confirmed.getAggregateId().toString(), confirmed.getPayload());
        assertThat(created.getPublishedAt()).isNotNull();
        assertThat(confirmed.getPublishedAt()).isNotNull();
    }

    @Test
    @DisplayName("stops at the first failed send - nothing after it goes out or gets marked")
    void stopsOnFailure() {
        OutboxEvent first = event("BOOKING_CREATED");
        OutboxEvent second = event("BOOKING_CONFIRMED");
        OutboxEvent third = event("BOOKING_CANCELLED");
        when(outbox.tryLock(OutboxPublisher.LOCK_KEY)).thenReturn(true);
        when(outbox.findUnpublished(anyInt())).thenReturn(List.of(first, second, third));
        when(kafka.send(eq(TOPIC), eq(first.getAggregateId().toString()), anyString())).thenReturn(acked());
        when(kafka.send(eq(TOPIC), eq(second.getAggregateId().toString()), anyString()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("broker down")));

        publisher.publishPending();

        assertThat(first.getPublishedAt()).isNotNull();
        assertThat(second.getPublishedAt()).isNull();
        assertThat(third.getPublishedAt()).isNull();
        verify(kafka, never()).send(eq(TOPIC), eq(third.getAggregateId().toString()), anyString());
    }

    @Test
    @DisplayName("another instance holds the lock - does nothing")
    void lockTaken() {
        when(outbox.tryLock(OutboxPublisher.LOCK_KEY)).thenReturn(false);

        publisher.publishPending();

        verify(outbox, never()).findUnpublished(anyInt());
        verifyNoInteractions(kafka);
    }

    @Test
    @DisplayName("cleanup deletes sent events older than a week")
    void cleanup() {
        when(outbox.deletePublishedBefore(any())).thenReturn(3);

        LocalDateTime before = LocalDateTime.now();
        publisher.deleteOldSent();

        LocalDateTime weekAgo = before.minusDays(7);
        verify(outbox).deletePublishedBefore(argThat(cutoff ->
                !cutoff.isBefore(weekAgo) && cutoff.isBefore(weekAgo.plusSeconds(5))));
    }
}
