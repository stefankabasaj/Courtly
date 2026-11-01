package com.stefankabasaj.courtly.notifications;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("BookingEmails")
class BookingEmailsTest {

    private final BookingEmails emails = new BookingEmails();

    private BookingEvent event(String type, String reason, LocalDateTime payBy) {
        LocalDateTime start = LocalDateTime.of(2030, 6, 1, 10, 0);
        return new BookingEvent(UUID.randomUUID(), type, LocalDateTime.now(), UUID.randomUUID(),
                "stefan@courtly.com", "Court A - Clay", start, start.plusHours(2),
                new BigDecimal("50"), reason, payBy);
    }

    @Test
    @DisplayName("created - subject, details and the pay-by time")
    void created() {
        Email email = emails.compose(event("BOOKING_CREATED", null, LocalDateTime.of(2030, 5, 30, 18, 15))).orElseThrow();

        assertThat(email.to()).isEqualTo("stefan@courtly.com");
        assertThat(email.subject()).isEqualTo("Booking received: Court A - Clay, Sat 1 Jun 10:00");
        assertThat(email.body())
                .contains("Court A - Clay")
                .contains("Saturday 1 June 2030, 10:00-12:00")
                .contains("€50.00")
                .contains("Please pay by 18:15");
    }

    @Test
    @DisplayName("created without payBy still tells them to pay")
    void createdWithoutPayBy() {
        assertThat(emails.compose(event("BOOKING_CREATED", null, null)).orElseThrow().body())
                .contains("Please pay soon");
    }

    @Test
    @DisplayName("confirmed")
    void confirmed() {
        Email email = emails.compose(event("BOOKING_CONFIRMED", null, null)).orElseThrow();
        assertThat(email.subject()).startsWith("Booking confirmed: ");
        assertThat(email.body()).contains("Payment received");
    }

    @Test
    @DisplayName("cancelled - shows the reason when there is one")
    void cancelled() {
        assertThat(emails.compose(event("BOOKING_CANCELLED", "Court maintenance", null)).orElseThrow().body())
                .contains("Reason: Court maintenance");
        assertThat(emails.compose(event("BOOKING_CANCELLED", null, null)).orElseThrow().body())
                .doesNotContain("Reason:");
    }

    @Test
    @DisplayName("expired")
    void expired() {
        Email email = emails.compose(event("BOOKING_EXPIRED", "Not paid in time", null)).orElseThrow();
        assertThat(email.subject()).startsWith("Booking released: ");
        assertThat(email.body()).contains("didn't get a payment in time");
    }

    @Test
    @DisplayName("an event type we don't know - no email")
    void unknownType() {
        assertThat(emails.compose(event("BOOKING_MOVED", null, null))).isEmpty();
    }
}
