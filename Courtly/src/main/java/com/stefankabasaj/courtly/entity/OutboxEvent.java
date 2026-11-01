package com.stefankabasaj.courtly.entity;

import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.domain.Persistable;

import java.time.LocalDateTime;
import java.util.UUID;

// a booking event waiting to go to Kafka - see V5 and OutboxPublisher
@Entity
@Table(name = "outbox_events", schema = "courtly")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OutboxEvent implements Persistable<UUID> {

    // set in code, not generated: the same id is the eventId inside the payload
    @Id
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(name = "aggregate_id", nullable = false, updatable = false)
    private UUID aggregateId;

    @Column(name = "event_type", nullable = false, length = 50, updatable = false)
    private String eventType;

    @Column(nullable = false, length = 4000, updatable = false)
    private String payload;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    // null until it's been sent to Kafka
    @Column(name = "published_at")
    private LocalDateTime publishedAt;

    // with an id we set ourselves, Spring Data can't tell new from existing and
    // would SELECT before every INSERT. new until it's been saved or loaded once
    @Transient
    @Builder.Default
    private boolean isNew = true;

    @Override
    public boolean isNew() {
        return isNew;
    }

    @PostPersist
    @PostLoad
    void markNotNew() {
        this.isNew = false;
    }
}
