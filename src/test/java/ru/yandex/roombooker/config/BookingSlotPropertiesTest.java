package ru.yandex.roombooker.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import ru.yandex.roombooker.domain.model.BookingSlot;

class BookingSlotPropertiesTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-02T10:00:00Z"), ZoneOffset.UTC);
    private static final String TZ = "Europe/Moscow";
    private static final String PREFIX = "room-booker[1].slots[2]";

    @ParameterizedTest
    @CsvSource({
            "1, 18:30, 2026-10-05T18:30:00",
            "2, 18:30, 2026-10-06T18:30:00",
            "3, 18:30, 2026-10-07T18:30:00",
            "4, 18:30, 2026-10-08T18:30:00",
            "5, 18:30, 2026-10-09T18:30:00",
            "5, 09:00, 2026-10-09T09:00:00",
            "6, 18:30, 2026-10-03T18:30:00",
            "7, 18:30, 2026-10-04T18:30:00",
            "1, '  18:30:15  ', 2026-10-05T18:30:15"
    })
    void shouldResolveNextWeekday(int day, String time, LocalDateTime expected) {
        BookingSlotProperties slot = new BookingSlotProperties(null, day, time, "1h");

        assertThat(slot.bookingStart(CLOCK, TZ, PREFIX)).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({
            "2026-10-02T21:30:00Z, 6, 2026-10-10T14:00:00",
            "2026-12-31T10:00:00Z, 5, 2027-01-01T14:00:00",
            "2028-02-28T10:00:00Z, 2, 2028-02-29T14:00:00"
    })
    void shouldUseTimeZoneAndHandleCalendarBoundaries(Instant now, int day, LocalDateTime expected) {
        BookingSlotProperties slot = new BookingSlotProperties(null, day, "14:00", "1h");

        assertThat(slot.bookingStart(Clock.fixed(now, ZoneOffset.UTC), TZ, PREFIX)).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({
            "2026-10-12, 1", "2026-10-13, 2", "2026-10-14, 3", "2026-10-15, 4",
            "2026-10-16, 5", "2026-10-17, 6", "2026-10-18, 7", "2026-10-02, 5"
    })
    void shouldUseExplicitDateWhenWeekdayMatches(String date, int day) {
        BookingSlotProperties slot = new BookingSlotProperties(date, day, "14:00", "1h");

        assertThat(slot.bookingStart(CLOCK, TZ, PREFIX)).isEqualTo(LocalDateTime.parse(date + "T14:00"));
    }

    @Test
    void shouldUseExplicitDateWithoutWeekdayAndOverrideDurationAcrossMidnight() {
        BookingSlotProperties slot = new BookingSlotProperties("2026-10-05", null, "23:30", "1h30m");

        assertThat(slot.resolve(CLOCK, TZ, "30m", PREFIX)).isEqualTo(new BookingSlot(
                LocalDateTime.parse("2026-10-05T23:30"), LocalDateTime.parse("2026-10-06T01:00")
        ));
    }

    @Test
    void shouldInheritDurationFromItsOwnPlan() {
        BookingSlotProperties slot = new BookingSlotProperties(null, 1, "14:00", null);

        assertThat(slot.resolve(CLOCK, TZ, "1h", PREFIX)).isEqualTo(new BookingSlot(
                LocalDateTime.parse("2026-10-05T14:00"), LocalDateTime.parse("2026-10-05T15:00")
        ));
    }

    @ParameterizedTest
    @CsvSource({
            ", , 14:00, day-of-week", ", 0, 14:00, day-of-week", ", 8, 14:00, day-of-week",
            ", -1, 14:00, day-of-week", "2026-10-05, 0, 14:00, day-of-week",
            "2026-10-05, 2, 14:00, day-of-week", "2026-02-30, , 14:00, date",
            "1, , 14:00, date", "monday, , 14:00, date", "2026-10-05T18:30:00, , 14:00, date",
            ", 1, , time", ", 1, 25:00, time", ", 1, 14:60, time", ", 1, 14:00 extra, time"
    })
    void shouldIdentifyInvalidField(String date, Integer day, String time, String field) {
        BookingSlotProperties slot = new BookingSlotProperties(date, day, time, "1h");

        assertThatThrownBy(() -> slot.resolve(CLOCK, TZ, null, PREFIX))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining(PREFIX + "." + field);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "no-duration", "0s", "-1h"})
    void shouldRequirePositiveDuration(String duration) {
        BookingSlotProperties slot = new BookingSlotProperties(null, 1, "14:00", duration);

        assertThatThrownBy(() -> slot.resolve(CLOCK, TZ, null, PREFIX))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining(PREFIX + ".duration");
    }
}
