package com.stefankabasaj.courtly.notifications;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("BookingEventListener")
class BookingEventListenerTest {

    @Mock ProcessedEvents processed;
    @Mock JavaMailSender  mailSender;

    private BookingEventListener listener;
    private final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
    private final UUID eventId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        // configured like Spring Boot's ObjectMapper: unknown fields are ignored
        ObjectMapper json = new ObjectMapper().findAndRegisterModules()
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        listener = new BookingEventListener(json, new BookingEmails(),
                processed, mailSender, "Courtly <no-reply@courtly.local>", metrics);
    }

    // shaped like the API's JSON, including a field this service doesn't know about
    private String payload(String type) {
        return """
                {"eventId":"%s","type":"%s","occurredAt":"2030-05-30T18:00:00",
                 "bookingId":"%s","userEmail":"stefan@courtly.com","courtName":"Court A - Clay",
                 "startTime":"2030-06-01T10:00:00","endTime":"2030-06-01T12:00:00",
                 "totalPrice":50.00,"payBy":"2030-05-30T18:15:00","someNewField":"ignored"}
                """.formatted(eventId, type, UUID.randomUUID());
    }

    @Test
    @DisplayName("sends the email, then marks the event as sent")
    void sendsAndMarks() throws Exception {
        when(processed.alreadySent(eventId)).thenReturn(false);

        listener.onBookingEvent(payload("BOOKING_CREATED"));

        ArgumentCaptor<SimpleMailMessage> sent = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(sent.capture());
        assertThat(sent.getValue().getTo()).containsExactly("stefan@courtly.com");
        assertThat(sent.getValue().getFrom()).isEqualTo("Courtly <no-reply@courtly.local>");
        assertThat(sent.getValue().getSubject()).startsWith("Booking received: ");
        verify(processed).markSent(eventId);
        assertThat(metrics.counter("courtly.notifications", "type", "BOOKING_CREATED", "result", "sent").count())
                .isEqualTo(1.0);
    }

    @Test
    @DisplayName("a duplicate of an event that was already emailed is skipped")
    void duplicate() throws Exception {
        when(processed.alreadySent(eventId)).thenReturn(true);

        listener.onBookingEvent(payload("BOOKING_CREATED"));

        verifyNoInteractions(mailSender);
        verify(processed, never()).markSent(any());
        assertThat(metrics.counter("courtly.notifications", "type", "BOOKING_CREATED", "result", "duplicate").count())
                .isEqualTo(1.0);
    }

    @Test
    @DisplayName("mail server down - throws so Kafka retries, and the event is NOT marked")
    void mailFails() {
        when(processed.alreadySent(eventId)).thenReturn(false);
        doThrow(new MailSendException("connection refused")).when(mailSender).send(any(SimpleMailMessage.class));

        assertThatThrownBy(() -> listener.onBookingEvent(payload("BOOKING_CONFIRMED")))
                .isInstanceOf(MailSendException.class);
        verify(processed, never()).markSent(any());
        assertThat(metrics.counter("courtly.notifications", "type", "BOOKING_CONFIRMED", "result", "failed").count())
                .isEqualTo(1.0);
    }

    @Test
    @DisplayName("broken JSON fails fast (goes to the DLT, no retries)")
    void brokenJson() {
        assertThatThrownBy(() -> listener.onBookingEvent("not json"))
                .isInstanceOf(JsonProcessingException.class);
        verifyNoInteractions(mailSender, processed);
    }

    @Test
    @DisplayName("JSON without an eventId is invalid")
    void missingEventId() {
        assertThatThrownBy(() -> listener.onBookingEvent("{\"type\":\"BOOKING_CREATED\"}"))
                .isInstanceOf(InvalidEventException.class);
        verifyNoInteractions(mailSender);
    }

    @Test
    @DisplayName("an event type with no email is acknowledged and skipped")
    void unknownType() throws Exception {
        when(processed.alreadySent(eventId)).thenReturn(false);

        listener.onBookingEvent(payload("BOOKING_MOVED"));

        verifyNoInteractions(mailSender);
    }
}
