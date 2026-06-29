package com.stefankabasaj.courtly.service;

import com.stefankabasaj.courtly.dto.request.AdminCancelRequest;
import com.stefankabasaj.courtly.dto.request.BookingRequest;
import com.stefankabasaj.courtly.dto.request.CancelBookingRequest;
import com.stefankabasaj.courtly.dto.response.BookingResponse;
import com.stefankabasaj.courtly.dto.response.PageResponse;
import com.stefankabasaj.courtly.entity.Booking;
import com.stefankabasaj.courtly.entity.Court;
import com.stefankabasaj.courtly.entity.User;
import com.stefankabasaj.courtly.exception.BusinessException;
import com.stefankabasaj.courtly.exception.ConflictException;
import com.stefankabasaj.courtly.exception.ResourceNotFoundException;
import com.stefankabasaj.courtly.repository.BookingRepository;
import com.stefankabasaj.courtly.repository.CourtRepository;
import com.stefankabasaj.courtly.repository.UserRepository;
import com.stefankabasaj.courtly.security.AuthenticatedUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.springframework.test.context.ActiveProfiles;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ActiveProfiles("test")
@ExtendWith(MockitoExtension.class)
@DisplayName("BookingService")
class BookingServiceTest {

    @Mock  BookingRepository bookingRepository;
    @Mock  CourtRepository   courtRepository;
    @Mock  UserRepository    userRepository;
    @Spy   BookingValidator  bookingValidator = new BookingValidator();
    @InjectMocks BookingService bookingService;

    private User  user;
    private Court court;
    private LocalDateTime start;
    private LocalDateTime end;

    @BeforeEach
    void setUp() {
        user  = User.builder().id(UUID.randomUUID())
                .email("stefan@courtly.com").role(User.Role.USER).active(true).build();
        court = Court.builder().id(UUID.randomUUID())
                .name("Court A").surface(Court.Surface.CLAY)
                .pricePerHour(new BigDecimal("25.00")).active(true).build();
        start = LocalDateTime.now().plusDays(1)
                .withHour(10).withMinute(0).withSecond(0).withNano(0);
        end   = start.plusHours(2);
    }

    private Booking buildBooking(Booking.BookingStatus status) {
        return Booking.builder()
                .id(UUID.randomUUID()).user(user).court(court)
                .startTime(start).endTime(end)
                .totalPrice(new BigDecimal("50.00"))
                .status(status).build();
    }

    private AuthenticatedUser userPrincipal() {
        return new AuthenticatedUser(user.getId(), user.getEmail(), "USER");
    }

    private AuthenticatedUser adminPrincipal() {
        return new AuthenticatedUser(UUID.randomUUID(), "admin@courtly.com", "ADMIN");
    }

    @Nested @DisplayName("createBooking")
    class Create {

        @Test @DisplayName("success - correct total price and duration")
        void success() {
            when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
            when(courtRepository.findById(court.getId())).thenReturn(Optional.of(court));
            when(bookingRepository.existsOverlappingBooking(any(), any(), any())).thenReturn(false);
            when(bookingRepository.save(any())).thenAnswer(i -> i.getArgument(0));

            BookingResponse resp = bookingService.createBooking(
                    user.getId(), new BookingRequest(court.getId(), start, end));

            assertThat(resp.status()).isEqualTo("PENDING");
            assertThat(resp.totalPrice()).isEqualByComparingTo("50.00");
            assertThat(resp.durationHours()).isEqualTo(2);
            assertThat(resp.courtName()).isEqualTo("Court A");
            verify(bookingRepository).save(any());
        }

        @Test @DisplayName("court not found → 404")
        void courtNotFound() {
            when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
            when(courtRepository.findById(any())).thenReturn(Optional.empty());

            assertThatThrownBy(() -> bookingService.createBooking(
                    user.getId(), new BookingRequest(UUID.randomUUID(), start, end)))
                    .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test @DisplayName("inactive court → BusinessException")
        void inactiveCourt() {
            court.setActive(false);
            when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
            when(courtRepository.findById(court.getId())).thenReturn(Optional.of(court));

            assertThatThrownBy(() -> bookingService.createBooking(
                    user.getId(), new BookingRequest(court.getId(), start, end)))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("not currently available");
        }

        @Test @DisplayName("time not on the hour → BusinessException")
        void notOnHour() {
            when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
            when(courtRepository.findById(court.getId())).thenReturn(Optional.of(court));

            assertThatThrownBy(() -> bookingService.createBooking(
                    user.getId(), new BookingRequest(court.getId(), start.withMinute(30), end)))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("on the hour");
        }

        @Test @DisplayName("over 4h duration → BusinessException")
        void tooLong() {
            when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
            when(courtRepository.findById(court.getId())).thenReturn(Optional.of(court));

            assertThatThrownBy(() -> bookingService.createBooking(
                    user.getId(), new BookingRequest(court.getId(), start, start.plusHours(5))))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("between 1 and 4 hours");
        }

        @Test @DisplayName("overlapping slot → ConflictException")
        void overlap() {
            when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
            when(courtRepository.findById(court.getId())).thenReturn(Optional.of(court));
            when(bookingRepository.existsOverlappingBooking(any(), any(), any())).thenReturn(true);

            assertThatThrownBy(() -> bookingService.createBooking(
                    user.getId(), new BookingRequest(court.getId(), start, end)))
                    .isInstanceOf(ConflictException.class)
                    .hasMessageContaining("already booked");

            verify(bookingRepository, never()).save(any());
        }
    }

    @Nested @DisplayName("getBookingById")
    class GetById {

        @Test @DisplayName("user can view their own booking")
        void userViewsOwn() {
            Booking booking = buildBooking(Booking.BookingStatus.PENDING);
            when(bookingRepository.findByIdWithDetails(booking.getId()))
                    .thenReturn(Optional.of(booking));

            BookingResponse resp = bookingService.getBookingById(booking.getId(), userPrincipal());
            assertThat(resp.userId()).isEqualTo(user.getId());
        }

        @Test @DisplayName("user cannot view another user's booking → 404")
        void userViewsOther() {
            Booking booking = buildBooking(Booking.BookingStatus.PENDING);
            when(bookingRepository.findByIdWithDetails(booking.getId()))
                    .thenReturn(Optional.of(booking));

            // Different user trying to access this booking
            AuthenticatedUser otherUser = new AuthenticatedUser(UUID.randomUUID(), "other@courtly.com", "USER");
            assertThatThrownBy(() -> bookingService.getBookingById(booking.getId(), otherUser))
                    .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test @DisplayName("admin can view any booking")
        void adminViewsAny() {
            Booking booking = buildBooking(Booking.BookingStatus.PENDING);
            when(bookingRepository.findByIdWithDetails(booking.getId()))
                    .thenReturn(Optional.of(booking));

            // Admin has a different userId - but should still be able to view
            BookingResponse resp = bookingService.getBookingById(booking.getId(), adminPrincipal());
            assertThat(resp.userId()).isEqualTo(user.getId());
        }
    }

    @Nested @DisplayName("cancelBooking")
    class Cancel {

        @Test @DisplayName("owner cancels PENDING booking")
        void ownerCancels() {
            Booking booking = buildBooking(Booking.BookingStatus.PENDING);
            when(bookingRepository.findByIdWithDetails(booking.getId()))
                    .thenReturn(Optional.of(booking));
            when(bookingRepository.save(any())).thenAnswer(i -> i.getArgument(0));

            BookingResponse resp = bookingService.cancelBooking(
                    booking.getId(), user.getId(), new CancelBookingRequest("Too busy"));

            assertThat(resp.status()).isEqualTo("CANCELLED");
            assertThat(resp.cancellationReason()).isEqualTo("Too busy");
        }

        @Test @DisplayName("non-owner cannot cancel → BusinessException")
        void nonOwner() {
            Booking booking = buildBooking(Booking.BookingStatus.PENDING);
            when(bookingRepository.findByIdWithDetails(booking.getId()))
                    .thenReturn(Optional.of(booking));

            assertThatThrownBy(() -> bookingService.cancelBooking(
                    booking.getId(), UUID.randomUUID(), null))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("only cancel your own");
        }

        @Test @DisplayName("CONFIRMED booking cannot be self-cancelled")
        void confirmedBlocked() {
            Booking booking = buildBooking(Booking.BookingStatus.CONFIRMED);
            when(bookingRepository.findByIdWithDetails(booking.getId()))
                    .thenReturn(Optional.of(booking));

            assertThatThrownBy(() -> bookingService.cancelBooking(
                    booking.getId(), user.getId(), null))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("Paid bookings");
        }
    }

    @Nested @DisplayName("adminCancelBooking")
    class AdminCancel {

        @Test @DisplayName("admin can cancel a CONFIRMED booking")
        void adminCancelsConfirmed() {
            Booking booking = buildBooking(Booking.BookingStatus.CONFIRMED);
            when(bookingRepository.findByIdWithDetails(booking.getId()))
                    .thenReturn(Optional.of(booking));
            when(bookingRepository.save(any())).thenAnswer(i -> i.getArgument(0));

            BookingResponse resp = bookingService.adminCancelBooking(
                    booking.getId(), new AdminCancelRequest("Court maintenance"));

            assertThat(resp.status()).isEqualTo("CANCELLED");
            assertThat(resp.cancellationReason()).isEqualTo("Court maintenance");
        }

        @Test @DisplayName("already cancelled → BusinessException")
        void alreadyCancelled() {
            Booking booking = buildBooking(Booking.BookingStatus.CANCELLED);
            when(bookingRepository.findByIdWithDetails(booking.getId()))
                    .thenReturn(Optional.of(booking));

            assertThatThrownBy(() -> bookingService.adminCancelBooking(
                    booking.getId(), new AdminCancelRequest("reason")))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("already cancelled");
        }
    }

    @Nested @DisplayName("getAllBookings")
    class Pagination {

        @Test @DisplayName("returns correct page metadata")
        void paginationMetadata() {
            Booking b = buildBooking(Booking.BookingStatus.PENDING);
            var page = new PageImpl<>(List.of(b), PageRequest.of(0, 20), 1L);
            when(bookingRepository.findAllWithDetails(any())).thenReturn(page);

            PageResponse<BookingResponse> result = bookingService.getAllBookings(0, 20);

            assertThat(result.content()).hasSize(1);
            assertThat(result.totalElements()).isEqualTo(1L);
            assertThat(result.totalPages()).isEqualTo(1);
            assertThat(result.last()).isTrue();
        }
    }
}
