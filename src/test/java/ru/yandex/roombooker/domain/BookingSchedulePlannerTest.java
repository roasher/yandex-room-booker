package ru.yandex.roombooker.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

import org.junit.jupiter.api.Test;
import ru.yandex.roombooker.config.MeetingRoomEntry;

class BookingSchedulePlannerTest {

    private static final ZoneId MOSCOW = ZoneId.of("Europe/Moscow");

    private final BookingSchedulePlanner planner = new BookingSchedulePlanner(
            Clock.fixed(Instant.parse("2026-06-22T10:00:00+03:00"), MOSCOW)
    );

    @Test
    void shouldScheduleAttemptWhenBookingWindowIsStillClosed() {
        MeetingRoomEntry room = new MeetingRoomEntry(
                "Yoga",
                "conf_st_yoga",
                "Office",
                "90m",
                "3d"
        );

        LocalDateTime slotStart = LocalDateTime.parse("2026-06-25T14:00:00");
        LocalDateTime attemptAt = planner.firstAttemptAt(slotStart, room, Duration.ofSeconds(30));

        assertThat(attemptAt).isEqualTo(LocalDateTime.parse("2026-06-22T14:00:30"));
    }

    @Test
    void shouldAttemptImmediatelyWhenBookingWindowIsAlreadyOpen() {
        MeetingRoomEntry room = new MeetingRoomEntry(
                "Yoga",
                "conf_st_yoga",
                "Office",
                "90m",
                "3d"
        );
        BookingSchedulePlanner openPlanner = new BookingSchedulePlanner(
                Clock.fixed(Instant.parse("2026-06-22T15:00:00+03:00"), MOSCOW)
        );

        LocalDateTime attemptAt = openPlanner.firstAttemptAt(
                LocalDateTime.parse("2026-06-25T14:00:00"),
                room,
                Duration.ofSeconds(30)
        );

        assertThat(attemptAt).isEqualTo(LocalDateTime.parse("2026-06-22T15:00:00"));
    }

    @Test
    void shouldAttemptImmediatelyWhenRoomHasNoBookableAheadPolicy() {
        MeetingRoomEntry room = new MeetingRoomEntry(
                "Yoga",
                "conf_st_yoga",
                "Office",
                "90m",
                null
        );

        LocalDateTime attemptAt = planner.firstAttemptAt(
                LocalDateTime.parse("2026-06-25T14:00:00"),
                room,
                Duration.ZERO
        );

        assertThat(attemptAt).isEqualTo(LocalDateTime.parse("2026-06-22T10:00:00"));
    }

    @Test
    void shouldRejectDurationLongerThanRoomMaximum() {
        MeetingRoomEntry room = new MeetingRoomEntry(
                "Yoga",
                "conf_st_yoga",
                "Office",
                "90m",
                "3d"
        );

        assertThatThrownBy(() -> planner.validateDuration(Duration.ofHours(2), room, "Yoga"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("exceeds max-duration");
    }
}
