package com.stefankabasaj.courtly.repository;

import com.stefankabasaj.courtly.entity.Booking;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface BookingRepository extends JpaRepository<Booking, UUID> {

    // JOIN FETCH pulls user + court in the same query. without it you either get
    // a LazyInitializationException or N+1 queries when looping over results
    @Query("""
        SELECT b FROM Booking b
        JOIN FETCH b.user
        JOIN FETCH b.court
        WHERE b.id = :id
        """)
    Optional<Booking> findByIdWithDetails(@Param("id") UUID id);

    @Query("""
        SELECT b FROM Booking b
        JOIN FETCH b.user
        JOIN FETCH b.court
        WHERE b.user.id = :userId
        ORDER BY b.createdAt DESC
        """)
    List<Booking> findByUserIdWithDetails(@Param("userId") UUID userId);

    // Spring can't build the count query when JOIN FETCH is in play, so I give it one explicitly
    // id as a tie-breaker so rows with equal createdAt can't shuffle between pages
    @Query(
        value      = "SELECT b FROM Booking b JOIN FETCH b.user JOIN FETCH b.court ORDER BY b.createdAt DESC, b.id",
        countQuery = "SELECT COUNT(b) FROM Booking b"
    )
    Page<Booking> findAllWithDetails(Pageable pageable);

    // overlap = startA < endB AND endA > startB. cancelled bookings don't count, the slot's free
    @Query("""
        SELECT COUNT(b) > 0
        FROM Booking b
        WHERE b.court.id  = :courtId
          AND b.status   <> 'CANCELLED'
          AND b.startTime < :endTime
          AND b.endTime   > :startTime
        """)
    boolean existsOverlappingBooking(
            @Param("courtId")   UUID courtId,
            @Param("startTime") LocalDateTime startTime,
            @Param("endTime")   LocalDateTime endTime
    );

    // bookings that still hold part of [from, to) - used to build the availability grid
    @Query("""
        SELECT b FROM Booking b
        WHERE b.court.id  = :courtId
          AND b.status   <> :cancelled
          AND b.startTime < :to
          AND b.endTime   > :from
        """)
    List<Booking> findActiveOverlapping(
            @Param("courtId")   UUID courtId,
            @Param("from")      LocalDateTime from,
            @Param("to")        LocalDateTime to,
            @Param("cancelled") Booking.BookingStatus cancelled
    );

    // bulk update skips Hibernate's @Version and @UpdateTimestamp handling, so both are set here.
    // statuses are parameters so the query doesn't need the enum's fully qualified name
    @Modifying
    @Query("""
        UPDATE Booking b
        SET b.status             = :cancelled,
            b.cancellationReason = :reason,
            b.updatedAt          = :now,
            b.version            = b.version + 1
        WHERE b.status    = :pending
          AND b.createdAt < :cutoff
        """)
    int cancelPendingCreatedBefore(
            @Param("cutoff")    LocalDateTime cutoff,
            @Param("now")       LocalDateTime now,
            @Param("reason")    String reason,
            @Param("pending")   Booking.BookingStatus pending,
            @Param("cancelled") Booking.BookingStatus cancelled
    );
}
