package com.stefankabasaj.courtly.notifications;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Sends booking emails and records delivered event IDs.
 * Check, send, then mark: a crash after sending can produce a duplicate, while
 * marking before sending could lose an email. KafkaConfig handles retries and
 * sends invalid messages to the dead-letter topic.
 */
@Component
public class BookingEventListener {

    private static final Logger log = LoggerFactory.getLogger(BookingEventListener.class);

    private final ObjectMapper objectMapper;
    private final BookingEmails emails;
    private final ProcessedEvents processedEvents;
    private final JavaMailSender mailSender;
    private final String from;
    private final MeterRegistry metrics;

    public BookingEventListener(ObjectMapper objectMapper, BookingEmails emails, ProcessedEvents processedEvents,
                                JavaMailSender mailSender, @Value("${app.mail.from}") String from,
                                MeterRegistry metrics) {
        this.objectMapper = objectMapper;
        this.emails = emails;
        this.processedEvents = processedEvents;
        this.mailSender = mailSender;
        this.from = from;
        this.metrics = metrics;
    }

    @KafkaListener(topics = "${app.events.topic}")
    public void onBookingEvent(String payload) throws JsonProcessingException {
        BookingEvent event;
        try {
            event = objectMapper.readValue(payload, BookingEvent.class);
        } catch (JsonProcessingException e) {
            recordNotification("unknown", "invalid");
            throw e;
        }
        if (event.eventId() == null || event.type() == null) {
            recordNotification("unknown", "invalid");
            throw new InvalidEventException("Event without eventId or type: " + payload);
        }

        // Correlate consumer logs with the API request that created the event.
        if (event.requestId() != null) {
            MDC.put("requestId", event.requestId());
        }
        try {
            handle(event);
        } finally {
            MDC.remove("requestId");
        }
    }

    private void handle(BookingEvent event) {
        if (processedEvents.alreadySent(event.eventId())) {
            recordNotification(event.type(), "duplicate");
            log.info("Already emailed event {} ({}), skipping the duplicate", event.eventId(), event.type());
            return;
        }

        Optional<Email> email = emails.compose(event);
        if (email.isEmpty()) {
            recordNotification(event.type(), "ignored");
            log.debug("No email for event type {}", event.type());
            return;
        }

        Email notification = email.get();
        try {
            mailSender.send(toMessage(notification));
        } catch (RuntimeException e) {
            recordNotification(event.type(), "failed");
            throw e;
        }
        processedEvents.markSent(event.eventId());
        recordNotification(event.type(), "sent");
        log.info("Emailed {} for booking {} ({})", notification.to(), event.bookingId(), event.type());
    }

    private void recordNotification(String type, String result) {
        metrics.counter("courtly.notifications", "type", type, "result", result).increment();
    }

    private SimpleMailMessage toMessage(Email email) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(email.to());
        message.setSubject(email.subject());
        message.setText(email.body());
        return message;
    }
}
