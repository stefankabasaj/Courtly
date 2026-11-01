package com.stefankabasaj.courtly.repository;

import com.stefankabasaj.courtly.entity.Payment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface PaymentRepository extends JpaRepository<Payment, UUID> {

    // fetch the booking too so getBooking() in the service doesn't lazy-load
    @Query("""
        SELECT p FROM Payment p
        JOIN FETCH p.booking
        WHERE p.booking.id = :bookingId
        """)
    Optional<Payment> findByBookingId(@Param("bookingId") UUID bookingId);
}
