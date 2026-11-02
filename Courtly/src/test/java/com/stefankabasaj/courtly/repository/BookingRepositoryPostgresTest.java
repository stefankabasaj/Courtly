package com.stefankabasaj.courtly.repository;

import com.stefankabasaj.courtly.entity.Booking;
import com.stefankabasaj.courtly.entity.Court;
import com.stefankabasaj.courtly.entity.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("BookingRepository on PostgreSQL")
class BookingRepositoryPostgresTest extends PostgresRepositoryTest {

    @Autowired BookingRepository bookings;
    @Autowired CourtRepository   courts;
    @Autowired UserRepository    users;
    @Autowired JdbcTemplate      jdbc;
    @Autowired PlatformTransactionManager txManager;

    private User  user;
    private Court court;
    private Court otherCourt;
    private final LocalDate day = LocalDate.now().plusDays(30);

    @BeforeEach
    void setUp() {
        // the four seeded courts come from the V1 migration
        List<Court> seeded = courts.findAllByOrderByNameAsc();
        court = seeded.get(0);
        otherCourt = seeded.get(1);
        user = users.saveAndFlush(User.builder()
                .email("pg-" + UUID.randomUUID() + "@courtly.test")
                .passwordHash("x").role(User.Role.USER).build());
    }

    private Booking booking(Court c, int from, int to, Booking.BookingStatus status) {
        return Booking.builder()
                .user(user).court(c)
                .startTime(day.atTime(from, 0)).endTime(day.atTime(to, 0))
                .totalPrice(new BigDecimal("25.00"))
                .status(status)
                .build();
    }

    // --- the V2 exclusion constraint -----------------------------------------------

    @Test
    @DisplayName("an overlapping booking on the same court is rejected by the database")
    void overlapRejected() {
        bookings.saveAndFlush(booking(court, 10, 12, Booking.BookingStatus.PENDING));

        assertThatThrownBy(() -> bookings.saveAndFlush(booking(court, 11, 13, Booking.BookingStatus.PENDING)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("excl_bookings_no_overlap");
    }

    @Test
    @DisplayName("back-to-back bookings are fine - the ranges are half-open")
    void backToBack() {
        bookings.saveAndFlush(booking(court, 10, 12, Booking.BookingStatus.PENDING));
        bookings.saveAndFlush(booking(court, 12, 14, Booking.BookingStatus.CONFIRMED));
    }

    @Test
    @DisplayName("a cancelled booking doesn't hold the slot")
    void cancelledFreesSlot() {
        bookings.saveAndFlush(booking(court, 10, 12, Booking.BookingStatus.CANCELLED));
        bookings.saveAndFlush(booking(court, 10, 12, Booking.BookingStatus.PENDING));
    }

    @Test
    @DisplayName("the same time on another court is fine")
    void otherCourt() {
        bookings.saveAndFlush(booking(court, 10, 12, Booking.BookingStatus.PENDING));
        bookings.saveAndFlush(booking(otherCourt, 10, 12, Booking.BookingStatus.PENDING));
    }

    // --- queries ------------------------------------------------------------------

    @Test
    @DisplayName("findActiveOverlapping - overlapping PENDING/CONFIRMED only")
    void activeOverlapping() {
        Booking pending   = bookings.saveAndFlush(booking(court, 9, 11, Booking.BookingStatus.PENDING));
        Booking confirmed = bookings.saveAndFlush(booking(court, 14, 15, Booking.BookingStatus.CONFIRMED));
        bookings.saveAndFlush(booking(court, 11, 13, Booking.BookingStatus.CANCELLED));
        bookings.saveAndFlush(booking(court, 16, 18, Booking.BookingStatus.PENDING));      // outside the window
        bookings.saveAndFlush(booking(otherCourt, 10, 12, Booking.BookingStatus.PENDING)); // other court

        List<Booking> found = bookings.findActiveOverlapping(
                court.getId(), day.atTime(10, 0), day.atTime(16, 0), Booking.BookingStatus.CANCELLED);

        assertThat(found).extracting(Booking::getId).containsExactlyInAnyOrder(pending.getId(), confirmed.getId());
    }

    @Test
    @DisplayName("lockExpiredPendingIds - only PENDING bookings created before the cutoff")
    void expiredPending() {
        Booking old       = bookings.saveAndFlush(booking(court, 8, 9, Booking.BookingStatus.PENDING));
        Booking fresh     = bookings.saveAndFlush(booking(court, 9, 10, Booking.BookingStatus.PENDING));
        Booking oldPaid   = bookings.saveAndFlush(booking(court, 10, 11, Booking.BookingStatus.CONFIRMED));
        makeOld(old.getId());
        makeOld(oldPaid.getId());

        List<UUID> ids = bookings.lockExpiredPendingIds(LocalDateTime.now().minusMinutes(15), 100);

        assertThat(ids).contains(old.getId()).doesNotContain(fresh.getId(), oldPaid.getId());
    }

    // --- two instances at once ----------------------------------------------------

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)   // needs real, separate transactions
    @DisplayName("SKIP LOCKED - while one instance holds the expired bookings, the other gets none of them")
    void skipLocked() throws Exception {
        // no test transaction here, so setUp's user is committed - cleaned up below
        TransactionTemplate tx = new TransactionTemplate(txManager);
        List<UUID> created = tx.execute(s -> {
            Booking a = bookings.saveAndFlush(booking(court, 7, 8, Booking.BookingStatus.PENDING));
            Booking b = bookings.saveAndFlush(booking(otherCourt, 7, 8, Booking.BookingStatus.PENDING));
            return List.of(a.getId(), b.getId());
        });
        created.forEach(this::makeOld);

        try {
            CountDownLatch locked  = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            LocalDateTime cutoff = LocalDateTime.now().minusMinutes(15);

            // instance 1: locks the expired rows and keeps its transaction open
            CompletableFuture<List<UUID>> first = CompletableFuture.supplyAsync(() -> tx.execute(s -> {
                List<UUID> ids = bookings.lockExpiredPendingIds(cutoff, 100);
                locked.countDown();
                await(release);
                return ids;
            }));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

            // instance 2, meanwhile: skips the locked rows instead of waiting for them
            List<UUID> second = tx.execute(s -> bookings.lockExpiredPendingIds(cutoff, 100));
            release.countDown();

            assertThat(first.get(10, TimeUnit.SECONDS)).containsAll(created);
            assertThat(second).doesNotContainAnyElementsOf(created);
        } finally {
            created.forEach(id -> jdbc.update("DELETE FROM courtly.bookings WHERE id = ?", id));
            jdbc.update("DELETE FROM courtly.users WHERE id = ?", user.getId());
        }
    }

    // @CreationTimestamp always sets "now" on insert, so backdate it with SQL
    private void makeOld(UUID bookingId) {
        jdbc.update("UPDATE courtly.bookings SET created_at = ? WHERE id = ?",
                LocalDateTime.now().minusHours(1), bookingId);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
