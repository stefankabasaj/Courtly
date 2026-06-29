package com.stefankabasaj.courtly.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(
    name = "bookings",
    schema = "courtly",
    indexes = {
        @Index(name = "idx_bookings_user_id",    columnList = "user_id"),
        @Index(name = "idx_bookings_court_time", columnList = "court_id, start_time, end_time"),
        @Index(name = "idx_bookings_status",     columnList = "status")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Booking {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    // optimistic lock. user + admin can both hit cancel on the same booking at once -
    // without this one update silently clobbers the other. with it, the loser gets
    // OptimisticLockException and we fail cleanly
    @Version
    @Column(nullable = false)
    private Long version;

    // LAZY on purpose. the repo queries that actually need these use JOIN FETCH,
    // so we never hit the N+1 trap or a LazyInitializationException
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "court_id", nullable = false)
    private Court court;

    @Column(name = "start_time", nullable = false)
    private LocalDateTime startTime;

    @Column(name = "end_time", nullable = false)
    private LocalDateTime endTime;

    @Column(name = "total_price", nullable = false, precision = 10, scale = 2)
    private BigDecimal totalPrice;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private BookingStatus status;

    // only filled in on cancellation
    @Column(name = "cancellation_reason", length = 500)
    private String cancellationReason;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    // Hibernate bumps this on every update, we never set it ourselves
    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public enum BookingStatus {
        PENDING,    // just created, waiting for payment
        CONFIRMED,  // payment went through
        CANCELLED   // cancelled by user or admin
    }
}
