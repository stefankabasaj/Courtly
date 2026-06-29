package com.stefankabasaj.courtly.service;

import com.stefankabasaj.courtly.dto.request.PaymentRequest;
import com.stefankabasaj.courtly.dto.response.PaymentResponse;
import com.stefankabasaj.courtly.entity.Booking;
import com.stefankabasaj.courtly.entity.Court;
import com.stefankabasaj.courtly.entity.Payment;
import com.stefankabasaj.courtly.entity.User;
import com.stefankabasaj.courtly.exception.BusinessException;
import com.stefankabasaj.courtly.exception.ConflictException;
import com.stefankabasaj.courtly.exception.ResourceNotFoundException;
import com.stefankabasaj.courtly.repository.BookingRepository;
import com.stefankabasaj.courtly.repository.PaymentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.springframework.test.context.ActiveProfiles;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ActiveProfiles("test")
@ExtendWith(MockitoExtension.class)
@DisplayName("PaymentService")
class PaymentServiceTest {

    @Mock BookingRepository bookingRepository;
    @Mock PaymentRepository paymentRepository;
    @InjectMocks PaymentService paymentService;

    private User    user;
    private Booking pendingBooking;

    @BeforeEach
    void setUp() {
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
        when(bookingRepository.findByIdWithDetails(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> paymentService.processPayment(
                UUID.randomUUID(), user.getId(), new PaymentRequest("CREDIT_CARD")))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("wrong owner throws BusinessException")
    void wrongOwner_throws() {
        when(bookingRepository.findByIdWithDetails(pendingBooking.getId()))
                .thenReturn(Optional.of(pendingBooking));

        UUID differentUser = UUID.randomUUID();
        assertThatThrownBy(() -> paymentService.processPayment(
                pendingBooking.getId(), differentUser, new PaymentRequest("CREDIT_CARD")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("can only pay");
    }

    @Test
    @DisplayName("non-PENDING booking throws BusinessException")
    void nonPending_throws() {
        pendingBooking.setStatus(Booking.BookingStatus.CONFIRMED);
        when(bookingRepository.findByIdWithDetails(pendingBooking.getId()))
                .thenReturn(Optional.of(pendingBooking));

        assertThatThrownBy(() -> paymentService.processPayment(
                pendingBooking.getId(), user.getId(), new PaymentRequest("CREDIT_CARD")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("PENDING");
    }

    @Test
    @DisplayName("already paid throws ConflictException - idempotency")
    void alreadyPaid_throws() {
        when(bookingRepository.findByIdWithDetails(pendingBooking.getId()))
                .thenReturn(Optional.of(pendingBooking));
        when(paymentRepository.existsByBookingId(pendingBooking.getId()))
                .thenReturn(true);

        assertThatThrownBy(() -> paymentService.processPayment(
                pendingBooking.getId(), user.getId(), new PaymentRequest("CREDIT_CARD")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("already processed");
    }

    @Test
    @DisplayName("processPayment - returns SUCCESS or FAILED with correct data")
    void processPayment_returnsResult() {
        when(bookingRepository.findByIdWithDetails(pendingBooking.getId()))
                .thenReturn(Optional.of(pendingBooking));
        when(paymentRepository.existsByBookingId(pendingBooking.getId()))
                .thenReturn(false);
        when(bookingRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(paymentRepository.save(any())).thenAnswer(i -> {
            Payment p = i.getArgument(0);
            p.setBooking(pendingBooking);
            return p;
        });

        PaymentResponse resp = paymentService.processPayment(
                pendingBooking.getId(), user.getId(), new PaymentRequest("CREDIT_CARD"));

        assertThat(resp.bookingId()).isEqualTo(pendingBooking.getId());
        assertThat(resp.amount()).isEqualByComparingTo("50.00");
        assertThat(resp.status()).isIn("SUCCESS", "FAILED");
        assertThat(resp.paymentMethod()).isEqualTo("CREDIT_CARD");
    }
}
