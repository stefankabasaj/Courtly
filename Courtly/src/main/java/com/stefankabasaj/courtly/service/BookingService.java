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

    // must cancel at least this many hours ahead
    private static final int CANCEL_NOTICE_HOURS = 2;

    private final BookingRepository    bookingRepository;
    private final CourtRepository      courtRepository;
    private final UserRepository       userRepository;
    private final BookingValidator     bookingValidator;
    private final BookingEventRecorder events;
    private final BookingProperties    bookingProperties;

    @Transactional
    public BookingResponse createBooking(UUID userId, BookingRequest request) {
        log.info("Creating booking userId={} courtId={}", userId, request.courtId());

        User  user  = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", userId));
        Court court = courtRepository.findById(request.courtId())
                .orElseThrow(() -> new ResourceNotFoundException("Court", request.courtId()));

        // tokens are checked without a DB hit, so a deactivated user's token keeps
        // working until it expires. at least don't let it create new bookings
        if (!user.isActive()) {
            throw new BusinessException("This account has been deactivated. Please contact support.");
        }
        if (!court.isActive()) {
            throw new BusinessException("Court '" + court.getName() + "' is not currently available for booking.");
        }

        bookingValidator.validateBookingTimes(request.startTime(), request.endTime());

        // app-level check first for a clean error message.
        // the exclusion constraint in the DB (V2 migration) catches the race-condition case
        if (bookingRepository.existsOverlappingBooking(
                request.courtId(), request.startTime(), request.endTime())) {
            throw new ConflictException(
                    "Court '" + court.getName() + "' is already booked for " +
                    request.startTime() + " – " + request.endTime() +
                    ". Please choose a different time.");
        }

        long       hours = bookingValidator.durationHours(request.startTime(), request.endTime());
        BigDecimal total = court.getPricePerHour().multiply(BigDecimal.valueOf(hours));

        Booking booking = Booking.builder()
                .user(user)
                .court(court)
                .startTime(request.startTime())
                .endTime(request.endTime())
                .totalPrice(total)
                .status(Booking.BookingStatus.PENDING)
                .build();

        Booking saved = bookingRepository.save(booking);
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

        // admins see everything, normal users only their own.
        // 404 not 403 on purpose - 403 would leak that the booking exists
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

        // 404 not 400/403 - same reasoning as getBookingById, don't reveal other people's bookings
        if (!booking.getUser().getId().equals(userId)) {
            throw new ResourceNotFoundException("Booking", bookingId);
        }
        if (booking.getStatus() == Booking.BookingStatus.CANCELLED) {
            throw new BusinessException("This booking is already cancelled.");
        }
        // paid bookings can't be self-cancelled - that's a refund, which we handle via support
        if (booking.getStatus() == Booking.BookingStatus.CONFIRMED) {
            throw new BusinessException(
                    "Paid bookings cannot be cancelled through the app. Please contact support.");
        }
        if (!hasEnoughNoticeToCancel(booking)) {
            throw new BusinessException(
                    "Bookings must be cancelled at least " + CANCEL_NOTICE_HOURS +
                    " hours in advance.");
        }

        booking.setStatus(Booking.BookingStatus.CANCELLED);
        booking.setCancellationReason(
                request != null && StringUtils.hasText(request.reason())
                        ? request.reason().trim()
                        : "Cancelled by user");

        Booking saved = bookingRepository.save(booking);
        events.record(saved, BookingEventType.BOOKING_CANCELLED);
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

        booking.setStatus(Booking.BookingStatus.CANCELLED);
        booking.setCancellationReason(request.reason());

        Booking saved = bookingRepository.save(booking);
        events.record(saved, BookingEventType.BOOKING_CANCELLED);
        log.info("Booking {} admin-cancelled", bookingId);
        return toResponse(saved);
    }

    private Booking findOrThrow(UUID id) {
        return bookingRepository.findByIdWithDetails(id)
                .orElseThrow(() -> new ResourceNotFoundException("Booking", id));
    }

    // one rule for both "can this be cancelled?" (the response flag) and the actual
    // cancel - they used to disagree at exactly CANCEL_NOTICE_HOURS before the start
    private boolean hasEnoughNoticeToCancel(Booking booking) {
        return !LocalDateTime.now().plusHours(CANCEL_NOTICE_HOURS).isAfter(booking.getStartTime());
    }

    boolean isCancellable(Booking booking) {
        return booking.getStatus() == Booking.BookingStatus.PENDING
                && hasEnoughNoticeToCancel(booking);
    }

    BookingResponse toResponse(Booking b) {
        long hours = bookingValidator.durationHours(b.getStartTime(), b.getEndTime());
        return new BookingResponse(
                b.getId(),
                b.getUser().getId(),
                b.getUser().getEmail(),
                b.getCourt().getId(),
                b.getCourt().getName(),
                b.getCourt().getSurface().name(),
                b.getStartTime(),
                b.getEndTime(),
                hours,
                b.getCourt().getPricePerHour(),
                b.getTotalPrice(),
                b.getStatus().name(),
                b.getCancellationReason(),
                isCancellable(b),
                b.getStatus() == Booking.BookingStatus.PENDING && b.getCreatedAt() != null
                        ? bookingProperties.payBy(b.getCreatedAt()) : null,
                b.getCreatedAt(),
                b.getUpdatedAt()
        );
    }
}
