package com.stefankabasaj.courtly.service;

import com.stefankabasaj.courtly.config.BookingProperties;
import com.stefankabasaj.courtly.dto.request.PaymentRequest;
import com.stefankabasaj.courtly.dto.response.PaymentResponse;
import com.stefankabasaj.courtly.entity.Booking;
import com.stefankabasaj.courtly.entity.Payment;
import com.stefankabasaj.courtly.event.BookingEventRecorder;
import com.stefankabasaj.courtly.event.BookingEventType;
import com.stefankabasaj.courtly.exception.BusinessException;
import com.stefankabasaj.courtly.exception.ConflictException;
import com.stefankabasaj.courtly.exception.ResourceNotFoundException;
import com.stefankabasaj.courtly.repository.BookingRepository;
import com.stefankabasaj.courtly.repository.PaymentRepository;
import com.stefankabasaj.courtly.security.AuthenticatedUser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentService {

    // fake gateway - succeeds 90% of the time. real version would call Stripe here
    private static final double SUCCESS_RATE = 0.9;

    private final PaymentRepository    paymentRepository;
    private final BookingRepository    bookingRepository;
    private final BookingProperties    bookingProperties;
    private final BookingEventRecorder events;

    @Transactional
    public PaymentResponse processPayment(UUID bookingId, UUID payerId, PaymentRequest request) {
        log.info("Processing payment bookingId={} payerId={}", bookingId, payerId);

        // WithDetails so getUser() below doesn't trip a lazy load
        Booking booking = bookingRepository.findByIdWithDetails(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("Booking", bookingId));

        // can't pay for someone else's booking. 404 so we don't reveal it exists
        if (!booking.getUser().getId().equals(payerId)) {
            throw new ResourceNotFoundException("Booking", bookingId);
        }

        if (booking.getStatus() != Booking.BookingStatus.PENDING) {
            throw new BusinessException(
                    "Can only process payment for PENDING bookings. " +
                    "Current status: " + booking.getStatus());
        }

        // BookingExpiryJob cancels these within a minute; this closes that gap
        if (isPaymentWindowExpired(booking)) {
            throw new BusinessException(
                    "The payment window for this booking has expired. Please book again.");
        }

        // a booking has at most one payment row (unique constraint). a FAILED one is
        // reused for the retry, otherwise a declined card would lock the booking forever
        Payment payment = paymentRepository.findByBookingId(bookingId).orElse(null);
        if (payment != null && payment.getStatus() == Payment.PaymentStatus.SUCCESS) {
            throw new ConflictException("Payment has already been processed for this booking.");
        }
        if (payment == null) {
            payment = Payment.builder().booking(booking).build();
        }

        payment.setAmount(booking.getTotalPrice());
        payment.setPaymentMethod(request.paymentMethod());
        payment.setProcessedAt(LocalDateTime.now());

        if (ThreadLocalRandom.current().nextDouble() < SUCCESS_RATE) {
            payment.setStatus(Payment.PaymentStatus.SUCCESS);
            payment.setFailureReason(null);
            booking.setStatus(Booking.BookingStatus.CONFIRMED);
            bookingRepository.save(booking);
            events.record(booking, BookingEventType.BOOKING_CONFIRMED);
            log.info("Payment SUCCESS bookingId={} amount={}", bookingId, payment.getAmount());
        } else {
            payment.setStatus(Payment.PaymentStatus.FAILED);
            payment.setFailureReason("Card declined (simulated)");
            log.warn("Payment FAILED bookingId={}", bookingId);
        }

        return toResponse(paymentRepository.save(payment));
    }

    @Transactional(readOnly = true)
    public PaymentResponse getPaymentByBookingId(UUID bookingId, AuthenticatedUser principal) {
        Booking booking = bookingRepository.findByIdWithDetails(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("Booking", bookingId));

        // payment details belong to the booking's owner (or an admin)
        if (!principal.canAccess(booking.getUser().getId())) {
            throw new ResourceNotFoundException("Booking", bookingId);
        }

        Payment payment = paymentRepository.findByBookingId(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No payment found for booking: " + bookingId));
        return toResponse(payment);
    }

    private boolean isPaymentWindowExpired(Booking booking) {
        return booking.getCreatedAt() != null
                && bookingProperties.payBy(booking.getCreatedAt()).isBefore(LocalDateTime.now());
    }

    private PaymentResponse toResponse(Payment p) {
        return new PaymentResponse(
                p.getId(),
                p.getBooking().getId(),
                p.getAmount(),
                p.getStatus().name(),
                p.getPaymentMethod(),
                p.getFailureReason(),
                p.getCreatedAt(),
                p.getProcessedAt()
        );
    }
}
