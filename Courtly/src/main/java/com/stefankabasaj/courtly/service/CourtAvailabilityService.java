package com.stefankabasaj.courtly.service;

import com.stefankabasaj.courtly.config.OpeningHoursProperties;
import com.stefankabasaj.courtly.dto.response.CourtAvailabilityResponse;
import com.stefankabasaj.courtly.dto.response.CourtAvailabilityResponse.Slot;
import com.stefankabasaj.courtly.entity.Booking;
import com.stefankabasaj.courtly.entity.Court;
import com.stefankabasaj.courtly.exception.BusinessException;
import com.stefankabasaj.courtly.exception.ResourceNotFoundException;
import com.stefankabasaj.courtly.repository.BookingRepository;
import com.stefankabasaj.courtly.repository.CourtRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CourtAvailabilityService {

    private final CourtRepository        courtRepository;
    private final BookingRepository      bookingRepository;
    private final OpeningHoursProperties openingHours;

    @Transactional(readOnly = true)
    public CourtAvailabilityResponse getAvailability(UUID courtId, LocalDate date) {
        Court court = courtRepository.findById(courtId)
                .orElseThrow(() -> new ResourceNotFoundException("Court", courtId));
        if (!court.isActive()) {
            throw new BusinessException("Court '" + court.getName() + "' is not currently available for booking.");
        }

        LocalDateTime opens  = openingHours.opensAt(date);
        LocalDateTime closes = openingHours.closesAt(date);
        // one query for the whole day, then check each hour in memory
        List<Booking> taken  = bookingRepository.findActiveOverlapping(
                courtId, opens, closes, Booking.BookingStatus.CANCELLED);
        LocalDateTime now    = LocalDateTime.now();

        List<Slot> slots = new ArrayList<>();
        for (LocalDateTime start = opens; start.isBefore(closes); start = start.plusHours(1)) {
            LocalDateTime end = start.plusHours(1);
            boolean available = start.isAfter(now) && !overlapsAny(start, end, taken);
            slots.add(new Slot(start, end, available));
        }
        return new CourtAvailabilityResponse(court.getId(), court.getName(), date, slots);
    }

    // same rule as the database constraint: half-open ranges, so 10-12 and 12-13 don't overlap
    private static boolean overlapsAny(LocalDateTime start, LocalDateTime end, List<Booking> bookings) {
        return bookings.stream().anyMatch(b ->
                b.getStartTime().isBefore(end) && b.getEndTime().isAfter(start));
    }
}
