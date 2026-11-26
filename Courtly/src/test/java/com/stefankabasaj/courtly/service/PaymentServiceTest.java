package com.stefankabasaj.courtly.service;

import com.stefankabasaj.courtly.config.BookingProperties;
import com.stefankabasaj.courtly.dto.request.PaymentRequest;
import com.stefankabasaj.courtly.dto.response.PaymentResponse;
import com.stefankabasaj.courtly.entity.Booking;
import com.stefankabasaj.courtly.entity.Court;
import com.stefankabasaj.courtly.entity.Payment;
import com.stefankabasaj.courtly.entity.User;
import com.stefankabasaj.courtly.event.BookingEventRecorder;
import com.stefankabasaj.courtly.event.BookingEventType;
import com.stefankabasaj.courtly.exception.BusinessException;
import com.stefankabasaj.courtly.exception.ConflictException;
import com.stefankabasaj.courtly.exception.ResourceNotFoundException;
import com.stefankabasaj.courtly.repository.BookingRepository;
import com.stefankabasaj.courtly.repository.PaymentRepository;
import com.stefankabasaj.courtly.security.AuthenticatedUser;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.springframework.test.context.ActiveProfiles;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ActiveProfiles("test")
@ExtendWith(MockitoExtension.class)
@DisplayName("PaymentService")
class PaymentServiceTest {

    @Mock BookingRepository bookingRepository;
    @Mock PaymentRepository paymentRepository;
    @Mock BookingEventRecorder events;
    PaymentService paymentService;
    SimpleMeterRegistry metrics = new SimpleMeterRegistry();

    private User    user;
    private Booking pendingBooking;

    @BeforeEach
    void setUp() {
        paymentService = new PaymentService(
                paymentRepository, bookingRepository, new BookingProperties(Duration.ofMinutes(15)), events, metrics);
        user = User.builder().id(UUID.randomUUID())
                .email("stefan@courtly.com").role(User.Role.USER).active(true).build();
        Court court = Court.builder().id(UUID.randomUUID())
                .name("Court A").surface(Court.Surface.CLAY)
                .pricePerHour(new BigDecimal("25.00")).active(true).build();
        LocalDateTime start = LocalDateTime.now().plusDays(1)
                .withHour(10).withMinute(0).withSecond(0).withNano(0);
        pendingBooking = Booking.builder()
                .id(UUID.randomUUID()).user(user).court(court)
                .startTime(start).endTime(start.plusHours(2))
                .totalPrice(new BigDecimal("50.00"))
                .status(Booking.BookingStatus.PENDING)
                .build();
    }

    @Test
    @DisplayName("booking not found throws ResourceNotFoundException")
    void bookingNotFound_throws() {
        when(bookingRepository.findByIdForPayment(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> paymentService.processPayment(
                UUID.randomUUID(), user.getId(), new PaymentRequest("CREDIT_CARD")))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("wrong owner gets 404, not a hint that the booking exists")
    void wrongOwner_throws() {
        when(bookingRepository.findByIdForPayment(pendingBooking.getId()))
                .thenReturn(Optional.of(pendingBooking));

        UUID differentUser = UUID.randomUUID();
        assertThatThrownBy(() -> paymentService.processPayment(
                pendingBooking.getId(), differentUser, new PaymentRequest("CREDIT_CARD")))
                .isInstanceOf(ResourceNotFoundException.class);

        verify(paymentRepository, never()).save(any());
    }

    @Test
    @DisplayName("non-PENDING booking throws BusinessException")
    void nonPending_throws() {
        pendingBooking.setStatus(Booking.BookingStatus.CONFIRMED);
        when(bookingRepository.findByIdForPayment(pendingBooking.getId()))
                .thenReturn(Optional.of(pendingBooking));

        assertThatThrownBy(() -> paymentService.processPayment(
                pendingBooking.getId(), user.getId(), new PaymentRequest("CREDIT_CARD")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("PENDING");
    }

    @Test
    @DisplayName("already paid throws ConflictException - idempotency")
    void alreadyPaid_throws() {
        when(bookingRepository.findByIdForPayment(pendingBooking.getId()))
                .thenReturn(Optional.of(pendingBooking));
        when(paymentRepository.findByBookingId(pendingBooking.getId()))
                .thenReturn(Optional.of(paymentWithStatus(Payment.PaymentStatus.SUCCESS)));

        assertThatThrownBy(() -> paymentService.processPayment(
                pendingBooking.getId(), user.getId(), new PaymentRequest("CREDIT_CARD")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("already been processed");
    }

    @Test
    @DisplayName("approved payment confirms the booking and records the confirmation")
    void approvedPayment() {
        when(bookingRepository.findByIdForPayment(pendingBooking.getId()))
                .thenReturn(Optional.of(pendingBooking));
        when(paymentRepository.findByBookingId(pendingBooking.getId())).thenReturn(Optional.empty());
        when(paymentRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        PaymentResponse response = processPaymentWithOutcome(0.0, "CREDIT_CARD");

        assertThat(response.bookingId()).isEqualTo(pendingBooking.getId());
        assertThat(response.amount()).isEqualByComparingTo("50.00");
        assertThat(response.status()).isEqualTo("SUCCESS");
        assertThat(response.paymentMethod()).isEqualTo("CREDIT_CARD");
        assertThat(response.failureReason()).isNull();
        assertThat(pendingBooking.getStatus()).isEqualTo(Booking.BookingStatus.CONFIRMED);
        verify(events).record(pendingBooking, BookingEventType.BOOKING_CONFIRMED);
        assertThat(metrics.counter("courtly.payments", "result", "success").count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("declined payment keeps the booking pending and records no confirmation")
    void declinedPayment() {
        when(bookingRepository.findByIdForPayment(pendingBooking.getId()))
                .thenReturn(Optional.of(pendingBooking));
        when(paymentRepository.findByBookingId(pendingBooking.getId())).thenReturn(Optional.empty());
        when(paymentRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        PaymentResponse response = processPaymentWithOutcome(0.99, "CREDIT_CARD");

        assertThat(response.status()).isEqualTo("FAILED");
        assertThat(response.failureReason()).isEqualTo("Card declined (simulated)");
        assertThat(pendingBooking.getStatus()).isEqualTo(Booking.BookingStatus.PENDING);
        verifyNoInteractions(events);
        assertThat(metrics.counter("courtly.payments", "result", "failed").count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("payment window expired → BusinessException, nothing saved")
    void expiredWindow_throws() {
        pendingBooking.setCreatedAt(LocalDateTime.now().minusMinutes(16));
        when(bookingRepository.findByIdForPayment(pendingBooking.getId()))
                .thenReturn(Optional.of(pendingBooking));

        assertThatThrownBy(() -> paymentService.processPayment(
                pendingBooking.getId(), user.getId(), new PaymentRequest("CREDIT_CARD")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("expired");

        verify(paymentRepository, never()).save(any());
    }

    private Payment paymentWithStatus(Payment.PaymentStatus status) {
        return Payment.builder().id(UUID.randomUUID()).booking(pendingBooking)
                .amount(new BigDecimal("50.00")).paymentMethod("CREDIT_CARD")
                .status(status).failureReason(status == Payment.PaymentStatus.FAILED ? "Card declined" : null)
                .build();
    }

    @Test
    @DisplayName("approved retry reuses the failed payment and clears its failure reason")
    void approvedRetry() {
        Payment failed = paymentWithStatus(Payment.PaymentStatus.FAILED);
        when(bookingRepository.findByIdForPayment(pendingBooking.getId()))
                .thenReturn(Optional.of(pendingBooking));
        when(paymentRepository.findByBookingId(pendingBooking.getId())).thenReturn(Optional.of(failed));
        when(paymentRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        PaymentResponse response = processPaymentWithOutcome(0.0, "DEBIT_CARD");

        assertThat(response.id()).isEqualTo(failed.getId());
        assertThat(response.paymentMethod()).isEqualTo("DEBIT_CARD");
        assertThat(response.status()).isEqualTo("SUCCESS");
        assertThat(response.failureReason()).isNull();
        assertThat(pendingBooking.getStatus()).isEqualTo(Booking.BookingStatus.CONFIRMED);
        verify(events).record(pendingBooking, BookingEventType.BOOKING_CONFIRMED);
    }

    @Test
    @DisplayName("declined retry reuses the failed payment and leaves the booking pending")
    void declinedRetry() {
        Payment failed = paymentWithStatus(Payment.PaymentStatus.FAILED);
        when(bookingRepository.findByIdForPayment(pendingBooking.getId()))
                .thenReturn(Optional.of(pendingBooking));
        when(paymentRepository.findByBookingId(pendingBooking.getId())).thenReturn(Optional.of(failed));
        when(paymentRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        PaymentResponse response = processPaymentWithOutcome(0.99, "DEBIT_CARD");

        assertThat(response.id()).isEqualTo(failed.getId());
        assertThat(response.paymentMethod()).isEqualTo("DEBIT_CARD");
        assertThat(response.status()).isEqualTo("FAILED");
        assertThat(response.failureReason()).isEqualTo("Card declined (simulated)");
        assertThat(pendingBooking.getStatus()).isEqualTo(Booking.BookingStatus.PENDING);
        verifyNoInteractions(events);
    }

    private PaymentResponse processPaymentWithOutcome(double gatewayResult, String method) {
        ThreadLocalRandom random = mock(ThreadLocalRandom.class);
        when(random.nextDouble()).thenReturn(gatewayResult);
        try (var source = mockStatic(ThreadLocalRandom.class)) {
            source.when(ThreadLocalRandom::current).thenReturn(random);
            return paymentService.processPayment(pendingBooking.getId(), user.getId(), new PaymentRequest(method));
        }
    }

    @Test
    @DisplayName("getPayment - owner can read it")
    void getPayment_owner() {
        when(bookingRepository.findByIdWithDetails(pendingBooking.getId()))
                .thenReturn(Optional.of(pendingBooking));
        when(paymentRepository.findByBookingId(pendingBooking.getId()))
                .thenReturn(Optional.of(paymentWithStatus(Payment.PaymentStatus.SUCCESS)));

        PaymentResponse resp = paymentService.getPaymentByBookingId(
                pendingBooking.getId(), new AuthenticatedUser(user.getId(), user.getEmail(), "USER"));

        assertThat(resp.bookingId()).isEqualTo(pendingBooking.getId());
    }

    @Test
    @DisplayName("getPayment - admin can read anyone's")
    void getPayment_admin() {
        when(bookingRepository.findByIdWithDetails(pendingBooking.getId()))
                .thenReturn(Optional.of(pendingBooking));
        when(paymentRepository.findByBookingId(pendingBooking.getId()))
                .thenReturn(Optional.of(paymentWithStatus(Payment.PaymentStatus.SUCCESS)));

        PaymentResponse resp = paymentService.getPaymentByBookingId(
                pendingBooking.getId(), new AuthenticatedUser(UUID.randomUUID(), "admin@courtly.com", "ADMIN"));

        assertThat(resp.bookingId()).isEqualTo(pendingBooking.getId());
    }

    @Test
    @DisplayName("getPayment - another user gets 404")
    void getPayment_otherUser() {
        when(bookingRepository.findByIdWithDetails(pendingBooking.getId()))
                .thenReturn(Optional.of(pendingBooking));

        AuthenticatedUser stranger = new AuthenticatedUser(UUID.randomUUID(), "other@courtly.com", "USER");

        assertThatThrownBy(() -> paymentService.getPaymentByBookingId(pendingBooking.getId(), stranger))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(paymentRepository, never()).findByBookingId(any());
    }
}
