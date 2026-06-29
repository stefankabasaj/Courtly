package com.stefankabasaj.courtly.service;

import com.stefankabasaj.courtly.exception.BusinessException;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;

// pulled out of BookingService so the time rules sit in one place and can be
// unit-tested on their own
@Component
public class BookingValidator {

    private static final int MIN_HOURS = 1;
    private static final int MAX_HOURS = 4;

    public void validateBookingTimes(LocalDateTime start, LocalDateTime end) {
        if (!end.isAfter(start)) {
            throw new BusinessException("End time must be after start time.");
        }
        // full hours only - 10:30 starts just make everything messier
        if (start.getMinute() != 0 || start.getSecond() != 0 || start.getNano() != 0) {
            throw new BusinessException(
                    "Bookings must start exactly on the hour (e.g. 10:00, 14:00). Got: " + start);
        }
        if (end.getMinute() != 0 || end.getSecond() != 0 || end.getNano() != 0) {
            throw new BusinessException(
                    "Bookings must end exactly on the hour (e.g. 11:00, 15:00). Got: " + end);
        }
        long hours = Duration.between(start, end).toHours();
        if (hours < MIN_HOURS || hours > MAX_HOURS) {
            throw new BusinessException(
                    "Booking duration must be between " + MIN_HOURS + " and " + MAX_HOURS +
                    " hours. Got: " + hours + "h.");
        }
    }

    public long durationHours(LocalDateTime start, LocalDateTime end) {
        return Duration.between(start, end).toHours();
    }
}
