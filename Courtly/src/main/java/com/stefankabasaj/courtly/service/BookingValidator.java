package com.stefankabasaj.courtly.service;

import com.stefankabasaj.courtly.config.OpeningHoursProperties;
import com.stefankabasaj.courtly.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;

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
        // Use the start date for both boundaries so bookings cannot cross closing time.
        if (start.isBefore(openingHours.opensAt(start.toLocalDate()))
                || end.isAfter(openingHours.closesAt(start.toLocalDate()))) {
            throw new BusinessException(
                    "Bookings must be within opening hours (" + openingHours.describe() + ").");
        }
    }

    public long durationHours(LocalDateTime start, LocalDateTime end) {
        return Duration.between(start, end).toHours();
    }

    private static boolean isOnTheHour(LocalDateTime time) {
        return time.getMinute() == 0 && time.getSecond() == 0 && time.getNano() == 0;
    }
}
