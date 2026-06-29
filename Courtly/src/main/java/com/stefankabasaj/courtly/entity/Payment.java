package com.stefankabasaj.courtly.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(
    name = "payments",
    schema = "courtly",
    uniqueConstraints = @UniqueConstraint(name = "uq_payments_booking_id", columnNames = "booking_id")
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    // one payment per booking. the unique constraint is what actually guarantees it -
    // if something tries to charge twice the DB rejects the second insert
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "booking_id", nullable = false, unique = true)
    private Booking booking;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PaymentStatus status;

    @Column(name = "payment_method", nullable = false, length = 30)
    private String paymentMethod;

    // set only when the payment fails
    @Column(name = "failure_reason", length = 500)
    private String failureReason;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    // null until we've actually processed it
    @Column(name = "processed_at")
    private LocalDateTime processedAt;

    public enum PaymentStatus {
        PENDING,
        SUCCESS,
        FAILED
    }
}
