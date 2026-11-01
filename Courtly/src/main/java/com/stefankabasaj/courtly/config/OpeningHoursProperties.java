package com.stefankabasaj.courtly.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.LocalDate;
import java.time.LocalDateTime;

// app.courts.* - when the courts are open. Bookings must fit inside these hours
@ConfigurationProperties(prefix = "app.courts")
public record OpeningHoursProperties(
        @DefaultValue("7")  int openingHour,
        @DefaultValue("23") int closingHour
) {
    public OpeningHoursProperties {
        // closing hour 24 = open until midnight
        if (openingHour < 0 || closingHour > 24 || openingHour >= closingHour) {
            throw new IllegalArgumentException(
                    "Invalid opening hours " + openingHour + "-" + closingHour
                    + ": need 0 <= opening < closing <= 24");
        }
    }

    public LocalDateTime opensAt(LocalDate date) {
        return date.atStartOfDay().plusHours(openingHour);
    }

    public LocalDateTime closesAt(LocalDate date) {
        return date.atStartOfDay().plusHours(closingHour);
    }

    public String describe() {
        return String.format("%02d:00-%02d:00", openingHour, closingHour);
    }
}
