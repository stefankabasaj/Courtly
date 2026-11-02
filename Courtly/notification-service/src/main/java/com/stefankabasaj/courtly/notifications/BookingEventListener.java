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
 * One email per booking event.
 *
 * Order matters here: check -> send -> mark. Marking first and crashing before
 * the send would lose the email for good; this way a crash between the send
 * and the mark means one duplicate email, which is the better failure.
 *
 * Anything thrown goes to the error handler (KafkaConfig): a failed send is
 * retried, a message that can't be parsed goes straight to the DLT.
 */
@Component
public class BookingEventListener {

    private static final Logger log = LoggerFactory.getLogger(BookingEventListener.class);

    private final ObjectMapper     objectMapper;
    private final BookingEmails    emails;
    private final ProcessedEvents  processed;
    private final JavaMailSender   mailSender;
    private final String           from;
    private final MeterRegistry    metrics;

    public BookingEventListener(ObjectMapper objectMapper, BookingEmails emails, ProcessedEvents processed,
                                JavaMailSender mailSender, @Value("${app.mail.from}") String from,
                                MeterRegistry metrics) {
        this.objectMapper = objectMapper;
        this.emails       = emails;
        this.processed    = processed;
        this.mailSender   = mailSender;
        this.from         = from;
        this.metrics      = metrics;
    }

    @KafkaListener(topics = "${app.events.topic}")
    public void onBookingEvent(String payload) throws JsonProcessingException {
        BookingEvent event;
        try {
            event = objectMapper.readValue(payload, BookingEvent.class);
        } catch (JsonProcessingException e) {
            count("unknown", "invalid");
            throw e;
        }
        if (event.eventId() == null || event.type() == null) {
            count("unknown", "invalid");
            throw new InvalidEventException("Event without eventId or type: " + payload);
        }

        // same request id as the API's log lines for the request behind this event
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
        if (processed.alreadySent(event.eventId())) {
            count(event.type(), "duplicate");
            log.info("Already emailed event {} ({}), skipping the duplicate", event.eventId(), event.type());
            return;
        }

        Optional<Email> email = emails.compose(event);
        if (email.isEmpty()) {
            count(event.type(), "ignored");
            log.debug("No email for event type {}", event.type());
            return;
        }

        try {
            mailSender.send(toMessage(email.get()));
        } catch (RuntimeException e) {
            count(event.type(), "failed");
            throw e;
        }
        processed.markSent(event.eventId());
        count(event.type(), "sent");
        log.info("Emailed {} for booking {} ({})", email.get().to(), event.bookingId(), event.type());
    }

    // courtly_notifications_total{type, result="sent|duplicate|ignored|failed|invalid"}
    private void count(String type, String result) {
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
