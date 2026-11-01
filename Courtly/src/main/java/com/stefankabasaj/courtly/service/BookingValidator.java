package com.stefankabasaj.courtly.service;

import com.stefankabasaj.courtly.config.OpeningHoursProperties;
import com.stefankabasaj.courtly.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;

// pulled out of BookingService so the time rules sit in one place and can be
// unit-tested on their own
@Component
@RequiredArgsConstructor
public class BookingValidator {

    private static final int MIN_HOURS = 1;
    private static final int MAX_HOURS = 4;

    private final OpeningHoursProperties openingHours;

    public void validateBookingTimes(LocalDateTime start, LocalDateTime end) {
        if (!end.isAfter(start)) {
            throw new BusinessException("End time must be after start time.");
        }
        // full hours only - 10:30 starts just make everything messier
        if (!isOnTheHour(start)) {
            throw new BusinessException(
                    "Bookings must start exactly on the hour (e.g. 10:00, 14:00). Got: " + start);
        }
        if (!isOnTheHour(end)) {
            throw new BusinessException(
                    "Bookings must end exactly on the hour (e.g. 11:00, 15:00). Got: " + end);
        }
        long hours = durationHours(start, end);
        if (hours < MIN_HOURS || hours > MAX_HOURS) {
            throw new BusinessException(
                    "Booking duration must be between " + MIN_HOURS + " and " + MAX_HOURS +
                    " hours. Got: " + hours + "h.");
        }
        // measured from the start's day, so a booking can't run past closing into the next day
        if (start.isBefore(openingHours.opensAt(start.toLocalDate()))
                || end.isAfter(openingHours.closesAt(start.toLocalDate()))) {
            throw new BusinessException(
                    "Bookings must be within opening hours (" + openingHours.describe() + ").");
        }
    }

    public long durationHours(LocalDateTime start, LocalDateTime end) {
        return Duration.between(start, end).toHours();
    }

    private static boolean isOnTheHour(LocalDateTime t) {
        return t.getMinute() == 0 && t.getSecond() == 0 && t.getNano() == 0;
    }
}
