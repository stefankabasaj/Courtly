package com.stefankabasaj.courtly.service;

import com.stefankabasaj.courtly.config.BookingProperties;
import com.stefankabasaj.courtly.dto.request.AdminCancelRequest;
import com.stefankabasaj.courtly.dto.request.BookingRequest;
import com.stefankabasaj.courtly.dto.request.CancelBookingRequest;
import com.stefankabasaj.courtly.dto.response.BookingResponse;
import com.stefankabasaj.courtly.dto.response.PageResponse;
import com.stefankabasaj.courtly.entity.Booking;
import com.stefankabasaj.courtly.entity.Court;
import com.stefankabasaj.courtly.entity.User;
import com.stefankabasaj.courtly.event.BookingEventRecorder;
import com.stefankabasaj.courtly.event.BookingEventType;
import com.stefankabasaj.courtly.exception.BusinessException;
import com.stefankabasaj.courtly.exception.ConflictException;
import com.stefankabasaj.courtly.exception.ResourceNotFoundException;
import com.stefankabasaj.courtly.repository.BookingRepository;
import com.stefankabasaj.courtly.repository.CourtRepository;
import com.stefankabasaj.courtly.repository.UserRepository;
import com.stefankabasaj.courtly.security.AuthenticatedUser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class BookingService {

    private static final int CANCEL_NOTICE_HOURS = 2;

    private final BookingRepository bookingRepository;
    private final CourtRepository courtRepository;
    private final UserRepository userRepository;
    private final BookingValidator bookingValidator;
    private final BookingEventRecorder events;
    private final BookingProperties bookingProperties;

    @Transactional
    public BookingResponse createBooking(UUID userId, BookingRequest request) {
        log.info("Creating booking userId={} courtId={}", userId, request.courtId());

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", userId));
        Court court = courtRepository.findById(request.courtId())
                .orElseThrow(() -> new ResourceNotFoundException("Court", request.courtId()));

        // JWT authentication does not check account status in the database.
        if (!user.isActive()) {
            throw new BusinessException("This account has been deactivated. Please contact support.");
        }
        if (!court.isActive()) {
            throw new BusinessException("Court '" + court.getName() + "' is not currently available for booking.");
        }

        bookingValidator.validateBookingTimes(request.startTime(), request.endTime());

        // Give a useful error here; the database constraint handles concurrent requests.
        if (bookingRepository.existsOverlappingBooking(
                request.courtId(), request.startTime(), request.endTime())) {
            throw new ConflictException(
                    "Court '" + court.getName() + "' is already booked for " +
                    request.startTime() + " – " + request.endTime() +
                    ". Please choose a different time.");
        }

        long hours = bookingValidator.durationHours(request.startTime(), request.endTime());
        BigDecimal total = court.getPricePerHour().multiply(BigDecimal.valueOf(hours));

        Booking booking = Booking.builder()
                .user(user)
                .court(court)
                .startTime(request.startTime())
                .endTime(request.endTime())
                .totalPrice(total)
                .status(Booking.BookingStatus.PENDING)
                .build();

        // Flush before building the response so timestamps and the payment deadline are available.
        Booking saved = bookingRepository.saveAndFlush(booking);
        events.record(saved, BookingEventType.BOOKING_CREATED);
        log.info("Booking created id={} totalPrice={}", saved.getId(), saved.getTotalPrice());
        return toResponse(saved);
    }

    @Transactional(readOnly = true)
    public List<BookingResponse> getMyBookings(UUID userId) {
        return bookingRepository.findByUserIdWithDetails(userId)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public BookingResponse getBookingById(UUID bookingId, AuthenticatedUser principal) {
        Booking booking = findOrThrow(bookingId);

        // Return 404 for other users' bookings to avoid revealing that they exist.
        if (!principal.canAccess(booking.getUser().getId())) {
            throw new ResourceNotFoundException("Booking", bookingId);
        }

        return toResponse(booking);
    }

    @Transactional
    public BookingResponse cancelBooking(UUID bookingId, UUID userId,
                                         CancelBookingRequest request) {
        log.info("User {} cancelling booking {}", userId, bookingId);
        Booking booking = findOrThrow(bookingId);

        if (!booking.getUser().getId().equals(userId)) {
            throw new ResourceNotFoundException("Booking", bookingId);
        }
        if (booking.getStatus() == Booking.BookingStatus.CANCELLED) {
            throw new BusinessException("This booking is already cancelled.");
        }
        // Paid bookings need support because self-service refunds are not implemented.
        if (booking.getStatus() == Booking.BookingStatus.CONFIRMED) {
            throw new BusinessException(
                    "Paid bookings cannot be cancelled through the app. Please contact support.");
        }
        if (!hasEnoughNoticeToCancel(booking)) {
            throw new BusinessException(
                    "Bookings must be cancelled at least " + CANCEL_NOTICE_HOURS +
                    " hours in advance.");
        }

        String reason = request != null && StringUtils.hasText(request.reason())
                ? request.reason().trim()
                : "Cancelled by user";
        Booking saved = cancelAndRecord(booking, reason);
        log.info("Booking {} cancelled by user {}", bookingId, userId);
        return toResponse(saved);
    }

    @Transactional(readOnly = true)
    public PageResponse<BookingResponse> getAllBookings(int page, int size) {
        Page<Booking> result = bookingRepository.findAllWithDetails(PageRequest.of(page, size));
        List<BookingResponse> content = result.getContent().stream().map(this::toResponse).toList();
        return PageResponse.of(content, page, size, result.getTotalElements());
    }

    @Transactional
    public BookingResponse adminCancelBooking(UUID bookingId, AdminCancelRequest request) {
        log.info("Admin cancelling booking {} reason='{}'", bookingId, request.reason());
        Booking booking = findOrThrow(bookingId);

        if (booking.getStatus() == Booking.BookingStatus.CANCELLED) {
            throw new BusinessException("This booking is already cancelled.");
        }

        Booking saved = cancelAndRecord(booking, request.reason());
        log.info("Booking {} admin-cancelled", bookingId);
        return toResponse(saved);
    }

    private Booking cancelAndRecord(Booking booking, String reason) {
        booking.setStatus(Booking.BookingStatus.CANCELLED);
        booking.setCancellationReason(reason);

        // The response needs the updated timestamp before the transaction commits.
        Booking saved = bookingRepository.saveAndFlush(booking);
        events.record(saved, BookingEventType.BOOKING_CANCELLED);
        return saved;
    }

    private Booking findOrThrow(UUID id) {
        return bookingRepository.findByIdWithDetails(id)
                .orElseThrow(() -> new ResourceNotFoundException("Booking", id));
    }

    // Share the boundary rule with the response flag, including exactly two hours' notice.
    private boolean hasEnoughNoticeToCancel(Booking booking) {
        return !LocalDateTime.now().plusHours(CANCEL_NOTICE_HOURS).isAfter(booking.getStartTime());
    }

    boolean isCancellable(Booking booking) {
        return booking.getStatus() == Booking.BookingStatus.PENDING
                && hasEnoughNoticeToCancel(booking);
    }

    BookingResponse toResponse(Booking booking) {
        long hours = bookingValidator.durationHours(booking.getStartTime(), booking.getEndTime());
        return new BookingResponse(
                booking.getId(),
                booking.getUser().getId(),
                booking.getUser().getEmail(),
                booking.getCourt().getId(),
                booking.getCourt().getName(),
                booking.getCourt().getSurface().name(),
                booking.getStartTime(),
                booking.getEndTime(),
                hours,
                booking.getCourt().getPricePerHour(),
                booking.getTotalPrice(),
                booking.getStatus().name(),
                booking.getCancellationReason(),
                isCancellable(booking),
                booking.getStatus() == Booking.BookingStatus.PENDING && booking.getCreatedAt() != null
                        ? bookingProperties.payBy(booking.getCreatedAt()) : null,
                booking.getCreatedAt(),
                booking.getUpdatedAt()
        );
    }
}
