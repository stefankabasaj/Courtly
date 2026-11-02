package com.stefankabasaj.courtly.repository;

import com.stefankabasaj.courtly.entity.OutboxEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("OutboxEventRepository on PostgreSQL")
class OutboxEventRepositoryPostgresTest extends PostgresRepositoryTest {

    private static final long LOCK_KEY = 4_201_001L;

    @Autowired OutboxEventRepository outbox;
    @Autowired PlatformTransactionManager txManager;

    private OutboxEvent event(LocalDateTime createdAt, LocalDateTime publishedAt) {
        return OutboxEvent.builder()
                .id(UUID.randomUUID()).aggregateId(UUID.randomUUID())
                .eventType("BOOKING_CREATED").payload("{}")
                .createdAt(createdAt).publishedAt(publishedAt)
                .build();
    }

    @Test
    @DisplayName("findUnpublished - unpublished only, oldest first, up to the limit")
    void unpublishedOldestFirst() {
        LocalDateTime now = LocalDateTime.now();
        OutboxEvent second = outbox.saveAndFlush(event(now.minusMinutes(1), null));
        OutboxEvent first  = outbox.saveAndFlush(event(now.minusMinutes(2), null));
        outbox.saveAndFlush(event(now.minusMinutes(3), now));   // already sent
        OutboxEvent third  = outbox.saveAndFlush(event(now, null));

        assertThat(outbox.findUnpublished(10)).extracting(OutboxEvent::getId)
                .containsSubsequence(first.getId(), second.getId(), third.getId());
        assertThat(outbox.findUnpublished(1)).hasSize(1);
    }

    @Test
    @DisplayName("deletePublishedBefore - removes old sent rows, never unsent ones")
    void cleanup() {
        LocalDateTime now = LocalDateTime.now();
        OutboxEvent oldSent = outbox.saveAndFlush(event(now.minusDays(10), now.minusDays(9)));
        OutboxEvent newSent = outbox.saveAndFlush(event(now.minusDays(1), now.minusDays(1)));
        OutboxEvent unsent  = outbox.saveAndFlush(event(now.minusDays(10), null));

        outbox.deletePublishedBefore(now.minusDays(7));

        assertThat(outbox.findById(oldSent.getId())).isEmpty();
        assertThat(outbox.findById(newSent.getId())).isPresent();
        assertThat(outbox.findById(unsent.getId())).isPresent();
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)   // needs real, separate transactions
    @DisplayName("advisory lock - only one transaction at a time gets it, and it's free again after commit")
    void advisoryLock() throws Exception {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        CountDownLatch locked  = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        // instance 1 takes the lock and stays in its transaction
        CompletableFuture<Boolean> first = CompletableFuture.supplyAsync(() -> tx.execute(s -> {
            boolean got = outbox.tryLock(LOCK_KEY);
            locked.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return got;
        }));
        assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

        // instance 2 asks while it's held - gets "no" straight away, doesn't wait
        Boolean second = tx.execute(s -> outbox.tryLock(LOCK_KEY));
        release.countDown();

        assertThat(first.get(10, TimeUnit.SECONDS)).isTrue();
        assertThat(second).isFalse();

        // the lock ended with instance 1's transaction
        assertThat(tx.execute(s -> outbox.tryLock(LOCK_KEY))).isTrue();
    }

    @Test
    @DisplayName("the whole schema matches the entities (Hibernate validate on real Flyway migrations)")
    void schemaValidates() {
        // getting here means the context started: Flyway ran V1-V5 and
        // ddl-auto=validate found every entity's columns. one cheap query to be sure
        assertThat(outbox.findUnpublished(1)).isNotNull();
    }
}
