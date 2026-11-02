package com.stefankabasaj.courtly.repository;

import com.stefankabasaj.courtly.entity.OutboxEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

    @Query(value = """
        SELECT * FROM courtly.outbox_events
        WHERE published_at IS NULL
        ORDER BY created_at
        LIMIT :limit
        """, nativeQuery = true)
    List<OutboxEvent> findUnpublished(@Param("limit") int limit);

    // Postgres advisory lock, released when the transaction ends. only one
    // instance publishes at a time, which keeps events in order (see OutboxPublisher)
    @Query(value = "SELECT pg_try_advisory_xact_lock(:key)", nativeQuery = true)
    boolean tryLock(@Param("key") long key);

    // a bulk DELETE skips the persistence context - clear it so nothing in
    // this transaction keeps seeing rows that are already gone
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("DELETE FROM OutboxEvent e WHERE e.publishedAt < :before")
    int deletePublishedBefore(@Param("before") LocalDateTime before);
}
