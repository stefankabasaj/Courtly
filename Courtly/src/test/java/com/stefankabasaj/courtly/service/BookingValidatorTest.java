package com.stefankabasaj.courtly.service;

import com.stefankabasaj.courtly.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.springframework.test.context.ActiveProfiles;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.*;

@ActiveProfiles("test")
@DisplayName("BookingValidator")
class BookingValidatorTest {

    private final BookingValidator validator = new BookingValidator();

    private final LocalDateTime validStart = LocalDateTime.now().plusDays(1)
            .withHour(10).withMinute(0).withSecond(0).withNano(0);

    @Test @DisplayName("valid 2h booking - no exception")
    void valid_noException() {
        assertThatNoException().isThrownBy(() ->
                validator.validateBookingTimes(validStart, validStart.plusHours(2)));
    }

    @Test @DisplayName("end before start throws")
    void endBeforeStart() {
        assertThatThrownBy(() -> validator.validateBookingTimes(
                validStart, validStart.minusHours(1)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("after start");
    }

    @Test @DisplayName("start not on hour throws")
    void startNotOnHour() {
        assertThatThrownBy(() -> validator.validateBookingTimes(
                validStart.withMinute(15), validStart.withMinute(15).plusHours(1)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("on the hour");
    }

    @Test @DisplayName("1h duration - minimum - valid")
    void minDuration_valid() {
        assertThatNoException().isThrownBy(() ->
                validator.validateBookingTimes(validStart, validStart.plusHours(1)));
    }

    @Test @DisplayName("4h duration - maximum - valid")
    void maxDuration_valid() {
        assertThatNoException().isThrownBy(() ->
                validator.validateBookingTimes(validStart, validStart.plusHours(4)));
    }

    @Test @DisplayName("5h duration - over maximum - throws")
    void over4h_throws() {
        assertThatThrownBy(() -> validator.validateBookingTimes(
                validStart, validStart.plusHours(5)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("between 1 and 4 hours");
    }

    @Test @DisplayName("durationHours calculates correctly")
    void durationHours() {
        assertThat(validator.durationHours(validStart, validStart.plusHours(3))).isEqualTo(3);
    }
}
