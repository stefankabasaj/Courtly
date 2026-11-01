package com.stefankabasaj.courtly.notifications;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Optional;

// turns a booking event into the email the player gets. plain text on purpose
@Component
public class BookingEmails {

    private static final DateTimeFormatter SHORT_DAY = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH);
    private static final DateTimeFormatter LONG_DAY  = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", Locale.ENGLISH);
    private static final DateTimeFormatter TIME      = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH);

    public Optional<Email> compose(BookingEvent e) {
        return Optional.ofNullable(switch (e.type()) {
            case "BOOKING_CREATED" -> email(e, "Booking received", """
                    Hi,

                    We've got your booking:

                    %s

                    %s

                    Courtly
                    """.formatted(details(e), payReminder(e)));

            case "BOOKING_CONFIRMED" -> email(e, "Booking confirmed", """
                    Hi,

                    Payment received - the court is yours.

                    %s

                    See you on the court.

                    Courtly
                    """.formatted(details(e)));

            case "BOOKING_CANCELLED" -> email(e, "Booking cancelled", """
                    Hi,

                    Your booking has been cancelled.

                    %s
                    %s
                    Courtly
                    """.formatted(details(e), reasonLine(e)));

            case "BOOKING_EXPIRED" -> email(e, "Booking released", """
                    Hi,

                    We didn't get a payment in time, so this booking was cancelled and the slot released:

                    %s

                    If it's still free, you can book it again.

                    Courtly
                    """.formatted(details(e)));

            default -> null;   // a type we don't send email for (yet)
        });
    }

    private Email email(BookingEvent e, String what, String body) {
        // e.g. "Booking confirmed: Court A - Clay, Sat 1 Jun 10:00"
        String subject = what + ": " + e.courtName() + ", "
                + SHORT_DAY.format(e.startTime()) + " " + TIME.format(e.startTime());
        return new Email(e.userEmail(), subject, body);
    }

    private String details(BookingEvent e) {
        return "  " + e.courtName() + "\n"
             + "  " + LONG_DAY.format(e.startTime()) + ", "
                    + TIME.format(e.startTime()) + "-" + TIME.format(e.endTime()) + "\n"
             + "  " + price(e.totalPrice());
    }

    private String payReminder(BookingEvent e) {
        if (e.payBy() == null) {
            return "Please pay soon to keep the slot - unpaid bookings are cancelled after a few minutes.";
        }
        return "Please pay by " + TIME.format(e.payBy()) + " to keep the slot. "
                + "If it isn't paid by then, the booking is cancelled and the slot opens up again.";
    }

    private String reasonLine(BookingEvent e) {
        return e.reason() == null || e.reason().isBlank() ? "" : "\nReason: " + e.reason() + "\n";
    }

    private static String price(BigDecimal amount) {
        return amount == null ? "" : "€" + amount.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }
}
