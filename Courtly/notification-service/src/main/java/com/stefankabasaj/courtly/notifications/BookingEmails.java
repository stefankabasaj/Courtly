package com.stefankabasaj.courtly.notifications;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Optional;

@Component
public class BookingEmails {

    private static final DateTimeFormatter SHORT_DAY = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH);
    private static final DateTimeFormatter LONG_DAY = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", Locale.ENGLISH);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH);

    public Optional<Email> compose(BookingEvent event) {
        return Optional.ofNullable(switch (event.type()) {
            case "BOOKING_CREATED" -> createEmail(event, "Booking received", """
                    Hi,

                    We've got your booking:

                    %s

                    %s

                    Courtly
                    """.formatted(details(event), paymentReminder(event)));

            case "BOOKING_CONFIRMED" -> createEmail(event, "Booking confirmed", """
                    Hi,

                    Payment received - the court is yours.

                    %s

                    See you on the court.

                    Courtly
                    """.formatted(details(event)));

            case "BOOKING_CANCELLED" -> createEmail(event, "Booking cancelled", """
                    Hi,

                    Your booking has been cancelled.

                    %s
                    %s
                    Courtly
                    """.formatted(details(event), reasonLine(event)));

            case "BOOKING_EXPIRED" -> createEmail(event, "Booking released", """
                    Hi,

                    We didn't get a payment in time, so this booking was cancelled and the slot released:

                    %s

                    If it's still free, you can book it again.

                    Courtly
                    """.formatted(details(event)));

            default -> null; // Ignore event types introduced by newer API versions.
        });
    }

    private Email createEmail(BookingEvent event, String subjectPrefix, String body) {
        String subject = subjectPrefix + ": " + event.courtName() + ", "
                + SHORT_DAY.format(event.startTime()) + " " + TIME.format(event.startTime());
        return new Email(event.userEmail(), subject, body);
    }

    private String details(BookingEvent event) {
        return "  " + event.courtName() + "\n"
             + "  " + LONG_DAY.format(event.startTime()) + ", "
                    + TIME.format(event.startTime()) + "-" + TIME.format(event.endTime()) + "\n"
             + "  " + price(event.totalPrice());
    }

    private String paymentReminder(BookingEvent event) {
        if (event.payBy() == null) {
            return "Please pay soon to keep the slot - unpaid bookings are cancelled after a few minutes.";
        }
        return "Please pay by " + TIME.format(event.payBy()) + " to keep the slot. "
                + "If it isn't paid by then, the booking is cancelled and the slot opens up again.";
    }

    private String reasonLine(BookingEvent event) {
        return event.reason() == null || event.reason().isBlank() ? "" : "\nReason: " + event.reason() + "\n";
    }

    private static String price(BigDecimal amount) {
        return amount == null ? "" : "€" + amount.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }
}
