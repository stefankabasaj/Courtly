package com.stefankabasaj.courtly.notifications;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    public BookingEventListener(ObjectMapper objectMapper, BookingEmails emails, ProcessedEvents processed,
                                JavaMailSender mailSender, @Value("${app.mail.from}") String from) {
        this.objectMapper = objectMapper;
        this.emails       = emails;
        this.processed    = processed;
        this.mailSender   = mailSender;
        this.from         = from;
    }

    @KafkaListener(topics = "${app.events.topic}")
    public void onBookingEvent(String payload) throws JsonProcessingException {
        BookingEvent event = objectMapper.readValue(payload, BookingEvent.class);
        if (event.eventId() == null || event.type() == null) {
            throw new InvalidEventException("Event without eventId or type: " + payload);
        }

        if (processed.alreadySent(event.eventId())) {
            log.info("Already emailed event {} ({}), skipping the duplicate", event.eventId(), event.type());
            return;
        }

        Optional<Email> email = emails.compose(event);
        if (email.isEmpty()) {
            log.debug("No email for event type {}", event.type());
            return;
        }

        mailSender.send(toMessage(email.get()));
        processed.markSent(event.eventId());
        log.info("Emailed {} for booking {} ({})", email.get().to(), event.bookingId(), event.type());
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
