package com.stefankabasaj.courtly.service;

import com.stefankabasaj.courtly.dto.request.PaymentRequest;
import com.stefankabasaj.courtly.dto.response.PaymentResponse;
import com.stefankabasaj.courtly.entity.Booking;
import com.stefankabasaj.courtly.entity.Payment;
import com.stefankabasaj.courtly.exception.BusinessException;
import com.stefankabasaj.courtly.exception.ConflictException;
import com.stefankabasaj.courtly.exception.ResourceNotFoundException;
import com.stefankabasaj.courtly.repository.BookingRepository;
import com.stefankabasaj.courtly.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Random;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentService {

    // fake gateway - succeeds 90% of the time. real version would call Stripe here
    private static final double SUCCESS_RATE = 0.9;

    private final PaymentRepository paymentRepository;
    private final BookingRepository bookingRepository;
    private final Random            random = new Random();

    @Transactional
    public PaymentResponse processPayment(UUID bookingId, UUID payerId, PaymentRequest request) {
        log.info("Processing payment bookingId={} payerId={}", bookingId, payerId);

        // WithDetails so getUser() below doesn't trip a lazy load
        Booking booking = bookingRepository.findByIdWithDetails(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("Booking", bookingId));

        // can't pay for someone else's booking
        if (!booking.getUser().getId().equals(payerId)) {
            throw new BusinessException("You can only pay for your own bookings.");
        }

        if (booking.getStatus() != Booking.BookingStatus.PENDING) {
            throw new BusinessException(
                    "Can only process payment for PENDING bookings. " +
                    "Current status: " + booking.getStatus());
        }

        // app check for a nice message, unique constraint is the real guarantee
        if (paymentRepository.existsByBookingId(bookingId)) {
            throw new ConflictException("Payment has already been processed for this booking.");
        }

        Payment payment = Payment.builder()
                .booking(booking)
                .amount(booking.getTotalPrice())
                .paymentMethod(request.paymentMethod())
                .status(Payment.PaymentStatus.PENDING)
                .build();

        if (random.nextDouble() < SUCCESS_RATE) {
            payment.setStatus(Payment.PaymentStatus.SUCCESS);
            payment.setProcessedAt(LocalDateTime.now());
            booking.setStatus(Booking.BookingStatus.CONFIRMED);
            bookingRepository.save(booking);
            log.info("Payment SUCCESS bookingId={} amount={}", bookingId, payment.getAmount());
        } else {
            payment.setStatus(Payment.PaymentStatus.FAILED);
            payment.setFailureReason("Card declined (simulated)");
            payment.setProcessedAt(LocalDateTime.now());
            log.warn("Payment FAILED bookingId={}", bookingId);
        }

        return toResponse(paymentRepository.save(payment));
    }

    @Transactional(readOnly = true)
    public PaymentResponse getPaymentByBookingId(UUID bookingId) {
        Payment payment = paymentRepository.findByBookingId(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No payment found for booking: " + bookingId));
        return toResponse(payment);
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
