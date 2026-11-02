package com.stefankabasaj.courtly.event;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.stefankabasaj.courtly.config.BookingProperties;
import com.stefankabasaj.courtly.entity.Booking;
import com.stefankabasaj.courtly.entity.Court;
import com.stefankabasaj.courtly.entity.OutboxEvent;
import com.stefankabasaj.courtly.entity.User;
import com.stefankabasaj.courtly.repository.OutboxEventRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("BookingEventRecorder")
class BookingEventRecorderTest {

    @Mock OutboxEventRepository outbox;

    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private BookingEventRecorder recorder;
    private final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
    private Booking booking;

    @BeforeEach
    void setUp() {
        recorder = new BookingEventRecorder(outbox, json, new BookingProperties(Duration.ofMinutes(15)), metrics);
        LocalDateTime start = LocalDateTime.of(2030, 6, 1, 10, 0);
        booking = Booking.builder()
                .id(UUID.randomUUID())
                .user(User.builder().id(UUID.randomUUID()).email("stefan@courtly.com").build())
                .court(Court.builder().id(UUID.randomUUID()).name("Court A - Clay").build())
                .startTime(start).endTime(start.plusHours(2))
                .totalPrice(new BigDecimal("50.00"))
                .status(Booking.BookingStatus.PENDING)
                .createdAt(LocalDateTime.of(2030, 5, 30, 18, 0))
                .build();
    }

    private OutboxEvent saved() {
        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outbox).save(captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("CREATED - full payload, payBy = created + payment window, no reason")
    void created() throws Exception {
        recorder.record(booking, BookingEventType.BOOKING_CREATED);

        OutboxEvent row = saved();
        JsonNode payload = json.readTree(row.getPayload());

        assertThat(row.getAggregateId()).isEqualTo(booking.getId());
        assertThat(row.getEventType()).isEqualTo("BOOKING_CREATED");
        assertThat(row.getPublishedAt()).isNull();
        assertThat(row.isNew()).isTrue();

        // the row id and the eventId consumers dedupe on are the same thing
        assertThat(payload.get("eventId").asText()).isEqualTo(row.getId().toString());
        assertThat(payload.get("type").asText()).isEqualTo("BOOKING_CREATED");
        assertThat(payload.get("bookingId").asText()).isEqualTo(booking.getId().toString());
        assertThat(payload.get("userEmail").asText()).isEqualTo("stefan@courtly.com");
        assertThat(payload.get("courtName").asText()).isEqualTo("Court A - Clay");
        assertThat(payload.get("startTime").asText()).isEqualTo("2030-06-01T10:00:00");
        assertThat(payload.get("totalPrice").decimalValue()).isEqualByComparingTo("50.00");
        assertThat(payload.get("payBy").asText()).isEqualTo("2030-05-30T18:15:00");
        assertThat(payload.get("reason").isNull()).isTrue();
        assertThat(metrics.counter("courtly.bookings", "event", "created").count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("carries the id of the request that caused it, so it can be followed into the notification logs")
    void requestId() throws Exception {
        MDC.put("requestId", "req-123");

        recorder.record(booking, BookingEventType.BOOKING_CREATED);

        assertThat(json.readTree(saved().getPayload()).get("requestId").asText()).isEqualTo("req-123");
    }

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    @DisplayName("CANCELLED - carries the reason, no payBy")
    void cancelled() throws Exception {
        booking.setStatus(Booking.BookingStatus.CANCELLED);
        booking.setCancellationReason("Court maintenance");

        recorder.record(booking, BookingEventType.BOOKING_CANCELLED);

        JsonNode payload = json.readTree(saved().getPayload());
        assertThat(payload.get("reason").asText()).isEqualTo("Court maintenance");
        assertThat(payload.get("payBy").isNull()).isTrue();
    }
}
