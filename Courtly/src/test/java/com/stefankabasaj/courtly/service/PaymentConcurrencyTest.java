package com.stefankabasaj.courtly.service;

import com.stefankabasaj.courtly.config.BookingProperties;
import com.stefankabasaj.courtly.dto.request.PaymentRequest;
import com.stefankabasaj.courtly.dto.response.PaymentResponse;
import com.stefankabasaj.courtly.entity.Booking;
import com.stefankabasaj.courtly.entity.Court;
import com.stefankabasaj.courtly.entity.Payment;
import com.stefankabasaj.courtly.entity.User;
import com.stefankabasaj.courtly.event.BookingEventRecorder;
import com.stefankabasaj.courtly.exception.BusinessException;
import com.stefankabasaj.courtly.repository.BookingRepository;
import com.stefankabasaj.courtly.repository.CourtRepository;
import com.stefankabasaj.courtly.repository.PaymentRepository;
import com.stefankabasaj.courtly.repository.UserRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

@DataJpaTest(properties = "spring.datasource.url=jdbc:h2:mem:payment-race;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000;INIT=CREATE SCHEMA IF NOT EXISTS courtly")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import({PaymentService.class, PaymentConcurrencyTest.Config.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PaymentConcurrencyTest {

    @Autowired PaymentService service;
    @Autowired BookingRepository bookings;
    @Autowired PaymentRepository payments;
    @Autowired UserRepository users;
    @Autowired CourtRepository courts;
    @Autowired PlatformTransactionManager txManager;
    @Autowired EntityManager entityManager;
    @MockitoBean BookingEventRecorder events;

    @TestConfiguration
    static class Config {
        @Bean BookingProperties bookingProperties() { return new BookingProperties(Duration.ofMinutes(15)); }
        @Bean MeterRegistry metrics() { return new SimpleMeterRegistry(); }
    }

    @Test
    @Timeout(30)
    @DisplayName("a concurrent declined retry cannot overwrite a successful payment")
    void successfulPaymentSurvivesConcurrentRetry() throws Exception {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        Booking booking = tx.execute(status -> {
            User user = users.save(User.builder().email(UUID.randomUUID() + "@courtly.test")
                    .passwordHash("x").role(User.Role.USER).active(true).build());
            Court court = courts.save(Court.builder().name("Race test").surface(Court.Surface.CLAY)
                    .pricePerHour(new BigDecimal("25.00")).active(true).build());
            LocalDateTime start = LocalDateTime.now().plusDays(1).withHour(10).withMinute(0).withSecond(0).withNano(0);
            Booking created = bookings.saveAndFlush(Booking.builder().user(user).court(court)
                    .startTime(start).endTime(start.plusHours(1)).totalPrice(new BigDecimal("25.00"))
                    .status(Booking.BookingStatus.PENDING).build());
            payments.saveAndFlush(Payment.builder().booking(created).amount(new BigDecimal("25.00"))
                    .paymentMethod("CREDIT_CARD").status(Payment.PaymentStatus.FAILED)
                    .failureReason("Previous decline").build());
            return created;
        });
        UUID bookingId = booking.getId();
        UUID userId = booking.getUser().getId();
        CountDownLatch firstHolding = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondStarted = new CountDownLatch(1);
        CountDownLatch secondAtGateway = new CountDownLatch(1);

        try (var workers = Executors.newFixedThreadPool(2)) {
            var first = workers.submit(() -> withOutcome(0.0, () -> tx.execute(status -> {
                PaymentResponse response = service.processPayment(bookingId, userId, new PaymentRequest("CREDIT_CARD"));
                entityManager.flush();
                firstHolding.countDown();
                await(releaseFirst);
                return response;
            })));
            try {
                assertThat(firstHolding.await(10, TimeUnit.SECONDS)).isTrue();
                var second = workers.submit(() -> withOutcome(0.99, () -> tx.execute(status -> {
                    secondStarted.countDown();
                    return service.processPayment(bookingId, userId, new PaymentRequest("DEBIT_CARD"));
                }), secondAtGateway));
                assertThat(secondStarted.await(10, TimeUnit.SECONDS)).isTrue();
                // Without a row lock, the retry reaches the gateway before the first commit.
                secondAtGateway.await(1, TimeUnit.SECONDS);
                releaseFirst.countDown();
                assertThat(first.get(10, TimeUnit.SECONDS).status()).isEqualTo("SUCCESS");
                try {
                    second.get(10, TimeUnit.SECONDS);
                } catch (java.util.concurrent.ExecutionException expected) {
                    assertThat(expected.getCause()).isInstanceOf(BusinessException.class);
                }
                assertThat(payments.findByBookingId(bookingId).orElseThrow().getStatus())
                        .isEqualTo(Payment.PaymentStatus.SUCCESS);
                assertThat(bookings.findById(bookingId).orElseThrow().getStatus())
                        .isEqualTo(Booking.BookingStatus.CONFIRMED);
                assertThatThrownBy(() -> second.get(10, TimeUnit.SECONDS))
                        .hasCauseInstanceOf(BusinessException.class);
            } finally {
                releaseFirst.countDown();
            }
        } finally {
            tx.executeWithoutResult(status -> {
                payments.findByBookingId(bookingId).ifPresent(payments::delete);
                payments.flush();
                bookings.deleteById(bookingId);
                bookings.flush();
                courts.deleteById(booking.getCourt().getId());
                users.deleteById(userId);
            });
        }
    }

    private static <T> T withOutcome(double value, Supplier<T> action) {
        return withOutcome(value, action, new CountDownLatch(0));
    }

    private static <T> T withOutcome(double value, Supplier<T> action, CountDownLatch atGateway) {
        ThreadLocalRandom random = mock(ThreadLocalRandom.class);
        when(random.nextDouble()).thenAnswer(invocation -> { atGateway.countDown(); return value; });
        try (var source = mockStatic(ThreadLocalRandom.class)) {
            source.when(ThreadLocalRandom::current).thenReturn(random);
            return action.get();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) throw new AssertionError("Timed out waiting for transaction release");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }
}
